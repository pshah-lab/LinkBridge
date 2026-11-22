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

/** A refused sender's body is read and discarded so the reply isn't lost to a connection reset. */
class RefusalDrainTest {
    @org.junit.Test
    fun drainsExactlyTheRemainingBody() {
        val input = java.io.ByteArrayInputStream(ByteArray(5_000_000) + byteArrayOf(1, 2, 3))
        org.junit.Assert.assertEquals(5_000_000L, ReceiveLimits.drain(input, 5_000_000L, deadlineMillis = Long.MAX_VALUE))
        org.junit.Assert.assertEquals(3, input.available())
    }

    @org.junit.Test
    fun stopsAtEndOfStream() {
        val input = java.io.ByteArrayInputStream(ByteArray(10))
        org.junit.Assert.assertEquals(10L, ReceiveLimits.drain(input, 1_000L, deadlineMillis = Long.MAX_VALUE))
    }

    @org.junit.Test
    fun stopsAtTheDeadline() {
        val input = java.io.ByteArrayInputStream(ByteArray(1_000_000))
        org.junit.Assert.assertEquals(0L, ReceiveLimits.drain(input, 1_000_000L, deadlineMillis = 0L, now = { 1L }))
    }
}
