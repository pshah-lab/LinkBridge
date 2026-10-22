package com.linkbridge.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTokensTest {
    @Test fun lightAccentsMatchDesignTokens() {
        val a = AccentColors.of(dark = false)
        assertEquals(Color(0xFF3E62E8), a.action)
        assertEquals(Color.White, a.onAction)
        assertEquals(Color(0xFF35C98B), a.signal)
        assertEquals(Color(0xFF10151F), a.onSignal)
        assertEquals(Color(0xFFF08A5D), a.attention)
        assertEquals(Color(0xFF10151F), a.onAttention)
    }

    @Test fun darkAccentsUseInkLabels() {
        val a = AccentColors.of(dark = true)
        assertEquals(Color(0xFF7C95FF), a.action)
        assertEquals(Color(0xFF4ADBA0), a.signal)
        assertEquals(Color(0xFFFF9F78), a.attention)
        listOf(a.onAction, a.onSignal, a.onAttention).forEach { assertEquals(Color(0xFF10151F), it) }
    }

    @Test fun backdropIsCobaltSixPercentLightTenPercentDark() {
        val cobalt = Color(0xFF3E62E8)
        val light = backdropColor(Color.White, cobalt, dark = false)
        assertEquals(1f, light.alpha, 0f)
        assertEquals(1f - 0.06f * (1f - cobalt.red), light.red, 0.01f)
        val dark = backdropColor(Color.Black, Color(0xFF7C95FF), dark = true)
        assertEquals(0.10f * Color(0xFF7C95FF).blue, dark.blue, 0.01f)
    }
}
