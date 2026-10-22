package com.linkbridge.android

import com.linkbridge.android.presentation.Banner
import com.linkbridge.android.presentation.ConnectionState
import com.linkbridge.android.presentation.DeviceListItem
import com.linkbridge.android.presentation.HistoryRowContent
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.SendPhase

/**
 * A file picked for sending. [uri] is a string so the view model stays testable on the JVM;
 * it becomes an `android.net.Uri` only at the hub boundary. [persistable] means the app took a
 * persistable read grant, so a failed send can keep the URI for Retry.
 */
data class StagedFile(val uri: String, val name: String, val size: Long, val persistable: Boolean)

/** The pairing sheet's state. [name] is the Mac's name. */
sealed interface PairingStep {
    val name: String

    data class Connecting(override val name: String) : PairingStep
    data class Code(override val name: String, val code: String) : PairingStep
    data class Waiting(override val name: String) : PairingStep
    data class Paired(override val name: String) : PairingStep
    /** [itemId] is the Devices row, so Try again can call `pair(itemId)`. */
    data class Failed(override val name: String, val message: String, val itemId: String) : PairingStep
}

/** A transfer in progress, pinned above History. */
sealed interface LiveRow {
    data class Sending(val fileName: String, val progress: Float, val peerName: String) : LiveRow
    data class Waiting(val approval: PendingApproval) : LiveRow
    /** [total] is 0 or less when unknown. */
    data class Receiving(val fileName: String, val received: Long, val total: Long) : LiveRow
}

/** One day of finished transfers: "Today", "Yesterday" or a date. */
data class HistorySection(val label: String, val rows: List<HistoryRowContent>)

data class UiState(
    val devices: List<DeviceListItem> = emptyList(),
    val selectedId: String? = null,
    val connection: ConnectionState = ConnectionState.Searching,
    val sendPhase: SendPhase = SendPhase.Idle,
    val stagedFile: StagedFile? = null,
    val live: List<LiveRow> = emptyList(),
    val history: List<HistorySection> = emptyList(),
    val approvals: List<PendingApproval> = emptyList(),
    val banner: Banner? = null,
    val pairing: PairingStep? = null,
    /** Null until the identity store has loaded off the main thread. */
    val thisDevice: DeviceIdentity? = null,
    val trusted: List<TrustedPeer> = emptyList(),
    val receivingOn: Boolean = true,
    /** Whether the app may post notifications (always true below API 33). */
    val notificationsOn: Boolean = true,
    /** A one-off message for a snackbar; the UI calls `clearNotice()` once shown. */
    val notice: String? = null
)
