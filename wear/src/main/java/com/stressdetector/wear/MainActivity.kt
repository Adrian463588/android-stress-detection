@file:Suppress("FunctionNaming")

package com.stressdetector.wear

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material.AutoCenteringParams
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.wear.compose.material.rememberScalingLazyListState
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.stressdetector.shared.PermissionStatus
import com.stressdetector.shared.WearSessionMode
import com.stressdetector.wear.data.HealthSdkState
import com.stressdetector.wear.data.WearConnectionPhase
import com.stressdetector.wear.data.WearTrackingState
import com.stressdetector.wear.data.WearUiState
import com.stressdetector.wear.service.PpgTrackingService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

private data class WearScreenActions(
    val onGrantPermissions: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onStop: () -> Unit,
    val onResolveHealthSdk: () -> Unit
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearAppTheme {
                var permissionStatus by remember { mutableStateOf(wearPermissionStatus()) }
                var permissionRequestAttempted by remember { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    permissionStatus = wearPermissionStatus()
                }

                // Automatic Phone Connection Discovery
                val context = this@MainActivity
                val nodeClient = Wearable.getNodeClient(context)

                // This SDK version exposes connectedNodes as a Task but does
                // not expose a connected-node listener. Polling the Task is
                // bounded, lifecycle-scoped, and reflects actual node
                // connectivity rather than a stale capability advertisement.
                LaunchedEffect(nodeClient) {
                    while (true) {
                        nodeClient.connectedNodes.addOnSuccessListener { nodes: List<Node> ->
                            WearTrackingState.setPhoneNodeDiscovered(
                                nodes.isNotEmpty(),
                                if (nodes.isEmpty()) "No connected phone node" else "Phone node connected"
                            )
                        }
                        delay(2_000L)
                    }
                }

                WearScreen(
                    permissionStatus = permissionStatus,
                    permissionRequestAttempted = permissionRequestAttempted,
                    actions = WearScreenActions(
                        onGrantPermissions = {
                            val missing = missingWearPermissions(this@MainActivity)
                            if (missing.isNotEmpty()) {
                                permissionRequestAttempted = true
                                permissionLauncher.launch(missing.toTypedArray())
                            }
                        },
                        onOpenSettings = {
                            startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", packageName, null)
                                )
                            )
                        },
                        onStop = {
                            startService(
                                Intent(this, PpgTrackingService::class.java)
                                    .setAction(PpgTrackingService.ACTION_STOP)
                            )
                        },
                        onResolveHealthSdk = {
                            HealthSdkResolutionRegistry.resolve(this@MainActivity)
                        }
                    )
                )
            }
        }
    }

    private fun wearPermissionStatus(): PermissionStatus {
        val missing = missingWearPermissions(this)
        return PermissionStatus(
            allGranted = missing.isEmpty(),
            missingPermissions = missing,
            shouldShowRationale = missing.any { shouldShowRequestPermissionRationale(it) }
        )
    }
}

@Composable
private fun WearAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = MaterialTheme.colors.copy(
            primary = WearPrimary,
            secondary = WearMuted,
            background = WearBackground,
            surface = WearSurface,
            onPrimary = Color.White,
            onSecondary = Color.White,
            onBackground = WearOnBackground,
            onSurface = WearOnSurface
        ),
        content = content
    )
}

private val WearPrimary = Color(0xFF10B981)
private val WearWarning = Color(0xFFFBBF24)
private val WearError = Color(0xFFFCA5A5)

@Suppress("MagicNumber")
private val WearDanger = Color(0xFFB91C1C)
private val WearMuted = Color(0xFF94A3B8)
private val WearBackground = Color(0xFF0F172A)
private val WearSurface = Color(0xFF1E293B)
private val WearOnBackground = Color(0xFFF1F5F9)
private val WearOnSurface = Color(0xFFE2E8F0)

@Composable
private fun WearScreen(permissionStatus: PermissionStatus, permissionRequestAttempted: Boolean, actions: WearScreenActions) {
    val state by WearTrackingState.state.collectAsStateWithLifecycleCompat()

    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText(modifier = Modifier.fillMaxWidth()) },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = AutoCenteringParams(itemIndex = 1),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(
                top = 32.dp, // clears time bar on Galaxy Watch 5 Pro
                start = 8.dp, // tighter for round screen edge
                end = 8.dp,
                bottom = 40.dp // extra bottom so scroll indicator doesn't overlap
            )
        ) {
            item {
                HeaderSection(mode = state.sessionMode)
            }

            if (!permissionStatus.allGranted) {
                item {
                    PermissionGateCard(
                        status = permissionStatus,
                        onGrantPermissions = actions.onGrantPermissions,
                        onOpenSettings = actions.onOpenSettings,
                        showSettings = permissionRequestAttempted && !permissionStatus.shouldShowRationale
                    )
                }
            }

            item {
                MonitorCard(
                    state = state,
                    onResolveHealthSdk = actions.onResolveHealthSdk
                )
            }

            item {
                ControlActions(
                    isTracking = state.isTracking,
                    onStop = actions.onStop
                )
            }
        }
    }
}

@Composable
private fun HeaderSection(mode: com.stressdetector.shared.WearSessionMode?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when (mode) {
                com.stressdetector.shared.WearSessionMode.HOME_STRESS -> "Stress Detection"
                com.stressdetector.shared.WearSessionMode.VERBATIM_AROUSAL -> "Verbatim+ · PPG live"
                null -> "Stress Watch"
            },
            style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colors.onBackground,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun MonitorCard(state: WearUiState, onResolveHealthSdk: () -> Unit) {
    val statusText = monitorStatusText(state)
    val statusColor = monitorStatusColor(statusText)
    val reason = listOfNotNull(
        state.transportFailure,
        state.sensorFailure,
        state.healthSdkMessage
    ).firstOrNull { it.isNotBlank() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colors.surface)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MonitorStatusHeader(
            statusText = statusText,
            statusColor = statusColor,
            phaseLabel = monitorPhaseLabel(state.connectionPhase)
        )

        if (state.isTracking) {
            SessionProgressSection(state = state)
            ConnectionMetrics(state = state)
        }

        MonitorReason(reason)
        ResolveSensorAction(state = state, onResolveHealthSdk = onResolveHealthSdk)
    }
}

@Composable
private fun MonitorStatusHeader(statusText: String, statusColor: Color, phaseLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Column(
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        ) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold),
                color = statusColor
            )
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MonitorReason(reason: String?) {
    reason?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.caption2,
            color = WearError,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ResolveSensorAction(state: WearUiState, onResolveHealthSdk: () -> Unit) {
    if (state.healthSdkState != HealthSdkState.SDK_RESOLUTION_REQUIRED ||
        !state.healthSdkResolutionAvailable
    ) {
        return
    }

    Chip(
        onClick = onResolveHealthSdk,
        colors = ChipDefaults.primaryChipColors(
            backgroundColor = MaterialTheme.colors.primary,
            contentColor = Color.White
        ),
        label = {
            Text(
                text = "Resolve sensor",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}

private fun monitorStatusText(state: WearUiState): String = sdkStatusText(state)
    ?: failureStatusText(state)
    ?: sessionStatusText(state)
    ?: connectionStatusText(state)
    ?: "Standby"

private fun sdkStatusText(state: WearUiState): String? = when (state.healthSdkState) {
    HealthSdkState.SDK_RESOLUTION_REQUIRED -> "SDK action required"
    HealthSdkState.SDK_PERMISSION_REQUIRED -> "SDK permission needed"
    HealthSdkState.SDK_CONNECTING -> "Connecting sensor"
    HealthSdkState.SDK_UNAVAILABLE -> "Sensor unavailable"
    else -> null
}

private fun failureStatusText(state: WearUiState): String? = if (
    state.connectionPhase == WearConnectionPhase.FAILED ||
    state.transportFailure != null ||
    state.sensorFailure != null
) {
    "Session error"
} else {
    null
}

private fun sessionStatusText(state: WearUiState): String? = when (state.connectionPhase) {
    WearConnectionPhase.STREAMING -> "Recording"
    WearConnectionPhase.DRAINING -> "Finishing"
    WearConnectionPhase.COMPLETE -> "Complete"
    else -> null
}

private fun connectionStatusText(state: WearUiState): String? = when {
    state.isTracking && !state.isPhoneNodeDiscovered -> "Phone not found"
    state.isTracking && !state.isPhoneReachable -> "Phone offline"
    state.isTracking && !state.isConnectedToSamsung -> "Sensor offline"
    !state.isTracking -> "Standby"
    !state.samsungListenerRegistered -> "Starting sensor"
    state.connectionPhase == WearConnectionPhase.PHONE_REACHABLE -> "Waiting for sensor"
    state.connectionPhase == WearConnectionPhase.SENSOR_READY -> "Ready"
    else -> null
}

private fun monitorStatusColor(statusText: String): Color = when (statusText) {
    "Session error",
    "SDK action required",
    "SDK permission needed",
    "Sensor unavailable" -> WearError

    "Recording", "Complete", "Ready" -> WearPrimary
    "Standby" -> WearMuted
    else -> WearWarning
}

private fun monitorPhaseLabel(phase: WearConnectionPhase): String = when (phase) {
    WearConnectionPhase.IDLE -> "Waiting for a session from the phone"
    WearConnectionPhase.DISCOVERING -> "Finding the connected phone"
    WearConnectionPhase.PHONE_REACHABLE -> "Phone connected"
    WearConnectionPhase.SENSOR_READY -> "Sensors ready"
    WearConnectionPhase.STREAMING -> "Sending actual samples"
    WearConnectionPhase.DRAINING -> "Finishing and sending remaining samples"
    WearConnectionPhase.COMPLETE -> "Session complete"
    WearConnectionPhase.FAILED -> "Session stopped with an error"
}

@Composable
private fun ConnectionMetrics(state: WearUiState) {
    StatusMetricRow(
        label = "Phone",
        value = phoneStatus(state),
        healthy = state.isPhoneReachable
    )
    StatusMetricRow(
        label = "Sensor",
        value = sensorStatus(state),
        healthy = isSensorHealthy(state)
    )
    StatusMetricRow(
        label = "PPG",
        value = ppgStatus(state),
        healthy = state.greenSensorReady && state.irSensorReady
    )
}

private fun phoneStatus(state: WearUiState): String = when {
    state.isPhoneReachable -> "Connected"
    state.isPhoneNodeDiscovered -> "Found"
    else -> "Not found"
}

private fun sensorStatus(state: WearUiState): String = when {
    state.healthSdkState == HealthSdkState.SDK_PERMISSION_REQUIRED -> "Permission"
    state.healthSdkState == HealthSdkState.SDK_RESOLUTION_REQUIRED -> "Action needed"
    state.healthSdkState == HealthSdkState.SDK_CONNECTING -> "Connecting"
    state.healthSdkState == HealthSdkState.SDK_UNAVAILABLE -> "Unavailable"
    state.isConnectedToSamsung && state.samsungListenerRegistered -> "Listening"
    state.isConnectedToSamsung -> "Connected"
    else -> "Offline"
}

private fun ppgStatus(state: WearUiState): String = when {
    state.greenSensorReady && state.irSensorReady -> "Green + IR ready"
    state.greenSensorReady -> "Green ready"
    state.irSensorReady -> "IR ready"
    else -> "Waiting"
}

private fun isSensorHealthy(state: WearUiState): Boolean {
    if (state.healthSdkState != HealthSdkState.SDK_READY) return false
    return state.isConnectedToSamsung && state.samsungListenerRegistered
}

@Composable
private fun SessionProgressSection(state: WearUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.sessionMode == WearSessionMode.VERBATIM_AROUSAL) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PPG LIVE",
                    style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colors.primary
                )
                Text(
                    text = "Recording",
                    style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colors.onSurface
                )
            }
            MetricRow(label = "Elapsed", value = formatWearElapsed(state.elapsedMillis))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SESSION",
                    style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colors.primary
                )
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.caption1.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colors.onSurface
                )
            }
            MetricRow(
                label = "Elapsed",
                value = "${formatWearElapsed(state.elapsedMillis)} / 05:00"
            )
        }
        MetricRow(
            label = "Green",
            value = ppgChannelValue(state.greenSensorReady, state.greenSamplesSent)
        )
        MetricRow(
            label = "IR",
            value = ppgChannelValue(state.irSensorReady, state.irSamplesSent)
        )
        if (state.redSensorReady || state.redSamplesSent > 0 || state.redSensorFailure != null) {
            MetricRow(
                label = "Red diagnostic",
                value = ppgChannelValue(state.redSensorReady, state.redSamplesSent)
            )
        }
    }
}

private fun ppgChannelValue(sensorReady: Boolean, samplesSent: Int): String = when {
    samplesSent > 0 -> "$samplesSent sent"
    sensorReady -> "Ready"
    else -> "Waiting"
}

@Composable
private fun StatusMetricRow(label: String, value: String, healthy: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Medium),
            color = if (healthy) MaterialTheme.colors.primary else WearWarning
        )
    }
}

private fun formatWearElapsed(elapsedMillis: Long): String {
    val seconds = (elapsedMillis / 1_000L).coerceAtLeast(0L)
    return "%02d:%02d".format(seconds / 60L, seconds % 60L)
}

@Composable
private fun PermissionGateCard(
    status: PermissionStatus,
    onGrantPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    showSettings: Boolean
) {
    val missingNames = status.missingPermissions
        .joinToString { it.substringAfterLast('.').replace('_', ' ') }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(WearDanger.copy(alpha = 0.18f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Sensor access required",
            style = MaterialTheme.typography.title3.copy(fontWeight = FontWeight.Bold),
            color = WearError
        )
        Text(
            text = "Allow access to collect PPG from this watch.",
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurface
        )

        if (missingNames.isNotEmpty()) {
            Text(
                text = "Allow: $missingNames",
                style = MaterialTheme.typography.caption2,
                color = WearError,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        PermissionActionChip(
            label = "Grant permissions",
            onClick = onGrantPermissions,
            danger = true
        )
        if (showSettings) {
            SettingsRecovery(onOpenSettings = onOpenSettings)
        }
    }
}

@Composable
private fun PermissionActionChip(label: String, onClick: () -> Unit, danger: Boolean) {
    Chip(
        onClick = onClick,
        colors = if (danger) {
            ChipDefaults.primaryChipColors(
                backgroundColor = WearDanger,
                contentColor = Color.White
            )
        } else {
            ChipDefaults.chipColors(
                backgroundColor = WearSurface,
                contentColor = MaterialTheme.colors.onSurface
            )
        },
        label = {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SettingsRecovery(onOpenSettings: () -> Unit) {
    Text(
        text = "Akses sensor diblokir. Buka Settings untuk mengizinkannya.",
        style = MaterialTheme.typography.caption2,
        color = WearWarning
    )
    PermissionActionChip(
        label = "Buka Settings",
        onClick = onOpenSettings,
        danger = false
    )
}

@Composable
private fun ControlActions(isTracking: Boolean, onStop: () -> Unit) {
    if (!isTracking) {
        Text(
            text = "Start a session from the phone",
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    } else {
        Chip(
            onClick = onStop,
            colors = ChipDefaults.chipColors(
                backgroundColor = WearDanger,
                contentColor = Color.White
            ),
            label = {
                Text(
                    text = "Stop session",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
@SuppressLint("StateFlowValueCalledInComposition", "ProduceStateDoesNotAssignValue")
private fun <T> StateFlow<T>.collectAsStateWithLifecycleCompat(): State<T> {
    val source = this
    val lifecycleOwner = LocalContext.current as? LifecycleOwner
    return produceState(initialValue = source.value, source, lifecycleOwner) {
        value = source.value
        if (lifecycleOwner == null) {
            source.collect { value = it }
        } else {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                source.collect { value = it }
            }
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.body2.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colors.onSurface
        )
    }
}
