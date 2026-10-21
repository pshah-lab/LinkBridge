package com.linkbridge.android.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the Mac "Accent" checks (macos/Sources/LinkBridgeCoreChecks). */
class AccentTest {
    @Test
    fun hexValuesMatchTheDesignLanguage() {
        assertEquals(0x3E62E8L, Accent.ACTION.lightHex)
        assertEquals(0x7C95FFL, Accent.ACTION.darkHex)
        assertEquals(0x35C98BL, Accent.SIGNAL.lightHex)
        assertEquals(0x4ADBA0L, Accent.SIGNAL.darkHex)
        assertEquals(0xF08A5DL, Accent.ATTENTION.lightHex)
        assertEquals(0xFF9F78L, Accent.ATTENTION.darkHex)
    }

    @Test
    fun labelsUseWhiteOnLightCobaltAndInkOtherwise() {
        assertEquals(0xFFFFFFL, Accent.ACTION.onLightHex)
        for (accent in Accent.entries) assertEquals(0x10151FL, accent.onDarkHex)
        assertEquals(0x10151FL, Accent.SIGNAL.onLightHex)
        assertEquals(0x10151FL, Accent.ATTENTION.onLightHex)
    }

    @Test
    fun everyLabelReachesAA() {
        for (accent in Accent.entries) {
            assertTrue("$accent light", Accent.contrastRatio(accent.onLightHex, accent.lightHex) >= 4.5)
            assertTrue("$accent dark", Accent.contrastRatio(accent.onDarkHex, accent.darkHex) >= 4.5)
        }
    }

    @Test
    fun contrastRatioMatchesWcagExtremes() {
        assertEquals(21.0, Accent.contrastRatio(0x000000L, 0xFFFFFFL), 1e-9)
        assertEquals(21.0, Accent.contrastRatio(0xFFFFFFL, 0x000000L), 1e-9)
        assertEquals(1.0, Accent.contrastRatio(0x3E62E8L, 0x3E62E8L), 1e-9)
    }
}
