package com.linkbridge.android.presentation

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Accent colours from the shared LinkBridge design language, as 0xRRGGBB.
 * Mirrors `Accent` in macos/Sources/LinkBridgePresentation.
 */
enum class Accent(val lightHex: Long, val darkHex: Long) {
    ACTION(0x3E62E8, 0x7C95FF),
    SIGNAL(0x35C98B, 0x4ADBA0),
    ATTENTION(0xF08A5D, 0xFF9F78);

    /** Label colour on a light fill: white only on cobalt, where it reaches 4.5:1; ink otherwise. */
    val onLightHex: Long get() = if (this == ACTION) WHITE else INK

    /** Label colour on a dark fill: always ink. */
    val onDarkHex: Long get() = INK

    companion object {
        private const val WHITE = 0xFFFFFFL
        private const val INK = 0x10151FL

        /** WCAG 2 contrast ratio between two sRGB colours given as 0xRRGGBB. */
        fun contrastRatio(fgHex: Long, bgHex: Long): Double {
            val a = luminance(fgHex)
            val b = luminance(bgHex)
            return (max(a, b) + 0.05) / (min(a, b) + 0.05)
        }

        private fun luminance(hex: Long): Double {
            fun channel(shift: Int): Double {
                val c = ((hex shr shift) and 0xFF).toDouble() / 255
                return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }
    }
}
