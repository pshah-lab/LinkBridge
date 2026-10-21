package com.linkbridge.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveLimitsTest {
    @Test
    fun refusesFilesThatWouldFillTheStorage() {
        val margin = ReceiveLimits.FREE_SPACE_MARGIN
        assertTrue(ReceiveLimits.hasRoom(100, margin + 100))
        assertFalse(ReceiveLimits.hasRoom(101, margin + 100))
        assertFalse(ReceiveLimits.hasRoom(1, margin - 1))
        assertTrue("unknown free space is allowed", ReceiveLimits.hasRoom(1, -1))
    }

    @Test
    fun declaredSizeMustFitTheBody() {
        assertTrue(ReceiveLimits.sizeIsPlausible(10, 100))
        assertFalse(ReceiveLimits.sizeIsPlausible(101, 100))
        assertFalse(ReceiveLimits.sizeIsPlausible(-1, 100))
    }
}
