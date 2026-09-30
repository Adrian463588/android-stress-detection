package com.stressdetector.mobile.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeContrastTest {
    @Test
    fun actionTextColorsMeetNormalTextContrastAgainstTheirContainers() {
        assertTrue(contrastRatio(AppPalette.Background, AppPalette.Primary) >= 4.5)
        assertTrue(contrastRatio(AppPalette.Background, AppPalette.Secondary) >= 4.5)
        assertTrue(contrastRatio(AppPalette.Background, AppPalette.Error) >= 4.5)
    }

    private fun contrastRatio(foreground: Color, background: Color): Double {
        val foregroundLuminance = relativeLuminance(foreground)
        val backgroundLuminance = relativeLuminance(background)
        val lighter = maxOf(foregroundLuminance, backgroundLuminance)
        val darker = minOf(foregroundLuminance, backgroundLuminance)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }

        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }
}
