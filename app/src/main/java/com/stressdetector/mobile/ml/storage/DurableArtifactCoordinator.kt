package com.stressdetector.mobile.ml.storage

import com.stressdetector.mobile.ml.artifacts.ArtifactState
import com.stressdetector.mobile.ml.artifacts.EmbeddingArtifactManager
import com.stressdetector.mobile.ml.llm.ModelManager
import com.stressdetector.mobile.ml.llm.ModelReadiness
import com.stressdetector.mobile.ml.speech.SpeechBundleReadiness
import com.stressdetector.mobile.ml.speech.SpeechCapabilityReadiness
import com.stressdetector.mobile.ml.speech.SpeechModelBundleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class DurableArtifact {
    GEMMA,
    SPEECH_BUNDLE,
    VOICE_CAPABILITY,
    MULTILINGUAL_EMBEDDING
}

/** Origin of a ready app-private artifact. No source is inferred for failed recovery. */
enum class DurableArtifactSource {
    CANONICAL_SAF,
    LEGACY_SAF,
    APP_PRIVATE
}

/** UI-safe recovery state. Detailed provider failures remain diagnostic-only. */
enum class DurableArtifactRecoveryState {
    RESTORING,
    CHECKSUM_READY,
    RUNTIME_INCOMPATIBLE,
    READY,
    ERROR,
    CORRUPT,
    MISSING,
    PERMISSION_LOST
}

/**
 * Immutable provenance for one durable artifact. A ready checksum is supplied by the
 * owning runtime manager; the coordinator never re-hashes multi-gigabyte files solely
 * for UI rendering.
 */
data class DurableArtifactRecovery(
    val source: DurableArtifactSource? = null,
    val state: DurableArtifactRecoveryState,
    val sizeBytes: Long? = null,
    val checksumSha256: String? = null,
    val checksumVerified: Boolean = false,
    val networkDownloadRequired: Boolean = false,
    val detail: String? = null
)

/** A missing optional runtime cache is expected, not a SAF connection failure. */
sealed interface DurableArtifactSyncStatus {
    data object Restoring : DurableArtifactSyncStatus
    data object Synced : DurableArtifactSyncStatus
    data object AlreadyCurrent : DurableArtifactSyncStatus
    data object Restored : DurableArtifactSyncStatus
    data class MissingLocalSource(val message: String) : DurableArtifactSyncStatus
    data class Corrupt(val message: String) : DurableArtifactSyncStatus
    data class PermissionLost(val message: String) : DurableArtifactSyncStatus
    data class RuntimeIncompatible(val message: String) : DurableArtifactSyncStatus
    data class Failed(val message: String) : DurableArtifactSyncStatus
}

data class DurableSyncReport(
    val storage: ModelStorageState,
    val artifacts: Map<DurableArtifact, DurableArtifactSyncStatus>,
    val recovery: Map<DurableArtifact, DurableArtifactRecovery> = emptyMap()
) {
    private fun effectiveRecovery(): List<DurableArtifactRecovery> = DurableArtifact.entries.map(::recoveryOf)

    fun statusOf(artifact: DurableArtifact): DurableArtifactSyncStatus =
        artifacts[artifact] ?: DurableArtifactSyncStatus.MissingLocalSource("Belum diperiksa.")

    fun recoveryOf(artifact: DurableArtifact): DurableArtifactRecovery = recovery[artifact] ?: recoveryFor(statusOf(artifact))

    fun isRestoring(): Boolean = effectiveRecovery().any {
        it.state == DurableArtifactRecoveryState.RESTORING
    }

    fun networkDownloadRequired(artifact: DurableArtifact): Boolean = recoveryOf(artifact).state in setOf(
        DurableArtifactRecoveryState.MISSING,
        DurableArtifactRecoveryState.CORRUPT
    )

    fun userMessage(): String = when {
        isRestoring() -> "Memulihkan artefak offline"
        !storage.connected -> "Hubungkan kembali folder"
        effectiveRecovery().any { it.state == DurableArtifactRecoveryState.PERMISSION_LOST } ->
            "Hubungkan kembali folder"
        effectiveRecovery().any { it.state == DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE } ->
            "Model tersedia, runtime tidak kompatibel"
        effectiveRecovery().any { it.state == DurableArtifactRecoveryState.ERROR } ->
            "Periksa status artefak"
        effectiveRecovery().any { it.state == DurableArtifactRecoveryState.CORRUPT } ->
            "Periksa artefak yang rusak"
        effectiveRecovery().any { it.state == DurableArtifactRecoveryState.MISSING } ->
            "Sebagian artefak belum tersedia"
        effectiveRecovery().isNotEmpty() -> "Folder model siap"
        else -> "Folder model tersambung"
    }

    fun technicalMessages(): List<String> = buildList {
        storage.error?.let(::add)
        artifacts.values.forEach { status ->
            when (status) {
                is DurableArtifactSyncStatus.Corrupt -> add(status.message)
                is DurableArtifactSyncStatus.PermissionLost -> add(status.message)
                is DurableArtifactSyncStatus.RuntimeIncompatible -> add(status.message)
                is DurableArtifactSyncStatus.Failed -> add(status.message)
                else -> Unit
            }
        }
    }.distinct()

    companion object {
        fun restoring(storage: ModelStorageState): DurableSyncReport = DurableSyncReport(
            storage = storage,
            artifacts = DurableArtifact.entries.associateWith { DurableArtifactSyncStatus.Restoring },
            recovery = DurableArtifact.entries.associateWith {
                DurableArtifactRecovery(state = DurableArtifactRecoveryState.RESTORING)
            }
        )

        private fun recoveryFor(status: DurableArtifactSyncStatus): DurableArtifactRecovery = when (status) {
            DurableArtifactSyncStatus.Restoring ->
                DurableArtifactRecovery(state = DurableArtifactRecoveryState.RESTORING)
            DurableArtifactSyncStatus.Synced,
            DurableArtifactSyncStatus.AlreadyCurrent,
            DurableArtifactSyncStatus.Restored ->
                DurableArtifactRecovery(
                    source = DurableArtifactSource.APP_PRIVATE,
                    state = DurableArtifactRecoveryState.READY,
                    checksumVerified = true
                )
            is DurableArtifactSyncStatus.Corrupt ->
                DurableArtifactRecovery(
                    state = DurableArtifactRecoveryState.CORRUPT,
                    networkDownloadRequired = true,
                    detail = status.message
                )
            is DurableArtifactSyncStatus.MissingLocalSource ->
                DurableArtifactRecovery(
                    state = DurableArtifactRecoveryState.MISSING,
                    networkDownloadRequired = true,
                    detail = status.message
                )
            is DurableArtifactSyncStatus.PermissionLost ->
                DurableArtifactRecovery(
                    state = DurableArtifactRecoveryState.PERMISSION_LOST,
                    networkDownloadRequired = false,
                    detail = status.message
                )
            is DurableArtifactSyncStatus.RuntimeIncompatible ->
                DurableArtifactRecovery(
                    state = DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
                    networkDownloadRequired = false,
                    detail = status.message
                )
            is DurableArtifactSyncStatus.Failed ->
                DurableArtifactRecovery(
                    state = DurableArtifactRecoveryState.ERROR,
                    networkDownloadRequired = false,
                    detail = status.message
                )
        }
    }
}

private data class ArtifactAssessment(val status: DurableArtifactSyncStatus, val recovery: DurableArtifactRecovery)

private data class SpeechAssessment(val artifact: ArtifactAssessment, val capabilities: SpeechCapabilityReadiness)

/**
 * Coordinates durable storage without exposing SAF I/O to a ViewModel.
 * Runtime managers remain the only owners of their own artifact validation.
 */
class DurableArtifactCoordinator(
    private val storage: ModelStorageManager,
    private val modelManager: ModelManager,
    private val speechBundleManager: SpeechModelBundleManager,
    private val embeddingArtifactManager: EmbeddingArtifactManager
) {
    suspend fun restore(): DurableSyncReport = withContext(Dispatchers.IO) {
        val storageState = storage.reconcile()
        if (!storageState.connected) return@withContext unavailableReport(storageState)

        val gemma = restoreGemma(storageState)
        val speech = restoreSpeech(storageState)
        val embedding = restoreEmbedding(storageState)
        val voiceCapability = voiceCapabilityAssessment(speech)

        DurableSyncReport(
            storage = storageState,
            artifacts = mapOf(
                DurableArtifact.GEMMA to gemma.status,
                DurableArtifact.SPEECH_BUNDLE to speech.artifact.status,
                DurableArtifact.VOICE_CAPABILITY to voiceCapability.status,
                DurableArtifact.MULTILINGUAL_EMBEDDING to embedding.status
            ),
            recovery = mapOf(
                DurableArtifact.GEMMA to gemma.recovery,
                DurableArtifact.SPEECH_BUNDLE to speech.artifact.recovery,
                DurableArtifact.VOICE_CAPABILITY to voiceCapability.recovery,
                DurableArtifact.MULTILINGUAL_EMBEDDING to embedding.recovery
            )
        )
    }

    suspend fun selectAndSynchronize(selection: SafTreeSelection): DurableSyncReport = withContext(Dispatchers.IO) {
        val pending = storage.prepareTreeSelection(selection, requiredMirrorBytes()).getOrElse {
            return@withContext unavailableReport(storage.state.value)
        }
        val report = synchronize()
        val switchFailed = report.artifacts.values.any {
            it is DurableArtifactSyncStatus.Failed ||
                it is DurableArtifactSyncStatus.PermissionLost ||
                it is DurableArtifactSyncStatus.Corrupt
        }
        if (switchFailed) {
            storage.rollbackTreeSelection(pending)
            return@withContext report.copy(storage = storage.state.value)
        }
        storage.commitTreeSelection(pending).fold(
            onSuccess = { report.copy(storage = storage.state.value) },
            onFailure = { error ->
                storage.rollbackTreeSelection(pending)
                report.copy(
                    storage = storage.state.value.copy(
                        error = error.message ?: "Folder model baru tidak dapat disimpan."
                    )
                )
            }
        )
    }

    suspend fun synchronize(): DurableSyncReport = withContext(Dispatchers.IO) {
        val restored = restore()
        if (!restored.storage.connected) return@withContext restored

        val gemma = syncGemma(restored.statusOf(DurableArtifact.GEMMA))
        val speech = syncSpeech(restored.statusOf(DurableArtifact.SPEECH_BUNDLE))
        DurableSyncReport(
            storage = storage.state.value,
            artifacts = mapOf(
                DurableArtifact.GEMMA to gemma,
                DurableArtifact.SPEECH_BUNDLE to speech,
                DurableArtifact.VOICE_CAPABILITY to restored.statusOf(DurableArtifact.VOICE_CAPABILITY),
                DurableArtifact.MULTILINGUAL_EMBEDDING to syncEmbedding(
                    restored.statusOf(DurableArtifact.MULTILINGUAL_EMBEDDING)
                )
            ),
            recovery = restored.recovery
        )
    }

    private suspend fun restoreGemma(storageState: ModelStorageState): ArtifactAssessment {
        val before = modelManager.currentReadiness()
        val readiness = modelManager.refreshReadiness()
        val restored = before !is ModelReadiness.Ready && readiness is ModelReadiness.Ready
        return ArtifactAssessment(
            status = modelReadinessStatus(readiness, restored),
            recovery = gemmaRecovery(readiness, restored, storageState)
        )
    }

    private suspend fun restoreSpeech(storageState: ModelStorageState): SpeechAssessment {
        val before = speechBundleManager.refreshReadiness()
        val current = speechBundleManager.ensureReadiness(force = true)
        val restored = before !is SpeechBundleReadiness.Ready && current is SpeechBundleReadiness.Ready
        return SpeechAssessment(
            artifact = ArtifactAssessment(
                status = speechReadinessStatus(current, restored),
                recovery = speechRecovery(current, restored, storageState)
            ),
            capabilities = speechBundleManager.capabilityReadiness()
        )
    }

    private suspend fun restoreEmbedding(storageState: ModelStorageState): ArtifactAssessment {
        val status = embeddingStatus(embeddingArtifactManager.reconcile())
        val ready = status is DurableArtifactSyncStatus.AlreadyCurrent || status is DurableArtifactSyncStatus.Restored
        val size = listOf(embeddingArtifactManager.modelFile(), embeddingArtifactManager.tokenizerFile())
            .filter { it.isFile }
            .sumOf { it.length() }
            .takeIf { it > 0L }
        return ArtifactAssessment(
            status = status,
            recovery = recoveryForStatus(
                status = status,
                source = if (ready) DurableArtifactSource.APP_PRIVATE else null,
                sizeBytes = size,
                checksumVerified = ready
            )
        )
    }

    private fun voiceCapabilityAssessment(speech: SpeechAssessment): ArtifactAssessment {
        val speechRecovery = speech.artifact.recovery
        if (speechRecovery.state == DurableArtifactRecoveryState.PERMISSION_LOST) {
            return ArtifactAssessment(
                DurableArtifactSyncStatus.PermissionLost("Folder capability suara perlu dihubungkan ulang."),
                speechRecovery.copy(detail = "Capability suara memakai bundle Sherpa bersama.")
            )
        }
        if (speechRecovery.state == DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE ||
            speechRecovery.state == DurableArtifactRecoveryState.CHECKSUM_READY
        ) {
            return ArtifactAssessment(
                DurableArtifactSyncStatus.RuntimeIncompatible("Bundle Sherpa tersedia, tetapi runtime belum kompatibel."),
                speechRecovery.copy(
                    state = DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
                    networkDownloadRequired = false,
                    detail = "Capability VoiceAI memakai bundle Sherpa bersama."
                )
            )
        }
        if (speech.capabilities.voiceAiReady && speechRecovery.state == DurableArtifactRecoveryState.READY) {
            return ArtifactAssessment(
                speech.artifact.status,
                speechRecovery.copy(
                    sizeBytes = 0L,
                    detail = "Capability VoiceAI memakai bundle Sherpa bersama."
                )
            )
        }
        val missing = speech.capabilities.missingComponents.joinToString().ifBlank { "bundle Sherpa" }
        return ArtifactAssessment(
            DurableArtifactSyncStatus.MissingLocalSource("Capability VoiceAI belum lengkap: $missing."),
            DurableArtifactRecovery(
                state = DurableArtifactRecoveryState.MISSING,
                networkDownloadRequired = true,
                detail = "Capability VoiceAI memakai bundle Sherpa bersama."
            )
        )
    }

    private suspend fun syncGemma(restoredStatus: DurableArtifactSyncStatus): DurableArtifactSyncStatus {
        if (restoredStatus !is DurableArtifactSyncStatus.AlreadyCurrent &&
            restoredStatus !is DurableArtifactSyncStatus.Restored
        ) {
            return restoredStatus
        }
        return modelManager.syncActiveModelToDurableStorage().fold(
            onSuccess = { DurableArtifactSyncStatus.Synced },
            onFailure = { DurableArtifactSyncStatus.Failed(it.message ?: "Gemma tidak dapat disinkronkan.") }
        )
    }

    private suspend fun syncSpeech(restoredStatus: DurableArtifactSyncStatus): DurableArtifactSyncStatus {
        if (restoredStatus !is DurableArtifactSyncStatus.AlreadyCurrent &&
            restoredStatus !is DurableArtifactSyncStatus.Restored
        ) {
            return restoredStatus
        }
        return speechBundleManager.syncActiveBundleToDurableStorage().fold(
            onSuccess = { DurableArtifactSyncStatus.Synced },
            onFailure = { DurableArtifactSyncStatus.Failed(it.message ?: "Bundle Verbatim tidak dapat disinkronkan.") }
        )
    }

    private fun modelReadinessStatus(readiness: ModelReadiness, restored: Boolean): DurableArtifactSyncStatus = when (readiness) {
        is ModelReadiness.Ready -> if (restored) {
            DurableArtifactSyncStatus.Restored
        } else {
            DurableArtifactSyncStatus.AlreadyCurrent
        }
        ModelReadiness.Missing -> DurableArtifactSyncStatus.MissingLocalSource("Gemma belum tersedia.")
        is ModelReadiness.Corrupted -> DurableArtifactSyncStatus.Corrupt(readiness.reason)
        is ModelReadiness.Untrusted -> DurableArtifactSyncStatus.Corrupt(readiness.reason)
        is ModelReadiness.UnsupportedDevice -> DurableArtifactSyncStatus.RuntimeIncompatible(readiness.reason)
    }

    private fun speechReadinessStatus(readiness: SpeechBundleReadiness, restored: Boolean): DurableArtifactSyncStatus = when (readiness) {
        is SpeechBundleReadiness.Ready -> if (restored) {
            DurableArtifactSyncStatus.Restored
        } else {
            DurableArtifactSyncStatus.AlreadyCurrent
        }
        SpeechBundleReadiness.Checking -> DurableArtifactSyncStatus.Failed("Bundle Verbatim sedang diperiksa.")
        SpeechBundleReadiness.Missing ->
            DurableArtifactSyncStatus.MissingLocalSource("Bundle Verbatim belum tersedia. Unduh terlebih dahulu.")
        is SpeechBundleReadiness.Invalid -> DurableArtifactSyncStatus.Corrupt(readiness.reason)
    }

    private fun requiredMirrorBytes(): Long = listOf(
        modelManager.getModelFile(),
        speechBundleManager.bundleFile(),
        embeddingArtifactManager.modelFile(),
        embeddingArtifactManager.tokenizerFile()
    ).filter { it.isFile }.sumOf { it.length() } + MIRROR_MARGIN_BYTES

    private suspend fun syncEmbedding(restoredStatus: DurableArtifactSyncStatus): DurableArtifactSyncStatus {
        if (restoredStatus !is DurableArtifactSyncStatus.AlreadyCurrent &&
            restoredStatus !is DurableArtifactSyncStatus.Restored
        ) {
            return restoredStatus
        }
        return embeddingArtifactManager.syncToSaf().fold(
            onSuccess = { DurableArtifactSyncStatus.Synced },
            onFailure = { DurableArtifactSyncStatus.Failed(it.message ?: "Embedding tidak dapat disinkronkan.") }
        )
    }

    private fun embeddingStatus(state: ArtifactState): DurableArtifactSyncStatus = when (state) {
        is ArtifactState.Ready -> DurableArtifactSyncStatus.AlreadyCurrent
        ArtifactState.Missing -> DurableArtifactSyncStatus.MissingLocalSource("Embedding multilingual belum tersedia.")
        is ArtifactState.Corrupt -> DurableArtifactSyncStatus.Corrupt(state.reason)
        is ArtifactState.RuntimeIncompatible -> DurableArtifactSyncStatus.RuntimeIncompatible(state.reason)
        is ArtifactState.RetryableFailure -> DurableArtifactSyncStatus.Failed(state.reason)
        else -> DurableArtifactSyncStatus.Failed("Embedding sedang diproses.")
    }

    private fun unavailableReport(storageState: ModelStorageState): DurableSyncReport {
        val message = storageState.error ?: "Folder model perlu dihubungkan ulang."
        return DurableSyncReport(
            storage = storageState,
            artifacts = DurableArtifact.entries.associateWith {
                DurableArtifactSyncStatus.PermissionLost(message)
            },
            recovery = DurableArtifact.entries.associateWith {
                DurableArtifactRecovery(
                    state = DurableArtifactRecoveryState.PERMISSION_LOST,
                    networkDownloadRequired = false,
                    detail = message
                )
            }
        )
    }

    private fun gemmaRecovery(readiness: ModelReadiness, restored: Boolean, storageState: ModelStorageState): DurableArtifactRecovery =
        when (readiness) {
            is ModelReadiness.Ready -> DurableArtifactRecovery(
                source = readySource(DurableArtifact.GEMMA, restored, storageState),
                state = DurableArtifactRecoveryState.READY,
                sizeBytes = modelManager.getModelFile().takeIf { it.isFile }?.length(),
                checksumSha256 = modelManager.officialModelContract.sha256,
                checksumVerified = true,
                networkDownloadRequired = false
            )
            ModelReadiness.Missing -> DurableArtifactRecovery(
                state = DurableArtifactRecoveryState.MISSING,
                networkDownloadRequired = true,
                detail = "Gemma belum tersedia pada folder yang dipilih."
            )
            is ModelReadiness.Corrupted,
            is ModelReadiness.Untrusted -> DurableArtifactRecovery(
                state = DurableArtifactRecoveryState.CORRUPT,
                networkDownloadRequired = true,
                detail = modelReadinessDetail(readiness)
            )
            is ModelReadiness.UnsupportedDevice -> DurableArtifactRecovery(
                state = DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
                sizeBytes = modelManager.getModelFile().takeIf { it.isFile }?.length(),
                checksumSha256 = modelManager.officialModelContract.sha256,
                networkDownloadRequired = false,
                detail = readiness.reason
            )
        }

    private fun speechRecovery(
        readiness: SpeechBundleReadiness,
        restored: Boolean,
        storageState: ModelStorageState
    ): DurableArtifactRecovery = when (readiness) {
        is SpeechBundleReadiness.Ready -> DurableArtifactRecovery(
            source = readySource(DurableArtifact.SPEECH_BUNDLE, restored, storageState),
            state = DurableArtifactRecoveryState.READY,
            sizeBytes = speechBundleManager.bundleFile().takeIf { it.isFile }?.length(),
            checksumVerified = true,
            networkDownloadRequired = false,
            detail = "Checksum setiap file bundle diverifikasi terhadap manifest."
        )
        SpeechBundleReadiness.Checking -> DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.RESTORING,
            networkDownloadRequired = false
        )
        SpeechBundleReadiness.Missing -> DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.MISSING,
            networkDownloadRequired = true,
            detail = "Bundle Verbatim belum tersedia pada folder yang dipilih."
        )
        is SpeechBundleReadiness.Invalid -> DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.CORRUPT,
            networkDownloadRequired = true,
            detail = readiness.reason
        )
    }

    private fun recoveryForStatus(
        status: DurableArtifactSyncStatus,
        source: DurableArtifactSource?,
        sizeBytes: Long?,
        checksumVerified: Boolean
    ): DurableArtifactRecovery = when (status) {
        DurableArtifactSyncStatus.Restoring -> DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.RESTORING
        )
        DurableArtifactSyncStatus.Synced,
        DurableArtifactSyncStatus.AlreadyCurrent,
        DurableArtifactSyncStatus.Restored -> DurableArtifactRecovery(
            source = source,
            state = DurableArtifactRecoveryState.READY,
            sizeBytes = sizeBytes,
            checksumVerified = checksumVerified,
            networkDownloadRequired = false
        )
        is DurableArtifactSyncStatus.Corrupt -> DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.CORRUPT,
            networkDownloadRequired = true,
            detail = status.message
        )
        is DurableArtifactSyncStatus.MissingLocalSource -> DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.MISSING,
            networkDownloadRequired = true,
            detail = status.message
        )
        is DurableArtifactSyncStatus.PermissionLost -> DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.PERMISSION_LOST,
            networkDownloadRequired = false,
            detail = status.message
        )
        is DurableArtifactSyncStatus.RuntimeIncompatible -> DurableArtifactRecovery(
            source = source,
            state = DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
            sizeBytes = sizeBytes,
            checksumVerified = checksumVerified,
            networkDownloadRequired = false,
            detail = status.message
        )
        is DurableArtifactSyncStatus.Failed -> DurableArtifactRecovery(
            state = DurableArtifactRecoveryState.ERROR,
            sizeBytes = sizeBytes,
            checksumVerified = checksumVerified,
            networkDownloadRequired = false,
            detail = status.message
        )
    }

    private fun readySource(artifact: DurableArtifact, restored: Boolean, storageState: ModelStorageState): DurableArtifactSource = when {
        !restored -> DurableArtifactSource.APP_PRIVATE
        hasLegacySource(artifact, storageState) -> DurableArtifactSource.LEGACY_SAF
        else -> DurableArtifactSource.CANONICAL_SAF
    }

    private fun hasLegacySource(artifact: DurableArtifact, storageState: ModelStorageState): Boolean {
        val path = when (artifact) {
            DurableArtifact.GEMMA -> "${LegacySafPaths.ROOT_DIRECTORY}/${LegacySafPaths.MODEL_DIRECTORY}/${LegacySafPaths.GEMMA_DIRECTORY}"
            DurableArtifact.SPEECH_BUNDLE ->
                "${LegacySafPaths.ROOT_DIRECTORY}/${LegacySafPaths.MODEL_DIRECTORY}/${LegacySafPaths.SPEECH_BUNDLE_DIRECTORY}"
            DurableArtifact.VOICE_CAPABILITY ->
                "${LegacySafPaths.ROOT_DIRECTORY}/${LegacySafPaths.MODEL_DIRECTORY}/${LegacySafPaths.VOICE_DIRECTORY}"
            DurableArtifact.MULTILINGUAL_EMBEDDING -> {
                return storageState.legacyLocations.any { path ->
                    path.endsWith("/${ModelStorageSlot.MULTILINGUAL_EMBEDDING.directoryName}") ||
                        path.endsWith("/Embedding") ||
                        path.endsWith("/embedding") ||
                        path.endsWith("/E5") ||
                        path.endsWith("/e5")
                }
            }
        }
        return storageState.legacyLocations.any { it == path }
    }

    private fun modelReadinessDetail(readiness: ModelReadiness): String = when (readiness) {
        is ModelReadiness.Corrupted -> readiness.reason
        is ModelReadiness.Untrusted -> readiness.reason
        else -> ""
    }

    private companion object {
        const val MIRROR_MARGIN_BYTES = 16L * 1024L * 1024L
    }
}
