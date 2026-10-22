package com.linkbridge.android.presentation

import com.linkbridge.android.TransferHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryRowContentTest {
    private val zone: ZoneId = ZoneOffset.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private val completed = at(2026, 10, 21, 14, 5)
    private val now = at(2026, 10, 21, 18, 0)

    private fun entry(
        outgoing: Boolean, succeeded: Boolean = true, uri: String? = "content://x/1", detail: String? = null
    ) = TransferHistoryEntry("report.pdf", 2_200_000, "Mac mini", outgoing, completed, succeeded, detail, uri)

    @Test
    fun sentRowDetail() {
        val row = HistoryRowContent(entry(outgoing = true), now, zone)
        assertEquals("report.pdf", row.title)
        assertEquals("To Mac mini · 2.1 MB · 21 Oct, 14:05", row.detail)
        assertEquals(HistoryDirection.SENT, row.direction)
        assertEquals(Accent.SIGNAL, row.accent)
        assertNull(row.failureReason)
    }

    @Test
    fun receivedRowDetail() {
        val row = HistoryRowContent(entry(outgoing = false), now, zone)
        assertEquals("From Mac mini · 2.1 MB · 21 Oct, 14:05", row.detail)
        assertEquals(HistoryDirection.RECEIVED, row.direction)
    }

    @Test
    fun failedRowShowsReason() {
        val row = HistoryRowContent(entry(outgoing = true, succeeded = false, detail = "Connection lost"), now, zone)
        assertEquals(HistoryDirection.FAILED, row.direction)
        assertEquals(Accent.ATTENTION, row.accent)
        assertEquals("Connection lost", row.failureReason)
    }

    @Test
    fun canRetryOnlyFailedSendsWithUri() {
        assertTrue(HistoryRowContent(entry(true, succeeded = false), now, zone).canRetry)
        assertFalse(HistoryRowContent(entry(true, succeeded = false, uri = null), now, zone).canRetry)
        assertFalse(HistoryRowContent(entry(true, succeeded = true), now, zone).canRetry)
        assertFalse(HistoryRowContent(entry(false, succeeded = false), now, zone).canRetry)
    }

    @Test
    fun canOpenOnlySuccessfulReceivesWithUri() {
        assertTrue(HistoryRowContent(entry(false), now, zone).canOpen)
        assertFalse(HistoryRowContent(entry(false, uri = null), now, zone).canOpen)
        assertFalse(HistoryRowContent(entry(false, succeeded = false), now, zone).canOpen)
        assertFalse(HistoryRowContent(entry(true), now, zone).canOpen)
    }

    @Test
    fun keepsItsEntry() {
        val e = entry(true)
        assertEquals(e, HistoryRowContent(e, now, zone).entry)
    }

    @Test
    fun dayLabelBoundaries() {
        assertEquals("Today", dayLabel(at(2026, 10, 21, 0, 0), now, zone))
        assertEquals("Yesterday", dayLabel(at(2026, 10, 20, 23, 59), now, zone))
        assertEquals("Yesterday", dayLabel(at(2026, 10, 20, 0, 0), now, zone))
        assertEquals("19 Oct", dayLabel(at(2026, 10, 19, 23, 59), now, zone))
        assertEquals("1 Jan", dayLabel(at(2026, 1, 1, 9, 0), at(2026, 1, 3, 9, 0), zone))
    }

    @Test
    fun dayLabelUsesTheZone() {
        // 20 Oct 23:00 UTC is 21 Oct 08:00 in Tokyo; 21 Oct 12:00 UTC is 21 Oct 21:00 there.
        val tokyo = ZoneId.of("Asia/Tokyo")
        val noon = at(2026, 10, 21, 12, 0)
        assertEquals("Today", dayLabel(at(2026, 10, 20, 23, 0), noon, tokyo))
        assertEquals("Yesterday", dayLabel(at(2026, 10, 20, 23, 0), noon, zone))
    }
}
