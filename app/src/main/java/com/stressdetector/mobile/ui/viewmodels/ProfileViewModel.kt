package com.stressdetector.mobile.ui.viewmodels

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.stressdetector.mobile.audio.LocalSpeechRuntime
import com.stressdetector.mobile.data.db.ConsentDao
import com.stressdetector.mobile.data.db.UserConsentEntity
import com.stressdetector.mobile.di.AppLocator
import com.stressdetector.mobile.domain.engine.LlmRuntimeReadiness
import com.stressdetector.mobile.domain.engine.OnDeviceLlmEngine
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.stress.StressHistoryContextProvider
import com.stressdetector.mobile.domain.stress.StressHistoryContextState
import com.stressdetector.mobile.domain.voice.VoiceLanguage
import com.stressdetector.mobile.domain.voice.VoiceSendMode
import com.stressdetector.mobile.domain.voice.VoiceSettingsRepository
import com.stressdetector.mobile.lifecycle.DataLifecycleService
import com.stressdetector.mobile.ml.artifacts.ArtifactState
import com.stressdetector.mobile.ml.artifacts.EmbeddingArtifactManager
import com.stressdetector.mobile.ml.llm.ModelDownloadState
import com.stressdetector.mobile.ml.llm.ModelReadiness
import com.stressdetector.mobile.ml.llm.isRuntimePreflightFailure
import com.stressdetector.mobile.ml.rag.DocumentIngestionService
import com.stressdetector.mobile.ml.speech.SpeechBundleDownloadManager
import com.stressdetector.mobile.ml.speech.SpeechBundleDownloadState
import com.stressdetector.mobile.ml.speech.SpeechBundleReadiness
import com.stressdetector.mobile.ml.speech.SpeechCapabilityReadiness
import com.stressdetector.mobile.ml.speech.SpeechModelBundleManager
import com.stressdetector.mobile.ml.storage.DurableArtifactCoordinator
import com.stressdetector.mobile.ml.storage.DurableSyncReport
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.ml.storage.ModelStorageState
import com.stressdetector.mobile.ml.storage.SafTreeSelection
import com.stressdetector.mobile.security.KeyManager
import com.stressdetector.mobile.security.PrivacyIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ModelActivationState {
    data object Missing : ModelActivationState
    data object Verifying : ModelActivationState
    data object Verified : ModelActivationState
    data class RuntimeUnavailable(val reason: String) : ModelActivationState
    data object Ready : ModelActivationState
    data class Corrupted(val reason: String) : ModelActivationState
}

data class ProfileUiState(
    val aiCounselorConsentGranted: Boolean = false,
    val sensitiveFileEncryptionReady: Boolean = false,
    val isDatabaseEncrypted: Boolean = false,
    val isLifecycleOperationRunning: Boolean = false,
    val statusMessage: String? = null,
    val isModelReady: Boolean = false,
    val isDownloadingModel: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadBytes: Long = 0L,
    val downloadTotalBytes: Long = 0L,
    val hasCompleteModelCandidate: Boolean = false,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle,
    val modelMetadata: String? = null,
    val gemmaActivationState: ModelActivationState = ModelActivationState.Missing,
    val gemmaStatusMessage: String? = null,
    val runtimeReadiness: LlmRuntimeReadiness = LlmRuntimeReadiness.Unknown,
    val runtimeTechnicalMessage: String? = null,
    val speechBundleReadiness: SpeechBundleReadiness = SpeechBundleReadiness.Missing,
    val speechCapabilities: SpeechCapabilityReadiness = SpeechCapabilityReadiness(
        verbatimReady = false,
        voiceAiReady = false,
        missingComponents = listOf("shared speech bundle")
    ),
    val speechStatusMessage: String? = null,
    val isImportingSpeechBundle: Boolean = false,
    val speechDownloadState: SpeechBundleDownloadState = SpeechBundleDownloadState.Idle,
    val speechDownloadBytes: Long = 0L,
    val speechDownloadTotalBytes: Long = 0L,
    val speechCatalogTotalBytes: Long = 0L,
    val speechCatalogDisclosure: String = "",
    val speechDownloadComponent: String? = null,
    val embeddingArtifactState: ArtifactState = ArtifactState.Missing,
    val embeddingCatalogDisclosure: String = "",
    val voiceLanguage: VoiceLanguage = VoiceLanguage.INDONESIAN,
    val voiceSendMode: VoiceSendMode = VoiceSendMode.REVIEW,
    val voiceReplyEnabled: Boolean = true,
    val modelStorage: ModelStorageState = ModelStorageState(),
    val durableSyncReport: DurableSyncReport? = null,
    val useStressHistoryInAi: Boolean = true,
    val stressHistoryContextStatus: String? = null
)

class ProfileViewModel(
    private val consentDao: ConsentDao,
    private val modelManager: com.stressdetector.mobile.ml.llm.ModelManager,
    private val dataLifecycleService: DataLifecycleService,
    private val keyManager: KeyManager,
    private val llmEngine: OnDeviceLlmEngine? = null,
    private val speechBundleManager: SpeechModelBundleManager,
    private val speechBundleDownloadManager: SpeechBundleDownloadManager,
    private val speechRuntime: LocalSpeechRuntime? = null,
    private val voiceSettingsRepository: VoiceSettingsRepository? = null,
    private val modelStorageManager: ModelStorageManager,
    private val durableArtifactCoordinator: DurableArtifactCoordinator,
    private val stressHistoryContextProvider: StressHistoryContextProvider? = null,
    private val documentIngestionService: DocumentIngestionService? = null,
    private val embeddingArtifactManager: EmbeddingArtifactManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ProfileUiState(
            speechCatalogTotalBytes = speechBundleDownloadManager.totalDownloadBytes,
            speechCatalogDisclosure = speechBundleDownloadManager.sourceLicenseDisclosure,
            embeddingCatalogDisclosure = embeddingArtifactManager.artifact().let { artifact ->
                "${artifact.sourcePage} · Lisensi ${artifact.license} · revisi ${artifact.revision}"
            }
        )
    )
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadConsents()
        viewModelScope.launch(Dispatchers.IO) {
            publishDurableSyncReport(durableArtifactCoordinator.restore())
        }
        documentIngestionService?.let { ingestion ->
            viewModelScope.launch(Dispatchers.IO) {
                // Clinical documents remain app-private and are not rehydrated from SAF.
            }
        }
        viewModelScope.launch {
            embeddingArtifactManager.state.collect { artifactState ->
                _uiState.update { it.copy(embeddingArtifactState = artifactState) }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            embeddingArtifactManager.reconcile()
        }
        viewModelScope.launch {
            modelStorageManager.state.collect { storage ->
                _uiState.update { it.copy(modelStorage = storage) }
            }
        }
        stressHistoryContextProvider?.let { provider ->
            viewModelScope.launch {
                provider.observe().collect { contextState ->
                    _uiState.update {
                        when (contextState) {
                            StressHistoryContextState.Disabled -> it.copy(
                                useStressHistoryInAi = false,
                                stressHistoryContextStatus = "Riwayat stress tidak digunakan oleh AI+."
                            )
                            is StressHistoryContextState.Ready -> it.copy(
                                useStressHistoryInAi = true,
                                stressHistoryContextStatus = "Riwayat stress tersedia untuk ringkasan AI+."
                            )
                            is StressHistoryContextState.Unavailable -> it.copy(
                                stressHistoryContextStatus = "Riwayat stress belum tersedia."
                            )
                        }
                    }
                }
            }
        }
        voiceSettingsRepository?.let { repository ->
            viewModelScope.launch {
                repository.settings.collect { settings ->
                    _uiState.update {
                        it.copy(
                            voiceLanguage = settings.language,
                            voiceSendMode = settings.sendMode,
                            voiceReplyEnabled = settings.replyEnabled
                        )
                    }
                }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // SpeechModelBundleManager intentionally does not hash the ZIP in its
            // constructor. Readiness is reconciled on IO before the UI exposes it.
            speechBundleManager.ensureReadiness()
            speechBundleManager.readiness.collect { readiness ->
                _uiState.update {
                    it.copy(
                        speechBundleReadiness = readiness,
                        speechCapabilities = speechBundleManager.capabilityReadiness()
                    )
                }
                if (readiness is SpeechBundleReadiness.Ready) validateSpeechRuntime()
            }
        }
        viewModelScope.launch {
            speechBundleDownloadManager.state.collect { downloadState ->
                val speechProgress = when (downloadState) {
                    is SpeechBundleDownloadState.Downloading -> Triple(
                        downloadState.bytesDownloaded,
                        downloadState.totalBytes,
                        downloadState.component
                    )
                    is SpeechBundleDownloadState.Verifying -> Triple(
                        downloadState.bytesProcessed,
                        downloadState.totalBytes,
                        downloadState.component
                    )
                    is SpeechBundleDownloadState.Assembling -> Triple(
                        downloadState.bytesProcessed,
                        downloadState.totalBytes,
                        downloadState.component
                    )
                    else -> null
                }
                _uiState.update {
                    it.copy(
                        speechDownloadState = downloadState,
                        speechDownloadBytes = speechProgress?.first ?: it.speechDownloadBytes,
                        speechDownloadTotalBytes = speechProgress?.second ?: it.speechDownloadTotalBytes,
                        speechDownloadComponent = speechProgress?.third ?: it.speechDownloadComponent,
                        speechStatusMessage = when (downloadState) {
                            is SpeechBundleDownloadState.Downloading -> "Sherpa sedang mengunduh ${downloadState.component}."
                            is SpeechBundleDownloadState.Verifying ->
                                "Memverifikasi SHA-256 " + downloadState.component + "."
                            is SpeechBundleDownloadState.Assembling -> when (downloadState.stage) {
                                com.stressdetector.mobile.ml.speech.SpeechAssemblyStage.EXTRACTING ->
                                    "Mengekstrak " + downloadState.component + "."
                                com.stressdetector.mobile.ml.speech.SpeechAssemblyStage.EXTRACTED_SHA256 ->
                                    "Memverifikasi checksum hasil ekstraksi " + downloadState.component + "."
                                com.stressdetector.mobile.ml.speech.SpeechAssemblyStage.BUILDING_ZIP ->
                                    "Membangun canonical Sherpa ZIP."
                            }
                            is SpeechBundleDownloadState.Installing -> downloadState.step
                            SpeechBundleDownloadState.Ready -> "Sherpa Speech Bundle siap digunakan offline."
                            SpeechBundleDownloadState.Cancelling -> "Membatalkan Sherpa setelah worker berhenti dengan aman."
                            SpeechBundleDownloadState.Deleting -> "Menghapus partial Sherpa setelah worker berhenti dengan aman."
                            SpeechBundleDownloadState.Cancelled -> "Download Sherpa dibatalkan; partial file dipertahankan untuk resume."
                            is SpeechBundleDownloadState.Failed -> "Download Sherpa gagal: ${downloadState.message}"
                            SpeechBundleDownloadState.Idle -> it.speechStatusMessage
                        }
                    )
                }
            }
        }
        llmEngine?.let { engine ->
            viewModelScope.launch {
                engine.runtimeReadiness().collect { runtime ->
                    _uiState.update {
                        it.copy(
                            runtimeReadiness = runtime,
                            runtimeTechnicalMessage = (runtime as? LlmRuntimeReadiness.Unavailable)?.technicalMessage,
                            gemmaActivationState = activationStateFor(
                                modelReady = it.isModelReady,
                                runtime = runtime
                            )
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            modelManager.readiness.collect { readiness ->
                val metadata = (readiness as? com.stressdetector.mobile.ml.llm.ModelReadiness.Ready)?.metadata
                _uiState.update {
                    val candidateRuntimeFailure = (it.modelDownloadState as? ModelDownloadState.Failed)
                        ?.takeIf(ModelDownloadState.Failed::isRuntimePreflightFailure)
                    it.copy(
                        isModelReady = metadata != null,
                        hasCompleteModelCandidate = modelManager.hasCompleteDownloadCandidate(),
                        modelMetadata = metadata?.let { asset ->
                            "${asset.fileName} · ${asset.version} · ${asset.sizeBytes} bytes · " +
                                "SHA-256 ${asset.sha256.take(12)}… · Lisensi ${asset.license} · ${asset.source}"
                        },
                        gemmaActivationState = candidateRuntimeFailure?.let {
                            ModelActivationState.RuntimeUnavailable(
                                "LiteRT-LM menolak candidate model setelah checksum terverifikasi."
                            )
                        } ?: activationStateFor(
                            modelReady = metadata != null,
                            runtime = it.runtimeReadiness
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            modelManager.observeDownload().collect { downloadState ->
                val progress = (downloadState as? ModelDownloadState.Downloading)
                val verification = (downloadState as? ModelDownloadState.Verifying)
                val retry = (downloadState as? ModelDownloadState.TransferRetry)
                val visibleProgress = when {
                    progress != null -> progress.bytesDownloaded.toFloat() / progress.totalBytes.coerceAtLeast(1L)
                    verification != null -> verification.bytesProcessed.toFloat() / verification.totalBytes.coerceAtLeast(1L)
                    retry != null -> retry.bytesDownloaded.toFloat() / retry.totalBytes.coerceAtLeast(1L)
                    downloadState is ModelDownloadState.Ready -> 1f
                    else -> 0f
                }.coerceIn(0f, 1f)
                val visibleBytes = progress?.bytesDownloaded
                    ?: verification?.bytesProcessed
                    ?: retry?.bytesDownloaded
                    ?: 0L
                val visibleTotal = progress?.totalBytes
                    ?: verification?.totalBytes
                    ?: retry?.totalBytes
                    ?: 0L
                _uiState.update {
                    it.copy(
                        modelDownloadState = downloadState,
                        hasCompleteModelCandidate = modelManager.hasCompleteDownloadCandidate(),
                        isDownloadingModel = downloadState is ModelDownloadState.WaitingForNetwork ||
                            downloadState is ModelDownloadState.TransferRetry ||
                            downloadState is ModelDownloadState.Downloading ||
                            downloadState is ModelDownloadState.Verifying,
                        downloadProgress = visibleProgress,
                        downloadBytes = visibleBytes,
                        downloadTotalBytes = visibleTotal,
                        gemmaActivationState = when {
                            downloadState is ModelDownloadState.Verifying -> ModelActivationState.Verifying
                            downloadState is ModelDownloadState.Downloading ||
                                downloadState is ModelDownloadState.WaitingForNetwork ||
                                downloadState is ModelDownloadState.TransferRetry -> if (it.isModelReady) {
                                activationStateFor(it.isModelReady, it.runtimeReadiness)
                            } else {
                                ModelActivationState.Missing
                            }
                            downloadState is ModelDownloadState.Failed &&
                                downloadState.isRuntimePreflightFailure() ->
                                ModelActivationState.RuntimeUnavailable(
                                    "LiteRT-LM menolak candidate model setelah checksum terverifikasi."
                                )
                            else -> activationStateFor(it.isModelReady, it.runtimeReadiness)
                        },
                        gemmaStatusMessage = when (downloadState) {
                            is ModelDownloadState.WaitingForNetwork -> downloadState.message
                            is ModelDownloadState.TransferRetry ->
                                "Transfer Gemma gagal sementara (attempt ${downloadState.attempt}); ${downloadState.message}"
                            is ModelDownloadState.Downloading ->
                                "Transfer Gemma berjalan; model aktif dipertahankan hingga verifikasi selesai."
                            is ModelDownloadState.Verifying -> "Verifying SHA-256: ${(
                                downloadState.bytesProcessed * 100 / downloadState.totalBytes.coerceAtLeast(
                                    1L
                                )
                                ).coerceIn(0L, 100L)}%"
                            ModelDownloadState.Ready -> "Gemma 4 E2B is ready for offline AI+ use."
                            ModelDownloadState.Cancelled -> "Download cancelled. The partial file was kept so it can resume later."
                            is ModelDownloadState.Failed -> if (downloadState.isRuntimePreflightFailure()) {
                                "Checksum Gemma valid, tetapi runtime LiteRT-LM belum menerima candidate pada perangkat ini. " +
                                    "Candidate dipertahankan; ulangi preflight tanpa mengunduh ulang. Detail: ${downloadState.message}"
                            } else if (it.hasCompleteModelCandidate) {
                                "File Gemma tersedia dan siap diverifikasi oleh runtime."
                            } else {
                                "Gemma 4 E2B download failed: ${downloadState.message}"
                            }
                            ModelDownloadState.Idle -> "Gemma belum diunduh atau belum dipilih untuk provisioning."
                        }
                    )
                }
                if (downloadState is ModelDownloadState.Ready) {
                    refreshGemmaRuntime("Gemma 4 E2B is ready for offline AI+ use.")
                }
            }
        }
    }

    private fun validateSpeechRuntime() {
        val runtime = speechRuntime ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (
                val result = runCatching { runtime.preflight() }.getOrElse {
                    SpeechRuntimeState.Failed(it.message ?: "Sherpa runtime initialization failed.")
                }
            ) {
                SpeechRuntimeState.Checking -> {
                    _uiState.update {
                        it.copy(
                            speechCapabilities = SpeechCapabilityReadiness(false, false),
                            speechStatusMessage = "Speech bundle sedang diperiksa."
                        )
                    }
                }
                SpeechRuntimeState.Ready -> {
                    val capabilities = speechBundleManager.capabilityReadiness()
                    _uiState.update {
                        it.copy(
                            speechCapabilities = capabilities,
                            speechStatusMessage = if (capabilities.indonesianSttReady) {
                                "STT Bahasa Indonesia, VAD, dan diarization siap digunakan offline."
                            } else {
                                "Runtime Sherpa siap, tetapi STT Bahasa Indonesia perlu di-update."
                            }
                        )
                    }
                }
                is SpeechRuntimeState.Unavailable -> {
                    _uiState.update {
                        it.copy(
                            speechCapabilities = SpeechCapabilityReadiness(false, false, listOf(result.reason)),
                            speechStatusMessage = "Sherpa runtime belum tersedia: ${result.reason}"
                        )
                    }
                }
                is SpeechRuntimeState.Failed -> {
                    _uiState.update {
                        it.copy(
                            speechCapabilities = SpeechCapabilityReadiness(false, false, listOf(result.message)),
                            speechStatusMessage = "Sherpa runtime gagal diaktifkan: ${result.message}"
                        )
                    }
                }
            }
        }
    }

    private fun activationStateFor(modelReady: Boolean, runtime: LlmRuntimeReadiness): ModelActivationState {
        if (!modelReady) return ModelActivationState.Missing
        return when (runtime) {
            is LlmRuntimeReadiness.Ready -> ModelActivationState.Ready
            is LlmRuntimeReadiness.Unavailable -> ModelActivationState.RuntimeUnavailable(
                runtime.userMessage
            )
            else -> ModelActivationState.Verified
        }
    }

    private suspend fun refreshGemmaRuntime(statusMessage: String? = null) {
        val modelReadiness = modelManager.refreshReadiness()
        val engine = llmEngine
        val runtime = if (modelReadiness is ModelReadiness.Ready && engine != null) {
            runCatching { engine.preflight() }.getOrElse { error ->
                LlmRuntimeReadiness.Unavailable(
                    userMessage = "AI lokal belum dapat menjalankan model pada perangkat ini.",
                    technicalMessage = error.message
                )
            }
        } else {
            LlmRuntimeReadiness.Unknown
        }
        val metadata = (modelReadiness as? ModelReadiness.Ready)?.metadata
        _uiState.update {
            val activation = activationStateFor(metadata != null, runtime)
            it.copy(
                isModelReady = metadata != null,
                modelMetadata = metadata?.let { asset ->
                    "${asset.fileName} · ${asset.sizeBytes} bytes · SHA-256 ${asset.sha256.take(12)}…"
                },
                runtimeReadiness = runtime,
                runtimeTechnicalMessage = (runtime as? LlmRuntimeReadiness.Unavailable)?.technicalMessage,
                gemmaActivationState = activation,
                gemmaStatusMessage = statusMessage ?: activation.userStatusMessage()
            )
        }
    }

    private fun loadConsents() {
        viewModelScope.launch(Dispatchers.IO) {
            val modelReadiness = modelManager.reconcileDownloadState()
            runCatching { speechBundleDownloadManager.reconcile() }
            val chat = consentDao.getConsent("AI_COUNSELING")?.isGranted ?: false
            val runtime = if (modelReadiness is com.stressdetector.mobile.ml.llm.ModelReadiness.Ready && llmEngine != null) {
                runCatching { llmEngine.preflight() }.getOrElse { error ->
                    LlmRuntimeReadiness.Unavailable(
                        userMessage = "AI lokal belum dapat menjalankan model pada perangkat ini.",
                        technicalMessage = error.message
                    )
                }
            } else {
                LlmRuntimeReadiness.Unknown
            }

            _uiState.update {
                val activation = activationStateFor(
                    modelReady = modelReadiness is ModelReadiness.Ready,
                    runtime = runtime
                )
                it.copy(
                    aiCounselorConsentGranted = chat,
                    sensitiveFileEncryptionReady = runCatching {
                        keyManager.getOrCreateFileEncryptionKey(PrivacyIdentity.LOCAL_PROFILE_ID)
                        keyManager.isKeyValid(PrivacyIdentity.LOCAL_PROFILE_ID)
                    }.getOrDefault(false),
                    isModelReady = modelReadiness is ModelReadiness.Ready,
                    hasCompleteModelCandidate = modelManager.hasCompleteDownloadCandidate(),
                    gemmaActivationState = activation,
                    runtimeReadiness = runtime,
                    runtimeTechnicalMessage = (runtime as? LlmRuntimeReadiness.Unavailable)?.technicalMessage,
                    gemmaStatusMessage = if (modelManager.hasCompleteDownloadCandidate()) {
                        com.stressdetector.mobile.ml.llm.ModelManager.COMPLETE_CANDIDATE_MESSAGE
                    } else {
                        activation.userStatusMessage()
                    }
                )
            }
        }
    }

    private fun ModelActivationState.userStatusMessage(): String = when (this) {
        ModelActivationState.Missing -> "Gemma belum tersedia di penyimpanan lokal."
        ModelActivationState.Verifying -> "Gemma sedang diverifikasi."
        ModelActivationState.Verified -> "File Gemma terverifikasi; runtime belum selesai diperiksa."
        is ModelActivationState.RuntimeUnavailable ->
            "File Gemma tersedia, tetapi runtime belum kompatibel: $reason"
        ModelActivationState.Ready -> "Gemma siap digunakan secara offline."
        is ModelActivationState.Corrupted -> "File Gemma gagal verifikasi: $reason"
    }

    fun toggleConsent(type: String, granted: Boolean) {
        viewModelScope.launch {
            consentDao.insertConsent(
                UserConsentEntity(
                    consentType = type,
                    grantedAtMillis = System.currentTimeMillis(),
                    isGranted = granted
                )
            )
            loadConsents()
            _uiState.update { it.copy(statusMessage = "Consent for $type updated.") }
        }
    }

    fun exportAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLifecycleOperationRunning = true, statusMessage = null) }
            runCatching { dataLifecycleService.exportUserData(PrivacyIdentity.LOCAL_PROFILE_ID) }
                .onSuccess { export ->
                    _uiState.update {
                        it.copy(
                            isLifecycleOperationRunning = false,
                            statusMessage = "Export created: ${export.exportFile.name} (${export.checksumSha256.take(12)}…)."
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLifecycleOperationRunning = false, statusMessage = error.message ?: "Export failed.")
                    }
                }
        }
    }

    fun purgeAllData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLifecycleOperationRunning = true, statusMessage = null) }
            runCatching { dataLifecycleService.deleteUserData(PrivacyIdentity.LOCAL_PROFILE_ID) }
                .onSuccess {
                    _uiState.update {
                        it.copy(
                            isLifecycleOperationRunning = false,
                            aiCounselorConsentGranted = false,
                            sensitiveFileEncryptionReady = false,
                            statusMessage = "Local records, encrypted files, exports, and profile keys were purged."
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(isLifecycleOperationRunning = false, statusMessage = error.message ?: "Purge failed.")
                    }
                }
        }
    }
    fun deleteModel() {
        viewModelScope.launch(Dispatchers.IO) {
            llmEngine?.unload()
            val deleted = modelManager.deleteModel()
            _uiState.update {
                it.copy(
                    isModelReady = false,
                    modelMetadata = null,
                    gemmaActivationState = ModelActivationState.Missing,
                    gemmaStatusMessage = if (deleted) {
                        "Validated .litertlm model and manifest deleted."
                    } else {
                        "Model deletion was incomplete."
                    }
                )
            }
        }
    }

    fun downloadModel(forceReplaceActive: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (forceReplaceActive) llmEngine?.unload()
                modelManager.enqueueModelDownload(forceReplaceActive)
            }
                .onSuccess { workId ->
                    _uiState.update {
                        it.copy(
                            gemmaStatusMessage = if (workId == null && modelManager.isModelReady()) {
                                "Gemma 4 E2B is already installed and remains active; no duplicate download was started."
                            } else {
                                "Download Gemma masuk antrean; jaringan akan diverifikasi sebelum transfer. " +
                                    "Model aktif tetap dipertahankan sampai verifikasi selesai."
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(gemmaStatusMessage = error.message ?: "Gemma 4 E2B download could not be started.") }
                }
        }
    }

    fun retryRuntimeInitialization() {
        viewModelScope.launch(Dispatchers.IO) {
            refreshGemmaRuntime("Retrying Gemma 4 E2B runtime activation…")
        }
    }

    fun cancelModelDownload() {
        modelManager.cancelModelDownload(keepPartial = true)
    }

    fun retryModelDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { modelManager.retryDownload() }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(gemmaStatusMessage = error.message ?: "Download Gemma tidak dapat diantrekan ulang.")
                    }
                }
        }
    }

    fun retryVerification() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { modelManager.retryVerification() }
                .onSuccess { workId ->
                    _uiState.update {
                        it.copy(
                            gemmaStatusMessage = if (workId == null) {
                                "Tidak ada candidate model yang dapat diverifikasi. Silakan mulai download Gemma 4 E2B lagi."
                            } else {
                                "Retrying Gemma 4 E2B verification..."
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(gemmaStatusMessage = error.message ?: "Verification retry could not be started.") }
                }
        }
    }

    fun discardPartialModelDownload() {
        modelManager.discardPartialDownload()
        _uiState.update { it.copy(gemmaStatusMessage = "Partial Gemma 4 E2B download deleted.") }
    }

    fun importSpeechBundle(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update {
                it.copy(isImportingSpeechBundle = true, speechStatusMessage = "Validating Sherpa STT/VAD/diarization bundle...")
            }
            val result = speechBundleManager.importBundle(uri)
            _uiState.update {
                it.copy(
                    isImportingSpeechBundle = false,
                    // Keep showing an already active, verified bundle when a new
                    // import is rejected; the status message carries the failure.
                    speechBundleReadiness = speechBundleManager.readiness.value,
                    speechCapabilities = speechBundleManager.capabilityReadiness(),
                    speechStatusMessage = when (result) {
                        SpeechBundleReadiness.Checking -> "Checking Sherpa speech bundle..."
                        is SpeechBundleReadiness.Ready -> if (speechBundleManager.capabilityReadiness().indonesianSttReady) {
                            "Sherpa bundle validated: Indonesian/English STT and two-speaker diarization ready."
                        } else {
                            "Sherpa bundle validated, but Indonesian streaming STT is missing. Update the bundle."
                        }
                        SpeechBundleReadiness.Missing -> "Sherpa bundle is not installed."
                        is SpeechBundleReadiness.Invalid -> "Sherpa bundle rejected: ${result.reason}"
                    }
                )
            }
        }
    }

    fun downloadSpeechBundle() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { speechBundleDownloadManager.enqueue() }
                .onSuccess { enqueued ->
                    _uiState.update {
                        it.copy(
                            speechStatusMessage = if (enqueued) {
                                "Sherpa Speech Bundle queued. Gemma can continue downloading in parallel."
                            } else {
                                "Sherpa Speech Bundle is already downloading or ready; no duplicate job was created."
                            }
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update { it.copy(speechStatusMessage = error.message ?: "Sherpa download could not be started.") }
                }
        }
    }

    fun cancelSpeechBundleDownload() {
        speechBundleDownloadManager.cancel()
    }

    fun retrySpeechBundleDownload() {
        downloadSpeechBundle()
    }

    fun discardPartialSpeechBundleDownload() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { speechBundleDownloadManager.deletePartialDownloadAndWait() }
                .onSuccess {
                    _uiState.update { it.copy(speechStatusMessage = "Partial Sherpa download deleted.") }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(speechStatusMessage = error.message ?: "Partial Sherpa deletion failed.")
                    }
                }
        }
    }

    fun deleteSpeechBundle() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                speechRuntime?.close()
                speechBundleDownloadManager.deletePartialDownloadAndWait()
                speechBundleManager.deleteBundle()
            }.onSuccess { deleted ->
                _uiState.update {
                    it.copy(
                        speechBundleReadiness = SpeechBundleReadiness.Missing,
                        speechCapabilities = speechBundleManager.capabilityReadiness(),
                        speechStatusMessage = if (deleted) {
                            "Sherpa speech bundle deleted from local storage."
                        } else {
                            "Sherpa speech bundle could not be deleted."
                        }
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(speechStatusMessage = error.message ?: "Sherpa bundle deletion failed.")
                }
            }
        }
    }

    fun setVoiceLanguage(language: VoiceLanguage) {
        voiceSettingsRepository?.setLanguage(language)
    }

    fun setVoiceSendMode(mode: VoiceSendMode) {
        voiceSettingsRepository?.setSendMode(mode)
    }

    fun setVoiceReplyEnabled(enabled: Boolean) {
        voiceSettingsRepository?.setReplyEnabled(enabled)
    }

    fun setUseStressHistoryInAi(enabled: Boolean) {
        stressHistoryContextProvider?.setEnabled(enabled)
        _uiState.update {
            it.copy(
                useStressHistoryInAi = enabled,
                stressHistoryContextStatus = if (enabled) {
                    "Ringkasan riwayat stress diizinkan untuk AI+."
                } else {
                    "Ringkasan riwayat stress dimatikan untuk AI+."
                }
            )
        }
    }

    fun selectModelFolder(selection: SafTreeSelection) {
        _uiState.update {
            it.copy(
                durableSyncReport = DurableSyncReport.restoring(it.modelStorage),
                statusMessage = "Memulihkan artefak offline."
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            publishDurableSyncReport(durableArtifactCoordinator.selectAndSynchronize(selection))
        }
    }

    fun disconnectModelFolder() {
        modelStorageManager.disconnect()
        _uiState.update {
            it.copy(
                durableSyncReport = null,
                statusMessage = "Folder model dilepas. Model app-private tetap dapat digunakan."
            )
        }
    }

    fun syncModelFolder() {
        _uiState.update {
            it.copy(
                durableSyncReport = DurableSyncReport.restoring(it.modelStorage),
                statusMessage = "Memeriksa artefak offline."
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            publishDurableSyncReport(durableArtifactCoordinator.synchronize())
        }
    }

    fun downloadEmbedding() = embeddingArtifactManager.enqueue()

    fun discardPartialEmbedding() = embeddingArtifactManager.discardPartial()

    private fun publishDurableSyncReport(report: DurableSyncReport) {
        _uiState.update {
            it.copy(
                durableSyncReport = report,
                statusMessage = report.userMessage()
            )
        }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val db = AppLocator.database(context)
            val modelManager = AppLocator.modelManager(context)
            return ProfileViewModel(
                consentDao = db.consentDao(),
                modelManager = modelManager,
                dataLifecycleService = AppLocator.dataLifecycleService(context),
                keyManager = AppLocator.keyManager,
                llmEngine = AppLocator.llmEngine(context),
                speechBundleManager = AppLocator.speechBundleManager(context),
                speechBundleDownloadManager = AppLocator.speechBundleDownloadManager(context),
                speechRuntime = AppLocator.speechRuntime(context),
                voiceSettingsRepository = AppLocator.voiceSettingsRepository(context),
                modelStorageManager = AppLocator.modelStorageManager(context),
                durableArtifactCoordinator = AppLocator.durableArtifactCoordinator(context),
                stressHistoryContextProvider = AppLocator.stressHistoryContextProvider(context),
                documentIngestionService = AppLocator.documentIngestionService(context),
                embeddingArtifactManager = AppLocator.embeddingArtifactManager(context)
            ) as T
        }
    }
}
