package com.linkbridge.android.ui

import com.linkbridge.android.PairingStep
import com.linkbridge.android.presentation.BannerAction
import com.linkbridge.android.presentation.DeviceListItem

/** The three tabs of the floating nav bar. Settings is a separate destination, not a tab. */
enum class Tab { DEVICES, SEND, HISTORY }

/** Devices is the start tab; if a sendable device exists, the app opens on Send instead. */
fun startTab(devices: List<DeviceListItem>): Tab =
    if (devices.any { it.isSendable }) Tab.SEND else Tab.DEVICES

/**
 * Whether the pairing sheet may be swiped or backed away. Not while a network step runs
 * (Connecting, Waiting): those close only through their Cancel button.
 */
val PairingStep.isDismissible: Boolean
    get() = this !is PairingStep.Connecting && this !is PairingStep.Waiting

/** What TalkBack reads for a pairing code: each digit on its own ("123 456" → "1 2 3 4 5 6"). */
fun spokenCode(code: String): String = code.filter(Char::isDigit).toList().joinToString(" ")

/** The status banner's button label. */
val BannerAction.label: String
    get() = when (this) {
        BannerAction.START -> "Start"
        BannerAction.RETRY -> "Retry"
        BannerAction.ENABLE_NOTIFICATIONS -> "Turn on"
    }
