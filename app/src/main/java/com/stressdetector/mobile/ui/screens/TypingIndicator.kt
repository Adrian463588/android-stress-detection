package com.stressdetector.mobile.ui.screens

import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.LocalAppMotion

@Composable
internal fun typingIndicator() {
    val motion = LocalAppMotion.current
    val infiniteTransition = if (motion.enabled) {
        rememberInfiniteTransition(label = "typing")
    } else {
        null
    }
    val firstOffset = infiniteTransition?.let {
        animatedTypingDotOffset(it, TypingDotTiming.FIRST, "dot1")
    }
    val secondOffset = infiniteTransition?.let {
        animatedTypingDotOffset(it, TypingDotTiming.SECOND, "dot2")
    }
    val thirdOffset = infiniteTransition?.let {
        animatedTypingDotOffset(it, TypingDotTiming.THIRD, "dot3")
    }
    Row(
        modifier = Modifier.padding(top = TYPING_INDICATOR_TOP_PADDING),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TYPING_INDICATOR_SPACING)
    ) {
        Text(
            text = "AI Counselor sedang berpikir",
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = MaterialTheme.colorScheme.primary
        )
        if (motion.enabled) {
            typingDot(firstOffset ?: TYPING_DOT_REST_OFFSET)
            typingDot(secondOffset ?: TYPING_DOT_REST_OFFSET)
            typingDot(thirdOffset ?: TYPING_DOT_REST_OFFSET)
        }
    }
}

@Composable
private fun animatedTypingDotOffset(transition: InfiniteTransition, timing: TypingDotTiming, label: String): Float {
    val offset by transition.animateFloat(
        initialValue = TYPING_DOT_REST_OFFSET,
        targetValue = TYPING_DOT_RAISED_OFFSET,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = TYPING_CYCLE_DURATION_MILLIS
                TYPING_DOT_REST_OFFSET at TYPING_CYCLE_START_MILLIS
                TYPING_DOT_RAISED_OFFSET at timing.raiseAtMillis
                TYPING_DOT_REST_OFFSET at timing.settleAtMillis
                TYPING_DOT_REST_OFFSET at TYPING_CYCLE_DURATION_MILLIS
            },
            repeatMode = RepeatMode.Restart
        ),
        label = label
    )
    return offset
}

@Composable
private fun typingDot(offset: Float) {
    Box(
        modifier = Modifier
            .offset(y = offset.dp)
            .size(TYPING_DOT_SIZE)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}

private data class TypingDotTiming(val raiseAtMillis: Int, val settleAtMillis: Int) {
    companion object {
        val FIRST = TypingDotTiming(FIRST_DOT_RAISE_MILLIS, FIRST_DOT_SETTLE_MILLIS)
        val SECOND = TypingDotTiming(SECOND_DOT_RAISE_MILLIS, SECOND_DOT_SETTLE_MILLIS)
        val THIRD = TypingDotTiming(THIRD_DOT_RAISE_MILLIS, THIRD_DOT_SETTLE_MILLIS)
    }
}

private val TYPING_INDICATOR_TOP_PADDING = 4.dp
private val TYPING_INDICATOR_SPACING = 4.dp
private val TYPING_DOT_SIZE = 4.dp
private const val TYPING_DOT_REST_OFFSET = 0f
private const val TYPING_DOT_RAISED_OFFSET = -5f
private const val TYPING_CYCLE_START_MILLIS = 0
private const val TYPING_CYCLE_DURATION_MILLIS = 1_000
private const val FIRST_DOT_RAISE_MILLIS = 250
private const val FIRST_DOT_SETTLE_MILLIS = 500
private const val SECOND_DOT_RAISE_MILLIS = 400
private const val SECOND_DOT_SETTLE_MILLIS = 650
private const val THIRD_DOT_RAISE_MILLIS = 550
private const val THIRD_DOT_SETTLE_MILLIS = 800
