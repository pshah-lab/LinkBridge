package com.linkbridge.android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.linkbridge.android.presentation.Accent

/** The three LinkBridge accents and their label colours, for the current light/dark mode. */
@Immutable
data class AccentColors(
    val action: Color,
    val onAction: Color,
    val signal: Color,
    val onSignal: Color,
    val attention: Color,
    val onAttention: Color,
) {
    companion object {
        /** Built from [Accent] so the hex values live in one place (DESIGN.md mirror). */
        fun of(dark: Boolean): AccentColors {
            fun fill(a: Accent) = rgb(if (dark) a.darkHex else a.lightHex)
            fun on(a: Accent) = rgb(if (dark) a.onDarkHex else a.onLightHex)
            return AccentColors(
                action = fill(Accent.ACTION),
                onAction = on(Accent.ACTION),
                signal = fill(Accent.SIGNAL),
                onSignal = on(Accent.SIGNAL),
                attention = fill(Accent.ATTENTION),
                onAttention = on(Accent.ATTENTION),
            )
        }
    }
}

/** Opaque colour from a 0xRRGGBB value. */
internal fun rgb(hex: Long): Color = Color(0xFF000000L or hex)

val LocalAccents: ProvidableCompositionLocal<AccentColors> =
    staticCompositionLocalOf { AccentColors.of(dark = false) }
