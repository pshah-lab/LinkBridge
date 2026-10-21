package com.linkbridge.android.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the Mac "Send phase" checks (macos/Sources/LinkBridgeCoreChecks). */
class SendPhaseTest {
    @Test
    fun idleAsksForAFile() {
        assertEquals("Choose a file", SendPhase.Idle.actionTitle)
        assertFalse(SendPhase.Idle.isEnabled(targetSendable = true))
    }

    @Test
    fun readySendsOnlyToASendableTarget() {
        val ready = SendPhase.Ready("a.txt")
        assertEquals("Send", ready.actionTitle)
        assertTrue(ready.isEnabled(targetSendable = true))
        assertFalse(ready.isEnabled(targetSendable = false))
    }

    @Test
    fun sendingAndSentAreDisabled() {
        val sending = SendPhase.Sending("a", 0.5f)
        val sent = SendPhase.Sent("a")
        assertEquals("Sending…", sending.actionTitle)
        assertEquals("Sent", sent.actionTitle)
        assertFalse(sending.isEnabled(targetSendable = true))
        assertFalse(sent.isEnabled(targetSendable = true))
    }

    @Test
    fun failedOffersRetry() {
        val failed = SendPhase.Failed("a", "x")
        assertEquals("Retry", failed.actionTitle)
        assertTrue(failed.isEnabled(targetSendable = true))
        assertFalse(failed.isEnabled(targetSendable = false))
    }
}
