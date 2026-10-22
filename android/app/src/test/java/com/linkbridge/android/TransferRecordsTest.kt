package com.linkbridge.android

import com.linkbridge.android.presentation.ReceiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferRecordsTest {
    private val now = 5_000L

    @Test
    fun listeningAndProgressRecordNoHistory() {
        assertEquals(
            ReceiveUpdate(ReceiveState.Listening),
            TransferRecords.onReceiveEvent(ReceiveEvent.Listening, now)
        )
        assertEquals(
            ReceiveUpdate(ReceiveState.Receiving("a.jpg", 10, 100)),
            TransferRecords.onReceiveEvent(ReceiveEvent.Progress("a.jpg", 10, 100), now)
        )
    }

    @Test
    fun serverFailureIsTheOnlyFailedState() {
        val update = TransferRecords.onReceiveEvent(ReceiveEvent.ServerFailed("port in use"), now)
        assertEquals(ReceiveState.Failed("port in use"), update.state)
        assertNull(update.history)
    }

    @Test
    fun failedTransferIsFailedReceivedHistoryAndKeepsListening() {
        val update = TransferRecords.onReceiveEvent(ReceiveEvent.TransferFailed("a.jpg", "disk full"), now)
        assertEquals(ReceiveState.Listening, update.state)
        val entry = update.history!!
        assertEquals("a.jpg", entry.fileName)
        assertFalse(entry.isOutgoing)
        assertFalse(entry.succeeded)
        assertEquals("disk full", entry.detail)
        assertEquals(now, entry.completedAt)
        assertNull(entry.contentUri)
    }

    @Test
    fun failedTransferWithoutNameUsesUnknownFile() {
        val entry = TransferRecords.onReceiveEvent(ReceiveEvent.TransferFailed(null, "bad header"), now).history!!
        assertEquals("Unknown file", entry.fileName)
    }

    @Test
    fun completedTransferStoresContentUriAndKeepsListening() {
        val transfer = ReceivedTransfer("a.jpg", 2048, "Mac", "content://media/1")
        val update = TransferRecords.onReceiveEvent(ReceiveEvent.Completed(transfer), now)
        assertEquals(ReceiveState.Listening, update.state)
        assertEquals(
            TransferHistoryEntry("a.jpg", 2048, "Mac", isOutgoing = false, completedAt = now, contentUri = "content://media/1"),
            update.history
        )
    }

    @Test
    fun failedSendKeepsUriOnlyWhenPersistable() {
        val kept = TransferRecords.failedSend("a.jpg", 10, "Mac", "refused", "content://x", persistable = true, now = now)
        assertTrue(kept.isOutgoing)
        assertFalse(kept.succeeded)
        assertEquals("refused", kept.detail)
        assertEquals("content://x", kept.contentUri)
        val dropped = TransferRecords.failedSend("a.jpg", 10, "Mac", "refused", "content://x", persistable = false, now = now)
        assertNull(dropped.contentUri)
    }
}
