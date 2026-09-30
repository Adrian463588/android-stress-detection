package com.stressdetector.mobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import com.stressdetector.app.navigation.AppNavGraph
import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.mobile.domain.CalendarDayItem
import com.stressdetector.mobile.domain.PredictionAvailability
import com.stressdetector.mobile.domain.history.HistoryResultStatus
import com.stressdetector.mobile.domain.history.HistoryStatusMapper
import com.stressdetector.mobile.navigation.BackNavigationAction
import com.stressdetector.mobile.navigation.BackNavigationPolicy
import com.stressdetector.mobile.ui.APP_DESTINATIONS
import com.stressdetector.mobile.ui.AppPalette
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.DualPpgChartSection
import com.stressdetector.mobile.ui.LocalAppMotion
import com.stressdetector.mobile.ui.MetricRow
import com.stressdetector.mobile.ui.MonitoringScreen
import com.stressdetector.mobile.ui.PremiumCard
import com.stressdetector.mobile.ui.Screen
import com.stressdetector.mobile.ui.StatusDot
import com.stressdetector.mobile.ui.StressDetectorTheme
import com.stressdetector.mobile.ui.home.HomeEvent
import com.stressdetector.mobile.ui.home.HomeSessionStatusCard
import com.stressdetector.mobile.ui.home.HomeUiState
import com.stressdetector.mobile.ui.parentDestinationFor
import com.stressdetector.mobile.ui.screens.AiCounselorScreen
import com.stressdetector.mobile.ui.screens.CounselorNavigation
import com.stressdetector.mobile.ui.screens.CounselorPersonaScreen
import com.stressdetector.mobile.ui.screens.DocumentLibraryScreen
import com.stressdetector.mobile.ui.screens.HistoryDetailScreen
import com.stressdetector.mobile.ui.screens.ProfileScreen
import com.stressdetector.mobile.ui.viewmodels.AiReminderViewModel
import com.stressdetector.mobile.ui.viewmodels.CounselorPersonaViewModel
import com.stressdetector.mobile.ui.viewmodels.CounselorViewModel
import com.stressdetector.mobile.ui.viewmodels.DocumentLibraryViewModel
import com.stressdetector.mobile.ui.viewmodels.HistoryDetailViewModel
import com.stressdetector.mobile.ui.viewmodels.ProfileViewModel
import com.stressdetector.mobile.ui.viewmodels.SessionCommandState
import com.stressdetector.shared.PermissionStatus
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
// ─── Semantic compatibility aliases ─────────────────────────────────────────
// The palette lives in AppTheme.kt; these aliases keep legacy screen helpers readable.
private val NeonEmerald = AppPalette.Primary
private val OceanBlue = AppPalette.Secondary
private val CharcoalSlate = AppPalette.Surface
private val TextPrimary = AppPalette.TextPrimary
private val TextSecondary = AppPalette.TextSecondary
private val AlertRed = AppPalette.Error
private val WarnAmber = AppPalette.Warning
private const val STRESS_MODEL_WINDOW_SECONDS = 180

// ─── Activity ─────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    private val reminderIntentId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    @Suppress("LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleReminderIntent(intent)

        setContent {
            val windowSize = calculateWindowSizeClass(this)
            val windowWidthSizeClass = windowSize.widthSizeClass
            StressDetectorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: StressViewModel = viewModel(factory = StressViewModel.Factory)
                    val unifiedSessionViewModel: com.stressdetector.mobile.ui.viewmodels.UnifiedSessionViewModel =
                        viewModel(factory = com.stressdetector.mobile.ui.viewmodels.UnifiedSessionViewModel.Factory(this))
                    val counselorViewModel: CounselorViewModel = viewModel(factory = CounselorViewModel.Factory(this))
                    val profileViewModel: ProfileViewModel = viewModel(factory = ProfileViewModel.Factory(this))
                    var notificationPermissionGranted by remember { mutableStateOf(currentNotificationPermissionGranted()) }
                    val aiReminderViewModel: AiReminderViewModel = viewModel(
                        factory = AiReminderViewModel.factory(this, notificationPermissionGranted)
                    )
                    val documentLibraryViewModel: DocumentLibraryViewModel = viewModel(factory = DocumentLibraryViewModel.Factory(this))
                    val counselorPersonaViewModel: CounselorPersonaViewModel = viewModel(factory = CounselorPersonaViewModel.Factory(this))
                    val historyDetailViewModel: HistoryDetailViewModel = viewModel(factory = HistoryDetailViewModel.Factory)

                    var permissionStatus by remember { mutableStateOf(currentPermissionStatus()) }
                    var pendingReminderEnable by remember { mutableStateOf(false) }
                    val incomingReminderId by reminderIntentId.collectAsState()

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissionStatus = currentPermissionStatus() }

                    val settingsLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.StartActivityForResult()
                    ) {
                        permissionStatus = currentPermissionStatus()
                        notificationPermissionGranted = currentNotificationPermissionGranted()
                        aiReminderViewModel.setNotificationPermissionGranted(notificationPermissionGranted)
                        if (pendingReminderEnable) {
                            if (notificationPermissionGranted) aiReminderViewModel.setEnabled(true)
                            pendingReminderEnable = false
                        }
                    }

                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { _ ->
                        notificationPermissionGranted = currentNotificationPermissionGranted()
                        aiReminderViewModel.setNotificationPermissionGranted(notificationPermissionGranted)
                        if (pendingReminderEnable && notificationPermissionGranted) {
                            pendingReminderEnable = false
                            aiReminderViewModel.setEnabled(true)
                        } else {
                            pendingReminderEnable = false
                        }
                    }

                    LaunchedEffect(permissionStatus.allGranted) {
                        viewModel.setPermissionsGranted(permissionStatus.allGranted)
                    }

                    StressDetectorApp(
                        viewModel = viewModel,
                        unifiedSessionViewModel = unifiedSessionViewModel,
                        counselorViewModel = counselorViewModel,
                        profileViewModel = profileViewModel,
                        aiReminderViewModel = aiReminderViewModel,
                        documentLibraryViewModel = documentLibraryViewModel,
                        counselorPersonaViewModel = counselorPersonaViewModel,
                        historyDetailViewModel = historyDetailViewModel,
                        permissionStatus = permissionStatus,
                        windowSizeClass = windowWidthSizeClass,
                        onGrantPermissions = {
                            val missing = missingMobilePermissions()
                            if (missing.isEmpty()) {
                                permissionStatus = currentPermissionStatus()
                            } else {
                                permissionLauncher.launch(missing.toTypedArray())
                            }
                        },
                        onOpenSettings = {
                            settingsLauncher.launch(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null)
                                )
                            )
                        },
                        onRequestNotificationPermission = {
                            pendingReminderEnable = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                settingsLauncher.launch(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                    }
                                )
                            }
                        },
                        reminderIntentId = incomingReminderId,
                        onReminderIntentConsumed = { reminderIntentId.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleReminderIntent(intent)
    }

    private fun handleReminderIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_AI_PLUS) {
            reminderIntentId.value = intent.getStringExtra(EXTRA_REMINDER_ID)?.takeIf(String::isNotBlank)
        }
    }

    private fun currentPermissionStatus(): PermissionStatus {
        val missing = missingMobilePermissions()
        return PermissionStatus(
            allGranted = missing.isEmpty(),
            missingPermissions = missing,
            shouldShowRationale = missing.any { shouldShowRequestPermissionRationale(it) }
        )
    }

    private fun missingMobilePermissions(): List<String> = requiredMobilePermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun currentNotificationPermissionGranted(): Boolean = (
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) &&
        NotificationManagerCompat.from(this).areNotificationsEnabled()

    companion object {
        const val ACTION_OPEN_AI_PLUS = "com.stressdetector.mobile.action.OPEN_AI_PLUS"
        const val EXTRA_REMINDER_ID = "com.stressdetector.mobile.extra.REMINDER_ID"
    }
}

private fun requiredMobilePermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    permissions.add(Manifest.permission.RECORD_AUDIO)

    if (Build.VERSION.SDK_INT >= 31) {
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    return permissions.toTypedArray()
}

// ─── Root App Scaffold ────────────────────────────────────────────────────────

@Composable
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
private fun StressDetectorApp(
    viewModel: StressViewModel,
    unifiedSessionViewModel: com.stressdetector.mobile.ui.viewmodels.UnifiedSessionViewModel,
    counselorViewModel: CounselorViewModel,
    profileViewModel: ProfileViewModel,
    aiReminderViewModel: AiReminderViewModel,
    documentLibraryViewModel: DocumentLibraryViewModel,
    counselorPersonaViewModel: CounselorPersonaViewModel,
    historyDetailViewModel: HistoryDetailViewModel,
    permissionStatus: PermissionStatus,
    windowSizeClass: WindowWidthSizeClass,
    onGrantPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    reminderIntentId: String?,
    onReminderIntentConsumed: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val verbatimState by unifiedSessionViewModel.uiState.collectAsState()
    val navController = androidx.navigation.compose.rememberNavController()
    var showVerbatimExitDialog by remember { mutableStateOf(false) }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val selectedDestination = parentDestinationFor(currentRoute)
    val lifecycleOwner = LocalLifecycleOwner.current
    val verbatimCommandBusy = verbatimState.sessionCommandState !is SessionCommandState.Idle &&
        verbatimState.sessionCommandState !is SessionCommandState.Error
    val verbatimBusy = verbatimState.isSessionActive ||
        verbatimState.isFinalizing ||
        verbatimCommandBusy

    fun navigateToRoot(destination: Screen) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.startDestinationId) { saveState = false }
            launchSingleTop = true
            restoreState = false
        }
    }

    fun navigateHome() = navigateToRoot(Screen.Home)

    LaunchedEffect(currentRoute, permissionStatus.allGranted) {
        if (currentRoute == Screen.Home.route && permissionStatus.allGranted) {
            viewModel.onHomeResumed()
        }
    }

    DisposableEffect(lifecycleOwner, currentRoute, permissionStatus.allGranted) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME &&
                currentRoute == Screen.Home.route &&
                permissionStatus.allGranted
            ) {
                viewModel.onHomeResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(reminderIntentId) {
        reminderIntentId?.takeIf(String::isNotBlank)?.let { id ->
            aiReminderViewModel.markOpened(id)
            navigateToRoot(Screen.Chat)
            onReminderIntentConsumed()
        }
    }

    val verbatimRouteBackEnabled = currentRoute == Screen.Verbatim.route && verbatimBusy
    val backEnabled = state.isRecording || currentRoute != Screen.Home.route || verbatimRouteBackEnabled
    BackHandler(enabled = backEnabled) {
        if (currentRoute == Screen.Verbatim.route && verbatimBusy) {
            showVerbatimExitDialog = true
            return@BackHandler
        }
        when (BackNavigationPolicy.decide(currentRoute, documentLibraryVisible = false, state.isRecording)) {
            BackNavigationAction.DISMISS_OVERLAY -> Unit
            BackNavigationAction.POP_NESTED -> navController.popBackStack()
            BackNavigationAction.GO_HOME -> navigateHome()
            BackNavigationAction.STAY -> Unit
        }
    }

    if (showVerbatimExitDialog) {
        AlertDialog(
            onDismissRequest = { showVerbatimExitDialog = false },
            title = { Text("Sesi Verbatim+ masih aktif") },
            text = { Text("Pilih Stop dan simpan untuk menutup service dengan aman, atau batal untuk tetap di sesi.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showVerbatimExitDialog = false
                        unifiedSessionViewModel.stopSession()
                    },
                    enabled = verbatimState.isSessionActive
                ) { Text("Stop dan simpan") }
            },
            dismissButton = {
                TextButton(onClick = { showVerbatimExitDialog = false }) { Text("Batal") }
            }
        )
    }

    val motion = LocalAppMotion.current
    @OptIn(ExperimentalAnimationApi::class)
    @Suppress("DEPRECATION")
    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = state.isRecording,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                fadeIn(tween(motion.duration(300))) with fadeOut(tween(motion.duration(200)))
            },
            label = "monitoringNav"
        ) { isRecording ->
            if (isRecording) {
                MonitoringScreen(
                    progress = state.progress,
                    elapsedMillis = state.elapsedMillis,
                    greenSamples = state.greenSamples,
                    irSamples = state.irSamples,
                    redDiagnosticSamples = state.redDiagnosticSamples,
                    greenChart = state.greenChart,
                    irChart = state.irChart,
                    redChart = state.redChart,
                    connectionStatus = state.connectionState.status,
                    connectionPhase = state.connectionState.reachabilityPhase.name,
                    phoneMessage = state.connectionState.message,
                    terminalReceiptReceived = state.terminalReceiptReceived,
                    transportComplete = state.transportComplete,
                    transportIncomplete = state.transportIncomplete,
                    transportFailureReason = state.transportFailureReason,
                    observedDataElapsedMillis = state.observedDataElapsedMillis,
                    appliedBatchCount = state.appliedBatchCount,
                    duplicateBatchCount = state.duplicateBatchCount,
                    outOfOrderBatchCount = state.outOfOrderBatchCount,
                    missingSequenceCount = state.missingSequenceCount,
                    highestGreenSequence = state.highestGreenSequence,
                    highestIrSequence = state.highestIrSequence,
                    highestRedDiagnosticSequence = state.highestRedDiagnosticSequence,
                    modelStatus = modelStatusFor(state),
                    debugLivePpgCaptureAvailable = BuildConfig.DEBUG,
                    debugLivePpgCaptureEnabled = state.debugLivePpgCaptureEnabled,
                    twoColumnLayout = windowSizeClass != WindowWidthSizeClass.Compact,
                    onDebugLivePpgCaptureEnabledChange = viewModel::setDebugLivePpgCaptureEnabled,
                    onStop = viewModel::stopWatchTracking
                )
            } else {
                Scaffold(
                    bottomBar = {
                        if (windowSizeClass == WindowWidthSizeClass.Compact) {
                            AppBottomNav(
                                selectedDestination = selectedDestination,
                                onSelect = ::navigateToRoot
                            )
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        if (windowSizeClass != WindowWidthSizeClass.Compact) {
                            AppNavigationRail(
                                selectedDestination = selectedDestination,
                                onSelect = ::navigateToRoot
                            )
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .widthIn(max = AppSpacing.ReadableContent)
                            ) {
                                AppNavGraph(
                                    navController = navController,
                                    innerPadding = PaddingValues(),
                                    homeScreen = {
                                        sessionTab(
                                            viewModel = viewModel,
                                            config = SessionUi(
                                                state = state,
                                                permissionStatus = permissionStatus,
                                                windowSizeClass = windowSizeClass
                                            ),
                                            permissionActions = PermissionActions(
                                                onGrant = onGrantPermissions,
                                                onOpenSettings = onOpenSettings
                                            ),
                                            onNavigateToProfile = { navigateToRoot(Screen.Profile) }
                                        )
                                    },
                                    historyScreen = {
                                        reportTab(
                                            viewModel = viewModel,
                                            windowSizeClass = windowSizeClass,
                                            onSessionSelected = { sessionId ->
                                                navController.navigate(Screen.HistoryDetail.createRoute(sessionId)) {
                                                    launchSingleTop = true
                                                }
                                            }
                                        )
                                    },
                                    historyDetailScreen = { sessionId ->
                                        HistoryDetailScreen(
                                            sessionId = sessionId,
                                            viewModel = historyDetailViewModel,
                                            onBack = { navController.popBackStack() }
                                        )
                                    },
                                    verbatimScreen = {
                                        com.stressdetector.mobile.ui.screens.UnifiedSessionScreen(
                                            viewModel = unifiedSessionViewModel,
                                            windowWidthSizeClass = windowSizeClass,
                                            onNavigateToProfile = { navigateToRoot(Screen.Profile) }
                                        )
                                    },
                                    counselorScreen = {
                                        AiCounselorScreen(
                                            viewModel = counselorViewModel,
                                            aiReminderViewModel = aiReminderViewModel,
                                            navigation = CounselorNavigation(
                                                windowSizeClass = windowSizeClass,
                                                onNavigateToLibrary = {
                                                    navController.navigate(Screen.DocumentLibrary.route) {
                                                        launchSingleTop = true
                                                    }
                                                },
                                                onNavigateToProfile = { navigateToRoot(Screen.Profile) },
                                                onRequestNotificationPermission = onRequestNotificationPermission
                                            )
                                        )
                                    },
                                    counselorPersonaScreen = {
                                        CounselorPersonaScreen(
                                            viewModel = counselorPersonaViewModel,
                                            onBack = { navController.popBackStack() },
                                            onConnectStorage = {
                                                navController.navigate(Screen.Profile.route) {
                                                    launchSingleTop = true
                                                }
                                            }
                                        )
                                    },
                                    profileScreen = {
                                        ProfileScreen(
                                            viewModel = profileViewModel,
                                            documentLibraryViewModel = documentLibraryViewModel,
                                            aiReminderViewModel = aiReminderViewModel,
                                            onNavigateToLibrary = {
                                                navController.navigate(Screen.DocumentLibrary.route) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onNavigateToPersona = {
                                                navController.navigate(Screen.CounselorPersona.route) {
                                                    launchSingleTop = true
                                                }
                                            },
                                            onRequestNotificationPermission = onRequestNotificationPermission
                                        )
                                    },
                                    documentLibraryScreen = {
                                        DocumentLibraryScreen(
                                            viewModel = documentLibraryViewModel,
                                            windowSizeClass = windowSizeClass,
                                            onBack = { navController.popBackStack() },
                                            onDocumentSelected = { documentId ->
                                                navController.navigate(Screen.DocumentDetail.createRoute(documentId)) {
                                                    launchSingleTop = true
                                                }
                                            }
                                        )
                                    },
                                    documentDetailScreen = { documentId ->
                                        com.stressdetector.mobile.ui.screens.DocumentDetailScreen(
                                            documentId = documentId,
                                            viewModel = documentLibraryViewModel,
                                            onBack = { navController.popBackStack() }
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── 5-Destination Bottom Navigation ───────────────────────────────────────────

@Composable
@Suppress("FunctionNaming")
private fun AppBottomNav(selectedDestination: Screen?, onSelect: (Screen) -> Unit) {
    NavigationBar(
        modifier = Modifier.fillMaxWidth(),
        containerColor = CharcoalSlate
    ) {
        APP_DESTINATIONS.forEach { destination ->
            NavigationBarItem(
                modifier = Modifier
                    .weight(1f)
                    .semantics {
                        selected = selectedDestination == destination
                        stateDescription = if (selectedDestination == destination) {
                            "Tab aktif"
                        } else {
                            "Tab tidak aktif"
                        }
                    },
                selected = selectedDestination == destination,
                onClick = { onSelect(destination) },
                icon = { androidx.compose.material3.Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label, fontSize = 10.sp, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = NeonEmerald,
                    indicatorColor = NeonEmerald.copy(alpha = 0.15f),
                    unselectedTextColor = TextSecondary
                )
            )
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun AppNavigationRail(selectedDestination: Screen?, onSelect: (Screen) -> Unit) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = CharcoalSlate
    ) {
        APP_DESTINATIONS.forEach { destination ->
            NavigationRailItem(
                modifier = Modifier.semantics {
                    selected = selectedDestination == destination
                    stateDescription = if (selectedDestination == destination) {
                        "Tab aktif"
                    } else {
                        "Tab tidak aktif"
                    }
                },
                selected = selectedDestination == destination,
                onClick = { onSelect(destination) },
                icon = {
                    androidx.compose.material3.Icon(
                        destination.icon,
                        contentDescription = destination.label
                    )
                },
                label = { Text(destination.label, fontSize = 11.sp, maxLines = 1) },
                alwaysShowLabel = true
            )
        }
    }
}

// ─── Session Tab ──────────────────────────────────────────────────────────────

@Composable
private fun sessionTab(
    viewModel: StressViewModel,
    config: SessionUi,
    permissionActions: PermissionActions,
    onNavigateToProfile: () -> Unit
) {
    val homeState = HomeUiState.from(config.state, config.permissionStatus)
    val horizontalPadding = when (config.windowSizeClass) {
        WindowWidthSizeClass.Expanded -> AppSpacing.Large
        WindowWidthSizeClass.Medium -> AppSpacing.ExtraLarge
        else -> AppSpacing.Screen
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            sessionHeader()
        }

        if (!config.permissionStatus.allGranted) {
            item {
                PermissionGateCard(
                    status = config.permissionStatus,
                    onGrantPermissions = permissionActions.onGrant,
                    onOpenSettings = permissionActions.onOpenSettings
                )
            }
        }

        item {
            HomeSessionStatusCard(
                state = homeState,
                onEvent = { event -> handleHomeEvent(viewModel, event, onNavigateToProfile) }
            )
        }

        if (config.state.hasChartData()) {
            item {
                sessionCharts(config.state, config.windowSizeClass != WindowWidthSizeClass.Compact)
            }
        }
    }
}

private data class SessionUi(val state: MobileUiState, val permissionStatus: PermissionStatus, val windowSizeClass: WindowWidthSizeClass)

private data class PermissionActions(val onGrant: () -> Unit, val onOpenSettings: () -> Unit)

@Composable
private fun sessionHeader() {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = "Clinical Companion",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )
        Text(
            text = "Live Stress Monitoring",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

private fun handleHomeEvent(viewModel: StressViewModel, event: HomeEvent, onNavigateToProfile: () -> Unit) {
    when (event) {
        HomeEvent.Start -> viewModel.startWatchTracking()
        HomeEvent.Stop -> viewModel.stopWatchTracking()
        HomeEvent.RefreshConnection -> viewModel.refreshWatchConnection()
        HomeEvent.RetryFinalization -> viewModel.retryFinalization()
        HomeEvent.OpenProfile -> onNavigateToProfile()
    }
}

@Composable
private fun sessionCharts(state: MobileUiState, twoColumnLayout: Boolean) {
    DualPpgChartSection(
        green = state.greenChart,
        ir = state.irChart,
        red = state.redChart,
        greenSamples = state.greenSamples,
        irSamples = state.irSamples,
        redSamples = state.redDiagnosticSamples,
        twoColumnLayout = twoColumnLayout
    )
}

private fun MobileUiState.hasChartData(): Boolean = isRecording ||
    !greenChart.isEmpty ||
    !irChart.isEmpty ||
    !redChart.isEmpty

private fun modelStatusFor(state: MobileUiState): String = "Green: ${predictionStatus(state.primaryPredictionState)} · " +
    "IR: ${predictionStatus(state.irPredictionState)}"

private fun predictionStatus(availability: PredictionAvailability): String = when (availability) {
    is PredictionAvailability.WarmingUp -> modelWindowStatus(availability)
    is PredictionAvailability.Ready -> availability.prediction.modelVersion ?: "ready"
    is PredictionAvailability.Rejected -> "rejected"
    is PredictionAvailability.Unavailable -> "unavailable"
    is PredictionAvailability.InferenceError -> "error"
}

private fun modelWindowStatus(availability: PredictionAvailability.WarmingUp): String {
    val required = availability.requiredSamples.coerceAtLeast(1)
    val seconds = ((availability.currentSamples.toFloat() / required.toFloat()) * STRESS_MODEL_WINDOW_SECONDS)
        .toInt()
        .coerceIn(0, STRESS_MODEL_WINDOW_SECONDS)
    return "model window $seconds/$STRESS_MODEL_WINDOW_SECONDS}s"
}

// ─── Permission Gate Card ─────────────────────────────────────────────────────

@Composable
private fun PermissionGateCard(status: PermissionStatus, onGrantPermissions: () -> Unit, onOpenSettings: () -> Unit) {
    if (status.allGranted) {
        PermissionGrantedCard()
        return
    }

    PermissionMissingCard(status, onGrantPermissions, onOpenSettings)
}

@Composable
private fun PermissionGrantedCard() {
    PremiumCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatusDot(color = NeonEmerald)
            Text("Izin sudah diberikan", color = NeonEmerald, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PermissionMissingCard(status: PermissionStatus, onGrantPermissions: () -> Unit, onOpenSettings: () -> Unit) {
    val isPermanentlyDenied = !status.shouldShowRationale && status.missingPermissions.isNotEmpty()

    PremiumCard(
        borderColor = AlertRed.copy(alpha = 0.6f),
        backgroundColor = AlertRed.copy(alpha = 0.08f)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(color = AlertRed)
                Text(
                    text = "Izin diperlukan",
                    color = AlertRed,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Text(
                text = "Diperlukan: ${status.missingPermissions.joinToString { it.substringAfterLast('.') }}",
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
            if (isPermanentlyDenied) {
                Text(
                    text = "Izin ditolak. Aktifkan kembali melalui pengaturan Android.",
                    color = WarnAmber,
                    style = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = onOpenSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = WarnAmber, contentColor = Color.Black)
                ) {
                    Text("Buka pengaturan aplikasi", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onGrantPermissions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AlertRed, contentColor = Color.White)
                ) {
                    Text("Izinkan akses", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Session Progress Card ────────────────────────────────────────────────────

// ─── Report Tab ───────────────────────────────────────────────────────────────

@Composable
private fun reportTab(
    viewModel: StressViewModel,
    windowSizeClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    onSessionSelected: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val horizontalPadding = when (windowSizeClass) {
        WindowWidthSizeClass.Expanded -> AppSpacing.Large
        WindowWidthSizeClass.Medium -> AppSpacing.ExtraLarge
        else -> AppSpacing.Screen
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            reportHeader()
        }

        item { CalendarCard(state = state, viewModel = viewModel) }

        item { selectedDaySummaryCard(state) }

        item { weeklyTrendCard(state) }

        item { sessionHistoryCard(state, onSessionSelected) }
    }
}

@Composable
private fun reportHeader() {
    Column(modifier = Modifier.padding(bottom = 4.dp)) {
        Text(
            text = "Session History",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )
        Text(
            text = "Past recordings and trends",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun selectedDaySummaryCard(state: MobileUiState) {
    PremiumCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "SELECTED DAY",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = NeonEmerald
            )
            Text(formatDate(state.selectedReportDateMillis), color = TextPrimary, fontWeight = FontWeight.SemiBold)
            MetricRow("Total Sessions", "${state.selectedDaySummary.total}")
            MetricRow("Stress", "${state.selectedDaySummary.stressCount}")
            MetricRow("Normal", "${state.selectedDaySummary.normalCount}")
            MetricRow("Stress Rate", "${"%.0f".format(state.selectedDaySummary.stressRate * 100)}%")
        }
    }
}

@Composable
private fun weeklyTrendCard(state: MobileUiState) {
    PremiumCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "WEEKLY TREND",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = OceanBlue
            )
            state.weeklyTrend.forEach { item ->
                MetricRow(item.dayLabel, "${item.stressCount}/${item.total} stress")
            }
        }
    }
}

@Composable
private fun sessionHistoryCard(state: MobileUiState, onSessionSelected: (String) -> Unit) {
    PremiumCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "SESSION HISTORY",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            if (state.selectedDayHistory.isEmpty()) {
                Text("No sessions recorded for this day.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            } else {
                state.selectedDayHistory.forEach { session ->
                    HistoryRow(session = session, onClick = { onSessionSelected(session.sessionId) })
                }
            }
        }
    }
}

// ─── Calendar Card ────────────────────────────────────────────────────────────

@Composable
private fun CalendarCard(state: MobileUiState, viewModel: StressViewModel) {
    PremiumCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.IconButton(onClick = {
                    viewModel.changeReportMonth(-1)
                }, modifier = Modifier.semantics { contentDescription = "Bulan sebelumnya" }) {
                    androidx.compose.material3.Icon(Icons.Default.ChevronLeft, contentDescription = null)
                }
                Text(
                    formatMonth(state.reportMonthMillis),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                androidx.compose.material3.IconButton(onClick = {
                    viewModel.changeReportMonth(1)
                }, modifier = Modifier.semantics { contentDescription = "Bulan berikutnya" }) {
                    androidx.compose.material3.Icon(Icons.Default.ChevronRight, contentDescription = null)
                }
            }
            Button(
                onClick = viewModel::selectToday,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = NeonEmerald.copy(alpha = 0.15f), contentColor = NeonEmerald)
            ) { Text("Today") }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Su", "Mo", "Tu", "We", "Th", "Fr", "Sa").forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            state.calendarDays.chunked(7).forEach { week ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    week.forEach { day ->
                        CalendarDayCell(
                            day = day,
                            selected = sameDay(day.dateMillis, state.selectedReportDateMillis),
                            onClick = { viewModel.selectReportDate(day.dateMillis) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(day: CalendarDayItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bgColor = when {
        selected -> NeonEmerald
        else -> Color.Transparent
    }
    val textColor = when {
        selected -> Color.White
        !day.inCurrentMonth -> TextSecondary.copy(alpha = 0.4f)
        else -> TextPrimary
    }
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .clickable(
                role = Role.Button,
                onClickLabel = "Pilih ${day.dayOfMonth}",
                onClick = onClick
            )
            .semantics {
                this.selected = selected
                stateDescription = if (selected) "Dipilih" else "Tidak dipilih"
                contentDescription = "${day.dayOfMonth}, ${day.total} sesi, ${day.stressCount} stres"
            }
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("${day.dayOfMonth}", color = textColor, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        if (day.total > 0) {
            Text(
                "${day.stressCount}/${day.total}",
                color = if (selected) Color.White.copy(alpha = 0.8f) else WarnAmber,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ─── Session History Row ──────────────────────────────────────────────────────

@Composable
private fun HistoryRow(session: StressSessionEntity, onClick: () -> Unit) {
    val transportIncomplete = session.transportIncomplete
    val status = HistoryStatusMapper.map(
        finalLabel = session.finalLabel,
        errorReason = session.errorReason,
        reportStatus = null,
        transportIncomplete = transportIncomplete
    )
    val (statusLabel, accent) = when (status) {
        HistoryResultStatus.STRESS -> "Stress" to AlertRed
        HistoryResultStatus.NORMAL -> "Normal" to NeonEmerald
        HistoryResultStatus.INVALID -> "Tidak valid" to Color(0xFF94A3B8)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Buka laporan sesi ${session.sessionId}" }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(session.formatTime(), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        Column {
            Text(
                statusLabel,
                color = accent,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )
            if (transportIncomplete) {
                Text("Transport incomplete", color = WarnAmber, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (session.stressProbability.isFinite() && session.stressProbability in 0f..1f) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "S ${session.stressProbability.asPercent()} · N ${(1f - session.stressProbability).asPercent()}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    "C ${session.confidence.asPercent()} — ${if (session.confidence >= 0.80f) "High" else "Review"}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        } else {
            Text("Review required", color = WarnAmber, style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ─── Format Helpers ───────────────────────────────────────────────────────────

private fun StressSessionEntity.formatTime(): String = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(endedAtMillis))

private fun Float.asPercent(): String = "${(coerceIn(0f, 1f) * 100f).toInt()}%"

private fun formatDate(timeMillis: Long): String = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault()).format(Date(timeMillis))

private fun formatMonth(timeMillis: Long): String = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date(timeMillis))

private fun sameDay(left: Long, right: Long): Boolean {
    val a = Calendar.getInstance().apply { timeInMillis = left }
    val b = Calendar.getInstance().apply { timeInMillis = right }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
        a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}
