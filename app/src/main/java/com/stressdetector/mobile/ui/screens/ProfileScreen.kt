package com.stressdetector.mobile.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.voice.VoiceLanguage
import com.stressdetector.mobile.domain.voice.VoiceSendMode
import com.stressdetector.mobile.ml.artifacts.ArtifactState
import com.stressdetector.mobile.ml.llm.ModelDownloadState
import com.stressdetector.mobile.ml.llm.isRuntimePreflightFailure
import com.stressdetector.mobile.ml.speech.SpeechBundleDownloadState
import com.stressdetector.mobile.ml.speech.SpeechBundleReadiness
import com.stressdetector.mobile.ml.storage.DurableArtifact
import com.stressdetector.mobile.ml.storage.DurableArtifactRecovery
import com.stressdetector.mobile.ml.storage.DurableArtifactRecoveryState
import com.stressdetector.mobile.ml.storage.DurableArtifactSource
import com.stressdetector.mobile.ml.storage.DurableArtifactSyncStatus
import com.stressdetector.mobile.ml.storage.DurableSyncReport
import com.stressdetector.mobile.ml.storage.LocalOnlyDocumentTreeContract
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.LocalAppMotion
import com.stressdetector.mobile.ui.viewmodels.AiReminderViewModel
import com.stressdetector.mobile.ui.viewmodels.DocumentLibraryViewModel
import com.stressdetector.mobile.ui.viewmodels.ModelActivationState
import com.stressdetector.mobile.ui.viewmodels.ProfileViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val MIN_TOUCH_TARGET_DP = 48
private const val TECHNICAL_DETAILS_ENTER_MILLIS = 180
private const val TECHNICAL_DETAILS_EXIT_MILLIS = 120
private const val ARTIFACT_PROGRESS_MILLIS = 180

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
fun ProfileScreen(
    viewModel: ProfileViewModel,
    modifier: Modifier = Modifier,
    documentLibraryViewModel: DocumentLibraryViewModel? = null,
    aiReminderViewModel: AiReminderViewModel? = null,
    onNavigateToLibrary: () -> Unit,
    onNavigateToPersona: () -> Unit,
    onRequestNotificationPermission: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val aiReminderState = if (aiReminderViewModel == null) {
        null
    } else {
        val observed by aiReminderViewModel.uiState.collectAsStateWithLifecycleCompat()
        observed
    }
    var showTechnicalDetails by rememberSaveable { mutableStateOf(false) }
    var showVoiceSettings by rememberSaveable { mutableStateOf(false) }
    var showSherpaSources by rememberSaveable { mutableStateOf(false) }
    var backupAction by remember { mutableStateOf<ProfileBackupAction?>(null) }
    var destructiveAction by remember { mutableStateOf<ProfileDestructiveAction?>(null) }
    val motion = LocalAppMotion.current

    val speechBundleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importSpeechBundle) }
    val modelFolderLauncher = rememberLauncherForActivityResult(
        LocalOnlyDocumentTreeContract()
    ) { selection -> selection?.let(viewModel::selectModelFolder) }

    val restoring = state.durableSyncReport?.isRestoring() == true
    val speechDownloadBusy = state.speechDownloadState.isBusy()
    val documentCount = documentLibraryViewModel
        ?.documents
        ?.collectAsStateWithLifecycleCompat()
        ?.value
        ?.size
        ?: 0

    var detailSheet by remember { mutableStateOf<ProfileDetail?>(null) }
    val openDetail: (ProfileDetail) -> Unit = { detail ->
        detailSheet = detail
        showTechnicalDetails = false
    }
    val openTechnicalDetails = {
        detailSheet = ProfileDetail.MODELS
        showTechnicalDetails = true
    }
    val requestDestructiveAction: (ProfileDestructiveAction) -> Unit = { action ->
        detailSheet = null
        destructiveAction = action
    }
    val aiReady = state.gemmaActivationState == ModelActivationState.Ready
    val gemmaRecovery = state.durableSyncReport?.recoveryOf(DurableArtifact.GEMMA)
    val gemmaAction = gemmaContextualAction(
        context = GemmaActionContext(
            activation = state.gemmaActivationState,
            candidateReadyForVerification = state.hasCompleteModelCandidate,
            downloadState = state.modelDownloadState,
            recovery = gemmaRecovery,
            storageConnected = state.modelStorage.connected
        ),
        callbacks = object : GemmaActionCallbacks {
            override fun connect() = modelFolderLauncher.launch(null)
            override fun sync() = viewModel.syncModelFolder()
            override fun download() = viewModel.downloadModel()
            override fun retryDownload() = viewModel.retryModelDownload()
            override fun retryVerification() = viewModel.retryVerification()
            override fun cancel() = viewModel.cancelModelDownload()
            override fun retryRuntime() = viewModel.retryRuntimeInitialization()
            override fun showDetails() = openTechnicalDetails()
        }
    )
    val speechRecovery = state.durableSyncReport?.recoveryOf(DurableArtifact.SPEECH_BUNDLE)
    val speechAction = speechContextualAction(
        recovery = speechRecovery,
        readiness = state.speechBundleReadiness,
        downloadState = state.speechDownloadState,
        storageConnected = state.modelStorage.connected,
        onConnect = { modelFolderLauncher.launch(null) },
        onSync = viewModel::syncModelFolder,
        onCancel = viewModel::cancelSpeechBundleDownload,
        onRetry = viewModel::retrySpeechBundleDownload,
        onPrepare = { showSherpaSources = true },
        onShowDetails = openTechnicalDetails
    )
    val embeddingRecovery = state.durableSyncReport?.recoveryOf(DurableArtifact.MULTILINGUAL_EMBEDDING)
    val embeddingAction = embeddingContextualAction(
        state = state.embeddingArtifactState,
        recovery = embeddingRecovery,
        storageConnected = state.modelStorage.connected,
        onConnect = { modelFolderLauncher.launch(null) },
        onSync = viewModel::syncModelFolder,
        onDownload = viewModel::downloadEmbedding,
        onShowDetails = openTechnicalDetails
    )
    val voiceRecovery = state.durableSyncReport?.recoveryOf(DurableArtifact.VOICE_CAPABILITY)
    val voiceReady = state.speechCapabilities.voiceAiReady

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = AppSpacing.Screen, vertical = AppSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Section)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
                Text(
                    text = "Profil",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "Kesiapan aplikasi, model offline, dan data lokal.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            ProfileReadinessCard(
                privacyReady = state.sensitiveFileEncryptionReady,
                storageStatus = when {
                    restoring -> "Memulihkan"
                    state.modelStorage.connected -> "Tersambung"
                    else -> "Belum terhubung"
                },
                storageReady = state.modelStorage.connected,
                aiStatus = if (aiReady) {
                    "Siap offline"
                } else {
                    gemmaStatusLabel(
                        activation = state.gemmaActivationState,
                        recovery = gemmaRecovery,
                        candidateReadyForVerification = state.hasCompleteModelCandidate
                    )
                },
                aiReady = aiReady
            )
        }
        item {
            ProfileOverviewSection(title = "Pengaturan") {
                ProfileNavigationRow(
                    title = "Penyimpanan offline",
                    supporting = when {
                        restoring -> "Pemulihan sedang berjalan"
                        state.modelStorage.connected -> "Folder model tersambung"
                        else -> "Hubungkan folder model untuk memakai file lokal"
                    },
                    onClick = { openDetail(ProfileDetail.STORAGE) }
                )
                ProfileNavigationRow(
                    title = "Model offline",
                    supporting = "Gemma, Sherpa, embedding, dan Voice AI+",
                    onClick = { openDetail(ProfileDetail.MODELS) },
                    showDivider = true
                )
                ProfileNavigationRow(
                    title = "AI+ dan pengetahuan",
                    supporting = "$documentCount dokumen · " +
                        if (state.aiCounselorConsentGranted) {
                            "persetujuan aktif"
                        } else {
                            "persetujuan belum diberikan"
                        },
                    onClick = { openDetail(ProfileDetail.AI) },
                    showDivider = true
                )
            }
        }
        item {
            ProfileOverviewSection(title = "Data dan keamanan") {
                ProfileNavigationRow(
                    title = "Data lokal",
                    supporting = "Ekspor, koneksi folder, dan hapus data dengan konfirmasi",
                    onClick = { openDetail(ProfileDetail.DATA) }
                )
            }
        }
    }

    when (detailSheet) {
        ProfileDetail.STORAGE -> ProfileDetailSheet(
            title = "Penyimpanan offline",
            subtitle = when {
                restoring -> "Memulihkan file dari folder pilihan."
                state.modelStorage.connected -> state.durableSyncReport?.userMessage()
                    ?: "Folder model tersambung."
                else -> "Pilih folder StressDetection untuk memulihkan file yang sudah ada."
            },
            onDismiss = { detailSheet = null }
        ) {
            val storageActionLabel = when {
                restoring -> null
                state.modelStorage.connected -> "Sinkronkan"
                else -> "Hubungkan folder"
            }
            storageActionLabel?.let { label ->
                Button(
                    onClick = {
                        if (state.modelStorage.connected) {
                            viewModel.syncModelFolder()
                        } else {
                            modelFolderLauncher.launch(null)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                        .semantics { contentDescription = label }
                ) { Text(label) }
            }
            TextButton(
                onClick = { showTechnicalDetails = !showTechnicalDetails },
                modifier = Modifier
                    .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                    .semantics {
                        role = Role.Button
                        stateDescription = if (showTechnicalDetails) "Terbuka" else "Tertutup"
                    }
            ) {
                Text(if (showTechnicalDetails) "Sembunyikan detail teknis" else "Lihat detail teknis")
            }
            AnimatedVisibility(
                visible = showTechnicalDetails,
                enter = fadeIn(tween(motion.duration(TECHNICAL_DETAILS_ENTER_MILLIS))) + expandVertically(),
                exit = fadeOut(tween(motion.duration(TECHNICAL_DETAILS_EXIT_MILLIS))) + shrinkVertically()
            ) {
                TechnicalStorageDetails(
                    report = state.durableSyncReport,
                    provider = state.modelStorage.providerAuthority,
                    treeUri = state.modelStorage.treeUri?.toString(),
                    providerRoot = state.modelStorage.providerRootId,
                    resolvedDocument = state.modelStorage.resolvedRootDocumentId,
                    modelPath = state.modelStorage.modelPath,
                    readPermission = state.modelStorage.persistedReadPermission,
                    writePermission = state.modelStorage.persistedWritePermission,
                    availableBytes = state.modelStorage.availableBytes,
                    lastAuditAtMillis = state.modelStorage.lastAuditAtMillis,
                    legacyLocations = state.modelStorage.legacyLocations,
                    storageError = state.modelStorage.error,
                    modelMetadata = state.modelMetadata,
                    embeddingCatalogDisclosure = state.embeddingCatalogDisclosure,
                    runtimeTechnicalMessage = state.runtimeTechnicalMessage
                )
            }
        }
        ProfileDetail.MODELS -> ProfileDetailSheet(
            title = "Model offline",
            subtitle = "File diverifikasi sebelum dipakai. Runtime tidak kompatibel tidak dianggap hilang.",
            onDismiss = { detailSheet = null }
        ) {
            ArtifactSettingsRow(
                title = "Gemma AI Counselor",
                status = gemmaStatusLabel(
                    activation = state.gemmaActivationState,
                    recovery = gemmaRecovery,
                    candidateReadyForVerification = state.hasCompleteModelCandidate
                ),
                supporting = state.gemmaStatusMessage,
                action = gemmaAction,
                progress = state.downloadProgress.takeIf {
                    state.isDownloadingModel && state.modelDownloadState.hasTransferProgress()
                },
                progressLabel = state.modelDownloadState.modelProgressLabel(
                    state.downloadBytes,
                    state.downloadTotalBytes
                )
            )
            val speechActionForSheet = speechAction
            ArtifactSettingsRow(
                title = "Sherpa + Whisper offline",
                status = speechStatusLabel(
                    readiness = state.speechBundleReadiness,
                    capabilitiesReady = state.speechCapabilities.indonesianSttReady,
                    recovery = speechRecovery
                ),
                supporting = state.speechStatusMessage,
                action = speechActionForSheet,
                progress = state.speechDownloadState.progressOrNull(),
                progressLabel = state.speechDownloadState.progressLabel()
            )
            ArtifactSettingsRow(
                title = "Embedding multilingual",
                status = embeddingStatusLabel(state.embeddingArtifactState, embeddingRecovery),
                action = embeddingAction,
                progress = (state.embeddingArtifactState as? ArtifactState.Downloading)?.let {
                    if (it.totalBytes > 0L) it.bytes.toFloat() / it.totalBytes else 0f
                },
                progressLabel = (state.embeddingArtifactState as? ArtifactState.Downloading)?.let {
                    "${formatBytes(it.bytes)} / ${formatBytes(it.totalBytes)}"
                }
            )
            ArtifactSettingsRow(
                title = "Voice AI+",
                status = voiceStatusLabel(voiceReady, speechDownloadBusy, voiceRecovery),
                supporting = "Input dan balasan suara memakai bundle Sherpa yang sama.",
                action = if (voiceReady) {
                    ArtifactAction(
                        label = "Atur",
                        description = "Atur Voice AI+",
                        onClick = {
                            detailSheet = null
                            showVoiceSettings = true
                        }
                    )
                } else {
                    speechAction?.copy(description = "Siapkan Voice AI+")
                }
            )
        }
        ProfileDetail.AI -> ProfileDetailSheet(
            title = "AI+ dan pengetahuan",
            subtitle = "Atur sumber pengetahuan dan privasi AI lokal.",
            onDismiss = { detailSheet = null }
        ) {
            ListItem(
                headlineContent = { Text("Persona AI Counselor") },
                supportingContent = { Text("Atur gaya interaksi lokal AI Counselor.") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                trailingContent = {
                    TextButton(
                        onClick = {
                            detailSheet = null
                            onNavigateToPersona()
                        },
                        modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                    ) { Text("Atur") }
                }
            )
            ListItem(
                headlineContent = { Text("Knowledge Base") },
                supportingContent = { Text("$documentCount dokumen tersimpan") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                trailingContent = {
                    TextButton(
                        onClick = {
                            detailSheet = null
                            onNavigateToLibrary()
                        },
                        modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                    ) { Text("Kelola") }
                }
            )
            SettingToggleRow(
                title = "Gunakan riwayat stres di AI+",
                subtitle = "Hanya ringkasan lokal; raw PPG dan transcript tidak masuk prompt.",
                checked = state.useStressHistoryInAi,
                onCheckedChange = viewModel::setUseStressHistoryInAi
            )
            state.stressHistoryContextStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Small)
                )
            }
            SettingToggleRow(
                title = "Persetujuan AI Counselor",
                subtitle = "Izinkan AI membaca ringkasan riwayat stres lokal.",
                checked = state.aiCounselorConsentGranted,
                onCheckedChange = { viewModel.toggleConsent("AI_COUNSELING", it) }
            )
            if (aiReminderViewModel != null && aiReminderState != null) {
                AiReminderSettingsSection(
                    state = aiReminderState,
                    actions = AiReminderSettingsActions(
                        onDisplayNameSaved = aiReminderViewModel::saveDisplayName,
                        onReminderConsentChanged = aiReminderViewModel::setReminderConsent,
                        onEnabledChanged = aiReminderViewModel::setEnabled,
                        onRequestNotificationPermission = onRequestNotificationPermission,
                        onPause = aiReminderViewModel::pause,
                        onRetry = aiReminderViewModel::retry
                    )
                )
            }
        }
        ProfileDetail.DATA -> ProfileDetailSheet(
            title = "Data dan keamanan",
            subtitle = "Cadangan SAF terenkripsi bisa dipulihkan setelah instal ulang dengan kata sandi Anda.",
            onDismiss = { detailSheet = null }
        ) {
            ProfileBackupSection(
                state = state.userBackup,
                connected = state.modelStorage.connected,
                operationRunning = state.isLifecycleOperationRunning,
                actions = ProfileBackupActions(
                    onConnect = { modelFolderLauncher.launch(null) },
                    onBackup = { backupAction = ProfileBackupAction.BACKUP },
                    onRestore = { backupAction = ProfileBackupAction.RESTORE }
                )
            )
            Button(
                onClick = viewModel::exportAllData,
                enabled = !state.isLifecycleOperationRunning,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
            ) { Text("Ekspor data lokal") }
            state.statusMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            Text(
                text = "Tindakan berisiko",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = AppSpacing.Medium)
            )
            if (state.modelStorage.connected) {
                DestructiveActionButton(
                    label = "Lepas koneksi folder",
                    onClick = { requestDestructiveAction(ProfileDestructiveAction.DISCONNECT_STORAGE) }
                )
            }
            if (state.isModelReady) {
                DestructiveActionButton(
                    label = "Hapus salinan Gemma dari aplikasi",
                    onClick = { requestDestructiveAction(ProfileDestructiveAction.DELETE_GEMMA) }
                )
            }
            if (state.speechBundleReadiness is SpeechBundleReadiness.Ready) {
                DestructiveActionButton(
                    label = "Hapus salinan Sherpa dari aplikasi",
                    onClick = { requestDestructiveAction(ProfileDestructiveAction.DELETE_SPEECH) }
                )
            }
            if (state.modelDownloadState is ModelDownloadState.Cancelled ||
                state.modelDownloadState is ModelDownloadState.Failed
            ) {
                DestructiveActionButton(
                    label = "Hapus partial Gemma",
                    onClick = { requestDestructiveAction(ProfileDestructiveAction.DISCARD_GEMMA_PARTIAL) }
                )
            }
            if (state.speechDownloadState is SpeechBundleDownloadState.Cancelled ||
                state.speechDownloadState is SpeechBundleDownloadState.Failed
            ) {
                DestructiveActionButton(
                    label = "Hapus partial Sherpa",
                    onClick = { requestDestructiveAction(ProfileDestructiveAction.DISCARD_SPEECH_PARTIAL) }
                )
            }
            if (state.embeddingArtifactState is ArtifactState.RetryableFailure ||
                state.embeddingArtifactState is ArtifactState.Corrupt
            ) {
                DestructiveActionButton(
                    label = "Hapus partial embedding",
                    onClick = { requestDestructiveAction(ProfileDestructiveAction.DISCARD_EMBEDDING_PARTIAL) }
                )
            }
            DestructiveActionButton(
                label = "Hapus semua data lokal aplikasi",
                onClick = { requestDestructiveAction(ProfileDestructiveAction.PURGE_DATA) }
            )
        }
        null -> Unit
    }

    if (showVoiceSettings) {
        ModalBottomSheet(
            onDismissRequest = { showVoiceSettings = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            VoiceSettingsSheet(
                settings = VoiceSettingsState(
                    language = state.voiceLanguage,
                    reviewTranscript = state.voiceSendMode == VoiceSendMode.REVIEW,
                    replyEnabled = state.voiceReplyEnabled
                ),
                onDismiss = { showVoiceSettings = false },
                onReviewTranscriptChanged = { enabled ->
                    viewModel.setVoiceSendMode(
                        if (enabled) VoiceSendMode.REVIEW else VoiceSendMode.AUTO_SEND
                    )
                },
                onReplyEnabledChanged = viewModel::setVoiceReplyEnabled,
                onLanguageSelected = viewModel::setVoiceLanguage
            )
        }
    }

    if (showSherpaSources) {
        AlertDialog(
            onDismissRequest = { showSherpaSources = false },
            title = { Text("Siapkan atau perbarui Sherpa") },
            text = {
                Text(
                    "Tambahkan Whisper lokal melalui bundle terbaru. " +
                        "Salinan lama tetap dipakai sampai pembaruan tervalidasi; rekaman dan data sesi tidak dihapus."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSherpaSources = false
                        viewModel.downloadSpeechBundle()
                    },
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                ) { Text("Unduh via Wi-Fi") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showSherpaSources = false
                        speechBundleLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                ) { Text("Impor ZIP lokal") }
            }
        )
    }

    backupAction?.let { action ->
        ProfileBackupDialog(
            action = action,
            onConfirm = if (action == ProfileBackupAction.BACKUP) viewModel::createSafBackup else viewModel::restoreSafBackup,
            onDismiss = { backupAction = null }
        )
    }

    destructiveAction?.let { action ->
        AlertDialog(
            onDismissRequest = { destructiveAction = null },
            title = { Text(action.title) },
            text = { Text(action.message) },
            confirmButton = {
                Button(
                    onClick = {
                        when (action) {
                            ProfileDestructiveAction.DISCONNECT_STORAGE -> viewModel.disconnectModelFolder()
                            ProfileDestructiveAction.DELETE_GEMMA -> viewModel.deleteModel()
                            ProfileDestructiveAction.DELETE_SPEECH -> viewModel.deleteSpeechBundle()
                            ProfileDestructiveAction.DISCARD_GEMMA_PARTIAL -> viewModel.discardPartialModelDownload()
                            ProfileDestructiveAction.DISCARD_SPEECH_PARTIAL ->
                                viewModel.discardPartialSpeechBundleDownload()
                            ProfileDestructiveAction.DISCARD_EMBEDDING_PARTIAL ->
                                viewModel.discardPartialEmbedding()
                            ProfileDestructiveAction.PURGE_DATA -> viewModel.purgeAllData()
                        }
                        destructiveAction = null
                    },
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Konfirmasi") }
            },
            dismissButton = {
                TextButton(
                    onClick = { destructiveAction = null },
                    modifier = Modifier.heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                ) { Text("Batal") }
            }
        )
    }
}

private enum class ProfileDetail {
    STORAGE,
    MODELS,
    AI,
    DATA
}

@Composable
private fun ProfileReadinessCard(privacyReady: Boolean, storageStatus: String, storageReady: Boolean, aiStatus: String, aiReady: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Text(
                text = "Status aplikasi",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            ProfileStatusRow("Privasi", if (privacyReady) "Siap" else "Perlu setup", privacyReady)
            ProfileStatusRow("Penyimpanan offline", storageStatus, storageReady)
            ProfileStatusRow("AI Counselor", aiStatus, aiReady)
        }
    }
}

@Composable
private fun ProfileOverviewSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
            shape = MaterialTheme.shapes.large
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun ProfileNavigationRow(title: String, supporting: String, onClick: () -> Unit, showDivider: Boolean = false) {
    Column {
        if (showDivider) {
            Divider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        }
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "$title. $supporting"
                },
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(supporting, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingContent = {
                Text(
                    text = "Kelola",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDetailSheet(title: String, subtitle: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileStatusRow(label: String, status: String, emphasized: Boolean) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "$label: $status"
                stateDescription = status
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            status,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ArtifactSettingsRow(
    title: String,
    status: String,
    supporting: String? = null,
    action: ArtifactAction?,
    progress: Float? = null,
    progressLabel: String? = null
) {
    val motion = LocalAppMotion.current
    val animatedProgress by animateFloatAsState(
        targetValue = progress?.coerceIn(0f, 1f) ?: 0f,
        animationSpec = tween(motion.duration(ARTIFACT_PROGRESS_MILLIS)),
        label = "artifactProgress"
    )
    Column {
        ListItem(
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
                    Text(status)
                    supporting?.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            trailingContent = action?.let { contextualAction ->
                {
                    TextButton(
                        onClick = contextualAction.onClick,
                        modifier = Modifier
                            .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                            .semantics { contentDescription = contextualAction.description }
                    ) { Text(contextualAction.label) }
                }
            }
        )
        progress?.let {
            LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.Medium)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = progressLabel ?: "Kemajuan $title"
                    }
            )
            progressLabel?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Micro)
                )
            }
        }
    }
}

@Composable
private fun SettingToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.semantics { contentDescription = title }
            )
        }
    )
}

private data class VoiceSettingsState(val language: VoiceLanguage, val reviewTranscript: Boolean, val replyEnabled: Boolean)

@Suppress("FunctionNaming")
@Composable
private fun VoiceSettingsSheet(
    settings: VoiceSettingsState,
    onDismiss: () -> Unit,
    onReviewTranscriptChanged: (Boolean) -> Unit,
    onReplyEnabledChanged: (Boolean) -> Unit,
    onLanguageSelected: (VoiceLanguage) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        VoiceSettingsHeader(onDismiss = onDismiss)
        SettingToggleRow(
            title = "Review transcript",
            subtitle = "Periksa hasil transkripsi sebelum masuk ke chat.",
            checked = settings.reviewTranscript,
            onCheckedChange = onReviewTranscriptChanged
        )
        SettingToggleRow(
            title = "Balas dengan suara",
            subtitle = "Bacakan jawaban setelah respons selesai disimpan.",
            checked = settings.replyEnabled,
            onCheckedChange = onReplyEnabledChanged
        )
        VoiceLanguageOptions(
            language = settings.language,
            onLanguageSelected = onLanguageSelected
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun VoiceSettingsHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
        ) {
            Text(
                text = "Pengaturan suara",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = "Tidak mengubah atau mengunduh model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(
            onClick = onDismiss,
            modifier = Modifier
                .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                .semantics { contentDescription = "Tutup pengaturan suara" }
        ) {
            Text("Tutup")
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun VoiceLanguageOptions(language: VoiceLanguage, onLanguageSelected: (VoiceLanguage) -> Unit) {
    Text(
        text = "Bahasa",
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        VoiceLanguageButton(
            label = "Indonesia",
            selected = language == VoiceLanguage.INDONESIAN,
            onClick = { onLanguageSelected(VoiceLanguage.INDONESIAN) }
        )
        VoiceLanguageButton(
            label = "English",
            selected = language == VoiceLanguage.ENGLISH,
            onClick = { onLanguageSelected(VoiceLanguage.ENGLISH) }
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun RowScope.VoiceLanguageButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val stateLabel = if (selected) "Dipilih" else "Tidak dipilih"
    val modifier = Modifier
        .weight(1f)
        .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
        .semantics {
            contentDescription = "$label, $stateLabel"
            stateDescription = stateLabel
        }
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
@Suppress("LongParameterList", "FunctionNaming")
private fun TechnicalStorageDetails(
    report: DurableSyncReport?,
    provider: String?,
    treeUri: String?,
    providerRoot: String?,
    resolvedDocument: String?,
    modelPath: String,
    readPermission: Boolean,
    writePermission: Boolean,
    availableBytes: Long?,
    lastAuditAtMillis: Long?,
    legacyLocations: List<String>,
    storageError: String?,
    modelMetadata: String?,
    embeddingCatalogDisclosure: String?,
    runtimeTechnicalMessage: String?
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TechnicalLine("Provider", provider ?: "belum tersedia")
            TechnicalLine("Tree URI", treeUri ?: "belum terhubung")
            TechnicalLine("Document ID", resolvedDocument ?: providerRoot ?: "belum tersedia")
            TechnicalLine("Path aktif", modelPath)
            TechnicalLine("Izin", "baca ${readPermission.yesNo()}, tulis ${writePermission.yesNo()}")
            TechnicalLine("Kapasitas", availableBytes?.let(::formatBytes) ?: "tidak dilaporkan")
            TechnicalLine(
                "Audit terakhir",
                lastAuditAtMillis?.let(::formatAuditTime) ?: "belum ada"
            )
            if (legacyLocations.isNotEmpty()) {
                TechnicalLine("Lokasi legacy", legacyLocations.joinToString())
            }
            DurableArtifact.entries.forEach { artifact ->
                report?.recoveryOf(artifact)?.let { recovery ->
                    TechnicalLine(artifact.displayName(), recovery.technicalLabel())
                }
            }
            modelMetadata?.let { TechnicalLine("Metadata Gemma", it) }
            embeddingCatalogDisclosure
                ?.takeIf(String::isNotBlank)
                ?.let { TechnicalLine("Catalog embedding", it) }
            listOfNotNull(storageError, runtimeTechnicalMessage)
                .plus(report?.technicalMessages().orEmpty())
                .distinct()
                .forEach { TechnicalLine("Diagnostik", it, isError = true) }
        }
    }
}

@Composable
private fun TechnicalLine(label: String, value: String, isError: Boolean = false) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DestructiveActionButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = MIN_TOUCH_TARGET_DP.dp),
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
    ) { Text(label) }
}

private data class ArtifactAction(val label: String, val description: String, val onClick: () -> Unit)

private data class GemmaActionContext(
    val activation: ModelActivationState,
    val candidateReadyForVerification: Boolean,
    val downloadState: ModelDownloadState,
    val recovery: DurableArtifactRecovery?,
    val storageConnected: Boolean
)

private interface GemmaActionCallbacks {
    fun connect()
    fun sync()
    fun download()
    fun retryDownload()
    fun retryVerification()
    fun cancel()
    fun retryRuntime()
    fun showDetails()
}

private fun gemmaContextualAction(context: GemmaActionContext, callbacks: GemmaActionCallbacks): ArtifactAction? =
    candidateVerificationAction(context, callbacks)
        ?: preflightFailureAction(context, callbacks)
        ?: durableRecoveryAction(context, callbacks)
        ?: downloadAction(context, callbacks)
        ?: activationAction(context, callbacks)
        ?: terminalRecoveryAction(context, callbacks)

private fun candidateVerificationAction(context: GemmaActionContext, callbacks: GemmaActionCallbacks): ArtifactAction? = if (
    context.candidateReadyForVerification &&
    context.activation != ModelActivationState.Ready &&
    !context.downloadState.isTransferBusy()
) {
    ArtifactAction(
        "Coba preflight",
        "Verifikasi candidate Gemma tanpa mengunduh ulang",
        callbacks::retryVerification
    )
} else {
    null
}

private fun ModelDownloadState.isTransferBusy(): Boolean = this is ModelDownloadState.Downloading || this is ModelDownloadState.Verifying

private fun preflightFailureAction(context: GemmaActionContext, callbacks: GemmaActionCallbacks): ArtifactAction? = if (
    context.downloadState is ModelDownloadState.Failed &&
    context.downloadState.isRuntimePreflightFailure()
) {
    ArtifactAction(
        "Coba preflight",
        "Ulangi pemeriksaan runtime tanpa mengunduh ulang Gemma",
        callbacks::retryVerification
    )
} else {
    null
}

private fun durableRecoveryAction(context: GemmaActionContext, callbacks: GemmaActionCallbacks): ArtifactAction? =
    when (context.recovery?.state) {
        DurableArtifactRecoveryState.PERMISSION_LOST ->
            ArtifactAction("Hubungkan", "Hubungkan folder Gemma", callbacks::connect)
        DurableArtifactRecoveryState.RESTORING -> null
        DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE ->
            ArtifactAction("Perbaiki", "Perbaiki runtime Gemma", callbacks::retryRuntime)
        DurableArtifactRecoveryState.CHECKSUM_READY ->
            ArtifactAction("Aktifkan", "Aktifkan runtime Gemma", callbacks::retryRuntime)
        DurableArtifactRecoveryState.ERROR ->
            ArtifactAction("Detail", "Lihat kegagalan Gemma", callbacks::showDetails)
        else -> null
    }

private fun downloadAction(context: GemmaActionContext, callbacks: GemmaActionCallbacks): ArtifactAction? = when {
    context.downloadState is ModelDownloadState.Downloading ||
        context.downloadState is ModelDownloadState.Verifying ->
        ArtifactAction("Batalkan", "Batalkan proses Gemma", callbacks::cancel)
    context.downloadState is ModelDownloadState.WaitingForNetwork ->
        ArtifactAction("Coba lagi", "Evaluasi ulang jaringan dan coba Gemma", callbacks::retryDownload)
    context.downloadState is ModelDownloadState.Failed && context.downloadState.retryable ->
        ArtifactAction("Coba lagi", "Coba lagi download Gemma", callbacks::retryDownload)
    context.downloadState is ModelDownloadState.TransferRetry ->
        ArtifactAction("Coba lagi", "Coba lagi transfer Gemma", callbacks::retryDownload)
    else -> null
}

private fun activationAction(context: GemmaActionContext, callbacks: GemmaActionCallbacks): ArtifactAction? = when {
    context.activation is ModelActivationState.RuntimeUnavailable ->
        ArtifactAction("Perbaiki", "Perbaiki runtime Gemma", callbacks::retryRuntime)
    context.activation == ModelActivationState.Missing &&
        context.recovery?.state == DurableArtifactRecoveryState.READY ->
        ArtifactAction("Pulihkan", "Pulihkan Gemma dari storage", callbacks::sync)
    context.activation == ModelActivationState.Missing && !context.storageConnected ->
        ArtifactAction("Hubungkan", "Hubungkan folder Gemma", callbacks::connect)
    context.activation == ModelActivationState.Missing ||
        context.activation is ModelActivationState.Corrupted ->
        ArtifactAction("Unduh", "Unduh Gemma", callbacks::download)
    else -> null
}

private fun terminalRecoveryAction(context: GemmaActionContext, callbacks: GemmaActionCallbacks): ArtifactAction? =
    when (context.recovery?.state) {
        DurableArtifactRecoveryState.CORRUPT,
        DurableArtifactRecoveryState.MISSING -> if (context.recovery.shouldOfferNetworkDownload()) {
            ArtifactAction("Unduh", "Unduh Gemma", callbacks::download)
        } else {
            ArtifactAction("Detail", "Lihat detail Gemma", callbacks::showDetails)
        }
        else -> null
    }

private fun speechContextualAction(
    recovery: DurableArtifactRecovery?,
    readiness: SpeechBundleReadiness,
    downloadState: SpeechBundleDownloadState,
    storageConnected: Boolean,
    onConnect: () -> Unit,
    onSync: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onPrepare: () -> Unit,
    onShowDetails: () -> Unit
): ArtifactAction? = when {
    recovery?.state == DurableArtifactRecoveryState.PERMISSION_LOST ->
        ArtifactAction("Hubungkan", "Hubungkan folder Sherpa", onConnect)
    recovery?.state == DurableArtifactRecoveryState.RESTORING -> null
    recovery?.state == DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE ->
        ArtifactAction("Detail", "Lihat kompatibilitas Sherpa", onShowDetails)
    recovery?.state == DurableArtifactRecoveryState.CHECKSUM_READY ->
        ArtifactAction("Pulihkan", "Pulihkan Sherpa", onSync)
    recovery?.state == DurableArtifactRecoveryState.ERROR ->
        ArtifactAction("Detail", "Lihat kegagalan Sherpa", onShowDetails)
    downloadState.isBusy() -> ArtifactAction("Batalkan", "Batalkan proses Sherpa", onCancel)
    downloadState is SpeechBundleDownloadState.Failed && downloadState.retryable ->
        ArtifactAction("Coba lagi", "Coba lagi Sherpa", onRetry)
    readiness is SpeechBundleReadiness.Ready -> readySpeechAction(readiness, onPrepare)
    recovery?.state == DurableArtifactRecoveryState.READY ->
        ArtifactAction("Pulihkan", "Pulihkan Sherpa", onSync)
    !storageConnected ->
        ArtifactAction("Hubungkan", "Hubungkan folder Sherpa", onConnect)
    else -> ArtifactAction("Siapkan", "Siapkan Sherpa", onPrepare)
}

private fun readySpeechAction(readiness: SpeechBundleReadiness.Ready, onPrepare: () -> Unit): ArtifactAction? =
    if (readiness.manifest.whisperStt == null) {
        ArtifactAction("Perbarui", "Tambahkan Whisper lokal tanpa menghapus sesi", onPrepare)
    } else {
        null
    }

private fun embeddingContextualAction(
    state: ArtifactState,
    recovery: DurableArtifactRecovery?,
    storageConnected: Boolean,
    onConnect: () -> Unit,
    onSync: () -> Unit,
    onDownload: () -> Unit,
    onShowDetails: () -> Unit
): ArtifactAction? = when {
    recovery?.state == DurableArtifactRecoveryState.PERMISSION_LOST ->
        ArtifactAction("Hubungkan", "Hubungkan folder embedding", onConnect)
    recovery?.state == DurableArtifactRecoveryState.RESTORING -> null
    recovery?.state == DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE ||
        state is ArtifactState.RuntimeIncompatible ->
        ArtifactAction("Detail", "Lihat kompatibilitas embedding", onShowDetails)
    recovery?.state == DurableArtifactRecoveryState.ERROR ->
        ArtifactAction("Detail", "Lihat kegagalan embedding", onShowDetails)
    recovery?.state == DurableArtifactRecoveryState.CHECKSUM_READY ||
        recovery?.state == DurableArtifactRecoveryState.READY &&
        state !is ArtifactState.Ready ->
        ArtifactAction("Pulihkan", "Pulihkan embedding", onSync)
    state is ArtifactState.Ready ||
        state is ArtifactState.Downloading ||
        state == ArtifactState.Queued ||
        state == ArtifactState.Verifying ||
        state == ArtifactState.Preflighting -> null
    !storageConnected ->
        ArtifactAction("Hubungkan", "Hubungkan folder embedding", onConnect)
    state == ArtifactState.Missing ||
        state is ArtifactState.Corrupt ||
        state is ArtifactState.RetryableFailure ->
        ArtifactAction("Unduh", "Unduh embedding", onDownload)
    else -> null
}

private fun gemmaStatusLabel(
    activation: ModelActivationState,
    recovery: DurableArtifactRecovery?,
    candidateReadyForVerification: Boolean
): String = when (recovery?.state) {
    DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
    DurableArtifactRecoveryState.CHECKSUM_READY,
    DurableArtifactRecoveryState.PERMISSION_LOST,
    DurableArtifactRecoveryState.RESTORING -> recovery.profileLabel()
    else -> when (activation) {
        ModelActivationState.Missing -> if (candidateReadyForVerification) {
            "File tersedia; siap diverifikasi"
        } else {
            "Belum tersedia"
        }
        ModelActivationState.Verifying -> "Memverifikasi file"
        ModelActivationState.Verified -> "Checksum siap; memeriksa runtime"
        is ModelActivationState.RuntimeUnavailable -> "File tersedia; runtime tidak kompatibel"
        ModelActivationState.Ready -> "Siap offline"
        is ModelActivationState.Corrupted -> "File rusak atau tidak tepercaya"
    }
}

private fun speechStatusLabel(readiness: SpeechBundleReadiness, capabilitiesReady: Boolean, recovery: DurableArtifactRecovery?): String =
    when (recovery?.state) {
        DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
        DurableArtifactRecoveryState.CHECKSUM_READY,
        DurableArtifactRecoveryState.PERMISSION_LOST,
        DurableArtifactRecoveryState.RESTORING -> recovery.profileLabel()
        else -> when (readiness) {
            SpeechBundleReadiness.Checking -> "Memeriksa bundle lokal"
            SpeechBundleReadiness.Missing -> "Belum tersedia"
            is SpeechBundleReadiness.Invalid -> "File rusak atau tidak tepercaya"
            is SpeechBundleReadiness.Ready -> if (capabilitiesReady) {
                "Siap offline"
            } else {
                "Bundle tersedia; STT Indonesia perlu pembaruan"
            }
        }
    }

private fun embeddingStatusLabel(state: ArtifactState, recovery: DurableArtifactRecovery?): String = when (recovery?.state) {
    DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE,
    DurableArtifactRecoveryState.CHECKSUM_READY,
    DurableArtifactRecoveryState.PERMISSION_LOST,
    DurableArtifactRecoveryState.RESTORING -> recovery.profileLabel()
    else -> when (state) {
        ArtifactState.Missing -> "Belum tersedia"
        ArtifactState.Queued -> "Menunggu Wi-Fi"
        is ArtifactState.Downloading -> "Mengunduh ${state.fileName}"
        ArtifactState.Verifying -> "Memverifikasi checksum"
        ArtifactState.Preflighting -> "Memeriksa runtime"
        is ArtifactState.Ready -> "Siap offline · ${state.mirrorState.name.lowercase()}"
        is ArtifactState.RetryableFailure -> "Proses dapat dicoba lagi"
        is ArtifactState.Corrupt -> "File rusak atau tidak tepercaya"
        is ArtifactState.RuntimeIncompatible -> "File tersedia; runtime tidak kompatibel"
    }
}

private fun voiceStatusLabel(ready: Boolean, speechBusy: Boolean, recovery: DurableArtifactRecovery?): String = when (recovery?.state) {
    DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE -> "File tersedia; runtime tidak kompatibel"
    DurableArtifactRecoveryState.CHECKSUM_READY -> "Checksum siap; menunggu aktivasi"
    DurableArtifactRecoveryState.PERMISSION_LOST -> "Hubungkan kembali folder"
    DurableArtifactRecoveryState.RESTORING -> "Memulihkan capability"
    else -> when {
        ready -> "Siap offline"
        speechBusy -> "Sedang disiapkan melalui Sherpa"
        else -> "Belum siap"
    }
}

private fun ModelDownloadState.modelProgressLabel(bytes: Long, totalBytes: Long): String? = when (this) {
    is ModelDownloadState.WaitingForNetwork -> message
    is ModelDownloadState.TransferRetry -> "Retry attempt $attempt · $message"
    is ModelDownloadState.Downloading -> "${formatBytes(bytes)} / ${formatBytes(totalBytes)}"
    is ModelDownloadState.Verifying -> "$stage · ${formatBytes(bytesProcessed)} / ${formatBytes(totalBytes)}"
    else -> null
}

private fun ModelDownloadState.hasTransferProgress(): Boolean = this is ModelDownloadState.TransferRetry ||
    this is ModelDownloadState.Downloading ||
    this is ModelDownloadState.Verifying

private fun SpeechBundleDownloadState.isBusy(): Boolean = this is SpeechBundleDownloadState.Downloading ||
    this is SpeechBundleDownloadState.Verifying ||
    this is SpeechBundleDownloadState.Assembling ||
    this is SpeechBundleDownloadState.Installing ||
    this == SpeechBundleDownloadState.Cancelling ||
    this == SpeechBundleDownloadState.Deleting

private fun SpeechBundleDownloadState.progressOrNull(): Float? = when (this) {
    is SpeechBundleDownloadState.Downloading -> ratio(bytesDownloaded, totalBytes)
    is SpeechBundleDownloadState.Verifying -> ratio(bytesProcessed, totalBytes)
    is SpeechBundleDownloadState.Assembling -> ratio(bytesProcessed, totalBytes)
    else -> null
}

private fun SpeechBundleDownloadState.progressLabel(): String? = when (this) {
    is SpeechBundleDownloadState.Downloading ->
        "$component · ${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
    is SpeechBundleDownloadState.Verifying ->
        "Verifikasi $component · ${formatBytes(bytesProcessed)} / ${formatBytes(totalBytes)}"
    is SpeechBundleDownloadState.Assembling ->
        "$component · ${formatBytes(bytesProcessed)} / ${formatBytes(totalBytes)}"
    is SpeechBundleDownloadState.Installing -> step
    SpeechBundleDownloadState.Cancelling -> "Membatalkan dengan aman"
    SpeechBundleDownloadState.Deleting -> "Menghapus partial dengan aman"
    else -> null
}

private fun ratio(value: Long, total: Long): Float = if (total > 0L) {
    value.toFloat() / total
} else {
    0f
}

private fun DurableArtifactSyncStatus.profileLabel(): String = when (this) {
    DurableArtifactSyncStatus.Restoring -> "memulihkan"
    DurableArtifactSyncStatus.Synced -> "disinkronkan"
    DurableArtifactSyncStatus.AlreadyCurrent -> "siap"
    DurableArtifactSyncStatus.Restored -> "dipulihkan"
    is DurableArtifactSyncStatus.MissingLocalSource -> message
    is DurableArtifactSyncStatus.Corrupt -> "perlu diperbarui"
    is DurableArtifactSyncStatus.PermissionLost -> "perlu hubungkan ulang"
    is DurableArtifactSyncStatus.RuntimeIncompatible -> "runtime tidak kompatibel"
    is DurableArtifactSyncStatus.Failed -> "sinkronisasi perlu dicoba lagi"
}

internal fun DurableArtifactRecovery.profileLabel(): String {
    val stateLabel = when (state) {
        DurableArtifactRecoveryState.RESTORING -> "memulihkan"
        DurableArtifactRecoveryState.CHECKSUM_READY -> "checksum siap"
        DurableArtifactRecoveryState.RUNTIME_INCOMPATIBLE -> "runtime tidak kompatibel"
        DurableArtifactRecoveryState.READY -> "siap"
        DurableArtifactRecoveryState.ERROR -> "perlu diperiksa"
        DurableArtifactRecoveryState.CORRUPT -> "rusak"
        DurableArtifactRecoveryState.MISSING -> "tidak tersedia"
        DurableArtifactRecoveryState.PERMISSION_LOST -> "Hubungkan folder"
    }
    val sourceLabel = when (source) {
        DurableArtifactSource.CANONICAL_SAF -> "mirror SAF"
        DurableArtifactSource.LEGACY_SAF -> "folder lama"
        DurableArtifactSource.APP_PRIVATE -> "aplikasi"
        null -> null
    }
    val sizeLabel = sizeBytes?.takeIf { it > 0L }?.let(::formatBytes)
    val checksumLabel = if (checksumVerified) "checksum terverifikasi" else null
    val networkLabel = if (shouldOfferNetworkDownload()) "unduh diperlukan" else null
    return listOfNotNull(stateLabel, sourceLabel, sizeLabel, checksumLabel, networkLabel).joinToString(" · ")
}

internal fun DurableArtifactRecovery.shouldOfferNetworkDownload(): Boolean = networkDownloadRequired &&
    state in setOf(
        DurableArtifactRecoveryState.MISSING,
        DurableArtifactRecoveryState.CORRUPT
    )

private fun DurableArtifactRecovery.technicalLabel(): String = buildList {
    add(profileLabel())
    checksumSha256?.let { add("SHA-256 $it") }
    detail?.let(::add)
}.joinToString(" · ")

private fun DurableArtifact.displayName(): String = when (this) {
    DurableArtifact.GEMMA -> "Gemma"
    DurableArtifact.SPEECH_BUNDLE -> "Sherpa"
    DurableArtifact.VOICE_CAPABILITY -> "Voice capability"
    DurableArtifact.MULTILINGUAL_EMBEDDING -> "Embedding"
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val megabytes = bytes / (1024L * 1024L)
    return if (megabytes >= 1024L) {
        String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
    } else {
        "$megabytes MB"
    }
}

private fun Boolean.yesNo(): String = if (this) "aktif" else "tidak aktif"

private fun formatAuditTime(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMillis))

private enum class ProfileDestructiveAction(val title: String, val message: String) {
    DISCONNECT_STORAGE(
        "Lepas koneksi folder?",
        "Izin aplikasi dilepas, tetapi file pada folder SAF tidak dihapus."
    ),
    DELETE_GEMMA(
        "Hapus salinan Gemma?",
        "Hanya salinan app-private yang dihapus. Mirror SAF tidak dihapus otomatis."
    ),
    DELETE_SPEECH(
        "Hapus salinan Sherpa?",
        "Runtime speech app-private dan mirror speech di SAF akan dihapus. Rekaman dan data sesi tetap ada."
    ),
    DISCARD_GEMMA_PARTIAL("Hapus partial Gemma?", "Progres download parsial akan hilang."),
    DISCARD_SPEECH_PARTIAL("Hapus partial Sherpa?", "Progres download parsial akan hilang."),
    DISCARD_EMBEDDING_PARTIAL("Hapus partial embedding?", "Progres download parsial akan hilang."),
    PURGE_DATA(
        "Hapus semua data lokal aplikasi?",
        "Riwayat, chat, dan data aplikasi akan dihapus. Tindakan ini tidak dapat dibatalkan."
    )
}
