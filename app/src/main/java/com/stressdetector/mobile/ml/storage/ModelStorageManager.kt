package com.stressdetector.mobile.ml.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ModelStorageSlot(val directoryName: String) {
    GEMMA("gemma-4-e2b"),
    SPEECH_BUNDLE("speech-bundle"),
    MULTILINGUAL_EMBEDDING("multilingual-e5-small"),
    PERSONA_AI("persona-ai"),
    USER_DATA_BACKUP("user-data-backup")
}

enum class SafStorageStatus {
    UNSELECTED,
    PREFLIGHTING,
    CONNECTED,
    SYNCING,
    PERMISSION_LOST,
    PROVIDER_UNAVAILABLE,
    UNSUPPORTED_PROVIDER,
    INSUFFICIENT_SPACE
}

data class ModelStorageState(
    val treeUri: Uri? = null,
    val connected: Boolean = false,
    val modelPath: String = "StressDetector/Artifacts",
    val resolvedRootDocumentId: String? = null,
    val resolvedRootLayout: SafArtifactRootLayout? = null,
    val schemaVersion: Int? = null,
    val status: SafStorageStatus = SafStorageStatus.UNSELECTED,
    val providerAuthority: String? = null,
    val providerRootId: String? = null,
    val localOnly: Boolean = false,
    val persistedReadPermission: Boolean = false,
    val persistedWritePermission: Boolean = false,
    val availableBytes: Long? = null,
    val lastAuditAtMillis: Long? = null,
    val legacyLocations: List<String> = emptyList(),
    val error: String? = null
)

data class SafTreeSelection(val uri: Uri, val grantFlags: Int)

/**
 * Validation policy for the exact SAF tree returned by DocumentsUI.
 *
 * A persisted tree grant does not authorize querying the provider-wide `/root`
 * collection. Keep this policy independent from provider I/O so it can be
 * regression-tested without fabricating a content provider.
 */
internal enum class SafProviderInspectionScope {
    SELECTED_TREE_DOCUMENT
}

internal object SafTreeDocumentPolicy {
    const val LOCAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    const val STRESS_DETECTION_TREE_DOCUMENT_ID = "primary:StressDetection"
    const val READ_WRITE_GRANT_FLAGS =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    val inspectionScope: SafProviderInspectionScope = SafProviderInspectionScope.SELECTED_TREE_DOCUMENT

    fun pickerReadWriteFlags(returnedFlags: Int): Int = returnedFlags and READ_WRITE_GRANT_FLAGS

    fun validateSelectedTree(
        authority: String,
        requestedDocumentId: String,
        returnedDocumentId: String,
        mimeType: String,
        documentFlags: Int,
        availableBytes: Long?,
        requiredBytes: Long
    ) {
        if (authority != LOCAL_STORAGE_AUTHORITY) {
            throw SafUnsupportedProviderException("Pilih folder penyimpanan lokal perangkat.")
        }
        if (requestedDocumentId.isBlank() || requestedDocumentId != returnedDocumentId) {
            throw SafUnsupportedProviderException("Folder SAF yang dipilih tidak dapat diverifikasi.")
        }
        if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            throw SafUnsupportedProviderException("Pilihan SAF bukan folder.")
        }
        if (documentFlags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0) {
            throw SafUnsupportedProviderException("Provider SAF tidak mendukung pembuatan file.")
        }
        if (availableBytes != null && availableBytes < requiredBytes) {
            throw SafInsufficientSpaceException(
                "Kapasitas SAF tidak cukup: tersedia $availableBytes byte, dibutuhkan $requiredBytes byte."
            )
        }
    }
}

internal fun classifySafFailure(error: Throwable): SafStorageStatus = when (error) {
    is SafInsufficientSpaceException -> SafStorageStatus.INSUFFICIENT_SPACE
    is SafUnsupportedProviderException -> SafStorageStatus.UNSUPPORTED_PROVIDER
    is SecurityException -> SafStorageStatus.PERMISSION_LOST
    else -> SafStorageStatus.PROVIDER_UNAVAILABLE
}

internal object LegacySafPaths {
    const val ROOT_DIRECTORY = "StressDetector"
    const val MODEL_DIRECTORY = "Model"
    const val GEMMA_DIRECTORY = "LLMLokal"
    const val SPEECH_BUNDLE_DIRECTORY = "BundleVerbatim"
    const val VOICE_DIRECTORY = "VoiceAI"
    const val VOICE_CAPABILITY_FILE = "capability.json"
    const val VOICE_CAPABILITY_PATH = "StressDetector/Model/VoiceAI/capability.json"
}

data class SafGenerationPayload(
    val source: File,
    val fileName: String,
    val expectedSizeBytes: Long,
    val expectedSha256: String,
    val mediaType: String = "application/octet-stream"
)

data class SafRestoredPayload(val generation: String, val sizeBytes: Long, val sha256: String)

class PendingSafTreeSelection internal constructor(
    val candidate: SafTreeSelection,
    internal val previousUri: Uri?,
    internal val previousFlags: Int
)

/**
 * Durable model source backed by a user-selected SAF tree.
 * Runtime engines still consume app-private materialized files; this class
 * owns only shared model persistence, validation, and restoration.
 */
class ModelStorageManager(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(loadState())
    private val gson = Gson()
    private val slotMutexes = ModelStorageSlot.entries.associateWith { Mutex() }

    val state: StateFlow<ModelStorageState> = mutableState.asStateFlow()

    suspend fun reconcile(): ModelStorageState = withContext(Dispatchers.IO) {
        val uri = state.value.treeUri
        if (uri == null) {
            publish(ModelStorageState())
        } else {
            runCatching {
                val flags = persistedGrantFlags(uri)
                requireReadWriteGrant(flags)
                val provider = inspectLocalProvider(uri, requiredBytes = 0L)
                // Reconciliation is read-only. Creating Artifacts here can turn a
                // valid selected StressDetector/StressDetection root into a nested
                // empty tree before legacy artifacts have been discovered.
                resolveArtifactRoot(uri, createIfMissing = false)
                connectedState(uri, flags, provider)
            }.onSuccess(::publish)
                .onFailure { error -> publishFailure(uri, error) }
        }
        state.value
    }

    fun prepareTreeSelection(selection: SafTreeSelection, requiredBytes: Long): Result<PendingSafTreeSelection> {
        val previousState = state.value
        val flags = selection.grantFlags and READ_WRITE_FLAGS
        val result = runCatching {
            requireReadWriteGrant(flags)
            publish(previousState.copy(status = SafStorageStatus.PREFLIGHTING, error = null))
            resolver.takePersistableUriPermission(selection.uri, flags)
            require((persistedGrantFlags(selection.uri) and READ_WRITE_FLAGS) == READ_WRITE_FLAGS) {
                "Izin baca/tulis folder tidak dapat dipertahankan oleh provider."
            }
            val provider = inspectLocalProvider(selection.uri, requiredBytes)
            preflightTree(selection.uri)
            PendingSafTreeSelection(
                candidate = selection.copy(grantFlags = flags),
                previousUri = previousState.treeUri,
                previousFlags = previousState.treeUri?.let(::persistedGrantFlags) ?: 0
            ).also {
                publish(connectedState(selection.uri, flags, provider).copy(status = SafStorageStatus.SYNCING))
            }
        }
        result.exceptionOrNull()?.let { error ->
            if (previousState.treeUri != selection.uri) {
                runCatching { releaseGrant(selection.uri, flags) }
            }
            if (previousState.treeUri != null) {
                publish(previousState.copy(error = error.message ?: "Folder kandidat tidak dapat digunakan."))
            } else {
                publishFailure(null, error)
            }
        }
        return result
    }

    fun commitTreeSelection(pending: PendingSafTreeSelection): Result<Unit> = runCatching {
        val resolvedState = state.value
        check(
            preferences.edit()
                .putString(TREE_URI_KEY, pending.candidate.uri.toString())
                .putInt(TREE_FLAGS_KEY, pending.candidate.grantFlags)
                .putString(RESOLVED_ROOT_PATH_KEY, resolvedState.modelPath)
                .putString(RESOLVED_ROOT_DOCUMENT_ID_KEY, resolvedState.resolvedRootDocumentId)
                .putString(RESOLVED_ROOT_LAYOUT_KEY, resolvedState.resolvedRootLayout?.name)
                .commit()
        ) { "Pilihan folder model tidak dapat disimpan secara durable." }
        pending.previousUri
            ?.takeUnless { it == pending.candidate.uri }
            ?.let { previous -> releaseGrant(previous, pending.previousFlags) }
        publish(state.value.copy(status = SafStorageStatus.CONNECTED, connected = true, error = null))
    }

    fun rollbackTreeSelection(pending: PendingSafTreeSelection) {
        val previous = pending.previousUri
        if (previous == null) {
            check(
                preferences.edit().remove(TREE_URI_KEY).remove(TREE_FLAGS_KEY)
                    .remove(RESOLVED_ROOT_PATH_KEY).remove(RESOLVED_ROOT_DOCUMENT_ID_KEY)
                    .remove(RESOLVED_ROOT_LAYOUT_KEY).commit()
            ) {
                "Rollback folder model tidak dapat disimpan."
            }
            releaseGrant(pending.candidate.uri, pending.candidate.grantFlags)
            publish(ModelStorageState(error = "Pergantian folder dibatalkan; folder sebelumnya tetap tidak berubah."))
        } else {
            check(
                preferences.edit().putString(TREE_URI_KEY, previous.toString())
                    .putInt(TREE_FLAGS_KEY, pending.previousFlags)
                    .remove(RESOLVED_ROOT_PATH_KEY).remove(RESOLVED_ROOT_DOCUMENT_ID_KEY)
                    .remove(RESOLVED_ROOT_LAYOUT_KEY).commit()
            ) { "Folder model sebelumnya tidak dapat dipulihkan." }
            if (previous != pending.candidate.uri) {
                releaseGrant(pending.candidate.uri, pending.candidate.grantFlags)
            }
            runCatching {
                val provider = inspectLocalProvider(previous, 0L)
                publish(connectedState(previous, pending.previousFlags, provider))
            }.onFailure { publishFailure(previous, it) }
        }
    }

    fun selectTree(selection: SafTreeSelection, requiredBytes: Long = 0L): Result<Unit> =
        prepareTreeSelection(selection, requiredBytes).fold(
            onSuccess = { pending -> commitTreeSelection(pending) },
            onFailure = { error -> Result.failure(error) }
        )

    @Deprecated("Use the picker-provided grant flags")
    fun selectTree(uri: Uri): Result<Unit> = selectTree(SafTreeSelection(uri, READ_WRITE_FLAGS))

    fun disconnect() {
        val current = state.value.treeUri
        check(
            preferences.edit().remove(TREE_URI_KEY).remove(TREE_FLAGS_KEY)
                .remove(RESOLVED_ROOT_PATH_KEY).remove(RESOLVED_ROOT_DOCUMENT_ID_KEY)
                .remove(RESOLVED_ROOT_LAYOUT_KEY).commit()
        ) {
            "Status disconnect folder model tidak dapat disimpan."
        }
        if (current != null) {
            runCatching {
                releaseGrant(current, persistedGrantFlags(current))
            }
        }
        publish(ModelStorageState())
    }

    suspend fun publishGeneration(
        slot: ModelStorageSlot,
        generation: String,
        version: String,
        revision: String?,
        payloads: List<SafGenerationPayload>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        slotMutexes.getValue(slot).withLock {
            runCatching {
                requireSafeGeneration(generation)
                require(version.isNotBlank()) { "Versi artifact SAF wajib tersedia." }
                require(payloads.isNotEmpty()) { "Generation SAF tidak boleh kosong." }
                require(payloads.map { it.fileName }.distinct().size == payloads.size) {
                    "Generation SAF memiliki nama payload duplikat."
                }
                require(
                    payloads.none {
                        it.fileName == COMPLETE_MANIFEST_FILE || '/' in it.fileName || '\\' in it.fileName
                    }
                ) { "Nama payload SAF tidak valid." }
                payloads.forEach { payload ->
                    require(payload.source.isFile) { "Artifact sumber tidak ditemukan: ${payload.fileName}" }
                    require(payload.source.length() == payload.expectedSizeBytes) {
                        "Ukuran artifact tidak sesuai: ${payload.fileName}"
                    }
                    require(sha256(payload.source) == payload.expectedSha256.lowercase()) {
                        "Checksum artifact tidak sesuai: ${payload.fileName}"
                    }
                }
                val treeUri = state.value.treeUri ?: error("Pilih folder model terlebih dahulu.")
                val destinationDirectory = writeSlotDirectory(treeUri, slot, generation)
                if (
                    generationMatchesPayloads(
                        directory = destinationDirectory,
                        slot = slot,
                        generation = generation,
                        version = version,
                        revision = revision,
                        payloads = payloads
                    )
                ) {
                    return@runCatching Unit
                }
                findChild(destinationDirectory, COMPLETE_MANIFEST_FILE)?.let { existingMarker ->
                    check(DocumentsContract.deleteDocument(resolver, existingMarker)) {
                        "Completion marker lama tidak dapat dinonaktifkan."
                    }
                }
                payloads.forEach { payload ->
                    val part = createOrReplace(
                        destinationDirectory,
                        ".${payload.fileName}.part",
                        payload.mediaType
                    )
                    copyToDocument(payload.source.inputStream(), part)
                    val copied = digestDocument(part)
                    require(copied.bytes == payload.expectedSizeBytes) {
                        "Ukuran artifact SAF tidak sesuai: ${payload.fileName}"
                    }
                    require(copied.sha256 == payload.expectedSha256.lowercase()) {
                        "Checksum artifact SAF tidak sesuai: ${payload.fileName}"
                    }
                    activateDocument(destinationDirectory, part, payload.fileName, payload.mediaType)
                }
                val manifest = SafGenerationManifest(
                    schemaVersion = STORAGE_SCHEMA_VERSION,
                    slot = slot.directoryName,
                    generation = generation,
                    version = version,
                    revision = revision,
                    files = payloads.map { payload ->
                        SafGenerationManifestFile(
                            fileName = payload.fileName,
                            sizeBytes = payload.expectedSizeBytes,
                            sha256 = payload.expectedSha256.lowercase()
                        )
                    },
                    complete = true
                )
                val marker = createOrReplace(destinationDirectory, ".$COMPLETE_MANIFEST_FILE.part", MIME_JSON)
                resolver.openOutputStream(marker, "wt")?.use { output ->
                    output.writer(Charsets.UTF_8).use { writer -> gson.toJson(manifest, writer) }
                } ?: error("SAF completion manifest output stream tidak tersedia")
                require(digestDocument(marker).bytes > 0L) { "Completion manifest SAF kosong." }
                activateDocument(destinationDirectory, marker, COMPLETE_MANIFEST_FILE, MIME_JSON)
            }
        }
    }

    suspend fun deleteSlot(
        slot: ModelStorageSlot,
        trigger: ModelStorageDeletionTrigger = ModelStorageDeletionTrigger.EXPLICIT_USER_DELETE
    ): Result<Unit> = withContext(Dispatchers.IO) {
        slotMutexes.getValue(slot).withLock {
            runCatching {
                require(DurableModelStoragePolicy.mayDeleteExternalArtifacts(trigger)) {
                    "Durable model deletion requires an explicit user action."
                }
                val treeUri = state.value.treeUri ?: return@runCatching Unit
                val artifacts = ensureModelDirectoryUris(treeUri)
                val directory = ensureSlotDirectory(artifacts, slot)
                listChildren(directory).forEach { childName ->
                    findChild(directory, childName)?.let { document ->
                        check(DocumentsContract.deleteDocument(resolver, document)) {
                            "Generation durable tidak dapat dihapus: $childName"
                        }
                    }
                }
                legacySlotDirectory(slot)?.let { legacyDirectory ->
                    check(DocumentsContract.deleteDocument(resolver, legacyDirectory)) {
                        "Artifact durable legacy tidak dapat dihapus: ${slot.directoryName}"
                    }
                }
                Unit
            }.onFailure { error ->
                reportError(error.message ?: "Artifact durable tidak dapat dihapus.")
            }
        }
    }

    fun reportError(message: String) {
        val current = state.value
        mutableState.value = current.copy(
            connected = current.treeUri != null,
            status = if (current.treeUri == null) SafStorageStatus.UNSELECTED else SafStorageStatus.CONNECTED,
            error = message
        )
    }

    suspend fun readText(slot: ModelStorageSlot, fileName: String, generation: String? = null): String? = withContext(Dispatchers.IO) {
        runCatching {
            val document = findChild(slotDirectory(slot, generation), fileName) ?: return@runCatching null
            resolver.openInputStream(document)?.use { input ->
                input.reader(Charsets.UTF_8).use { it.readText() }
            }
        }.getOrNull()
    }

    suspend fun restoreArtifactIfMissing(
        slot: ModelStorageSlot,
        fileName: String,
        target: File,
        expectedSizeBytes: Long,
        expectedSha256: String,
        generation: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (target.isFile &&
                target.length() == expectedSizeBytes &&
                sha256(target) == expectedSha256.lowercase()
            ) {
                return@runCatching true
            }
            val document = runCatching { findChild(slotDirectory(slot, generation), fileName) }.getOrNull()
                ?: findLegacyArtifact(slot, fileName)
                ?: return@runCatching false
            val durableDigest = digestDocument(document)
            require(durableDigest.bytes == expectedSizeBytes) { "Durable artifact size mismatch: $fileName" }
            require(durableDigest.sha256 == expectedSha256.lowercase()) {
                "Durable artifact checksum mismatch: $fileName"
            }
            val temporary = File(target.parentFile, ".${target.name}.restore.part")
            temporary.parentFile?.mkdirs()
            resolver.openInputStream(document)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Durable artifact cannot be read: $fileName")
            require(temporary.length() == expectedSizeBytes)
            replaceLocalFileAtomically(temporary, target, fileName)
            true
        }.getOrDefault(false)
    }

    /**
     * Restores one complete generation payload after independently verifying the
     * SAF completion manifest and content digest. The caller owns the private
     * destination and must authenticate/decrypt user data before importing it.
     */
    suspend fun copyLatestGenerationFile(slot: ModelStorageSlot, fileName: String, target: File): Result<SafRestoredPayload?> =
        withContext(Dispatchers.IO) {
            slotMutexes.getValue(slot).withLock {
                runCatching {
                    require(state.value.connected) { "Hubungkan kembali folder SAF sebelum pemulihan." }
                    require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName) {
                        "Nama payload SAF tidak valid."
                    }
                    val treeUri = state.value.treeUri ?: error("Folder SAF tidak dipilih.")
                    val base = ensureSlotDirectory(ensureModelDirectoryUris(treeUri), slot)
                    val directory = completedGenerationDirectory(base, slot) ?: return@runCatching null
                    val manifest = readGenerationManifest(directory) ?: error("Manifest cadangan SAF tidak tersedia.")
                    val file = manifest.files.singleOrNull { it.fileName == fileName }
                        ?: error("Payload cadangan SAF tidak tersedia: $fileName")
                    val document = findChild(directory, fileName) ?: error("File cadangan SAF hilang: $fileName")
                    val temporary = File(target.parentFile, ".${target.name}.restore.part")
                    temporary.parentFile?.mkdirs()
                    try {
                        copyDocument(document, temporary)
                        require(temporary.length() == file.sizeBytes && sha256(temporary) == file.sha256.lowercase()) {
                            "Checksum cadangan SAF tidak cocok: $fileName"
                        }
                        replaceLocalFileAtomically(temporary, target, fileName)
                    } finally {
                        temporary.delete()
                    }
                    SafRestoredPayload(manifest.generation, file.sizeBytes, file.sha256.lowercase())
                }
            }
        }

    suspend fun restoreSpeechBundleIfMissing(
        bundleTarget: File,
        manifestTarget: File,
        generation: String? = null,
        forceRestorePair: Boolean = false,
        validateCandidatePair: (bundle: File, manifest: File) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        if (!state.value.connected) return@withContext false
        val restoreBundle = forceRestorePair || !isValidSpeechBundleCache(bundleTarget)
        val restoreManifest = forceRestorePair || !isValidSpeechManifestCache(manifestTarget)
        if (!restoreBundle && !restoreManifest) return@withContext false
        val canonicalDirectory = runCatching {
            slotDirectory(ModelStorageSlot.SPEECH_BUNDLE, generation)
        }.getOrNull()
        val legacyDirectory = legacySlotDirectory(ModelStorageSlot.SPEECH_BUNDLE)
        val directory = canonicalDirectory ?: legacyDirectory ?: return@withContext false
        if (directory == legacyDirectory) validateLegacyVoiceCapabilityIfPresent()
        val bundle = findChild(directory, "sherpa_bundle.zip")
            ?: return@withContext false
        val manifest = findChild(directory, "manifest.json")
            ?: findChild(directory, "sherpa_manifest.json")
            ?: return@withContext false
        runCatching {
            val bundleTemporary = if (restoreBundle) {
                File(bundleTarget.parentFile, ".${bundleTarget.name}.restore.part").also {
                    it.parentFile?.mkdirs()
                    copyDocument(bundle, it)
                }
            } else {
                null
            }
            val manifestTemporary = if (restoreManifest) {
                File(manifestTarget.parentFile, ".${manifestTarget.name}.restore.part").also {
                    it.parentFile?.mkdirs()
                    copyDocument(manifest, it)
                }
            } else {
                null
            }
            require(
                validateCandidatePair(
                    bundleTemporary ?: bundleTarget,
                    manifestTemporary ?: manifestTarget
                )
            ) { "Mirror Sherpa tidak cocok dengan katalog canonical." }
            val bundleBackup = File(bundleTarget.parentFile, ".${bundleTarget.name}.restore.previous")
            val manifestBackup = File(manifestTarget.parentFile, ".${manifestTarget.name}.restore.previous")
            check(!bundleBackup.exists() || bundleBackup.delete()) { "Sherpa bundle stale backup cannot be removed" }
            check(!manifestBackup.exists() || manifestBackup.delete()) { "Sherpa manifest stale backup cannot be removed" }
            if (restoreBundle && bundleTarget.isFile) {
                check(bundleTarget.renameTo(bundleBackup)) {
                    "Sherpa bundle backup failed"
                }
            }
            if (restoreManifest && manifestTarget.isFile) {
                check(manifestTarget.renameTo(manifestBackup)) {
                    "Sherpa manifest backup failed"
                }
            }
            try {
                bundleTemporary?.let {
                    check(it.renameTo(bundleTarget)) { "Sherpa bundle restore failed" }
                }
                manifestTemporary?.let {
                    check(it.renameTo(manifestTarget)) { "Sherpa manifest restore failed" }
                }
                bundleBackup.delete()
                manifestBackup.delete()
                true
            } catch (error: Throwable) {
                if (restoreBundle) {
                    bundleTarget.delete()
                    if (bundleBackup.isFile) bundleBackup.renameTo(bundleTarget)
                }
                if (restoreManifest) {
                    manifestTarget.delete()
                    if (manifestBackup.isFile) manifestBackup.renameTo(manifestTarget)
                }
                bundleTemporary?.delete()
                manifestTemporary?.delete()
                throw error
            }
        }.getOrDefault(false)
    }

    private fun isValidSpeechBundleCache(file: File): Boolean = runCatching {
        file.isFile &&
            file.length() > 0L &&
            ZipFile(file).use { zip ->
                zip.getEntry("manifest.json") != null || zip.getEntry("assets/manifest.json") != null
            }
    }.getOrDefault(false)

    private fun isValidSpeechManifestCache(file: File): Boolean = runCatching {
        file.isFile && file.length() > 0L && file.readText(Charsets.UTF_8).contains("bundle_version")
    }.getOrDefault(false)

    private fun loadState(): ModelStorageState {
        val uri = preferences.getString(TREE_URI_KEY, null)?.let(Uri::parse)
        return if (uri == null) {
            ModelStorageState()
        } else {
            // SAF access is validated asynchronously by reconcile(); do not
            // claim the folder is usable before the provider is reachable.
            ModelStorageState(
                treeUri = uri,
                connected = false,
                modelPath = preferences.getString(RESOLVED_ROOT_PATH_KEY, null) ?: "StressDetector/Artifacts",
                resolvedRootDocumentId = preferences.getString(RESOLVED_ROOT_DOCUMENT_ID_KEY, null),
                resolvedRootLayout = preferences.getString(RESOLVED_ROOT_LAYOUT_KEY, null)
                    ?.let { runCatching { SafArtifactRootLayout.valueOf(it) }.getOrNull() },
                status = SafStorageStatus.PREFLIGHTING
            )
        }
    }

    private fun publish(value: ModelStorageState) {
        mutableState.value = value
    }

    private fun publishFailure(uri: Uri?, error: Throwable) {
        val status = classifySafFailure(error)
        publish(
            state.value.copy(
                treeUri = uri,
                connected = false,
                status = status,
                error = error.message ?: "Folder model tidak tersedia.",
                lastAuditAtMillis = System.currentTimeMillis()
            )
        )
    }

    private fun slotDirectory(slot: ModelStorageSlot, generation: String? = null): Uri {
        val uri = state.value.treeUri ?: error("Pilih folder model terlebih dahulu.")
        val modelDirectory = ensureModelDirectoryUris(uri)
        val base = ensureSlotDirectory(modelDirectory, slot)
        return if (generation == null) {
            completedGenerationDirectory(base, slot)
        } else {
            requireSafeGeneration(generation)
            findChild(base, generation)?.takeIf { isCompleteGeneration(it, slot, generation) }
        }
            ?: error("Mirror ${slot.name} belum memiliki $COMPLETE_MANIFEST_FILE yang valid.")
    }

    private fun writeSlotDirectory(treeUri: Uri, slot: ModelStorageSlot, generation: String): Uri {
        val artifacts = ensureModelDirectoryUris(treeUri)
        val base = ensureSlotDirectory(artifacts, slot)
        requireSafeGeneration(generation)
        return ensureDirectory(base, generation)
    }

    private fun completedGenerationDirectory(base: Uri, slot: ModelStorageSlot): Uri? = listChildren(base)
        .sortedDescending()
        .firstNotNullOfOrNull { name ->
            runCatching {
                val directory = findChild(base, name) ?: return@runCatching null
                directory.takeIf { isCompleteGeneration(it, slot, name) }
            }.getOrNull()
        }

    private fun isCompleteGeneration(directory: Uri, slot: ModelStorageSlot, generation: String): Boolean {
        val manifest = readGenerationManifest(directory) ?: return false
        return generationManifestMatches(directory, manifest, slot, generation)
    }

    private fun generationMatchesPayloads(
        directory: Uri,
        slot: ModelStorageSlot,
        generation: String,
        version: String,
        revision: String?,
        payloads: List<SafGenerationPayload>
    ): Boolean {
        val manifest = readGenerationManifest(directory) ?: return false
        val expectedFiles = payloads.associate { payload ->
            payload.fileName to (payload.expectedSizeBytes to payload.expectedSha256.lowercase())
        }
        val recordedFiles = manifest.files.associate { file ->
            file.fileName to (file.sizeBytes to file.sha256.lowercase())
        }
        return manifest.version == version &&
            manifest.revision == revision &&
            recordedFiles == expectedFiles &&
            generationManifestMatches(directory, manifest, slot, generation)
    }

    private fun readGenerationManifest(directory: Uri): SafGenerationManifest? {
        val marker = findChild(directory, COMPLETE_MANIFEST_FILE) ?: return null
        return runCatching {
            resolver.openInputStream(marker)?.use { input ->
                input.reader(Charsets.UTF_8).use { reader ->
                    gson.fromJson(reader, SafGenerationManifest::class.java)
                }
            }
        }.getOrNull()
    }

    private fun generationManifestMatches(
        directory: Uri,
        manifest: SafGenerationManifest,
        slot: ModelStorageSlot,
        generation: String
    ): Boolean = manifest.schemaVersion == STORAGE_SCHEMA_VERSION &&
        manifest.slot == slot.directoryName &&
        manifest.generation == generation &&
        manifest.version.isNotBlank() &&
        manifest.files.isNotEmpty() &&
        manifest.files.map(SafGenerationManifestFile::fileName).distinct().size == manifest.files.size &&
        manifest.files.all { file ->
            file.sizeBytes > 0L &&
                file.sha256.matches(SHA256_PATTERN) &&
                findChild(directory, file.fileName)?.let { document ->
                    runCatching { digestDocument(document) }.getOrNull()?.let { digest ->
                        digest.bytes == file.sizeBytes && digest.sha256 == file.sha256.lowercase()
                    }
                } == true
        } &&
        manifest.complete

    private fun requireSafeGeneration(value: String) {
        require(value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) {
            "Nama generation artifact tidak valid."
        }
    }

    private fun ensureModelDirectoryUris(treeUri: Uri): Uri {
        val root = resolveArtifactRoot(treeUri, createIfMissing = true).rootUri
            ?: error("Root artifact SAF tidak dapat dibuat.")
        val artifacts = ensureDirectory(root, "Artifacts")
        ModelStorageSlot.entries.forEach { slot -> ensureSlotDirectory(artifacts, slot) }
        ensureRootMarker(artifacts)
        return artifacts
    }

    private fun ensureSlotDirectory(modelDirectory: Uri, slot: ModelStorageSlot): Uri = ensureDirectory(modelDirectory, slot.directoryName)

    private fun ensureRootMarker(modelDirectory: Uri) {
        val marker = findChild(modelDirectory, ROOT_MARKER_FILE)
        if (marker == null) {
            val created = DocumentsContract.createDocument(
                resolver,
                modelDirectory,
                MIME_JSON,
                ROOT_MARKER_FILE
            ) ?: error("Marker storage tidak dapat dibuat.")
            writeRootMarker(created)
            return
        }
        val content = resolver.openInputStream(marker)?.use { input ->
            input.reader(Charsets.UTF_8).use { it.readText() }
        } ?: error("Marker storage tidak dapat dibaca.")
        require(content.contains("\"root\":\"StressDetector/Artifacts\"")) {
            "Marker storage tidak kompatibel."
        }
        if (content.contains("\"schemaVersion\":$STORAGE_SCHEMA_VERSION")) return
        require(content.contains("\"schemaVersion\":1") || content.contains("\"schemaVersion\":2")) {
            "Marker storage tidak kompatibel."
        }

        val part = createOrReplace(modelDirectory, "$ROOT_MARKER_FILE.part", MIME_JSON)
        writeRootMarker(part)
        activateDocument(modelDirectory, part, ROOT_MARKER_FILE, MIME_JSON)
    }

    private fun writeRootMarker(document: Uri) {
        resolver.openOutputStream(document, "wt")?.use { output ->
            output.writer(Charsets.UTF_8).use { writer ->
                writer.write(
                    "{\"schemaVersion\":$STORAGE_SCHEMA_VERSION,\"root\":\"StressDetector/Artifacts\"}"
                )
            }
        } ?: error("Marker storage tidak dapat ditulis.")
    }

    private fun ensureDirectory(parent: Uri, name: String): Uri {
        findChild(parent, name)?.let { return it }
        return DocumentsContract.createDocument(
            resolver,
            parent,
            DocumentsContract.Document.MIME_TYPE_DIR,
            name
        ) ?: error("Folder model tidak dapat dibuat: $name")
    }

    private fun findChild(parent: Uri, displayName: String): Uri? {
        require(displayName.isNotBlank()) { "Nama artifact tidak boleh kosong." }
        require('/' !in displayName && '\\' !in displayName) {
            "Nama artifact tidak valid."
        }
        val documentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentId)
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1) != displayName) continue
                val id = cursor.getString(0)
                return documentUri(parent, id)
            }
        }
        return null
    }

    private fun resolveArtifactRoot(treeUri: Uri, createIfMissing: Boolean): ResolvedSafArtifactRoot {
        val selected = documentUri(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val stressDetector = findChild(selected, "StressDetector")
        val stressDetection = findChild(selected, "StressDetection")
        val decision = SafArtifactRootResolver.resolve(
            SafArtifactRootEvidence(
                selectedDisplayName = documentDisplayName(selected),
                selected = rootEvidence(selected),
                stressDetectorChild = rootEvidence(stressDetector),
                stressDetectionChild = rootEvidence(stressDetection)
            )
        )
        val root = when (decision.layout) {
            SafArtifactRootLayout.SELECTED_TREE -> selected
            SafArtifactRootLayout.CHILD_STRESS_DETECTOR -> stressDetector
            SafArtifactRootLayout.CHILD_STRESS_DETECTION -> stressDetection
            SafArtifactRootLayout.NEW_CHILD_STRESS_DETECTOR -> if (createIfMissing) {
                ensureDirectory(selected, "StressDetector")
            } else {
                null
            }
        }
        return ResolvedSafArtifactRoot(root, decision)
    }

    private fun rootEvidence(uri: Uri?): SafArtifactRootCandidateEvidence {
        if (uri == null) return SafArtifactRootCandidateEvidence(exists = false)
        val artifacts = findChild(uri, "Artifacts")
        return SafArtifactRootCandidateEvidence(
            exists = true,
            hasArtifacts = artifacts != null,
            hasLegacyModel = findChild(uri, LegacySafPaths.MODEL_DIRECTORY) != null,
            hasMarker = artifacts?.let { findChild(it, ROOT_MARKER_FILE) } != null
        )
    }

    private fun documentDisplayName(uri: Uri): String? = resolver.query(
        uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
        null,
        null,
        null
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun listChildren(parent: Uri): List<String> {
        val documentId = DocumentsContract.getDocumentId(parent)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, documentId)
        val names = mutableListOf<String>()
        resolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let(names::add)
            }
        }
        return names
    }

    private fun documentUri(treeUri: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    private fun createOrReplace(parent: Uri, name: String, mimeType: String): Uri {
        findChild(parent, name)?.let { existing ->
            DocumentsContract.deleteDocument(resolver, existing)
        }
        return DocumentsContract.createDocument(resolver, parent, mimeType, name)
            ?: error("Artifact temporary tidak dapat dibuat: $name")
    }

    private fun activateDocument(parent: Uri, part: Uri, fileName: String, mimeType: String) {
        findChild(parent, fileName)?.let { active ->
            check(DocumentsContract.deleteDocument(resolver, active)) {
                "Artifact SAF lama tidak dapat dinonaktifkan: $fileName"
            }
        }
        var published: Uri? = null
        try {
            val activated = DocumentsContract.renameDocument(resolver, part, fileName)
                ?: createOrReplace(parent, fileName, mimeType).also { destination ->
                    resolver.openInputStream(part)?.use { input -> copyToDocument(input, destination) }
                        ?: error("Artifact durable tidak dapat disalin tanpa rename: $fileName")
                    check(DocumentsContract.deleteDocument(resolver, part)) {
                        "Artifact temporary tidak dapat dibersihkan: $fileName"
                    }
                }
            published = activated
            require(digestDocument(activated).bytes > 0L) { "Artifact durable kosong: $fileName" }
        } catch (error: Throwable) {
            published?.let { DocumentsContract.deleteDocument(resolver, it) }
            throw error
        }
    }

    private fun copyToDocument(input: InputStream, destination: Uri) {
        input.use { source ->
            resolver.openOutputStream(destination, "wt")?.use { output ->
                source.copyTo(output)
            } ?: error("SAF output stream tidak tersedia")
        }
    }

    private fun copyDocument(source: Uri, target: File) {
        resolver.openInputStream(source)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("SAF input stream tidak tersedia")
    }

    private fun replaceLocalFileAtomically(temporary: File, target: File, label: String) {
        val backup = File(target.parentFile, ".${target.name}.restore.previous")
        if (backup.exists() && !backup.delete()) {
            error("Backup runtime artifact tidak dapat dibersihkan: $label")
        }
        val hadTarget = target.exists()
        if (hadTarget && !target.renameTo(backup)) {
            error("Runtime artifact aktif tidak dapat diamankan: $label")
        }
        try {
            check(temporary.renameTo(target)) { "Runtime artifact cannot be restored: $label" }
            if (hadTarget) backup.delete()
        } catch (error: Throwable) {
            target.delete()
            if (hadTarget && backup.isFile) backup.renameTo(target)
            throw error
        }
    }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private data class DocumentDigest(val bytes: Long, val sha256: String)

    private fun digestDocument(uri: Uri): DocumentDigest = resolver.openInputStream(uri)?.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var bytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            bytes = Math.addExact(bytes, count.toLong())
            digest.update(buffer, 0, count)
        }
        DocumentDigest(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    } ?: error("SAF input stream tidak tersedia")

    private data class SafProviderInfo(val authority: String, val rootId: String, val availableBytes: Long?)

    private fun inspectLocalProvider(treeUri: Uri, requiredBytes: Long): SafProviderInfo {
        val authority = treeUri.authority?.takeIf(String::isNotBlank)
            ?: throw SafUnsupportedProviderException("Provider SAF tidak memiliki authority yang valid.")
        val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS
        )
        val selectedTree = documentUri(treeUri, treeDocumentId)
        val metadata = resolver.query(selectedTree, projection, null, null, null)?.use { cursor ->
            require(cursor.moveToFirst()) { "Folder SAF tidak dapat dibaca." }
            Triple(
                cursor.getString(0).orEmpty(),
                cursor.getString(1).orEmpty(),
                cursor.getInt(2)
            )
        } ?: throw SafUnsupportedProviderException("Folder SAF tidak dapat diverifikasi.")
        val availableBytes = bestEffortAvailableBytes(treeDocumentId)
        SafTreeDocumentPolicy.validateSelectedTree(
            authority = authority,
            requestedDocumentId = treeDocumentId,
            returnedDocumentId = metadata.first,
            mimeType = metadata.second,
            documentFlags = metadata.third,
            availableBytes = availableBytes,
            requiredBytes = requiredBytes
        )
        return SafProviderInfo(authority, treeDocumentId, availableBytes)
    }

    /**
     * Capacity is advisory. The SAF grant is authoritative for access, so an
     * unavailable capacity reading must not be reported as a permission loss.
     */
    @Suppress("DEPRECATION")
    private fun bestEffortAvailableBytes(treeDocumentId: String): Long? = runCatching {
        val volumeId = treeDocumentId.substringBefore(':')
        val volumeRoot = when {
            volumeId == "primary" -> Environment.getExternalStorageDirectory()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                appContext.getSystemService(StorageManager::class.java)
                    ?.storageVolumes
                    ?.firstOrNull { it.uuid == volumeId }
                    ?.directory
            }
            else -> null
        } ?: return@runCatching null
        StatFs(volumeRoot.absolutePath).availableBytes
    }.getOrNull()

    private fun preflightTree(treeUri: Uri) {
        val root = documentUri(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val flags = resolver.query(
            root,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS),
            null,
            null,
            null
        )?.use { cursor ->
            require(cursor.moveToFirst()) { "Folder SAF tidak dapat dibaca." }
            require(cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR) {
                "Pilihan SAF bukan folder."
            }
            cursor.getInt(1)
        } ?: throw SafUnsupportedProviderException("Kapabilitas folder SAF tidak tersedia.")
        require(flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0) {
            "Provider SAF tidak mendukung pembuatan file."
        }
        val directoryName = ".stressdetector-preflight-${UUID.randomUUID()}"
        val directory = DocumentsContract.createDocument(
            resolver,
            root,
            DocumentsContract.Document.MIME_TYPE_DIR,
            directoryName
        ) ?: throw SafUnsupportedProviderException("Provider SAF tidak dapat membuat folder uji.")
        val expected = PREFLIGHT_CONTENT.toByteArray(Charsets.UTF_8)
        try {
            val document = DocumentsContract.createDocument(resolver, directory, MIME_BINARY, "probe.bin")
                ?: throw SafUnsupportedProviderException("Provider SAF bersifat read-only.")
            resolver.openOutputStream(document, "wt")?.use { output ->
                output.write(expected)
                output.flush()
            } ?: throw SafUnsupportedProviderException("Provider SAF tidak dapat ditulis.")
            val digest = digestDocument(document)
            require(digest.bytes == expected.size.toLong() && digest.sha256 == sha256(expected.inputStream())) {
                "Provider SAF gagal membaca ulang file preflight secara konsisten."
            }
        } finally {
            check(DocumentsContract.deleteDocument(resolver, directory)) {
                "Provider SAF tidak mendukung penghapusan folder uji."
            }
        }
    }

    private fun connectedState(uri: Uri, flags: Int, provider: SafProviderInfo): ModelStorageState {
        val resolved = resolveArtifactRoot(uri, createIfMissing = false)
        return ModelStorageState(
            treeUri = uri,
            connected = true,
            modelPath = "${resolved.decision.relativeRootPath}/Artifacts".removePrefix("./"),
            resolvedRootDocumentId = resolved.rootUri?.let(DocumentsContract::getDocumentId),
            resolvedRootLayout = resolved.decision.layout,
            schemaVersion = STORAGE_SCHEMA_VERSION,
            status = SafStorageStatus.CONNECTED,
            providerAuthority = provider.authority,
            providerRootId = provider.rootId,
            localOnly = true,
            persistedReadPermission = flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0,
            persistedWritePermission = flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0,
            availableBytes = provider.availableBytes,
            lastAuditAtMillis = System.currentTimeMillis(),
            legacyLocations = legacyLocations(uri)
        )
    }

    private fun legacyLocations(treeUri: Uri): List<String> = runCatching {
        val stressDetector = resolveArtifactRoot(treeUri, createIfMissing = false).rootUri
            ?: return@runCatching emptyList()
        val legacyRoots = buildList {
            findChild(stressDetector, LegacySafPaths.MODEL_DIRECTORY)?.let { modelDirectory ->
                listChildren(modelDirectory).forEach { name ->
                    add("${LegacySafPaths.ROOT_DIRECTORY}/${LegacySafPaths.MODEL_DIRECTORY}/$name")
                }
            }
            listOf(LegacySafPaths.VOICE_DIRECTORY, "PersonaAI", "DokumenRAG", "RAG_INDEX").forEach { name ->
                if (findChild(stressDetector, name) != null) {
                    add("${LegacySafPaths.ROOT_DIRECTORY}/$name")
                }
            }
        }
        legacyRoots.distinct().sorted()
    }.getOrDefault(emptyList())

    private fun findLegacyArtifact(slot: ModelStorageSlot, fileName: String): Uri? =
        legacySlotDirectory(slot)?.let { directory -> findChild(directory, fileName) }

    private fun legacySlotDirectory(slot: ModelStorageSlot): Uri? {
        val treeUri = state.value.treeUri ?: return null
        val stressDetector = resolveArtifactRoot(treeUri, createIfMissing = false).rootUri ?: return null
        val model = findChild(stressDetector, LegacySafPaths.MODEL_DIRECTORY) ?: return null
        if (slot == ModelStorageSlot.MULTILINGUAL_EMBEDDING) {
            // Older releases stored E5 under a named child of Model, without the
            // canonical Artifacts generation/manifest. Resolve only children inside
            // the granted tree and require the requested payload to be present.
            val candidates = buildList {
                add(model)
                listOf(
                    slot.directoryName,
                    "Embedding",
                    "embedding",
                    "E5",
                    "e5",
                    "multilingual_e5_small"
                ).forEach { name -> findChild(model, name)?.let(::add) }
            }
            return candidates.firstOrNull { directory ->
                findChild(directory, EmbeddingArtifactNames.MODEL_FILE) != null ||
                    findChild(directory, EmbeddingArtifactNames.TOKENIZER_FILE) != null
            }
        }
        val legacyName = when (slot) {
            ModelStorageSlot.GEMMA -> LegacySafPaths.GEMMA_DIRECTORY
            ModelStorageSlot.SPEECH_BUNDLE -> LegacySafPaths.SPEECH_BUNDLE_DIRECTORY
            ModelStorageSlot.MULTILINGUAL_EMBEDDING -> return null
            ModelStorageSlot.PERSONA_AI -> return null
            ModelStorageSlot.USER_DATA_BACKUP -> return null
        }
        return findChild(model, legacyName)
    }

    private object EmbeddingArtifactNames {
        const val MODEL_FILE = "model.onnx"
        const val TOKENIZER_FILE = "tokenizer.json"
    }

    /** VoiceAI describes the shared Sherpa capability; it is metadata, not a replacement model. */
    private fun validateLegacyVoiceCapabilityIfPresent() {
        val capability = legacyVoiceCapabilityDocument() ?: return
        val content = resolver.openInputStream(capability)?.use { input ->
            input.reader(Charsets.UTF_8).use { it.readText() }
        } ?: error("Capability VoiceAI legacy tidak dapat dibaca.")
        require(
            content.contains(LegacySafPaths.SPEECH_BUNDLE_DIRECTORY) &&
                content.contains("shared-speech-tts")
        ) { "Capability VoiceAI legacy tidak cocok dengan Sherpa bundle bersama." }
    }

    private fun legacyVoiceCapabilityDocument(): Uri? {
        val treeUri = state.value.treeUri ?: return null
        val stressDetector = resolveArtifactRoot(treeUri, createIfMissing = false).rootUri ?: return null
        val model = findChild(stressDetector, LegacySafPaths.MODEL_DIRECTORY)
        val voiceDirectory = model?.let { findChild(it, LegacySafPaths.VOICE_DIRECTORY) }
            ?: findChild(stressDetector, LegacySafPaths.VOICE_DIRECTORY)
            ?: return null
        return findChild(voiceDirectory, LegacySafPaths.VOICE_CAPABILITY_FILE)
    }

    private fun persistedGrantFlags(uri: Uri): Int = resolver.persistedUriPermissions
        .firstOrNull { it.uri == uri }
        ?.let { permission ->
            (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        } ?: 0

    private fun requireReadWriteGrant(flags: Int) {
        require(flags and READ_WRITE_FLAGS == READ_WRITE_FLAGS) {
            "Folder harus memberikan izin baca dan tulis yang persisten."
        }
    }

    private fun releaseGrant(uri: Uri, flags: Int) {
        val granted = flags and READ_WRITE_FLAGS
        if (granted != 0) resolver.releasePersistableUriPermission(uri, granted)
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PREFERENCES_NAME = "model_storage"
        private const val TREE_URI_KEY = "tree_uri"
        private const val TREE_FLAGS_KEY = "tree_flags"
        private const val RESOLVED_ROOT_PATH_KEY = "resolved_root_path"
        private const val RESOLVED_ROOT_DOCUMENT_ID_KEY = "resolved_root_document_id"
        private const val RESOLVED_ROOT_LAYOUT_KEY = "resolved_root_layout"
        private const val STORAGE_SCHEMA_VERSION = 3
        private const val ROOT_MARKER_FILE = ".storage_manifest.json"
        const val COMPLETE_MANIFEST_FILE = "complete-manifest.json"
        private const val MIME_BINARY = "application/octet-stream"
        private const val MIME_JSON = "application/json"
        private const val PREFLIGHT_CONTENT = "StressDetector SAF v3 preflight"
        private const val READ_WRITE_FLAGS = SafTreeDocumentPolicy.READ_WRITE_GRANT_FLAGS
        private val SHA256_PATTERN = Regex("^[a-f0-9]{64}$")
    }
}

private data class ResolvedSafArtifactRoot(val rootUri: Uri?, val decision: SafArtifactRootDecision)

private data class SafGenerationManifest(
    val schemaVersion: Int,
    val slot: String,
    val generation: String,
    val version: String,
    val revision: String?,
    val files: List<SafGenerationManifestFile>,
    val complete: Boolean
)

private data class SafGenerationManifestFile(val fileName: String, val sizeBytes: Long, val sha256: String)

private class SafUnsupportedProviderException(message: String) : IllegalStateException(message)

private class SafInsufficientSpaceException(message: String) : IllegalStateException(message)
