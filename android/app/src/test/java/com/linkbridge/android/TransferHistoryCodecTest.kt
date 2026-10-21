package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferHistoryCodecTest {
    @Test
    fun oldFormatLoadsWithDefaults() {
        val raw = """[{"fileName":"a.pdf","bytes":2100000,"peerName":"Mac mini","isOutgoing":true,"completedAt":1700000000000}]"""
        val entry = TransferHistoryCodec.decode(raw).single()
        assertEquals(TransferHistoryEntry("a.pdf", 2_100_000, "Mac mini", true, 1_700_000_000_000), entry)
        assertTrue(entry.succeeded)
        assertNull(entry.detail)
        assertNull(entry.contentUri)
    }

    @Test
    fun roundTripsNewFields() {
        val entries = listOf(
            TransferHistoryEntry("a.pdf", 10, "Mac mini", true, 1L, succeeded = false, detail = "Connection lost", contentUri = "content://x/1"),
            TransferHistoryEntry("b.jpg", 20, "Mac mini", false, 2L)
        )
        assertEquals(entries, TransferHistoryCodec.decode(TransferHistoryCodec.encode(entries)))
    }

    @Test
    fun corruptJsonGivesEmptyList() {
        assertEquals(emptyList<TransferHistoryEntry>(), TransferHistoryCodec.decode("not json"))
        assertEquals(emptyList<TransferHistoryEntry>(), TransferHistoryCodec.decode(null))
    }
}
