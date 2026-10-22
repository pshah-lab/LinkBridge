package com.linkbridge.android.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from the Mac "Device selection" checks, plus the Android never-unpaired rule. */
class DeviceSelectionTest {
    private fun item(id: String, sendable: Boolean, paired: Boolean = true) = DeviceListItem(
        id = id, deviceId = id, name = id, platform = DevicePlatform.MAC, isPaired = paired,
        isNearby = true, isSendable = sendable, canPair = !paired, subtitle = "", peer = null
    )

    @Test
    fun preferredWinsWhenSendable() {
        assertEquals("b", DeviceSelection.resolve("a", "b", listOf(item("a", true), item("b", true)), isSending = false))
    }

    @Test
    fun currentKeptWhenSendable() {
        assertEquals("a", DeviceSelection.resolve("a", "c", listOf(item("b", true), item("a", true)), isSending = false))
    }

    @Test
    fun otherwiseFirstSendable() {
        assertEquals("b", DeviceSelection.resolve(null, null, listOf(item("a", false), item("b", true), item("c", true)), isSending = false))
    }

    @Test
    fun emptyListKeepsSelection() {
        assertEquals("b", DeviceSelection.resolve("b", "b", emptyList(), isSending = false))
    }

    @Test
    fun sendingNeverChangesSelection() {
        assertEquals("b", DeviceSelection.resolve("b", "b", listOf(item("a", true), item("b", false)), isSending = true))
    }

    @Test
    fun fallsBackWhilePreferredIsAwayThenReturns() {
        val fallback = DeviceSelection.resolve("b", "b", listOf(item("a", true), item("b", false)), isSending = false)
        val back = DeviceSelection.resolve(fallback, "b", listOf(item("a", true), item("b", true)), isSending = false)
        assertEquals("a", fallback)
        assertEquals("b", back)
    }

    @Test
    fun unpairedSelectionFallsBack() {
        val unpaired = item("b", sendable = false, paired = false)
        assertEquals("a", DeviceSelection.resolve("b", "b", listOf(item("a", true), unpaired), isSending = false))
        assertNull(DeviceSelection.resolve("b", "b", listOf(unpaired), isSending = false))
    }
}
