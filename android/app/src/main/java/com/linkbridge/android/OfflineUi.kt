package com.linkbridge.android

import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.offline.HotspotFailure
import com.linkbridge.android.offline.IDLE_TIMEOUT_MS

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

/**
 * Whether the hotspot's idle timer should be paused: a transfer in either direction, an approval
 * prompt waiting for an answer, or pairing in progress.
 */
internal fun isOfflineBusy(state: UiState, hubSend: SendState?): Boolean =
    state.live.isNotEmpty() ||
        hubSend is SendState.Progress ||
        state.pairing.let { it is PairingStep.Connecting || it is PairingStep.Code || it is PairingStep.Waiting }

internal fun minutesLeftLabel(busy: Boolean, idleForMs: Long): String {
    if (busy) return "Transfer in progress"
    val left = (IDLE_TIMEOUT_MS - idleForMs).coerceAtLeast(0)
    val minutes = ((left + 59_999) / 60_000).coerceAtLeast(1)
    return "Stops in $minutes min if idle"
}
