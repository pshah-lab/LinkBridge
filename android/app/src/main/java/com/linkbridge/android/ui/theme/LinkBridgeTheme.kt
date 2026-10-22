package com.linkbridge.android.ui.theme

import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import com.linkbridge.android.presentation.Accent
import com.linkbridge.android.ui.glass.LocalBlurEnabled
import com.linkbridge.android.ui.glass.LocalHazeState
import com.linkbridge.android.ui.glass.rememberBlurEnabled
import dev.chrisbanes.haze.HazeState

// Cobalt fallback schemes, used when dynamic colour is off (previews, or by choice).
// Only the primary family is set; the rest stays M3 baseline.
private val CobaltLight: ColorScheme = lightColorScheme(
    primary = rgb(Accent.ACTION.lightHex),
    onPrimary = rgb(Accent.ACTION.onLightHex),
    primaryContainer = rgb(0xDCE1FF),
    onPrimaryContainer = rgb(0x00164F),
    inversePrimary = rgb(Accent.ACTION.darkHex),
    surfaceTint = rgb(Accent.ACTION.lightHex),
)

private val CobaltDark: ColorScheme = darkColorScheme(
    primary = rgb(Accent.ACTION.darkHex),
    onPrimary = rgb(Accent.ACTION.onDarkHex),
    primaryContainer = rgb(0x2441B8),
    onPrimaryContainer = rgb(0xDCE1FF),
    inversePrimary = rgb(Accent.ACTION.lightHex),
    surfaceTint = rgb(Accent.ACTION.darkHex),
)

/**
 * LinkBridge's Compose theme: dynamic colour (always available from API 31) with cobalt
 * fallbacks, Material 3 Expressive motion, and the app locals: [LocalAccents],
 * [LocalHazeState] (one per theme root; screens put `hazeSource` on their content) and
 * [LocalBlurEnabled] (live cross-window blur setting of the hosting activity).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LinkBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && !LocalInspectionMode.current ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> CobaltDark
        else -> CobaltLight
    }
    val accents = remember(darkTheme) { AccentColors.of(darkTheme) }
    val hazeState = remember { HazeState() }
    val activity = LocalActivity.current
    val blurEnabled = if (activity != null && !LocalInspectionMode.current) {
        rememberBlurEnabled(activity).value
    } else {
        false
    }

    CompositionLocalProvider(
        LocalAccents provides accents,
        LocalHazeState provides hazeState,
        LocalBlurEnabled provides blurEnabled,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            content = content,
        )
    }
}

/**
 * False when the system animator duration scale is 0 ("Remove animations" or developer
 * options). Morphs should then become cross-fades with no scale or bounce.
 */
@Composable
fun animationsEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
}
