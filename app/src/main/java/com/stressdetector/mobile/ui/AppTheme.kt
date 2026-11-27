package com.stressdetector.mobile.ui

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Semantic palette for the local-first companion. Feature screens use MaterialTheme roles first. */
@Suppress("MagicNumber")
object AppPalette {
    val Primary = Color(0xFF10B981)
    val Secondary = Color(0xFF60A5FA)
    val Background = Color(0xFF0B1220)
    val Surface = Color(0xFF172235)
    val SurfaceElevated = Color(0xFF1E2B40)
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFFB5C0D0)
    val Error = Color(0xFFF87171)
    val Warning = Color(0xFFFBBF24)
}

object AppSpacing {
    val Micro = 4.dp
    val Screen = 16.dp
    val Section = 24.dp
    val Compact = 8.dp
    val Small = 12.dp
    val Medium = 16.dp
    val Large = 24.dp
    val ExtraLarge = 32.dp
    val ReadableContent = 1120.dp
}

data class AppMotionSpec(val enabled: Boolean) {
    fun duration(milliseconds: Int): Int = if (enabled) milliseconds else 0
}

val LocalAppMotion = staticCompositionLocalOf { AppMotionSpec(enabled = true) }

@Immutable
data class AppClinicalColors(
    val physiological: Color,
    val acoustic: Color,
    val marker: Color,
    val thermal: Color,
    val recording: Color,
    val surfaceSubtle: Color,
    val borderSubtle: Color
)

@Suppress("MagicNumber")
internal val DarkClinicalColors = AppClinicalColors(
    physiological = AppPalette.Primary,
    acoustic = AppPalette.Secondary,
    marker = AppPalette.Warning,
    thermal = Color(0xFFF59E0B),
    recording = AppPalette.Error,
    surfaceSubtle = Color(0xFF111B2B),
    borderSubtle = Color(0xFF2B3B54)
)

@Suppress("MagicNumber")
internal val LightClinicalColors = AppClinicalColors(
    physiological = Color(0xFF047857),
    acoustic = Color(0xFF1D4ED8),
    marker = Color(0xFFB45309),
    thermal = Color(0xFFB45309),
    recording = Color(0xFFB91C1C),
    surfaceSubtle = Color(0xFFF1F5F9),
    borderSubtle = Color(0xFFCBD5E1)
)

val LocalClinicalColors = staticCompositionLocalOf { DarkClinicalColors }

val MaterialTheme.clinicalColors: AppClinicalColors
    @Composable
    get() = LocalClinicalColors.current

private val AppColorScheme = darkColorScheme(
    primary = AppPalette.Primary,
    secondary = AppPalette.Secondary,
    background = AppPalette.Background,
    surface = AppPalette.Surface,
    surfaceVariant = AppPalette.SurfaceElevated,
    onPrimary = AppPalette.Background,
    onSecondary = AppPalette.Background,
    onBackground = AppPalette.TextPrimary,
    onSurface = AppPalette.TextPrimary,
    onSurfaceVariant = AppPalette.TextSecondary,
    error = AppPalette.Error,
    onError = AppPalette.Background
)

@Suppress("MagicNumber")
private val AppLightColorScheme = lightColorScheme(
    primary = AppPalette.Primary,
    secondary = Color(0xFF2563EB),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E8F0),
    onPrimary = Color(0xFF062019),
    onSecondary = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
@Suppress("FunctionNaming")
fun StressDetectorTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val animationScale by remember(context) {
        mutableStateOf(
            runCatching {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f
                )
            }.getOrDefault(1f)
        )
    }
    val motion = remember(animationScale) { AppMotionSpec(animationScale > 0f) }
    val colorScheme = if (darkTheme) AppColorScheme else AppLightColorScheme
    val clinicalColors = if (darkTheme) DarkClinicalColors else LightClinicalColors
    CompositionLocalProvider(
        LocalAppMotion provides motion,
        LocalClinicalColors provides clinicalColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            shapes = AppShapes,
            content = content
        )
    }
}

fun readableContentWidth(maxWidth: Dp): Dp = maxWidth.coerceAtMost(AppSpacing.ReadableContent)
