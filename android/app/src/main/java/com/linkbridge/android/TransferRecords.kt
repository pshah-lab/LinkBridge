package com.linkbridge.android

import com.linkbridge.android.presentation.ReceiveState

/** A `ReceiveServer` callback, as the service sees it. */
sealed interface ReceiveEvent {
    data object Listening : ReceiveEvent
    data class Progress(val fileName: String, val received: Long, val total: Long) : ReceiveEvent
    /** The server could not start or stopped listening. */
    data class ServerFailed(val reason: String) : ReceiveEvent
    /** One incoming file failed; the server keeps listening. */
    data class TransferFailed(val fileName: String?, val reason: String) : ReceiveEvent
    data class Completed(val transfer: ReceivedTransfer) : ReceiveEvent
}

/** The new receive state, plus the history entry to record, if any. */
data class ReceiveUpdate(val state: ReceiveState, val history: TransferHistoryEntry? = null)

/** Pure mapping from transfer outcomes to `ReceiveState` and history entries. */
object TransferRecords {
    const val UNKNOWN_FILE = "Unknown file"
    const val UNKNOWN_SENDER = "Unknown sender"

    fun onReceiveEvent(event: ReceiveEvent, now: Long): ReceiveUpdate = when (event) {
        ReceiveEvent.Listening -> ReceiveUpdate(ReceiveState.Listening)
        is ReceiveEvent.Progress ->
            ReceiveUpdate(ReceiveState.Receiving(event.fileName, event.received, event.total))
        is ReceiveEvent.ServerFailed -> ReceiveUpdate(ReceiveState.Failed(event.reason))
        // A single failed file is history, not a server failure: keep listening.
        is ReceiveEvent.TransferFailed -> ReceiveUpdate(
            ReceiveState.Listening,
            TransferHistoryEntry(
                fileName = event.fileName ?: UNKNOWN_FILE,
                bytes = 0,
                peerName = UNKNOWN_SENDER,
                isOutgoing = false,
                completedAt = now,
                succeeded = false,
                detail = event.reason
            )
        )
        is ReceiveEvent.Completed -> ReceiveUpdate(
            ReceiveState.Listening,
            TransferHistoryEntry(
                fileName = event.transfer.fileName,
                bytes = event.transfer.bytes,
                peerName = event.transfer.senderName,
                isOutgoing = false,
                completedAt = now,
                contentUri = event.transfer.contentUri
            )
        )
    }

    /** The URI is kept only when the caller holds a persistable read grant, so Retry can reopen it. */
    fun failedSend(
        fileName: String,
        bytes: Long,
        peerName: String,
        message: String,
        uri: String,
        persistable: Boolean,
        now: Long
    ) = TransferHistoryEntry(
        fileName = fileName,
        bytes = bytes,
        peerName = peerName,
        isOutgoing = true,
        completedAt = now,
        succeeded = false,
        detail = message,
        contentUri = if (persistable) uri else null
    )
}
