package com.linkbridge.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The haze source for glass. Provided by `LinkBridgeTheme`; null means no blur source. */
val LocalHazeState: ProvidableCompositionLocal<HazeState?> = staticCompositionLocalOf { null }

private const val GLASS_FILL_ALPHA = 0.70f
private const val GLASS_STATE_TINT_ALPHA = 0.16f
private val GlassBlurRadius = 24.dp
private val GlassBorderWidth = 1.dp

/**
 * A glass surface clipped to [shape]. With blur available (and a [LocalHazeState]): Haze
 * blur of the content behind, 24 dp, under a ~70% `surfaceContainer` tint. Otherwise a solid
 * `surfaceContainerHigh` fill with a 1 dp `outlineVariant` border.
 *
 * [tint] signals state only (cobalt for action, coral for attention); it is applied at low
 * alpha, so pass the plain accent colour.
 */
fun Modifier.glassSurface(shape: Shape, tint: Color? = null): Modifier = composed {
    val colors = MaterialTheme.colorScheme
    val hazeState = LocalHazeState.current
    val stateTint = tint?.copy(alpha = GLASS_STATE_TINT_ALPHA)
    if (LocalBlurEnabled.current && hazeState != null) {
        val style = HazeStyle(
            backgroundColor = colors.surface,
            tints = listOfNotNull(
                HazeTint(colors.surfaceContainer.copy(alpha = GLASS_FILL_ALPHA)),
                stateTint?.let { HazeTint(it) },
            ),
            blurRadius = GlassBlurRadius,
        )
        clip(shape).hazeEffect(state = hazeState, style = style)
    } else {
        val solid = clip(shape)
            .background(colors.surfaceContainerHigh, shape)
            .border(GlassBorderWidth, colors.outlineVariant, shape)
        if (stateTint != null) solid.background(stateTint, shape) else solid
    }
}
