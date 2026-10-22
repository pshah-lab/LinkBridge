package com.linkbridge.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

private const val BACKDROP_ALPHA_LIGHT = 0.06f
private const val BACKDROP_ALPHA_DARK = 0.10f

/** Cobalt at 6% (light) or 10% (dark) over [background], per DESIGN.md. */
fun backdropColor(background: Color, cobalt: Color, dark: Boolean): Color =
    cobalt.copy(alpha = if (dark) BACKDROP_ALPHA_DARK else BACKDROP_ALPHA_LIGHT).compositeOver(background)

/** Fills with the app backdrop: the colour glass surfaces sit over. Follows the theme's mode. */
fun Modifier.backdrop(): Modifier = composed {
    val background = MaterialTheme.colorScheme.background
    val dark = background.luminance() < 0.5f
    background(backdropColor(background, LocalAccents.current.action, dark))
}
