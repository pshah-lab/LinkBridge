package com.linkbridge.android.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from the Mac "Connection state" checks (macos/Sources/LinkBridgeCoreChecks). */
class ConnectionStateTest {
    private fun item(sendable: Boolean, nearby: Boolean = true) = DeviceListItem(
        id = "mac-1", deviceId = "mac-1", name = "Mac", platform = DevicePlatform.MAC,
        isPaired = true, isNearby = nearby, isSendable = sendable, canPair = false,
        subtitle = "", peer = null
    )

    @Test
    fun errorWinsOverEverything() {
        val state = ConnectionState.derive(1, item(sendable = true), SendPhase.Sending("a", 0.4f), "Boom")
        assertEquals(ConnectionState.Error("Boom"), state)
        assertEquals("Boom", state.title)
        assertEquals("Error", state.dotLabel)
        assertEquals(Accent.ATTENTION, state.accent)
    }

    @Test
    fun sendingShowsProgress() {
        val state = ConnectionState.derive(1, item(sendable = true), SendPhase.Sending("a", 0.4f), null)
        assertEquals(ConnectionState.Sending("Mac", 0.4f), state)
        assertEquals("Mac", state.title)
        assertEquals("Connected", state.dotLabel)
        assertEquals(Accent.SIGNAL, state.accent)
    }

    @Test
    fun sendingStaysVisibleWhenTheTargetBlinksOut() {
        val blink = item(sendable = false, nearby = false)
        assertEquals(
            ConnectionState.Sending("Mac", 0.5f),
            ConnectionState.derive(0, blink, SendPhase.Sending("a", 0.5f), null)
        )
    }

    @Test
    fun sendableSelectionIsReady() {
        val state = ConnectionState.derive(1, item(sendable = true), SendPhase.Idle, null)
        assertEquals(ConnectionState.Ready("Mac"), state)
        assertEquals("Mac", state.title)
        assertEquals("Connected", state.dotLabel)
        assertEquals(Accent.SIGNAL, state.accent)
    }

    @Test
    fun nothingNearbyIsSearching() {
        val state = ConnectionState.derive(0, null, SendPhase.Idle, null)
        assertEquals(ConnectionState.Searching, state)
        assertEquals("Looking for devices…", state.title)
        assertEquals("Searching", state.dotLabel)
        assertNull(state.accent)
    }

    @Test
    fun nearbyButNotSendableIsNoPairedDevice() {
        val state = ConnectionState.derive(2, item(sendable = false), SendPhase.Idle, null)
        assertEquals(ConnectionState.NoPairedDevice, state)
        assertEquals("No paired device nearby", state.title)
        assertEquals("Searching", state.dotLabel)
        assertNull(state.accent)
    }
}
