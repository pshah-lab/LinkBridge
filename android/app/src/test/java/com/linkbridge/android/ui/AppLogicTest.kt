package com.linkbridge.android.ui

import com.linkbridge.android.IncomingTransferRequest
import com.linkbridge.android.PairingStep
import com.linkbridge.android.presentation.BannerAction
import com.linkbridge.android.presentation.DeviceListItem
import com.linkbridge.android.presentation.DevicePlatform
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.SendPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogicTest {
    private fun item(id: String, sendable: Boolean) = DeviceListItem(
        id = id, deviceId = id, name = id, platform = DevicePlatform.MAC, isPaired = sendable,
        isNearby = true, isSendable = sendable, canPair = !sendable, subtitle = "", peer = null
    )

    @Test fun startsOnDevicesWithNoDevices() = assertEquals(Tab.DEVICES, startTab(emptyList()))

    @Test fun startsOnDevicesWhenNothingIsSendable() =
        assertEquals(Tab.DEVICES, startTab(listOf(item("a", sendable = false))))

    @Test fun startsOnSendWhenAnyDeviceIsSendable() =
        assertEquals(Tab.SEND, startTab(listOf(item("a", sendable = false), item("b", sendable = true))))

    @Test fun pairingSheetIsLockedOnlyWhileANetworkStepRuns() {
        assertFalse(PairingStep.Connecting("Studio").isDismissible)
        assertFalse(PairingStep.Waiting("Studio").isDismissible)
        assertTrue(PairingStep.Code("Studio", "123 456").isDismissible)
        assertTrue(PairingStep.Paired("Studio").isDismissible)
        assertTrue(PairingStep.Failed("Studio", "Timed out", "id").isDismissible)
    }

    @Test fun codeIsSpokenDigitByDigit() = assertEquals("1 2 3 4 5 6", spokenCode("123 456"))

    @Test fun bannerLabels() {
        assertEquals("Start", BannerAction.START.label)
        assertEquals("Retry", BannerAction.RETRY.label)
        assertEquals("Turn on", BannerAction.ENABLE_NOTIFICATIONS.label)
    }

    // ---- Send ----

    @Test fun fabPicksWhenIdleSendsWhenReadyOrFailedAndIsInertOtherwise() {
        assertEquals(FabAction.PICK, fabAction(SendPhase.Idle))
        assertEquals(FabAction.SEND, fabAction(SendPhase.Ready("a")))
        assertEquals(FabAction.SEND, fabAction(SendPhase.Failed("a", "x")))
        assertEquals(FabAction.NONE, fabAction(SendPhase.Sending("a", 0.5f)))
        assertEquals(FabAction.NONE, fabAction(SendPhase.Sent("a")))
    }

    @Test fun sendTargetIsTheSelectedSendableDevice() {
        val devices = listOf(item("a", sendable = false), item("b", sendable = true))
        assertEquals("b", sendTarget(devices, "b")?.id)
        assertNull(sendTarget(devices, "a"))
        assertNull(sendTarget(devices, null))
    }

    @Test fun sendScreenShowsWithATargetOrWhileSending() {
        assertTrue(showsSendScreen(item("b", sendable = true), SendPhase.Idle))
        assertTrue(showsSendScreen(null, SendPhase.Sending("a", 0f)))
        assertFalse(showsSendScreen(null, SendPhase.Idle))
        assertFalse(showsSendScreen(null, SendPhase.Failed("a", "x")))
    }

    @Test fun pickZoneCopy() {
        assertEquals("Choose files to send to Studio", pickZoneText(SendPhase.Idle, "Studio"))
        assertEquals("Choose files to send to Studio", pickZoneText(SendPhase.Ready("a"), "Studio"))
        assertEquals("Sending…", pickZoneText(SendPhase.Sending("a", 0.1f), "Studio"))
    }

    @Test fun failureCopy() = assertEquals(
        "Couldn't reach Studio — check it's on the same Wi-Fi, then retry.",
        sendFailureText("Studio"),
    )

    @Test fun percentIsWholeAndClamped() {
        assertEquals("0%", percentText(0f))
        assertEquals("42%", percentText(0.429f))
        assertEquals("100%", percentText(1.5f))
        assertEquals("0%", percentText(-1f))
    }

    // ---- Approvals ----

    @Test fun secondsLeftRoundsUpAndStopsAtZero() {
        assertEquals(42L, secondsLeft(expiresAt = 42_000, now = 0))
        assertEquals(42L, secondsLeft(expiresAt = 42_000, now = 1))
        assertEquals(1L, secondsLeft(expiresAt = 42_000, now = 41_999))
        assertEquals(0L, secondsLeft(expiresAt = 42_000, now = 42_000))
        assertEquals(0L, secondsLeft(expiresAt = 42_000, now = 50_000))
        assertEquals("Declines in 42 s", declinesInText(42))
    }

    @Test fun moreWaitingCountsRequestsBehindTheShownOne() {
        assertNull(moreWaitingText(1))
        assertNull(moreWaitingText(0))
        assertEquals("1 more waiting", moreWaitingText(2))
        assertEquals("2 more waiting", moreWaitingText(3))
    }

    @Test fun approvalSheetWaitsForThePairingSheet() {
        val first = PendingApproval(1, IncomingTransferRequest("Studio", "mac", "a.txt", 1), 10)
        val second = PendingApproval(2, IncomingTransferRequest("Studio", "mac", "b.txt", 1), 20)
        assertEquals(first, approvalToShow(null, listOf(first, second)))
        assertNull(approvalToShow(PairingStep.Code("Studio", "123 456"), listOf(first)))
        assertNull(approvalToShow(null, emptyList()))
    }
}
