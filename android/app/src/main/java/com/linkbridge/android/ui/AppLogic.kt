package com.linkbridge.android.ui

import com.linkbridge.android.PairingStep
import com.linkbridge.android.presentation.BannerAction
import com.linkbridge.android.presentation.DeviceListItem
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.SendPhase

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

// ---- Send ----

/** What tapping the Send FAB does in each phase. */
enum class FabAction { PICK, SEND, NONE }

fun fabAction(phase: SendPhase): FabAction = when (phase) {
    SendPhase.Idle -> FabAction.PICK
    is SendPhase.Ready, is SendPhase.Failed -> FabAction.SEND
    is SendPhase.Sending, is SendPhase.Sent -> FabAction.NONE
}

/** The device Send sends to: the selected device, if it is sendable. */
fun sendTarget(devices: List<DeviceListItem>, selectedId: String?): DeviceListItem? =
    devices.firstOrNull { it.id == selectedId && it.isSendable }

/** Send shows its screen (not the "Pair a Mac" empty state) with a target or while a send runs. */
fun showsSendScreen(target: DeviceListItem?, phase: SendPhase): Boolean =
    target != null || phase is SendPhase.Sending

const val NO_TARGET_TEXT = "Pair a Mac to send files"
const val NO_TARGET_ACTION = "Go to Devices"

/** The pick zone's label: "Sending…" while a send runs, else who the file goes to. */
fun pickZoneText(phase: SendPhase, macName: String): String =
    if (phase is SendPhase.Sending) "Sending…" else "Choose a file to send to $macName"

/** Shown under the pick zone after a failed send. */
fun sendFailureText(macName: String): String =
    "Couldn't reach $macName — check it's on the same Wi-Fi, then retry."

/** Whole percent of [progress] (0..1), e.g. "42%". */
fun percentText(progress: Float): String = "${(progress.coerceIn(0f, 1f) * 100).toInt()}%"

// ---- Approvals ----

/** Whole seconds until [expiresAt], rounded up so "0 s" shows only at the deadline. */
fun secondsLeft(expiresAt: Long, now: Long): Long =
    if (expiresAt <= now) 0 else (expiresAt - now + 999) / 1000

fun declinesInText(seconds: Long): String = "Declines in $seconds s"

/** "N more waiting" for the requests queued behind the shown one, or null when none. */
fun moreWaitingText(queued: Int): String? = if (queued > 1) "${queued - 1} more waiting" else null

/** The approval sheet shows the oldest request, but never on top of the pairing sheet. */
fun approvalToShow(pairing: PairingStep?, approvals: List<PendingApproval>): PendingApproval? =
    if (pairing != null) null else approvals.firstOrNull()

// ---- History ----

const val HISTORY_EMPTY_TEXT = "Files you send and receive will appear here."
