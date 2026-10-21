package com.linkbridge.android.ui

import com.linkbridge.android.PairingStep
import com.linkbridge.android.presentation.BannerAction
import com.linkbridge.android.presentation.DeviceListItem
import com.linkbridge.android.presentation.DevicePlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
