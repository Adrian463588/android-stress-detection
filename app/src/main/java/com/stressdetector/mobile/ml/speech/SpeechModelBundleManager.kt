package com.stressdetector.mobile.ml.speech

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.ml.storage.ModelStorageSlot
import com.stressdetector.mobile.ml.storage.SafGenerationPayload
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SpeechBundleManifest(
    @SerializedName("schema_version") val schemaVersion: String,
    @SerializedName("bundle_version") val bundleVersion: String,
    @SerializedName("runtime_version") val runtimeVersion: String = "",
    @SerializedName("sample_rate_hz") val sampleRateHz: Int,
    val languages: List<String>,
    val stt: SpeechSttManifest,
    @SerializedName("indonesian_streaming_stt") val indonesianStt: IndonesianStreamingSttManifest? = null,
    val vad: SpeechVadManifest,
    val diarization: SpeechDiarizationManifest,
    @SerializedName("tts_profiles") val ttsProfiles: Map<String, SpeechTtsProfileManifest> = emptyMap(),
    val files: Map<String, String>,
    @SerializedName("file_sizes_bytes") val fileSizesBytes: Map<String, Long> = emptyMap(),
    val sources: Map<String, String> = emptyMap(),
    val licenses: Map<String, String> = emptyMap()
)

data class SpeechTtsProfileManifest(
    val language: String,
    @SerializedName("voice_id") val voiceId: String,
    @SerializedName("engine_type") val engineType: String? = null,
    val archive: String = "",
    val model: String = "",
    val tokens: String = "",
    @SerializedName("data_dir") val dataDir: String = "",
    @SerializedName("sample_rate_hz") val sampleRateHz: Int = 0,
    val supertonic: SpeechSupertonicModelManifest? = null
)

/** Gson leaves absent Kotlin constructor defaults unset; normalize legacy Piper manifests once. */
val SpeechTtsProfileManifest.effectiveEngineType: String
    get() = engineType?.takeIf(String::isNotBlank) ?: "piper_vits"

data class SpeechSupertonicModelManifest(
    @SerializedName("duration_predictor") val durationPredictor: String,
    @SerializedName("text_encoder") val textEncoder: String,
    @SerializedName("vector_estimator") val vectorEstimator: String,
    val vocoder: String,
    @SerializedName("tts_json") val ttsJson: String,
    @SerializedName("unicode_indexer") val unicodeIndexer: String,
    @SerializedName("voice_style") val voiceStyle: String,
    @SerializedName("speaker_id") val speakerId: Int = 0,
    @SerializedName("speaker_count") val speakerCount: Int = 10,
    @SerializedName("num_steps") val numSteps: Int = 8,
    val speed: Float = 1.0f
)

data class SpeechSttManifest(
    @SerializedName("model_type") val modelType: String,
    val model: String = "",
    val tokens: String = "",
    // Retained as empty-compatible fields so old callers fail at validation, not compilation.
    val encoder: String = "",
    val decoder: String = "",
    val joiner: String = ""
)

data class IndonesianStreamingSttManifest(
    @SerializedName("model_type") val modelType: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
    val language: String = "id",
    val revision: String = ""
)

data class SpeechVadManifest(val enabled: Boolean, val model: String = "")

data class SpeechDiarizationManifest(
    val enabled: Boolean,
    @SerializedName("speaker_count") val speakerCount: Int,
    val segmentation: String = "",
    val embedding: String = ""
)

data class SpeechCapabilityReadiness(
    val verbatimReady: Boolean,
    val voiceAiReady: Boolean,
    val missingComponents: List<String> = emptyList(),
    val indonesianSttReady: Boolean = verbatimReady
)

internal fun projectSpeechCapabilities(readiness: SpeechBundleReadiness, requiredTtsProfileIds: Set<String>): SpeechCapabilityReadiness =
    when (readiness) {
        SpeechBundleReadiness.Checking -> SpeechCapabilityReadiness(
            verbatimReady = false,
            voiceAiReady = false,
            missingComponents = listOf("speech bundle readiness is being checked"),
            indonesianSttReady = false
        )
        SpeechBundleReadiness.Missing -> SpeechCapabilityReadiness(
            verbatimReady = false,
            voiceAiReady = false,
            missingComponents = listOf("shared speech bundle"),
            indonesianSttReady = false
        )
        is SpeechBundleReadiness.Invalid -> SpeechCapabilityReadiness(
            verbatimReady = false,
            voiceAiReady = false,
            missingComponents = listOf(readiness.reason),
            indonesianSttReady = false
        )
        is SpeechBundleReadiness.Ready -> {
            val requiredLanguages = requiredTtsProfileIds.map(String::lowercase).filter { it in setOf("id", "en") }.toSet()
            val availableLanguages = readiness.manifest.ttsProfiles.values.map { it.language.lowercase() }.toSet()
            val missingTts = requiredLanguages.filterNot(availableLanguages::contains)
            val indonesianReady = readiness.manifest.indonesianStt != null
            SpeechCapabilityReadiness(
                verbatimReady = indonesianReady,
                voiceAiReady = indonesianReady && missingTts.isEmpty(),
                missingComponents = buildList {
                    if (!indonesianReady) add("Indonesian streaming STT")
                    addAll(missingTts)
                },
                indonesianSttReady = indonesianReady
            )
        }
    }

sealed interface SpeechBundleReadiness {
    data object Checking : SpeechBundleReadiness
    data object Missing : SpeechBundleReadiness
    data class Ready(val manifest: SpeechBundleManifest) : SpeechBundleReadiness
    data class Invalid(val reason: String) : SpeechBundleReadiness
}

/** Owns the app-private, atomically activated Sherpa bundle and its integrity contract. */
class SpeechModelBundleManager(
    context: Context,
    private val durableStorage: ModelStorageManager? = null,
    private val descriptor: SpeechBundleDescriptor
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "speech_runtime")
    private val activeBundle = File(root, ACTIVE_BUNDLE_NAME)
    private val activeManifest = File(root, ACTIVE_MANIFEST_NAME)
    private val deletionMarker = File(root, DELETION_MARKER_NAME)
    private val gson = Gson()
    private val readinessMutex = Mutex()
    private var readinessLoaded = false

    // Do not open the ZIP or hash model files from the constructor/main thread.
    // The owner explicitly calls ensureReadiness() from an IO coroutine.
    private val _readiness = MutableStateFlow<SpeechBundleReadiness>(SpeechBundleReadiness.Checking)
    val readiness: StateFlow<SpeechBundleReadiness> = _readiness.asStateFlow()

    fun bundleFile(): File = activeBundle

    internal fun runtimeRoot(): File = root

    internal fun ttsMaterializedRoot(): File = File(root, "tts_materialized")

    internal fun downloadRoot(): File = File(root, DOWNLOAD_DIRECTORY_NAME)

    /** Materializes verified files because Sherpa's file API cannot read a dynamic AssetManager. */
    fun materializeRuntimeFiles(manifestOverride: SpeechBundleManifest? = null): File {
        val manifest = manifestOverride ?: (activeReadinessFromDisk() as? SpeechBundleReadiness.Ready)?.manifest
            ?: error("Sherpa speech bundle is not ready.")
        val materialized = File(root, "materialized")
        val fingerprint = manifest.files.toSortedMap().entries.joinToString("\n") { "${it.key}:${it.value}" }
        val marker = File(materialized, MATERIALIZED_MARKER)
        if (materialized.isDirectory &&
            marker.isFile &&
            marker.readText() == fingerprint &&
            manifest.files.keys.all { File(materialized, it).isFile }
        ) {
            return materialized
        }
        val temporary = File(root, ".materialized-${System.currentTimeMillis()}")
        require(temporary.mkdirs()) { "Sherpa runtime materialization directory could not be created." }
        try {
            ZipFile(activeBundle).use { zip ->
                manifest.files.keys.forEach { path ->
                    val entry = findEntry(zip, path) ?: error("Speech bundle file is missing: $path")
                    val destination = File(temporary, path)
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        destination.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            File(temporary, MATERIALIZED_MARKER).writeText(fingerprint)
            materialized.deleteRecursively()
            check(temporary.renameTo(materialized)) { "Verified speech files could not be materialized." }
            return materialized
        } catch (error: Exception) {
            temporary.deleteRecursively()
            throw error
        }
    }

    fun manifest(): SpeechBundleManifest? = (_readiness.value as? SpeechBundleReadiness.Ready)?.manifest

    fun refreshReadiness(): SpeechBundleReadiness = readinessFromDisk().also(::publish)

    /** Performs the expensive ZIP/hash validation once, off the main thread. */
    suspend fun ensureReadiness(force: Boolean = false): SpeechBundleReadiness = withContext(Dispatchers.IO) {
        readinessMutex.withLock {
            if (!force && readinessLoaded) return@withLock _readiness.value
            val localReadiness = readinessFromDisk()
            _readiness.value = SpeechBundleReadiness.Checking
            durableStorage?.reconcile()
            durableStorage?.restoreSpeechBundleIfMissing(
                activeBundle,
                activeManifest,
                durableGeneration(),
                forceRestorePair = localReadiness !is SpeechBundleReadiness.Ready,
                validateCandidatePair = { bundle, manifest ->
                    readinessFromFiles(bundle, manifest) is SpeechBundleReadiness.Ready
                }
            )
            readinessFromDisk().also(::publish)
        }
    }

    /** Returns the verified active artifact without relying on the cached StateFlow value. */
    internal fun activeReadinessFromDisk(): SpeechBundleReadiness = readinessFromDisk()

    internal fun requestDeletion() {
        root.mkdirs()
        deletionMarker.writeText("delete-requested")
    }

    internal fun clearDeletionRequest() {
        deletionMarker.delete()
    }

    internal fun isDeletionRequested(): Boolean = deletionMarker.isFile

    fun capabilityReadiness(): SpeechCapabilityReadiness = projectSpeechCapabilities(
        readiness = _readiness.value,
        requiredTtsProfileIds = descriptor.languages.toSet()
    )

    fun hasTtsProfiles(profileIds: Set<String>): Boolean {
        val manifest = manifest() ?: return false
        return profileIds.all { manifest.ttsProfiles.containsKey(it) }
    }

    suspend fun importBundle(uri: Uri): SpeechBundleReadiness = withContext(Dispatchers.IO) {
        root.mkdirs()
        clearDeletionRequest()
        val temporary = File(root, ".$ACTIVE_BUNDLE_NAME.tmp")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output ->
                    copyBounded(input, output, maximumImportedBundleBytes())
                }
            } ?: return@withContext publish(SpeechBundleReadiness.Invalid("Cannot read the selected bundle."))
            val result = readinessMutex.withLock { installBundleFile(temporary) }
            if (result is SpeechBundleReadiness.Ready) {
                // Durable storage is an optional recovery source. A provider failure
                // must not invalidate a bundle that was already verified locally.
                syncDurableBundle(result.manifest)
            }
            if (temporary.exists()) temporary.delete()
            result
        } catch (error: Exception) {
            temporary.delete()
            publish(SpeechBundleReadiness.Invalid(error.message ?: "Speech bundle validation failed."))
        }
    }

    /** Validates a candidate completely before swapping it into the active slot. */
    internal fun installBundleFile(candidate: File): SpeechBundleReadiness = try {
        check(!isDeletionRequested()) { "Speech bundle activation was cancelled by the user." }
        val manifest = validateBundle(candidate)
        activate(candidate, manifest)
        publish(SpeechBundleReadiness.Ready(manifest))
    } catch (error: Exception) {
        val invalid = SpeechBundleReadiness.Invalid(error.message ?: "Speech bundle activation failed.")
        // A failed candidate must never hide a previously verified active bundle.
        // The caller receives the error, while the readiness flow keeps the old
        // bundle available for existing/offline sessions.
        if (readinessFromDisk() !is SpeechBundleReadiness.Ready) {
            publish(invalid)
        }
        invalid
    }

    /**
     * Activates a candidate only for the duration of runtime validation. The previous
     * active bundle remains recoverable until [validateRuntime] succeeds.
     */
    internal suspend fun installBundleFileWithRuntimeValidation(
        candidate: File,
        validateRuntime: suspend (SpeechBundleManifest) -> Unit
    ): SpeechBundleReadiness = withContext(Dispatchers.IO) {
        readinessMutex.withLock {
            var backup: ActivationBackup? = null
            try {
                check(!isDeletionRequested()) { "Speech bundle activation was cancelled by the user." }
                val manifest = validateBundle(candidate)
                backup = activate(candidate, manifest, retainBackup = true)
                validateRuntime(manifest)
                commitActivation(backup)
                publish(SpeechBundleReadiness.Ready(manifest))
                // SAF is a recovery mirror. A provider failure must never roll
                // back a locally verified runtime.
                syncDurableBundle(manifest).onFailure { error ->
                    durableStorage?.reportError(
                        "Runtime speech lokal siap, tetapi mirror SAF gagal: ${error.message}"
                    )
                }
                SpeechBundleReadiness.Ready(manifest)
            } catch (error: CancellationException) {
                backup?.let(::rollbackActivation)
                backup?.let { publish(readinessFromDisk()) }
                throw error
            } catch (error: Exception) {
                backup?.let(::rollbackActivation)
                val invalid = SpeechBundleReadiness.Invalid(
                    error.message ?: "Speech bundle runtime validation failed."
                )
                val restored = readinessFromDisk()
                publish(if (restored is SpeechBundleReadiness.Ready) restored else invalid)
                invalid
            }
        }
    }

    suspend fun deleteBundle(): Boolean = withContext(Dispatchers.IO) {
        readinessMutex.withLock {
            requestDeletion()
            val durableBundleDelete = durableStorage?.deleteSlot(
                slot = ModelStorageSlot.SPEECH_BUNDLE
            ) ?: Result.success(Unit)
            if (durableBundleDelete.isFailure) {
                clearDeletionRequest()
                return@withLock false
            }
            val deleted = (!activeBundle.exists() || activeBundle.delete()) &&
                (!activeManifest.exists() || activeManifest.delete()) &&
                (!File(root, "materialized").exists() || File(root, "materialized").deleteRecursively()) &&
                (!ttsMaterializedRoot().exists() || ttsMaterializedRoot().deleteRecursively())
            if (deleted) {
                clearDeletionRequest()
                publish(SpeechBundleReadiness.Missing)
            }
            deleted
        }
    }

    private data class ActivationBackup(val bundle: File, val manifest: File)

    private fun activate(candidate: File, manifest: SpeechBundleManifest, retainBackup: Boolean = false): ActivationBackup? {
        root.mkdirs()
        val backup = File(root, ".${ACTIVE_BUNDLE_NAME}.bak")
        val manifestBackup = File(root, ".${ACTIVE_MANIFEST_NAME}.bak")
        val manifestTemporary = File(root, ".$ACTIVE_MANIFEST_NAME.tmp")
        backup.delete()
        manifestBackup.delete()
        manifestTemporary.delete()
        try {
            if (activeBundle.exists()) {
                check(activeBundle.renameTo(backup)) {
                    "Existing speech bundle could not be staged for replacement."
                }
            }
            if (activeManifest.exists()) {
                check(activeManifest.renameTo(manifestBackup)) {
                    "Existing speech manifest could not be staged for replacement."
                }
            }
            check(candidate.renameTo(activeBundle)) { "Speech bundle could not be published atomically." }
            manifestTemporary.writeText(gson.toJson(manifest))
            check(manifestTemporary.renameTo(activeManifest)) { "Speech manifest could not be published atomically." }
            if (!retainBackup) {
                backup.delete()
                manifestBackup.delete()
            }
            return if (retainBackup) ActivationBackup(backup, manifestBackup) else null
        } catch (error: Exception) {
            activeBundle.delete()
            activeManifest.delete()
            backup.renameTo(activeBundle)
            manifestBackup.renameTo(activeManifest)
            manifestTemporary.delete()
            throw error
        }
    }

    private fun commitActivation(backup: ActivationBackup?) {
        backup?.bundle?.delete()
        backup?.manifest?.delete()
    }

    suspend fun syncActiveBundleToDurableStorage(): Result<Unit> = withContext(Dispatchers.IO) {
        syncDurableBundle()
    }

    private suspend fun syncDurableBundle(manifestOverride: SpeechBundleManifest? = null): Result<Unit> {
        val durable = durableStorage
        if (durable == null || !durable.state.value.connected) return Result.success(Unit)
        val manifest = manifestOverride ?: when (val readiness = ensureReadiness()) {
            is SpeechBundleReadiness.Ready -> readiness.manifest
            SpeechBundleReadiness.Missing -> return Result.success(Unit)
            is SpeechBundleReadiness.Invalid -> {
                return Result.failure(IllegalStateException("Bundle Verbatim tidak valid: ${readiness.reason}"))
            }
            SpeechBundleReadiness.Checking -> {
                return Result.failure(IllegalStateException("Bundle Verbatim belum siap disinkronkan."))
            }
        }
        return runCatching {
            require(activeBundle.isFile && activeBundle.length() > 0L) {
                "Bundle Verbatim belum tersedia untuk disinkronkan."
            }
            require(activeManifest.isFile && activeManifest.length() > 0L) {
                "Manifest Bundle Verbatim belum tersedia untuk disinkronkan."
            }
            val bundleSha256 = activeBundle.inputStream().use(::sha256)
            val manifestSha256 = activeManifest.inputStream().use(::sha256)
            durable.publishGeneration(
                slot = ModelStorageSlot.SPEECH_BUNDLE,
                generation = durableGeneration(),
                version = manifest.bundleVersion,
                revision = descriptor.runtimeVersion,
                payloads = listOf(
                    SafGenerationPayload(
                        source = activeBundle,
                        fileName = ACTIVE_BUNDLE_NAME,
                        expectedSizeBytes = activeBundle.length(),
                        expectedSha256 = bundleSha256
                    ),
                    SafGenerationPayload(
                        source = activeManifest,
                        fileName = ACTIVE_MANIFEST_NAME,
                        expectedSizeBytes = activeManifest.length(),
                        expectedSha256 = manifestSha256,
                        mediaType = "application/json"
                    )
                )
            ).getOrThrow()
        }
    }

    private fun rollbackActivation(backup: ActivationBackup) {
        activeBundle.delete()
        activeManifest.delete()
        if (backup.bundle.isFile) {
            check(backup.bundle.renameTo(activeBundle)) {
                "Previously active speech bundle could not be restored."
            }
        }
        if (backup.manifest.isFile) {
            check(backup.manifest.renameTo(activeManifest)) {
                "Previously active speech manifest could not be restored."
            }
        }
    }

    private fun validateBundle(file: File): SpeechBundleManifest {
        require(file.isFile && file.length() > 0L) { "Speech bundle is empty." }
        require(file.length() <= maximumImportedBundleBytes()) {
            "Speech bundle exceeds the canonical catalog size limit."
        }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val expectedPaths = descriptor.components
                .flatMap(SpeechBundleComponentSpec::extractedFiles)
                .associateBy(SpeechBundleFileSpec::bundlePath)
            require(entries.size <= expectedPaths.size + MAX_ARCHIVE_METADATA_ENTRIES) {
                "Speech bundle contains too many entries."
            }
            val normalizedNames = mutableSetOf<String>()
            var uncompressedBytes = 0L
            entries.forEach { entry ->
                val normalized = entry.name.removePrefix("assets/")
                requireSafePath(normalized)
                require(normalizedNames.add(normalized)) { "Duplicate speech bundle path: $normalized" }
                require(!entry.isDirectory) { "Speech bundle directories are not allowed: $normalized" }
                require(entry.size >= 0L) { "Speech bundle entry size is unavailable: $normalized" }
                uncompressedBytes = Math.addExact(uncompressedBytes, entry.size)
                require(uncompressedBytes <= maximumUncompressedBundleBytes()) {
                    "Speech bundle expands beyond the canonical size limit."
                }
                if (entry.compressedSize > 0L) {
                    require(entry.size <= entry.compressedSize * MAX_COMPRESSION_RATIO) {
                        "Speech bundle entry compression ratio is unsafe: $normalized"
                    }
                }
            }
            val manifestEntry = zip.getEntry(MANIFEST_PATH)
                ?: zip.getEntry("assets/$MANIFEST_PATH")
                ?: error("Speech bundle must contain manifest.json.")
            val manifest = zip.getInputStream(manifestEntry).bufferedReader().use {
                gson.fromJson(it, SpeechBundleManifest::class.java)
            } ?: error("Speech bundle manifest is unreadable.")
            require(manifest.schemaVersion == SUPPORTED_SCHEMA) { "Unsupported speech bundle schema." }
            require(manifest.bundleVersion == descriptor.bundleVersion) {
                "Speech bundle version does not match the canonical catalog."
            }
            require(manifest.runtimeVersion == descriptor.runtimeVersion) {
                "Speech runtime version does not match the canonical catalog."
            }
            require(manifest.sampleRateHz == descriptor.sampleRateHz) {
                "Speech bundle sample rate must be ${descriptor.sampleRateHz} Hz."
            }
            require(manifest.languages.map(String::lowercase).containsAll(listOf("id", "en"))) {
                "Speech bundle must support Indonesian and English."
            }
            require(manifest.sources == descriptor.components.associate { it.id to it.upstreamSource }) {
                "Speech bundle sources do not match the canonical catalog."
            }
            require(manifest.licenses == descriptor.components.associate { it.id to it.license }) {
                "Speech bundle licenses do not match the canonical catalog."
            }
            require(manifest.stt.modelType == "omnilingual_ctc") {
                "Speech bundle must use the pinned Omnilingual CTC model."
            }
            manifest.indonesianStt?.let { indonesian ->
                require(indonesian.modelType == "zipformer2_transducer" && indonesian.language == "id") {
                    "Speech bundle Indonesian STT profile is unsupported."
                }
                listOf(indonesian.encoder, indonesian.decoder, indonesian.joiner, indonesian.tokens)
                    .forEach(::requireSafePath)
                require(
                    manifest.files.keys.containsAll(
                        listOf(indonesian.encoder, indonesian.decoder, indonesian.joiner, indonesian.tokens)
                    )
                ) {
                    "Speech bundle does not checksum every Indonesian STT file."
                }
                require(indonesian.revision == descriptor.indonesianStt?.revision) {
                    "Indonesian STT revision does not match the canonical catalog."
                }
            }
            require(manifest.vad.enabled && manifest.vad.model.isNotBlank()) {
                "Speech bundle Silero VAD is missing."
            }
            require(manifest.diarization.enabled && manifest.diarization.speakerCount == 2) {
                "Speech bundle must declare two-speaker diarization."
            }
            manifest.ttsProfiles.forEach { (id, profile) ->
                require(id.isNotBlank() && profile.language in setOf("id", "en")) {
                    "Invalid TTS profile language: $id"
                }
                require(profile.sampleRateHz > 0 && profile.voiceId.isNotBlank()) {
                    "Invalid TTS profile metadata: $id"
                }
                when (profile.effectiveEngineType) {
                    "piper_vits" -> {
                        listOf(profile.archive, profile.model, profile.tokens, profile.dataDir)
                            .forEach(::requireSafePath)
                        require(manifest.files.containsKey(profile.archive)) {
                            "TTS archive is not checksummed: $id"
                        }
                    }
                    "supertonic" -> {
                        val model = profile.supertonic
                            ?: error("Supertonic profile is missing model metadata: $id")
                        listOf(
                            model.durationPredictor,
                            model.textEncoder,
                            model.vectorEstimator,
                            model.vocoder,
                            model.ttsJson,
                            model.unicodeIndexer,
                            model.voiceStyle
                        ).forEach(::requireSafePath)
                        require(model.speakerId in 0 until model.speakerCount && model.speakerCount > 0) {
                            "Invalid Supertonic speaker metadata: $id"
                        }
                        require(model.numSteps > 0 && model.speed > 0f) {
                            "Invalid Supertonic generation metadata: $id"
                        }
                        require(
                            manifest.files.keys.containsAll(
                                listOf(
                                    model.durationPredictor,
                                    model.textEncoder,
                                    model.vectorEstimator,
                                    model.vocoder,
                                    model.ttsJson,
                                    model.unicodeIndexer,
                                    model.voiceStyle
                                )
                            )
                        ) {
                            "Supertonic runtime files are not fully checksummed: $id"
                        }
                    }
                    else -> error("Unsupported TTS engine type: ${profile.engineType}")
                }
            }
            require(manifest.ttsProfiles.keys == descriptor.ttsProfiles.map(SpeechTtsProfileSpec::id).toSet()) {
                "Speech TTS profiles do not exactly match the canonical bundle."
            }
            val requiredPaths = listOf(
                manifest.stt.model,
                manifest.stt.tokens,
                manifest.vad.model,
                manifest.diarization.segmentation,
                manifest.diarization.embedding
            )
            requiredPaths.forEach(::requireSafePath)
            require(manifest.files.isNotEmpty()) { "Speech bundle file checksums are missing." }
            require(manifest.files.keys.containsAll(requiredPaths)) {
                "Speech bundle manifest does not checksum every runtime file."
            }
            require(manifest.fileSizesBytes.keys.containsAll(manifest.files.keys)) {
                "Speech bundle manifest does not declare every runtime file size."
            }
            require(manifest.files.keys == expectedPaths.keys) {
                "Speech bundle content does not exactly match the canonical catalog."
            }
            require(manifest.fileSizesBytes.keys == expectedPaths.keys) {
                "Speech bundle size manifest does not exactly match the canonical catalog."
            }
            manifest.files.forEach { (path, expectedHash) ->
                requireSafePath(path)
                require(expectedHash.matches(SHA256_PATTERN)) { "Invalid checksum for $path." }
                val canonical = expectedPaths.getValue(path)
                require(canonical.expectedBytes == manifest.fileSizesBytes.getValue(path)) {
                    "Canonical file size mismatch for $path."
                }
                require(canonical.sha256.equals(expectedHash, ignoreCase = true)) {
                    "Canonical checksum mismatch for $path."
                }
                val entry = findEntry(zip, path) ?: error("Speech bundle file is missing: $path")
                require(entry.size == manifest.fileSizesBytes.getValue(path)) {
                    "File size mismatch for $path."
                }
                val actualHash = zip.getInputStream(entry).use(::sha256)
                require(actualHash.equals(expectedHash, ignoreCase = true)) {
                    "Checksum mismatch for $path."
                }
            }
            return manifest
        }
    }

    private fun maximumImportedBundleBytes(): Long = maximumUncompressedBundleBytes() + IMPORT_ARCHIVE_MARGIN_BYTES

    private fun copyBounded(input: java.io.InputStream, output: java.io.OutputStream, maximumBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            copied = Math.addExact(copied, count.toLong())
            require(copied <= maximumBytes) {
                "Speech bundle exceeds the canonical catalog size limit."
            }
            output.write(buffer, 0, count)
        }
    }

    private fun maximumUncompressedBundleBytes(): Long = descriptor.components
        .flatMap(SpeechBundleComponentSpec::extractedFiles)
        .sumOf { requireNotNull(it.expectedBytes) } + MANIFEST_MARGIN_BYTES

    private fun readinessFromDisk(): SpeechBundleReadiness = readinessFromFiles(activeBundle, activeManifest)

    private fun readinessFromFiles(bundle: File, manifestFile: File): SpeechBundleReadiness {
        if (!bundle.isFile || !manifestFile.isFile) return SpeechBundleReadiness.Missing
        return runCatching {
            val sidecar = gson.fromJson(manifestFile.readText(), SpeechBundleManifest::class.java)
                ?: error("Speech bundle manifest is unreadable.")
            val verified = validateBundle(bundle)
            require(verified == sidecar) { "Speech bundle sidecar does not match the verified ZIP." }
            SpeechBundleReadiness.Ready(verified)
        }.getOrElse { SpeechBundleReadiness.Invalid(it.message ?: "Speech bundle state is invalid.") }
    }

    private fun findEntry(zip: ZipFile, path: String) = zip.getEntry(path) ?: zip.getEntry("assets/$path")

    private fun requireSafePath(path: String) {
        require(
            path.isNotBlank() &&
                !path.startsWith('/') &&
                !path.contains('\\') &&
                path.split('/').none { it == ".." || it.isBlank() }
        ) {
            "Unsafe speech bundle path: $path"
        }
    }

    private fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun durableGeneration(): String {
        val fingerprint = descriptor.components
            .sortedBy(SpeechBundleComponentSpec::id)
            .joinToString("\n") { component -> "${component.id}:${component.expectedDownloadSha256}" }
        return "${descriptor.bundleVersion}-${sha256(fingerprint.byteInputStream()).take(12)}"
    }

    private fun publish(value: SpeechBundleReadiness): SpeechBundleReadiness {
        _readiness.value = value
        readinessLoaded = value !is SpeechBundleReadiness.Checking
        return value
    }

    internal companion object {
        const val SUPPORTED_SCHEMA = "2.0.0"
        const val REQUIRED_SAMPLE_RATE_HZ = 16_000
        const val ACTIVE_BUNDLE_NAME = "sherpa_bundle.zip"
        const val ACTIVE_MANIFEST_NAME = "manifest.json"
        const val LEGACY_MANIFEST_NAME = "sherpa_manifest.json"
        const val DOWNLOAD_DIRECTORY_NAME = "downloads"
        const val MANIFEST_PATH = "manifest.json"
        const val MATERIALIZED_MARKER = ".manifest-fingerprint"
        const val DELETION_MARKER_NAME = ".speech_delete_requested"
        private const val MAX_ARCHIVE_METADATA_ENTRIES = 2
        private const val MAX_COMPRESSION_RATIO = 100L
        private const val MANIFEST_MARGIN_BYTES = 1024L * 1024L
        private const val IMPORT_ARCHIVE_MARGIN_BYTES = 8L * 1024L * 1024L
        val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
    }
}
