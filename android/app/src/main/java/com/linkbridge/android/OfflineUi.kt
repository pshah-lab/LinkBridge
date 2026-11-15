package com.linkbridge.android

import com.linkbridge.android.offline.HotspotFailure

const val OFFLINE_CARD_TEXT = "No Wi-Fi? Connect directly"
const val OFFLINE_CARD_UNSUPPORTED_TEXT = "Needs Android 13 or later"
const val OFFLINE_CARD_ON_TEXT = "Offline link is on"
const val OFFLINE_PERMISSION_MESSAGE = "LinkBridge needs Nearby devices to start the link"
const val OFFLINE_STOPPED_MESSAGE = "Offline link stopped"
const val OFFLINE_HOTSPOT_BUSY_MESSAGE = "Couldn't start offline link: turn off Mobile Hotspot and try again"

/** What the Devices card and the Offline link screen show. */
data class OfflineUi(
    val cardVisible: Boolean = false,
    val cardText: String = OFFLINE_CARD_TEXT,
    /** False when the phone can't do it (Android 12 and below). */
    val cardEnabled: Boolean = true,
    /** The link is starting or on, so the card reopens its screen instead of starting it. */
    val on: Boolean = false,
    /** Non-null while the link is starting or on and the screen is open. */
    val screen: OfflineScreenUi? = null,
    /** A failure or "stopped" notice for the card. */
    val message: String? = null,
    /** The message needs the app's system settings (Nearby devices permission). */
    val openSettings: Boolean = false,
)

/** [qrText], [ssid], [passphrase] and [address] are null until the hotspot is up and its address is known. */
data class OfflineScreenUi(
    val qrText: String?,
    val ssid: String?,
    val passphrase: String?,
    val address: String?,
    val minutesLeftLabel: String,
)

fun hotspotFailureMessage(reason: HotspotFailure): String = when (reason) {
    HotspotFailure.NO_CHANNEL -> "Couldn't start offline link: no free Wi-Fi channel. Try again in a moment"
    HotspotFailure.TETHERING_ON, HotspotFailure.INCOMPATIBLE_MODE -> OFFLINE_HOTSPOT_BUSY_MESSAGE
    HotspotFailure.NOT_ALLOWED -> "Couldn't start offline link: this phone doesn't allow it"
    HotspotFailure.PERMISSION_DENIED -> OFFLINE_PERMISSION_MESSAGE
    HotspotFailure.UNSUPPORTED_OS -> OFFLINE_CARD_UNSUPPORTED_TEXT
    HotspotFailure.GENERIC -> "Couldn't start offline link. Try again"
}

internal fun minutesLeftLabel(deadline: Long?, now: Long): String {
    // No deadline while Active means the idle timer is paused for a transfer, approval or pairing.
    if (deadline == null) return "Transfer in progress"
    val minutes = (((deadline - now).coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
    return "Stops in $minutes min if idle"
}
