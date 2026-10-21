package com.linkbridge.android.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiveStateTest {
    private val notificationsOff =
        "Notifications are off, so files sent while LinkBridge is in the background are declined."

    @Test
    fun stoppedOffersStart() {
        assertEquals(
            Banner("LinkBridge isn't receiving — tap to start.", BannerAction.START),
            bannerFor(ReceiveState.Stopped, notificationsAllowed = true)
        )
    }

    @Test
    fun failedOffersRetryWithReason() {
        assertEquals(
            Banner("Receiving failed: Address in use", BannerAction.RETRY),
            bannerFor(ReceiveState.Failed("Address in use"), notificationsAllowed = true)
        )
    }

    @Test
    fun listeningWithNotificationsOffAsksToEnableThem() {
        assertEquals(
            Banner(notificationsOff, BannerAction.ENABLE_NOTIFICATIONS),
            bannerFor(ReceiveState.Listening, notificationsAllowed = false)
        )
    }

    @Test
    fun listeningWithNotificationsOnShowsNothing() {
        assertNull(bannerFor(ReceiveState.Listening, notificationsAllowed = true))
    }

    @Test
    fun stoppedOutranksNotificationsOff() {
        assertEquals(BannerAction.START, bannerFor(ReceiveState.Stopped, notificationsAllowed = false)?.action)
    }

    @Test
    fun failedOutranksNotificationsOff() {
        assertEquals(BannerAction.RETRY, bannerFor(ReceiveState.Failed("x"), notificationsAllowed = false)?.action)
    }

    @Test
    fun startingAndReceivingOnlyWarnAboutNotifications() {
        assertNull(bannerFor(ReceiveState.Starting, notificationsAllowed = true))
        assertNull(bannerFor(ReceiveState.Receiving("a.txt", 1, 2), notificationsAllowed = true))
        assertEquals(
            BannerAction.ENABLE_NOTIFICATIONS,
            bannerFor(ReceiveState.Receiving("a.txt", 1, 2), notificationsAllowed = false)?.action
        )
    }
}
