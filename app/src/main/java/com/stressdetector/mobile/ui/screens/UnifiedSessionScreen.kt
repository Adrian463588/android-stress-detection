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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Switch
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
import com.stressdetector.mobile.ui.LocalAppMotion
import com.stressdetector.mobile.ui.PremiumCard
import com.stressdetector.mobile.ui.viewmodels.SessionCommandState
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionEffect
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionUiState
import com.stressdetector.mobile.ui.viewmodels.UnifiedSessionViewModel
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerCounts
import com.stressdetector.mobile.ui.viewmodels.VerbatimMarkerFilter
import com.stressdetector.mobile.ui.viewmodels.VerbatimWorkspace
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
    ) { uri -> viewModel.exportLastSession(uri) }
    val textExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri -> viewModel.exportTranscript(uri) }
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
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    var showSessionNameDialog by rememberSaveable { mutableStateOf(false) }
    var sessionName by rememberSaveable { mutableStateOf("Sesi Verbatim") }
    var monitoringEnabled by rememberSaveable { mutableStateOf(false) }
    var consentConfirmedForPendingStart by rememberSaveable { mutableStateOf(false) }
    var deleteTargetSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var showDeleteAllDialog by rememberSaveable { mutableStateOf(false) }
    var showManualAnnotationDialog by rememberSaveable { mutableStateOf(false) }
    var manualAnnotationText by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                microphoneDenied = false
            }
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
        if (audioGranted && consentConfirmedForPendingStart) {
            microphoneDenied = false
            consentConfirmedForPendingStart = false
            viewModel.startSession(sessionName, monitoringEnabled)
        } else {
            microphoneDenied = needsMicrophone && !audioGranted
            consentConfirmedForPendingStart = false
            Toast.makeText(
                context,
                if (needsMicrophone) {
                    "Izin mikrofon diperlukan untuk merekam sesi."
                } else {
                    "Sesi import tidak memerlukan izin notifikasi."
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun requestStart() {
        val needsMicrophone = pendingAudioUri == null
        val audioPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
        if (!needsMicrophone || audioPermission == PackageManager.PERMISSION_GRANTED) {
            microphoneDenied = false
            consentConfirmedForPendingStart = false
            viewModel.startSession(sessionName, monitoringEnabled)
        } else {
            val permissions = buildList {
                if (needsMicrophone) add(Manifest.permission.RECORD_AUDIO)
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    if (showConsentDialog) {
        AlertDialog(
            onDismissRequest = { showConsentDialog = false },
            title = { Text("Persetujuan pasien") },
            text = {
                Text(
                    "Pastikan semua pembicara menyetujui perekaman dan analisis lokal." +
                        if (monitoringEnabled &&
                            pendingAudioUri == null
                        ) {
                            " Sesi ini juga memakai PPG Galaxy Watch dengan persetujuan pemakainya."
                        } else {
                            " PPG tidak digunakan."
                        }
                )
            },
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
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = sessionName,
                        onValueChange = { sessionName = it.take(80) },
                        label = { Text("Nama sesi") },
                        singleLine = true
                    )
                    Text("Bahasa sesi", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.setSessionLanguage("id") }, modifier = Modifier.weight(1f)) {
                            Text(if (uiState.selectedLanguage == "id") "✓ Indonesia" else "Indonesia")
                        }
                        TextButton(onClick = { viewModel.setSessionLanguage("en") }, modifier = Modifier.weight(1f)) {
                            Text(if (uiState.selectedLanguage == "en") "✓ English" else "English")
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("PPG Galaxy Watch (opsional)", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = monitoringEnabled && pendingAudioUri == null,
                            onCheckedChange = { monitoringEnabled = it },
                            enabled = pendingAudioUri == null,
                            modifier = Modifier.semantics { contentDescription = "Aktifkan analisis PPG Galaxy Watch untuk rekaman baru" }
                        )
                    }
                    Text(
                        if (pendingAudioUri != null) {
                            "Audio impor dianalisis tanpa PPG live."
                        } else {
                            "Audio tetap berfungsi tanpa Watch. PPG memerlukan sinyal berkualitas dan model ONNX siap; bukan diagnosis."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
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
                onOpenDrawer = ::openDrawer,
                onStartOrStop = {
                    when {
                        VerbatimWorkspace(uiState).canStop -> viewModel.stopSession()
                        uiState.canStartNewSession -> {
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
                onExport = { viewModel.prepareZipExport()?.let(exportLauncher::launch) },
                onExportText = { viewModel.prepareTranscriptExport()?.let(textExportLauncher::launch) },
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
                onDeleteManualAnnotation = viewModel::deleteManualAnnotation,
                microphoneDenied = microphoneDenied,
                onOpenMicrophoneSettings = {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${context.packageName}")
                        )
                    )
                }
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
                TextButton(enabled = VerbatimWorkspace(uiState).canManageStoredSessions, onClick = {
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
                TextButton(enabled = VerbatimWorkspace(uiState).canManageStoredSessions, onClick = {
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
        Text(label)
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
    val sessionBusy = !VerbatimWorkspace(uiState).canManageStoredSessions
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SessionContent(
    uiState: UnifiedSessionUiState,
    modifier: Modifier,
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
    onExportText: () -> Unit,
    onDelete: () -> Unit,
    onOpenProfile: () -> Unit,
    onRefreshReadiness: () -> Unit,
    onCycleSpeaker: (String) -> Unit,
    onEditTranscript: (String, String) -> Unit,
    onRequestAddManualAnnotation: () -> Unit,
    onDeleteManualAnnotation: (String) -> Unit,
    microphoneDenied: Boolean = false,
    onOpenMicrophoneSettings: () -> Unit = {}
) {
    val workspace = remember(uiState) { VerbatimWorkspace(uiState) }
    val counts = remember(uiState) { VerbatimMarkerCounts(uiState) }
    var panel by rememberSaveable(uiState.selectedSessionId) { mutableStateOf("TRANSCRIPT") }
    var filter by rememberSaveable(uiState.selectedSessionId) { mutableStateOf(VerbatimMarkerFilter.ALL) }
    var technicalExpanded by rememberSaveable(uiState.selectedSessionId) { mutableStateOf(false) }
    val transcriptItems = remember(uiState.transcriptSegments, workspace.capturing) {
        val ordered = uiState.transcriptSegments.sortedWith(compareBy({ it.startOffsetMs }, { it.id })).distinctBy { it.id }
        if (workspace.capturing) ordered.takeLast(LIVE_TRANSCRIPT_TAIL_LIMIT) else ordered
    }
    val markerItems = remember(uiState.divergenceEvents, filter) {
        uiState.divergenceEvents.distinctBy { it.eventId }.filter(filter::includes)
            .sortedWith(compareBy({ it.episodeStartElapsedMillis }, { it.eventId }))
    }
    val cueItems = if (filter == VerbatimMarkerFilter.ALL || filter == VerbatimMarkerFilter.TEXT) uiState.reviewCues else emptyList()
    val annotationItems = remember(uiState.annotations, filter) {
        if (filter == VerbatimMarkerFilter.ALL || filter == VerbatimMarkerFilter.MANUAL) {
            uiState.annotations.distinctBy { it.id }.sortedWith(compareBy({ it.timestampMs }, { it.id }))
        } else {
            emptyList()
        }
    }
    val transcriptScroll = rememberLazyListState()
    val markerScroll = rememberLazyListState()
    LaunchedEffect(uiState.selectedSessionId) {
        transcriptScroll.scrollToItem(0)
        markerScroll.scrollToItem(0)
    }
    Column(modifier.fillMaxSize().imePadding()) {
        LazyColumn(
            state = if (panel == "TRANSCRIPT") transcriptScroll else markerScroll,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = AppSpacing.Screen),
            contentPadding = PaddingValues(top = AppSpacing.Small, bottom = AppSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)
        ) {
            item("header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onOpenDrawer) { Icon(Icons.Default.Menu, contentDescription = "Buka daftar sesi") }
                    Column(Modifier.weight(1f)) {
                        Text("Verbatim+", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (workspace.capturing) sessionCommandStatus(uiState.sessionCommandState) else "Transkrip dan bukti wawancara",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            }
                        )
                    }
                }
            }
            if (uiState.storageState.needsVerbatimStorageRecovery() || uiState.speechRuntimeState !is SpeechRuntimeState.Ready) {
                item("readiness") { VerbatimReadinessBanner(uiState, onOpenProfile, onRefreshReadiness) }
            }
            if (microphoneDenied) {
                item("microphone-permission") {
                    Text(
                        "Izin mikrofon belum diberikan. Impor WAV dan review arsip tetap tersedia.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(onClick = onOpenMicrophoneSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Buka setelan izin mikrofon")
                    }
                }
            }
            if (workspace.capturing) {
                item("live-status") {
                    Text(formatElapsed(uiState.elapsedSeconds), style = MaterialTheme.typography.headlineSmall)
                    if (uiState.speechProcessingState.isDraining || uiState.speechProcessingState.backlogFrames > 0) {
                        Text(
                            "Transkripsi sedang diproses · ${uiState.speechProcessingState.backlogFrames} frame antrean",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item("live-pitch") {
                    VerbatimPitchPanel(uiState.livePitch, uiState.isPaused)
                }
            }
            uiState.selectedSession?.summary?.let { session ->
                item("archive") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                        Text(session.sessionName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${formatSessionTime(session.startedAtMillis)} · ${formatDuration(session.durationSeconds)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("${session.drawerStatusLabel()} · ${session.drawerStorageLabel()}", style = MaterialTheme.typography.bodySmall)
                        if (workspace.archiveReady) {
                            SessionPlaybackControls(
                                uiState.playbackState,
                                uiState.playbackPositionMillis,
                                uiState.playbackDurationMillis,
                                onPlay,
                                onPausePlayback,
                                onStopPlayback,
                                onSeekAudio,
                                onRetryPlayback,
                                enabled = workspace.canPrepareAudio
                            )
                            VerbatimArchiveActions(workspace, onExportText, onExport, onDelete)
                        }
                    }
                }
            }
            if (uiState.isSavingTranscript || uiState.isExporting || uiState.isDeletingSession) {
                item("saving") {
                    Text(
                        when {
                            uiState.isExporting -> "Mengekspor sesi…"
                            uiState.isDeletingSession -> "Menghapus sesi…"
                            else -> "Menyimpan koreksi…"
                        },
                        modifier = Modifier.semantics {
                            liveRegion =
                                LiveRegionMode.Polite
                        }
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (uiState.sessionCommandState is SessionCommandState.Error && uiState.finalizationEvidencePersisted != null) {
                item("partial") {
                    Text(
                        if (uiState.finalizationEvidencePersisted == true) {
                            "Sesi selesai sebagian; bukti yang tersedia sudah disimpan."
                        } else {
                            "Finalisasi gagal sebelum bukti dapat disimpan."
                        },
                        color = MaterialTheme.colorScheme.error
                    )
                    uiState.finalizationWarnings.distinct().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            uiState.error?.let { message ->
                item("error") {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
            if (uiState.selectedSessionId != null && !workspace.capturing && uiState.selectedSession == null) {
                item("loading") {
                    Text("Memuat sesi terpilih…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (workspace.capturing || uiState.selectedSessionId != null) {
                item("tabs") {
                    TabRow(selectedTabIndex = if (panel == "TRANSCRIPT") 0 else 1) {
                        Tab(
                            selected = panel == "TRANSCRIPT",
                            onClick = { panel = "TRANSCRIPT" },
                            modifier = Modifier.heightIn(min = 48.dp),
                            text = { Text("Transkrip") }
                        )
                        Tab(
                            selected = panel == "MARKERS",
                            onClick = { panel = "MARKERS" },
                            modifier = Modifier.heightIn(min = 48.dp),
                            text = {
                                Text("Marker · ${counts.count(VerbatimMarkerFilter.ALL)}")
                            }
                        )
                    }
                }
                item("technical") {
                    VerbatimDisclosure("Detail teknis sesi", technicalExpanded, { technicalExpanded = it }) {
                        if (workspace.capturing) {
                            uiState.speechProcessingState.decoderDescription?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text(uiState.watchStatus, style = MaterialTheme.typography.bodySmall)
                        Text(uiState.monitorStatus, style = MaterialTheme.typography.bodySmall)
                        uiState.lastMonitorSummary?.takeIf { !workspace.capturing }?.let { summary ->
                            Text(
                                "Window diterima ${summary.acceptedWindowCount} · ditolak ${summary.rejectedWindowCount}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        LiveBiosignalCard(uiState.liveBiosignal)
                        LiveAudioCard(uiState.acousticAnalysis)
                    }
                }
                if (panel == "TRANSCRIPT") {
                    item("explanation") {
                        Text(
                            if (workspace.capturing) {
                                "Teks sementara dapat berubah. Cue muncul pada ujaran final; prosodi menunggu baseline."
                            } else {
                                "Sorotan menunjukkan ekspresi leksikal. Buka cue atau detail ujaran untuk memeriksa buktinya."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (workspace.capturing && transcriptItems.isNotEmpty()) {
                        item("latest-utterance") {
                            val scope = rememberCoroutineScope()
                            val motion = LocalAppMotion.current
                            TextButton(onClick = {
                                scope.launch {
                                    val last = (transcriptScroll.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)
                                    if (motion.enabled) transcriptScroll.animateScrollToItem(last) else transcriptScroll.scrollToItem(last)
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Ke ujaran terbaru") }
                        }
                    }
                    if (transcriptItems.isEmpty()) {
                        item("empty") {
                            EmptyLiveState("Belum ada ujaran yang dapat ditampilkan. Tidak ada transkrip pengganti.")
                        }
                    }
                    if (uiState.transcriptSegments.size > transcriptItems.size) {
                        item("tail") {
                            Text(
                                "Live menampilkan ${transcriptItems.size} ujaran terbaru; seluruh ujaran tersedia setelah disimpan.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    items(transcriptItems, key = { "transcript:${it.id}" }, contentType = { "utterance" }) { segment ->
                        VerbatimTranscriptCard(
                            segment,
                            uiState.cueAnalyses.getValue(segment.id),
                            workspace.canReviewAudio,
                            workspace.canEditTranscript,
                            onCycleSpeaker,
                            onEditTranscript,
                            onPlayFromMarker
                        )
                    }
                } else {
                    item("filters") { VerbatimMarkerFilters(counts, filter, { filter = it }) }
                    item("marker-explanation") {
                        Text(
                            "Marker adalah bukti untuk ditinjau bersama konteks, bukan diagnosis atau bukti desepsi.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(
                            onClick = onRequestAddManualAnnotation,
                            enabled = workspace.canAnnotate,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) { Text("Tambah catatan") }
                    }
                    if (markerItems.isEmpty() && cueItems.isEmpty() && annotationItems.isEmpty()) {
                        item("marker-empty") {
                            EmptyLiveState(
                                if (filter ==
                                    VerbatimMarkerFilter.ALL
                                ) {
                                    "Belum ada marker. Ini tidak memastikan tidak adanya keraguan atau stres."
                                } else {
                                    "Belum ada marker ${filter.label.lowercase()}. Pilih Semua untuk melihat bukti lain."
                                }
                            )
                        }
                    }
                    items(markerItems, key = { "event:${it.eventId}" }, contentType = { "event" }) { event ->
                        VerbatimMarkerCard(
                            event,
                            uiState.markerPlaybackOffsets[event.eventId]?.takeIf {
                                workspace.canReviewAudio
                            },
                            onPlayFromMarker
                        )
                    }
                    items(cueItems, key = {
                        "cue:${it.id}"
                    }, contentType = { "cue" }) { cue -> VerbatimCueButton(cue, onPlayFromMarker, workspace.canReviewAudio) }
                    items(annotationItems, key = { "note:${it.id}" }, contentType = { "note" }) { annotation ->
                        ManualAnnotationRow(
                            annotation,
                            onPlayFromMarker,
                            onDeleteManualAnnotation,
                            workspace.canReviewAudio,
                            workspace.canAnnotate
                        )
                    }
                }
            } else {
                item("welcome") {
                    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Small)) {
                        Text("Rekam percakapan, tinjau cue dan unduh transkrip.", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Pilih Indonesia atau Inggris saat mulai. PPG Galaxy Watch bersifat opsional; proses inti tetap lokal.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.canStartNewSession) {
                            VerbatimPrimaryAction(
                                "Mulai rekaman",
                                "Mulai rekaman Verbatim+",
                                true,
                                Modifier.fillMaxWidth(),
                                onStartOrStop
                            )
                        }
                        TextButton(onClick = onOpenDrawer, modifier = Modifier.heightIn(min = 48.dp)) { Text("Buka sesi atau impor audio") }
                    }
                }
            }
        }
        if (workspace.capturing || uiState.isFinalizing) {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Screen)) {
                    VerbatimCaptureControls(
                        workspace,
                        if (uiState.isFinalizing) {
                            finalizationStageLabel(
                                uiState.finalizationStage
                            )
                        } else {
                            sessionCommandStatus(uiState.sessionCommandState)
                        },
                        onStartOrStop,
                        onPauseOrResume,
                        Modifier.fillMaxWidth()
                    )
                }
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
                    if (monitor.isMonitoring) "Aktif" else "Tidak aktif",
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
                    if (monitor.isMonitoring) "Menunggu data PPG" else "Pemantauan PPG tidak aktif",
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
private fun EmptyLiveState(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ManualAnnotationRow(
    annotation: DomainAnnotation,
    onSeekAudio: (Long) -> Unit,
    onDelete: (String) -> Unit,
    canPlay: Boolean,
    canEdit: Boolean
) {
    PremiumCard {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "Marker manual · ${annotation.timestampMs / 1_000L}s",
                fontWeight = FontWeight.SemiBold
            )
            Text(annotation.text, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
                TextButton(
                    onClick = { onSeekAudio(annotation.timestampMs.coerceAtLeast(0L)) },
                    enabled = canPlay,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Putar marker") }
                TextButton(
                    onClick = { onDelete(annotation.id) },
                    enabled = canEdit,
                    modifier = Modifier.heightIn(min = 48.dp),
                    contentPadding = PaddingValues(0.dp)
                ) { Text("Hapus") }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SessionPlaybackControls(
    state: PlaybackState,
    positionMillis: Long,
    durationMillis: Long,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    enabled: Boolean = true
) {
    val canPlay = enabled && (state == PlaybackState.Ready || state == PlaybackState.Paused || state == PlaybackState.Stopped)
    val canPause = enabled && state == PlaybackState.Playing
    val canStop = enabled && (state == PlaybackState.Playing || state == PlaybackState.Paused || state == PlaybackState.Ready)
    val canSeek =
        enabled && durationMillis > 0L && state != PlaybackState.Idle && state != PlaybackState.Preparing && state !is PlaybackState.Error
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableFloatStateOf(0f) }
    val safeDuration = durationMillis.coerceAtLeast(1L)
    val displayedPosition = if (isScrubbing) scrubPosition.toLong() else positionMillis
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            playbackStatus(state),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)) {
            IconButton(onClick = onPlay, enabled = canPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Putar audio")
            }
            IconButton(onClick = onPause, enabled = canPause) {
                Icon(Icons.Default.Pause, contentDescription = "Jeda audio")
            }
            IconButton(onClick = onStop, enabled = canStop) {
                Icon(Icons.Default.Stop, contentDescription = "Hentikan audio")
            }
            if (state is PlaybackState.Error) {
                TextButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
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
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Posisi audio rekaman" }
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
