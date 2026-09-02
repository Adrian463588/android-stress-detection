@file:Suppress("FunctionNaming", "LongParameterList", "LongMethod", "CyclomaticComplexMethod", "MaxLineLength")

package com.stressdetector.mobile.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.domain.engine.AcousticAnalysisSnapshot
import com.stressdetector.mobile.domain.engine.AudioInputSource
import com.stressdetector.mobile.domain.engine.DomainAnnotation
import com.stressdetector.mobile.domain.engine.LiveBiosignalSnapshot
import com.stressdetector.mobile.domain.engine.PlaybackState
import com.stressdetector.mobile.domain.engine.SessionFinalizationStage
import com.stressdetector.mobile.domain.engine.SpeechRuntimeState
import com.stressdetector.mobile.domain.engine.VerbatimSessionSummary
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.DefaultSpeakerBubbleToneResolver
import com.stressdetector.mobile.ui.PremiumCard
import com.stressdetector.mobile.ui.SpeakerBubbleTone
import com.stressdetector.mobile.ui.viewmodels.SessionCommandState
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionEffect
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionUiState
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionViewModel
import com.stressdetector.mobile.ui.viewmodels.drawerStatusLabel
import com.stressdetector.mobile.ui.viewmodels.drawerStorageLabel
import com.stressdetector.mobile.ui.viewmodels.needsVerbatimStorageRecovery
import com.stressdetector.mobile.ui.viewmodels.verbatimRecoveryMessage
import com.stressdetector.mobile.ui.viewmodels.verbatimStatusLabel
import com.stressdetector.shared.PpgType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val LIVE_TRANSCRIPT_TAIL_LIMIT = 200

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun UnifiedSessionScreen(
    viewModel: UnifiedSessionViewModel,
    modifier: Modifier = Modifier,
    windowWidthSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    onNavigateToProfile: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycleCompat()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    var pendingAudioUri by rememberSaveable { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportLastSession) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            pendingAudioUri = uri.toString()
            viewModel.requestNewSession(AudioInputSource.Imported(uri.toString()))
        }
    }

    var showConsentDialog by rememberSaveable { mutableStateOf(false) }
    var showSessionNameDialog by rememberSaveable { mutableStateOf(false) }
    var sessionName by rememberSaveable { mutableStateOf("Sesi Verbatim") }
    var consentConfirmedForPendingStart by rememberSaveable { mutableStateOf(false) }
    var deleteTargetSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteAllDialog by rememberSaveable { mutableStateOf(false) }
    var showManualAnnotationDialog by rememberSaveable { mutableStateOf(false) }
    var manualAnnotationText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.refreshSpeechRuntime()
            viewModel.effects.collect { effect ->
                when (effect) {
                    UnifiedSessionEffect.OpenStartDialog -> {
                        drawerScope.launch { drawerState.close() }
                        showSessionNameDialog = true
                    }
                    is UnifiedSessionEffect.ShowToast -> {
                        Toast.makeText(context, effect.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val needsMicrophone = pendingAudioUri == null
        val audioGranted = !needsMicrophone ||
            (permissions[Manifest.permission.RECORD_AUDIO] ?: false) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notificationGranted = android.os.Build.VERSION.SDK_INT < 33 ||
            (permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false) ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (audioGranted && notificationGranted && consentConfirmedForPendingStart) {
            consentConfirmedForPendingStart = false
            viewModel.startSession(sessionName)
        } else {
            consentConfirmedForPendingStart = false
            Toast.makeText(
                context,
                if (needsMicrophone) {
                    "Izin mikrofon dan notifikasi diperlukan untuk merekam sesi."
                } else {
                    "Izin notifikasi diperlukan agar pemrosesan import tetap terlihat."
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun requestStart() {
        val needsMicrophone = pendingAudioUri == null
        val audioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        val notificationPermission = if (android.os.Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PackageManager.PERMISSION_GRANTED
        }
        if ((!needsMicrophone || audioPermission == PackageManager.PERMISSION_GRANTED) &&
            notificationPermission == PackageManager.PERMISSION_GRANTED
        ) {
            consentConfirmedForPendingStart = false
            viewModel.startSession(sessionName)
        } else {
            val permissions = buildList {
                if (needsMicrophone) add(Manifest.permission.RECORD_AUDIO)
                if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Persetujuan pasien") },
            text = { Text("Pastikan persetujuan perekaman sudah diberikan sebelum sesi dimulai.") },
            confirmButton = {
                TextButton(onClick = {
                    consentConfirmedForPendingStart = true
                    showConsentDialog = false
                    requestStart()
                }) { Text("Persetujuan diberikan") }
            },
            dismissButton = { TextButton(onClick = { showConsentDialog = false }) { Text("Batal") } }
        )
    }

    if (showSessionNameDialog) {
        AlertDialog(
            onDismissRequest = { showSessionNameDialog = false },
            title = { Text("Buat rekaman baru") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = sessionName,
                        onValueChange = { sessionName = it.take(80) },
                        label = { Text("Nama sesi") },
                        singleLine = true
                    )
                    Text("Bahasa sesi", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.setSessionLanguage("id") }) {
                            Text(if (uiState.selectedLanguage == "id") "✓ Indonesia" else "Indonesia")
                        }
                        TextButton(onClick = { viewModel.setSessionLanguage("en") }) {
                            Text(if (uiState.selectedLanguage == "en") "✓ English" else "English")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSessionNameDialog = false
                    showConsentDialog = true
                }) { Text("Lanjut") }
            },
            dismissButton = { TextButton(onClick = { showSessionNameDialog = false }) { Text("Batal") } }
        )
    }

    if (showManualAnnotationDialog) {
        AlertDialog(
            onDismissRequest = { showManualAnnotationDialog = false },
            title = { Text("Marker manual") },
            text = {
                OutlinedTextField(
                    value = manualAnnotationText,
                    onValueChange = { manualAnnotationText = it.take(500) },
                    label = { Text("Catatan untuk ditinjau") },
                    supportingText = { Text("Timestamp mengikuti posisi audio atau waktu sesi saat ini.") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addManualAnnotation(manualAnnotationText)
                        showManualAnnotationDialog = false
                    },
                    enabled = manualAnnotationText.isNotBlank()
                ) { Text("Simpan") }
            },
            dismissButton = { TextButton(onClick = { showManualAnnotationDialog = false }) { Text("Batal") } }
        )
    }

    fun openDrawer() {
        drawerScope.launch { drawerState.open() }
    }
    fun closeDrawer() {
        drawerScope.launch { drawerState.close() }
    }

    val onSelectSession: (String) -> Unit = { sessionId ->
        viewModel.selectSession(sessionId)
        closeDrawer()
    }
    val drawerContent: @Composable () -> Unit = {
        SessionSidebar(
            uiState = uiState,
            onNewRecording = {
                pendingAudioUri = null
                viewModel.requestNewSession()
                closeDrawer()
            },
            onImportAudio = {
                closeDrawer()
                importLauncher.launch(arrayOf("audio/wav", "audio/x-wav", "audio/wave"))
            },
            onSelectSession = onSelectSession,
            onDeleteSession = {
                deleteTargetSessionId = it
                closeDrawer()
            },
            onDeleteAll = {
                showDeleteAllDialog = true
                closeDrawer()
            },
            onOpenProfile = onNavigateToProfile,
            onRefreshReadiness = {
                viewModel.refreshStorage()
                viewModel.refreshSpeechRuntime()
                viewModel.refreshSessionHistory()
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        val mainContent: @Composable () -> Unit = {
            SessionContent(
                uiState = uiState,
                modifier = Modifier,
                windowWidthSizeClass = windowWidthSizeClass,
                onOpenDrawer = ::openDrawer,
                onStartOrStop = {
                    when (uiState.sessionCommandState) {
                        SessionCommandState.Recording,
                        SessionCommandState.Paused
                        -> viewModel.stopSession()
                        SessionCommandState.Idle,
                        is SessionCommandState.Error -> {
                            pendingAudioUri = null
                            viewModel.requestNewSession()
                        }
                        else -> Unit
                    }
                },
                onPauseOrResume = {
                    when (uiState.sessionCommandState) {
                        SessionCommandState.Recording -> viewModel.pauseSession()
                        SessionCommandState.Paused -> viewModel.resumeSession()
                        else -> Unit
                    }
                },
                onPlay = viewModel::playAudio,
                onPausePlayback = viewModel::pauseAudio,
                onStopPlayback = viewModel::stopAudio,
                onSeekAudio = viewModel::seekAudio,
                onPlayFromMarker = viewModel::playFromMarker,
                onRetryPlayback = viewModel::prepareAudioPlayback,
                onExport = {
                    val name = uiState.selectedSession?.summary?.sessionName ?: "verbatim-session"
                    exportLauncher.launch("$name.zip")
                },
                onDelete = {
                    uiState.selectedSessionId?.let { deleteTargetSessionId = it }
                },
                onOpenProfile = onNavigateToProfile,
                onRefreshReadiness = {
                    viewModel.refreshStorage()
                    viewModel.refreshSpeechRuntime()
                },
                onCycleSpeaker = viewModel::cycleSpeakerLabel,
                onEditTranscript = viewModel::updateTranscriptText,
                onRequestAddManualAnnotation = {
                    manualAnnotationText = ""
                    showManualAnnotationDialog = true
                },
                onDeleteManualAnnotation = viewModel::deleteManualAnnotation
            )
        }

        if (windowWidthSizeClass == WindowWidthSizeClass.Expanded) {
            PermanentNavigationDrawer(
                drawerContent = { PermanentDrawerSheet { drawerContent() } },
                content = mainContent
            )
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { ModalDrawerSheet { drawerContent() } },
                content = mainContent
            )
        }
    }

    deleteTargetSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { deleteTargetSessionId = null },
            title = { Text("Hapus sesi?") },
            text = { Text("Audio, transcript, divergence events, dan summary sesi ini akan dihapus.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSession(sessionId)
                    deleteTargetSessionId = null
                }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { deleteTargetSessionId = null }) { Text("Batal") } }
        )
    }

    if (showDeleteAllDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllDialog = false },
            title = { Text("Hapus semua sesi?") },
            text = { Text("Semua rekaman Verbatim+ dan artifact terenkripsi akan dihapus.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllSessions()
                    showDeleteAllDialog = false
                }) { Text("Hapus semua") }
            },
            dismissButton = { TextButton(onClick = { showDeleteAllDialog = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun VerbatimPrimaryAction(
    label: String,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .heightIn(min = 48.dp)
            .semantics { stateDescription = description }
    ) {
        Icon(
            imageVector = when (label) {
                "Stop" -> Icons.Default.Stop
                "Buka Profile" -> Icons.Default.FolderOpen
                else -> Icons.Default.PlayArrow
            },
            contentDescription = description
        )
        Spacer(Modifier.size(6.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VerbatimReadinessBanner(uiState: UnifiedSessionUiState, onOpenProfile: () -> Unit, onRefreshReadiness: () -> Unit) {
    val storageNeedsAttention = uiState.storageState.needsVerbatimStorageRecovery()
    val runtimeNeedsAttention = uiState.speechRuntimeState !is SpeechRuntimeState.Ready
    if (!storageNeedsAttention && !runtimeNeedsAttention) return

    val runtimeChecking = uiState.speechRuntimeState is SpeechRuntimeState.Checking
    val storageChecking = uiState.storageState.status == com.stressdetector.mobile.ml.storage.SafStorageStatus.PREFLIGHTING ||
        uiState.storageState.status == com.stressdetector.mobile.ml.storage.SafStorageStatus.SYNCING
    val isChecking = runtimeChecking || storageChecking
    val title = when {
        isChecking -> "Menyiapkan Verbatim+"
        runtimeNeedsAttention && storageNeedsAttention -> "Verbatim+ belum siap"
        runtimeNeedsAttention -> "Speech belum siap"
        else -> "Penyimpanan offline belum siap"
    }
    val runtimeMessage = when (uiState.speechRuntimeState) {
        SpeechRuntimeState.Checking -> "Speech bundle sedang diperiksa."
        is SpeechRuntimeState.Unavailable -> "Speech bundle belum tersedia. Siapkan melalui Profile."
        is SpeechRuntimeState.Failed -> "Speech runtime belum dapat diaktifkan. Periksa Profile."
        SpeechRuntimeState.Ready -> null
    }
    val message = buildList {
        if (runtimeNeedsAttention) runtimeMessage?.let(::add)
        if (storageNeedsAttention) add(uiState.storageState.verbatimRecoveryMessage())
    }.joinToString("\n")
    val background = if (runtimeNeedsAttention && !runtimeChecking) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
    }

    PremiumCard(
        backgroundColor = background,
        modifier = Modifier.semantics {
            stateDescription = if (isChecking) "Sedang memeriksa kesiapan" else message
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (isChecking) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Button(
                    onClick = onOpenProfile,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Buka Profile") }
                TextButton(
                    onClick = onRefreshReadiness,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) { Text("Coba lagi") }
            }
        }
    }
}

private fun SpeechRuntimeState.verbatimUiLabel(): String = when (this) {
    SpeechRuntimeState.Checking -> "Sedang diperiksa"
    SpeechRuntimeState.Ready -> "Siap"
    is SpeechRuntimeState.Unavailable -> "Bundle belum tersedia"
    is SpeechRuntimeState.Failed -> "Perlu diperiksa"
}

@Composable
internal fun SessionSidebar(
    uiState: UnifiedSessionUiState,
    onNewRecording: () -> Unit,
    onImportAudio: () -> Unit,
    onSelectSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onOpenProfile: () -> Unit,
    onRefreshReadiness: () -> Unit
) {
    val sessionBusy = uiState.sessionCommandState !is SessionCommandState.Idle &&
        uiState.sessionCommandState !is SessionCommandState.Error
    LazyColumn(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 360.dp)
            .fillMaxWidth(),
        contentPadding = PaddingValues(
            start = AppSpacing.Screen,
            top = AppSpacing.Screen,
            end = AppSpacing.Screen,
            bottom = AppSpacing.Large
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
    ) {
        item {
            Text(
                "Sesi Verbatim+",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Pilih rekaman untuk membuka transcript dan menyiapkan audio.",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(AppSpacing.Small))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                Button(
                    onClick = onNewRecording,
                    enabled = !sessionBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Rekaman baru")
                }
                OutlinedButton(
                    onClick = onImportAudio,
                    enabled = !sessionBusy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Import WAV 16 kHz")
                }
            }
        }
        if (uiState.storageState.needsVerbatimStorageRecovery()) {
            item {
                PremiumCard(backgroundColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
                        Text(
                            uiState.storageState.verbatimStatusLabel(),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Speech: ${uiState.speechRuntimeState.verbatimUiLabel()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            uiState.storageState.verbatimRecoveryMessage(),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                        Button(
                            onClick = onOpenProfile,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        ) { Text("Buka Profile") }
                    }
                }
            }
        }
        if (uiState.isSessionActive) {
            item {
                PremiumCard(backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Rekaman sedang berjalan", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Selesaikan sesi aktif sebelum membuka rekaman lain.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        if (uiState.isSessionHistoryLoading) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Large),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text("Memuat rekaman…", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (uiState.sessionHistoryError != null) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Large),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Text("Rekaman belum dapat dimuat", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${uiState.sessionHistoryError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = onRefreshReadiness) { Text("Coba lagi") }
                }
            }
        } else if (uiState.sessionHistory.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = AppSpacing.Large),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Belum ada rekaman tersimpan.", fontWeight = FontWeight.SemiBold)
                    Text("Mulai rekaman baru untuk melihatnya di sini.", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "Rekaman tersimpan",
                        modifier = Modifier.weight(1f),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    TextButton(
                        onClick = onDeleteAll,
                        enabled = !sessionBusy,
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Hapus semua") }
                }
            }
            items(uiState.sessionHistory, key = { it.sessionId }) { session ->
                SessionHistoryItem(
                    session = session,
                    selected = session.sessionId == uiState.selectedSessionId,
                    enabled = !sessionBusy,
                    onSelect = { onSelectSession(session.sessionId) },
                    onDelete = { onDeleteSession(session.sessionId) }
                )
            }
        }
    }
}

@Composable
private fun SessionHistoryItem(
    session: VerbatimSessionSummary,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f),
        shape = MaterialTheme.shapes.medium,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = AppSpacing.Compact, top = AppSpacing.Compact, bottom = AppSpacing.Compact, end = AppSpacing.Micro),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = AppSpacing.Micro)
                    .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
                    .semantics {
                        stateDescription = when {
                            !enabled -> "Tidak tersedia saat sesi sedang diproses"
                            selected -> "Dipilih"
                            else -> "Tidak dipilih"
                        }
                        if (!enabled) disabled()
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                Icon(Icons.Default.History, contentDescription = null)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
                ) {
                    Text(
                        session.sessionName,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${formatSessionTime(session.startedAtMillis)} · ${formatDuration(session.durationSeconds)}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        session.drawerStatusLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(
                onClick = onDelete,
                enabled = enabled,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Hapus sesi ${session.sessionName}"
                )
            }
        }
    }
}

@Composable
private fun SessionContent(
    uiState: UnifiedSessionUiState,
    modifier: Modifier,
    windowWidthSizeClass: WindowWidthSizeClass,
    onOpenDrawer: () -> Unit,
    onStartOrStop: () -> Unit,
    onPauseOrResume: () -> Unit,
    onPlay: () -> Unit,
    onPausePlayback: () -> Unit,
    onStopPlayback: () -> Unit,
    onSeekAudio: (Long) -> Unit,
    onPlayFromMarker: (Long) -> Unit,
    onRetryPlayback: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onOpenProfile: () -> Unit,
    onRefreshReadiness: () -> Unit,
    onCycleSpeaker: (String) -> Unit,
    onEditTranscript: (String, String) -> Unit,
    onRequestAddManualAnnotation: () -> Unit,
    onDeleteManualAnnotation: (String) -> Unit
) {
    val sessionBusy = uiState.sessionCommandState !is SessionCommandState.Idle &&
        uiState.sessionCommandState !is SessionCommandState.Error
    val canStart = uiState.canStartNewSession
    val canStop = uiState.sessionCommandState is SessionCommandState.Recording ||
        uiState.sessionCommandState is SessionCommandState.Paused
    val canPauseOrResume = canStop
    val actionLabel = when {
        canStop -> "Stop"
        canStart -> "Mulai rekaman"
        else -> "Menyiapkan…"
    }
    val actionDescription = when {
        canStop -> "Stop dan simpan sesi"
        canStart -> "Mulai rekaman Verbatim+"
        else -> "Menyiapkan Verbatim+"
    }
    var selectedLivePanel by rememberSaveable { mutableStateOf("TRANSCRIPT") }
    var technicalEvidenceExpanded by rememberSaveable { mutableStateOf(false) }
    val transcriptItems = remember(uiState.transcriptSegments) {
        uiState.transcriptSegments
            .sortedWith(compareBy({ it.startOffsetMs }, { it.id }))
            .distinctBy { it.id }
    }
    val liveTranscriptItems = remember(transcriptItems) {
        transcriptItems.takeLast(LIVE_TRANSCRIPT_TAIL_LIMIT)
    }
    val markerItems = remember(uiState.divergenceEvents) {
        uiState.divergenceEvents
            .sortedWith(compareBy({ it.episodeStartElapsedMillis }, { it.eventId }))
            .distinctBy { it.eventId }
    }
    val annotationItems = remember(uiState.annotations) {
        uiState.annotations
            .sortedWith(compareBy({ it.timestampMs }, { it.id }))
            .distinctBy { it.id }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = AppSpacing.Screen),
        contentPadding = PaddingValues(top = AppSpacing.Small, bottom = AppSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Default.Menu, contentDescription = "Buka daftar sesi")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Verbatim+",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "Transkripsi lokal dan monitoring sesi",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${when {
                                uiState.isSessionActive -> "Merekam"
                                canStart -> "Siap"
                                uiState.speechRuntimeState is SpeechRuntimeState.Checking -> "Menyiapkan"
                                else -> "Belum siap"
                            }} · ${formatElapsed(uiState.elapsedSeconds)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (uiState.isSessionActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                }
                if (canStart || canStop) {
                    VerbatimPrimaryAction(
                        label = actionLabel,
                        description = actionDescription,
                        enabled = canStart || canStop,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onStartOrStop
                    )
                }
            }
        }
        if (uiState.storageState.needsVerbatimStorageRecovery() ||
            uiState.speechRuntimeState !is SpeechRuntimeState.Ready
        ) {
            item {
                VerbatimReadinessBanner(
                    uiState = uiState,
                    onOpenProfile = onOpenProfile,
                    onRefreshReadiness = onRefreshReadiness
                )
            }
        }
        item {
            if (uiState.isSessionActive || sessionBusy) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = onPauseOrResume, enabled = canPauseOrResume) {
                            Icon(
                                if (uiState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = null
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(
                                when (uiState.sessionCommandState) {
                                    SessionCommandState.Paused -> "Lanjutkan"
                                    SessionCommandState.Pausing -> "Menjeda…"
                                    SessionCommandState.Resuming -> "Melanjutkan…"
                                    else -> "Pause"
                                }
                            )
                        }
                        Text(
                            "${sessionCommandStatus(uiState.sessionCommandState)} · ${uiState.watchStatus}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "Monitor: ${uiState.monitorStatus}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val hasTranscriptionBacklog = uiState.speechProcessingState.isDraining ||
                        uiState.speechProcessingState.backlogFrames > 0
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(18.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            "Transkripsi tertinggal: ${uiState.speechProcessingState.backlogFrames} frame",
                            modifier = Modifier.alpha(if (hasTranscriptionBacklog) 1f else 0f),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
        uiState.selectedSession?.summary?.let { session ->
            item {
                PremiumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(session.sessionName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${formatSessionTime(session.startedAtMillis)} · ${formatDuration(session.durationSeconds)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "${session.drawerStatusLabel()} · ${session.drawerStorageLabel()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!uiState.isSessionActive) {
                            SessionPlaybackControls(
                                state = uiState.playbackState,
                                positionMillis = uiState.playbackPositionMillis,
                                durationMillis = uiState.playbackDurationMillis,
                                onPlay = onPlay,
                                onPause = onPausePlayback,
                                onStop = onStopPlayback,
                                onSeek = onSeekAudio,
                                onRetry = onRetryPlayback
                            )
                            if (windowWidthSizeClass == WindowWidthSizeClass.Compact) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                                ) {
                                    OutlinedButton(
                                        onClick = onExport,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    ) { Text("Export ZIP") }
                                    OutlinedButton(
                                        onClick = onDelete,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null)
                                        Spacer(Modifier.size(4.dp))
                                        Text("Hapus")
                                    }
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                                    OutlinedButton(onClick = onExport, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Text("Export ZIP")
                                    }
                                    OutlinedButton(onClick = onDelete, modifier = Modifier.heightIn(min = 48.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = null)
                                        Spacer(Modifier.size(4.dp))
                                        Text("Hapus")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (uiState.isFinalizing ||
            uiState.sessionCommandState is SessionCommandState.Starting ||
            uiState.sessionCommandState is SessionCommandState.Pausing ||
            uiState.sessionCommandState is SessionCommandState.Resuming ||
            uiState.sessionCommandState is SessionCommandState.Stopping
        ) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        }
        if (uiState.isFinalizing) {
            item {
                Text(
                    finalizationStageLabel(uiState.finalizationStage),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (uiState.sessionCommandState is SessionCommandState.Error &&
            uiState.finalizationEvidencePersisted != null
        ) {
            item {
                PremiumCard {
                    Text(
                        if (uiState.finalizationEvidencePersisted == true) {
                            "Finalisasi gagal sebagian; evidence yang tersedia sudah disimpan."
                        } else {
                            "Finalisasi gagal sebelum evidence dapat disimpan."
                        },
                        color = MaterialTheme.colorScheme.error
                    )
                    uiState.finalizationWarnings.distinct().forEach { warning ->
                        Text(warning, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        uiState.error?.let { message ->
            item {
                PremiumCard(
                    borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.45f),
                    backgroundColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
                ) { Text(message, color = MaterialTheme.colorScheme.error) }
            }
        }
        uiState.lastMonitorSummary?.takeIf { !uiState.isSessionActive }?.let { summary ->
            item {
                PremiumCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Ringkasan monitor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Accepted windows: ${summary.acceptedWindowCount}", style = MaterialTheme.typography.bodySmall)
                        Text("Rejected windows: ${summary.rejectedWindowCount}", style = MaterialTheme.typography.bodySmall)
                        Text("Divergence events: ${summary.divergenceCount}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (uiState.isSessionActive || uiState.selectedSessionId != null) {
            item {
                TextButton(
                    onClick = { technicalEvidenceExpanded = !technicalEvidenceExpanded },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Detail teknis signal dan audio" }
                ) {
                    Text(
                        if (technicalEvidenceExpanded) {
                            "Sembunyikan detail teknis"
                        } else {
                            "Lihat detail teknis signal dan audio"
                        }
                    )
                }
            }
            if (technicalEvidenceExpanded) {
                item { LiveBiosignalCard(uiState.liveBiosignal) }
                item { LiveAudioCard(uiState.acousticAnalysis) }
            }
            item {
                val selectedTabIndex = if (selectedLivePanel == "TRANSCRIPT") 0 else 1
                TabRow(selectedTabIndex = selectedTabIndex) {
                    Tab(
                        selected = selectedTabIndex == 0,
                        onClick = { selectedLivePanel = "TRANSCRIPT" },
                        modifier = Modifier.semantics {
                            stateDescription = if (selectedTabIndex == 0) "Tab aktif" else "Tab tidak aktif"
                        },
                        text = { Text("Transcript") }
                    )
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedLivePanel = "MARKERS" },
                        modifier = Modifier.semantics {
                            stateDescription = if (selectedTabIndex == 1) "Tab aktif" else "Tab tidak aktif"
                        },
                        text = { Text("Marker") }
                    )
                }
            }
            if (selectedLivePanel == "TRANSCRIPT") {
                item { LiveSectionHeader("LIVE TRANSCRIPT") }
                item {
                    LiveTranscriptFeed(
                        segments = liveTranscriptItems,
                        totalSegmentCount = transcriptItems.size,
                        onCycleSpeaker = onCycleSpeaker,
                        onEditTranscript = onEditTranscript,
                        onSeekAudio = onPlayFromMarker
                    )
                }
            } else {
                item { LiveSectionHeader("MARKER UNTUK DITINJAU") }
                item {
                    OutlinedButton(
                        onClick = onRequestAddManualAnnotation,
                        enabled = uiState.selectedSessionId != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Tambah marker manual") }
                }
                if (markerItems.isEmpty() && annotationItems.isEmpty()) {
                    item { EmptyLiveState("Belum ada marker fisiologis atau akustik pada sesi ini.") }
                } else if (markerItems.isNotEmpty()) {
                    items(markerItems, key = { it.eventId }) { event ->
                        DivergenceEventRow(event, onPlayFromMarker)
                    }
                }
                items(annotationItems, key = { it.id }) { annotation ->
                    ManualAnnotationRow(
                        annotation = annotation,
                        onSeekAudio = onPlayFromMarker,
                        onDelete = onDeleteManualAnnotation
                    )
                }
            }
        } else {
            item {
                EmptyLiveState("Belum ada sesi Verbatim+ yang dipilih. Mulai rekaman atau buka daftar sesi.")
            }
        }
    }
}

@Composable
private fun LiveBiosignalCard(snapshot: LiveBiosignalSnapshot) {
    val monitor = snapshot.monitor
    val stream = snapshot.ppgStream
    var selectedChannel by rememberSaveable { mutableStateOf("GREEN") }
    val chart = if (selectedChannel == "GREEN") snapshot.chart?.green else snapshot.chart?.ir
    val channel = if (selectedChannel == "GREEN") PpgType.GREEN else PpgType.IR
    val channelState = monitor.channelStates[channel]
    val channelQuality = channelState?.qualityScore
    val latestBpm = monitor.latestBpmByChannel[channel] ?: snapshot.latestBpmByChannel[channel]
    val bpmSummary = monitor.bpmSummaryByChannel[channel]
    val chartColor = MaterialTheme.colorScheme.primary
    PremiumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LIVE PPG / AROUSAL", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (monitor.isMonitoring) "Aktif" else "Siap",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (monitor.isMonitoring) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(
                "Indikator live untuk ditinjau; bukan keputusan stres Home lima menit.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TabRow(selectedTabIndex = if (selectedChannel == "GREEN") 0 else 1) {
                Tab(
                    selected = selectedChannel == "GREEN",
                    onClick = { selectedChannel = "GREEN" },
                    modifier = Modifier.semantics {
                        stateDescription = if (selectedChannel == "GREEN") "Kanal aktif" else "Kanal tidak aktif"
                    },
                    text = { Text("Green") }
                )
                Tab(
                    selected = selectedChannel == "IR",
                    onClick = { selectedChannel = "IR" },
                    modifier = Modifier.semantics {
                        stateDescription = if (selectedChannel == "IR") "Kanal aktif" else "Kanal tidak aktif"
                    },
                    text = { Text("IR") }
                )
            }
            if (latestBpm == null && bpmSummary == null) {
                Text(
                    "Menunggu data PPG",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    listOfNotNull(
                        latestBpm?.let { "latest ${formatBpm(it)}" },
                        bpmSummary?.meanBpm?.let { "mean ${formatBpm(it)}" },
                        bpmSummary?.medianBpm?.let { "median ${formatBpm(it)}" }
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                bpmSummary?.let { summary ->
                    Text(
                        "${summary.observationCount} observasi · ${summary.validPeakCount} peak valid",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                channelQuality?.let { "Quality ${(it * 100).coerceIn(0f, 100f).toInt()}%" }
                    ?: "Quality belum tersedia",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (chart != null && !chart.isEmpty) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .semantics {
                            contentDescription = "Grafik PPG ${channel.name}; tail 10 detik dari sampel aktual"
                        }
                ) {
                    val points = chart.normalized
                    val timestamps = chart.timestampsMillis
                    if (points.size > 1 && timestamps.size == points.size) {
                        val first = snapshot.chart?.axisStartMillis?.takeIf { it > 0L } ?: timestamps.first()
                        val last = snapshot.chart?.axisEndMillis?.takeIf { it > first } ?: timestamps.last()
                        val span = (last - first).coerceAtLeast(1L).toFloat()
                        for (index in 1 until points.size) {
                            val delta = timestamps[index] - timestamps[index - 1]
                            if (delta <= 0L || delta > 500L) continue
                            drawLine(
                                color = chartColor,
                                start = Offset(
                                    x = (timestamps[index - 1] - first).toFloat() / span * size.width,
                                    y = (1f - points[index - 1].coerceIn(0f, 1f)) * size.height
                                ),
                                end = Offset(
                                    x = (timestamps[index] - first).toFloat() / span * size.width,
                                    y = (1f - points[index].coerceIn(0f, 1f)) * size.height
                                ),
                                strokeWidth = 2f
                            )
                        }
                    }
                }
            } else {
                Text(
                    "Menunggu data PPG dari Watch…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                liveAvailabilityLabel(monitor.predictionAvailability),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snapshot.divergenceEvents.isNotEmpty()) {
                Text(
                    "${snapshot.divergenceEvents.size} marker review live",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            stream.transportFailureReason?.let { reason ->
                Text(
                    "Transport PPG: $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun LiveAudioCard(snapshot: AcousticAnalysisSnapshot?) {
    val waveformColor = MaterialTheme.colorScheme.tertiary
    PremiumCard {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LIVE AUDIO / QUALITY", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    if (snapshot == null) "Menunggu" else "${snapshot.startOffsetMs / 1_000L}s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (snapshot == null) {
                Text(
                    "Menunggu frame PCM dari mikrofon…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                val quality = (snapshot.qualityScore * 100f).coerceIn(0f, 100f).toInt()
                val rms = String.format(Locale.US, "%.1f dBFS", snapshot.rmsDb)
                Text(
                    "Quality $quality% · RMS $rms · finite ${(snapshot.finiteSampleRatio * 100f).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (snapshot.waveform.size > 1) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .semantics {
                                contentDescription = "Waveform audio nyata, RMS ${snapshot.rmsDb} dBFS"
                            }
                    ) {
                        val center = size.height / 2f
                        val step = size.width / (snapshot.waveform.size - 1).toFloat()
                        snapshot.waveform.forEachIndexed { index, amplitude ->
                            val x = index * step
                            val halfHeight = amplitude.coerceIn(0f, 1f) * center
                            drawLine(
                                color = waveformColor,
                                start = Offset(x, center - halfHeight),
                                end = Offset(x, center + halfHeight),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                }
                Text(
                    when (snapshot.markerType) {
                        com.stressdetector.mobile.domain.engine.SessionMarkerType.LOW_AUDIO_QUALITY ->
                            "Kualitas audio rendah; tinjau rekaman sebelum interpretasi."
                        com.stressdetector.mobile.domain.engine.SessionMarkerType.ACOUSTIC_DIVERGENCE ->
                            "Deviasi terhadap baseline akustik; bukan kesimpulan klinis."
                        else -> "Audio baseline sedang dipantau secara lokal."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (snapshot.markerType == com.stressdetector.mobile.domain.engine.SessionMarkerType.LOW_AUDIO_QUALITY) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

private fun liveAvailabilityLabel(availability: PredictionAvailability): String = when (availability) {
    is PredictionAvailability.WarmingUp ->
        "Mengumpulkan baseline: ${availability.currentSamples}/${availability.requiredSamples} sample"
    is PredictionAvailability.Ready -> listOfNotNull(
        "Inference PPG tersedia",
        availability.prediction.researchDisclaimer
    ).joinToString(" — ")
    is PredictionAvailability.Rejected -> "Window ditolak: ${availability.reason}"
    is PredictionAvailability.Unavailable -> "Monitor tidak tersedia: ${availability.reason}"
    is PredictionAvailability.InferenceError -> "Inference gagal: ${availability.reason}"
}

@Composable
private fun LiveSectionHeader(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun EmptyLiveState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/** Keeps the outer session timeline stable while live transcript rows arrive. */
@Composable
private fun LiveTranscriptFeed(
    segments: List<com.stressdetector.mobile.domain.engine.SpeechSegment>,
    totalSegmentCount: Int,
    onCycleSpeaker: (String) -> Unit,
    onEditTranscript: (String, String) -> Unit,
    onSeekAudio: (Long) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (segments.isEmpty()) {
            EmptyLiveState("Belum ada transcript. Mulai rekaman untuk melihat hasilnya.")
        } else {
            if (totalSegmentCount > segments.size) {
                Text(
                    text = "Menampilkan ${segments.size} segmen terbaru dari $totalSegmentCount segmen tersimpan.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            segments.forEach { segment ->
                TranscriptBubble(
                    segment = segment,
                    onCycleSpeaker = onCycleSpeaker,
                    onEditTranscript = onEditTranscript,
                    onSeekAudio = onSeekAudio
                )
            }
        }
    }
}

@Composable
private fun TranscriptBubble(
    segment: com.stressdetector.mobile.domain.engine.SpeechSegment,
    onCycleSpeaker: (String) -> Unit,
    onEditTranscript: (String, String) -> Unit,
    onSeekAudio: (Long) -> Unit
) {
    var editing by remember(segment.id, segment.editedText, segment.rawText) { mutableStateOf(false) }
    var draft by remember(segment.id, segment.editedText, segment.rawText) {
        mutableStateOf(segment.displayText)
    }
    val tone = DefaultSpeakerBubbleToneResolver.resolve(segment.speakerId)
    val speakerLabel = segment.displaySpeakerLabel
    val colors = when (tone) {
        SpeakerBubbleTone.FIRST -> MaterialTheme.colorScheme.primaryContainer
        SpeakerBubbleTone.SECOND -> MaterialTheme.colorScheme.tertiaryContainer
    }
    PremiumCard(backgroundColor = colors.copy(alpha = 0.72f)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TextButton(
                onClick = { onCycleSpeaker(segment.id) },
                enabled = segment.isFinal,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.semantics {
                    contentDescription = if (segment.isFinal) {
                        "Speaker $speakerLabel; ketuk untuk mengganti label"
                    } else {
                        "Speaker belum tersedia untuk transcript sementara"
                    }
                }
            ) {
                Text(
                    text = speakerLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = "${segment.startOffsetMs / 1_000L}s · UTC ${segment.startTimestampUtc} · ${segment.language} · " +
                    if (segment.confidence > 0f) "quality ${(segment.confidence * 100).toInt()}%" else "quality unavailable",
                modifier = Modifier.semantics {
                    contentDescription =
                        "Waktu ${segment.startOffsetMs / 1_000L} detik, UTC ${segment.startTimestampUtc}, bahasa ${segment.language}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            TextButton(
                onClick = { onSeekAudio(segment.startOffsetMs.coerceAtLeast(0L)) },
                enabled = segment.startOffsetMs >= 0L,
                contentPadding = PaddingValues(0.dp)
            ) { Text("Putar dari sini") }
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Edit transcript") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        onEditTranscript(segment.id, draft)
                        editing = false
                    }) { Text("Simpan") }
                    TextButton(onClick = {
                        draft = segment.displayText
                        editing = false
                    }) { Text("Batal") }
                }
            } else {
                Text(
                    text = segment.displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (segment.isFinal) {
                    TextButton(
                        onClick = { editing = true },
                        contentPadding = PaddingValues(0.dp)
                    ) { Text("Edit teks") }
                }
            }
            if (!segment.isFinal) {
                Text(
                    "Transkrip sementara. Speaker sedang dianalisis.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DivergenceEventRow(event: com.stressdetector.mobile.data.db.DivergenceEventEntity, onSeekAudio: (Long) -> Unit) {
    val copyText = event.copyText.ifBlank { markerEvidenceCopy(event.markerType) }
    PremiumCard(
        modifier = Modifier.semantics {
            contentDescription = "${event.markerType} pada ${event.episodeStartElapsedMillis / 1_000L} detik; $copyText"
        },
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.4f),
        backgroundColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.15f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val startSeconds = event.episodeStartElapsedMillis / 1_000L
            val endSeconds = event.episodeEndElapsedMillis / 1_000L
            val title = when (event.markerType) {
                "BPM_TRAJECTORY" -> "Trajektori BPM"
                "BPM_RISE" -> "Trajektori BPM · kenaikan"
                "BPM_RECOVERY" -> "Trajektori BPM · pemulihan"
                "TEMPORAL_BASELINE_AROUSAL" -> "Arousal terhadap baseline temporal"
                "CHANNEL_AGREEMENT" -> "Perbedaan antar kanal PPG"
                "ACOUSTIC_DIVERGENCE" -> "Divergensi akustik"
                "LOW_AUDIO_QUALITY" -> "Indikator kualitas audio"
                "MODEL_AROUSAL", "STRESS_ELEVATED" -> "Arousal fisiologis berbasis model"
                "INCONSISTENCY_CUE" -> "Cue ketidaksesuaian"
                "AUXILIARY_EVENT" -> "Event tambahan"
                "MANUAL" -> "Marker manual"
                else -> "Marker fisiologis untuk ditinjau"
            }
            val channels = event.sourceChannels.ifBlank { event.channel }
            Text(
                "$title · $channels · ${startSeconds}s–${endSeconds}s",
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = { onSeekAudio(event.episodeStartElapsedMillis.coerceAtLeast(0L)) },
                contentPadding = PaddingValues(0.dp)
            ) { Text("Putar marker") }
            if (event.markerType == "ACOUSTIC_DIVERGENCE" || event.markerType == "LOW_AUDIO_QUALITY") {
                Text(
                    "RMS ${event.audioRmsDb?.let { "%.1f dBFS".format(it) } ?: "-"} · " +
                        "Quality ${(event.signalQuality * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
                if (event.markerType == "LOW_AUDIO_QUALITY") {
                    Text(
                        "Kualitas audio tidak memadai; ini bukan marker stres atau arousal.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            } else if (event.markerType == "MODEL_AROUSAL" ||
                event.markerType == "STRESS_ELEVATED" ||
                event.markerType == "TEMPORAL_BASELINE_AROUSAL"
            ) {
                Text(
                    "Signal Quality: ${(event.signalQuality * 100).toInt()}% · " +
                        "Probabilitas ${event.currentProbability?.let { "%.2f".format(it) } ?: "tidak tersedia"}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (event.markerType == "CHANNEL_AGREEMENT") {
                Text(
                    "Perbandingan model independen · " +
                        "probabilitas ${event.currentProbability?.let { "%.2f".format(it) } ?: "tidak tersedia"}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else if (event.markerType == "BPM_TRAJECTORY" ||
                event.markerType == "BPM_RISE" ||
                event.markerType == "BPM_RECOVERY"
            ) {
                Text(
                    "BPM ${event.bpm?.toInt() ?: "-"} (Puncak ${event.peakBpm?.toInt() ?: "-"}) · " +
                        "Baseline ${event.baselineBpm?.toInt() ?: "-"} · " +
                        "Quality: ${(event.signalQuality * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(copyText, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                if (event.markerType == "LOW_AUDIO_QUALITY") {
                    "Tinjau kualitas rekaman pada bagian ini sebelum melakukan interpretasi."
                } else {
                    copyText
                },
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun markerEvidenceCopy(markerType: String): String = when (markerType) {
    "BPM_TRAJECTORY", "BPM_RISE", "BPM_RECOVERY" ->
        "Perubahan trajektori BPM dari baseline preceding; bukti fisiologis untuk ditinjau."
    "TEMPORAL_BASELINE_AROUSAL", "MODEL_AROUSAL", "STRESS_ELEVATED" ->
        "Perubahan probabilitas model terhadap baseline temporal; bukan diagnosis."
    "CHANNEL_AGREEMENT" ->
        "Perbedaan antar kanal PPG independen; tidak ada fusi kanal."
    "ACOUSTIC_DIVERGENCE" ->
        "Perubahan energi suara terhadap baseline akustik; bukan indikator kebohongan."
    "LOW_AUDIO_QUALITY" ->
        "Kualitas audio rendah; tinjau rekaman sebelum interpretasi."
    "MANUAL" -> "Catatan manual pengguna untuk ditinjau bersama konteks sesi."
    else -> "Evidence $markerType tersedia untuk ditinjau bersama konteks sesi."
}

@Composable
private fun ManualAnnotationRow(annotation: DomainAnnotation, onSeekAudio: (Long) -> Unit, onDelete: (String) -> Unit) {
    PremiumCard {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "Marker manual · ${annotation.timestampMs / 1_000L}s",
                fontWeight = FontWeight.SemiBold
            )
            Text(annotation.text, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = { onSeekAudio(annotation.timestampMs.coerceAtLeast(0L)) },
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Putar marker") }
                TextButton(
                    onClick = { onDelete(annotation.id) },
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Hapus") }
            }
        }
    }
}

@Composable
private fun SessionPlaybackControls(
    state: PlaybackState,
    positionMillis: Long,
    durationMillis: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit
) {
    val canPlay = state == PlaybackState.Ready || state == PlaybackState.Paused || state == PlaybackState.Stopped
    val canPause = state == PlaybackState.Playing
    val canStop = state == PlaybackState.Playing || state == PlaybackState.Paused || state == PlaybackState.Ready
    val canSeek = durationMillis > 0L && state != PlaybackState.Idle && state !is PlaybackState.Error
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val safeDuration = durationMillis.coerceAtLeast(1L)
    val displayedPosition = if (isScrubbing) scrubPosition.toLong() else positionMillis
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPlay, enabled = canPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Putar audio")
            }
            IconButton(onClick = onPause, enabled = canPause) {
                Icon(Icons.Default.Pause, contentDescription = "Jeda audio")
            }
            IconButton(onClick = onStop, enabled = canStop) {
                Icon(Icons.Default.Stop, contentDescription = "Hentikan audio")
            }
            Text(playbackStatus(state), style = MaterialTheme.typography.labelMedium)
            if (state is PlaybackState.Error) {
                TextButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.size(4.dp))
                    Text("Coba lagi")
                }
            }
        }
        if (durationMillis > 0L) {
            Slider(
                value = displayedPosition.coerceIn(0L, safeDuration).toFloat(),
                onValueChange = {
                    isScrubbing = true
                    scrubPosition = it
                },
                onValueChangeFinished = {
                    if (isScrubbing) {
                        isScrubbing = false
                        onSeek(scrubPosition.toLong())
                    }
                },
                valueRange = 0f..safeDuration.toFloat(),
                enabled = canSeek,
                modifier = Modifier.fillMaxWidth()
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDurationMillis(displayedPosition.coerceIn(0L, safeDuration)), style = MaterialTheme.typography.labelSmall)
                Text(formatDurationMillis(durationMillis), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun playbackStatus(state: PlaybackState): String = when (state) {
    PlaybackState.Idle -> "Belum dimuat"
    PlaybackState.Preparing -> "Menyiapkan audio…"
    PlaybackState.Ready -> "Audio siap"
    PlaybackState.Playing -> "Sedang diputar"
    PlaybackState.Paused -> "Dijeda"
    PlaybackState.Stopped -> "Berhenti"
    is PlaybackState.Error -> "Audio tidak tersedia: ${state.message}"
}

private fun sessionCommandStatus(state: SessionCommandState): String = when (state) {
    SessionCommandState.Idle -> "Siap"
    SessionCommandState.Starting -> "Memulai sesi…"
    SessionCommandState.Recording -> "Merekam"
    SessionCommandState.Pausing -> "Menjeda…"
    SessionCommandState.Paused -> "Dijeda"
    SessionCommandState.Resuming -> "Melanjutkan…"
    SessionCommandState.Stopping -> "Menghentikan…"
    SessionCommandState.Finalizing -> "Menyimpan…"
    is SessionCommandState.Error -> "Gagal: ${state.message}"
}

private fun formatElapsed(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private fun formatBpm(value: Float): String = String.format(Locale.US, "%.1f BPM", value)

private fun finalizationStageLabel(stage: SessionFinalizationStage): String = when (stage) {
    SessionFinalizationStage.RECORDING -> "Merekam"
    SessionFinalizationStage.STOP_REQUESTED -> "Menghentikan sesi"
    SessionFinalizationStage.AUDIO_DRAINING -> "Menyelesaikan audio"
    SessionFinalizationStage.PPG_DRAINING -> "Menyelesaikan PPG"
    SessionFinalizationStage.SNAPSHOT_CAPTURED -> "Snapshot final tersimpan di memori"
    SessionFinalizationStage.PERSISTING -> "Menyimpan evidence sesi"
    SessionFinalizationStage.COMPLETED -> "Finalisasi selesai"
    SessionFinalizationStage.FAILED_WITH_EVIDENCE -> "Finalisasi gagal dengan evidence parsial"
}

private fun formatDuration(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private fun formatDurationMillis(millis: Long): String = formatDuration(millis.coerceAtLeast(0L) / 1_000L)

private fun formatSessionTime(timestamp: Long): String = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID")).format(Date(timestamp))
