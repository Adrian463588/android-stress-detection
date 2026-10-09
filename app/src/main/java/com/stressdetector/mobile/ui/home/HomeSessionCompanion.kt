package com.stressdetector.mobile.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.LocalAppMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val GAME_ENTRY_DELAY_MILLIS = 2_000L
private const val CLOUD_JUMP_UP_MILLIS = 180
private const val CLOUD_JUMP_DOWN_MILLIS = 340
private const val CLOUD_JUMP_HEIGHT_DP = -64f
private const val CLOUD_OBSTACLE_SPEED = 0.32f
private const val CLOUD_COLLISION_START = 0.10f
private const val CLOUD_COLLISION_END = 0.27f
private const val CLOUD_CLEAR_POSITION = -0.08f
private const val CLOUD_SAFE_JUMP_OFFSET = -36f
private const val FRAME_NANOS_LIMIT = 50_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000f
private const val OBSTACLE_START = 1f

@Composable
internal fun ActiveSessionCompanion(state: HomeUiState) {
    when (state.status) {
        HomeSessionStatus.RECORDING -> RecordingCompanion(state.recordingSignalLabel)
        HomeSessionStatus.FINALIZING -> FinalizationCompanion(state.sessionId, state.canPlayWaitingGame)
        else -> Unit
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun RecordingCompanion(signalLabel: String) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
        Text("Penjaga sinyal", style = MaterialTheme.typography.labelMedium)
        Text(
            text = signalLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
        )
        Text(
            text = "PPG mencatat perubahan pantulan cahaya pada kulit untuk memperkirakan denyut nadi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun FinalizationCompanion(sessionId: String?, snapshotCaptured: Boolean) {
    if (sessionId == null || !snapshotCaptured) return
    var entryReady by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sessionId) {
        delay(GAME_ENTRY_DELAY_MILLIS)
        entryReady = true
    }
    if (entryReady && LocalAppMotion.current.enabled) CloudGameEntry(sessionId)
}

@Composable
private fun CloudGameEntry(sessionId: String) {
    var gameOpen by remember(sessionId) { mutableStateOf(false) }
    if (gameOpen) {
        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)) {
            Text("Awan Melayang", style = MaterialTheme.typography.labelMedium)
            CloudRunner(sessionId)
            TextButton(
                onClick = { gameOpen = false },
                modifier = Modifier.heightIn(min = HOME_MIN_TOUCH_TARGET_DP.dp)
            ) { Text("Tutup permainan") }
        }
    } else {
        TextButton(
            onClick = { gameOpen = true },
            modifier = Modifier.heightIn(min = HOME_MIN_TOUCH_TARGET_DP.dp)
        ) { Text("Main sebentar") }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun CloudRunner(sessionId: String) {
    var obstacle by remember(sessionId) { mutableFloatStateOf(OBSTACLE_START) }
    var cleared by remember(sessionId) { mutableIntStateOf(0) }
    var collided by remember(sessionId) { mutableStateOf(false) }
    val jump = remember(sessionId) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val motion = LocalAppMotion.current
    CloudRunnerMotion(sessionId, collided, obstacle, jump.value) { next, hit, passed ->
        obstacle = next
        if (hit) collided = true
        if (passed) cleared++
    }

    CloudRunnerScene(obstacle, cleared, collided, jump.value) {
        if (collided) {
            collided = false
            obstacle = OBSTACLE_START
            cleared = 0
        } else if (!jump.isRunning) {
            scope.launch {
                jump.animateTo(CLOUD_JUMP_HEIGHT_DP, tween(motion.duration(CLOUD_JUMP_UP_MILLIS)))
                jump.animateTo(0f, tween(motion.duration(CLOUD_JUMP_DOWN_MILLIS)))
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun CloudRunnerScene(obstacle: Float, cleared: Int, collided: Boolean, jumpOffset: Float, onTap: () -> Unit) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(144.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button, onClickLabel = if (collided) "Ulangi permainan" else "Lompat", onClick = onTap)
            .semantics {
                contentDescription = if (collided) "Ketuk untuk mengulang Awan Melayang" else "Ketuk untuk melompati rintangan"
                stateDescription = if (collided) "Rintangan tersentuh" else "$cleared rintangan dilewati"
            }
    ) {
        Icon(
            imageVector = Icons.Outlined.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 20.dp, bottom = 20.dp)
                .offset(y = jumpOffset.dp)
                .size(48.dp)
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = (maxWidth - 24.dp) * obstacle)
                .padding(bottom = 20.dp)
                .size(width = 24.dp, height = 32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.outline)
        )
        Text(
            text = if (collided) "Ketuk untuk mengulang" else "Ketuk untuk melompati rintangan · $cleared dilewati",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = AppSpacing.Compact)
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun CloudRunnerMotion(
    sessionId: String,
    collided: Boolean,
    obstacle: Float,
    jumpOffset: Float,
    onFrame: (Float, Boolean, Boolean) -> Unit
) {
    val currentObstacle by rememberUpdatedState(obstacle)
    val currentJumpOffset by rememberUpdatedState(jumpOffset)
    val currentOnFrame by rememberUpdatedState(onFrame)
    LaunchedEffect(sessionId, collided) {
        if (collided) return@LaunchedEffect
        var previousFrame = 0L
        while (isActive) {
            withFrameNanos { frame ->
                if (previousFrame != 0L) {
                    val seconds = (frame - previousFrame).coerceAtMost(FRAME_NANOS_LIMIT) / NANOS_PER_SECOND
                    val next = currentObstacle - seconds * CLOUD_OBSTACLE_SPEED
                    val passed = next < CLOUD_CLEAR_POSITION
                    val hit = next in CLOUD_COLLISION_START..CLOUD_COLLISION_END && currentJumpOffset > CLOUD_SAFE_JUMP_OFFSET
                    currentOnFrame(
                        if (passed) OBSTACLE_START else next,
                        !passed && hit,
                        passed
                    )
                }
                previousFrame = frame
            }
        }
    }
}
