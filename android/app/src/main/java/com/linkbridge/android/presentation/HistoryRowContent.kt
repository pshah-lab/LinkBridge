package com.linkbridge.android.presentation

import com.linkbridge.android.TransferHistoryEntry
import com.linkbridge.android.formatBytes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class HistoryDirection { SENT, RECEIVED, FAILED }

private val detailTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
private val dayDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

private fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()

/** Display values for one finished row in History. Mirrors the Mac's `HistoryRowContent`. */
class HistoryRowContent(val entry: TransferHistoryEntry, now: Long, zone: ZoneId) {
    val title: String = entry.fileName
    val detail: String
    val direction: HistoryDirection
    val accent: Accent = if (entry.succeeded) Accent.SIGNAL else Accent.ATTENTION
    val failureReason: String? = if (entry.succeeded) null else entry.detail
    val canRetry: Boolean = entry.isOutgoing && !entry.succeeded && entry.contentUri != null
    val canOpen: Boolean = !entry.isOutgoing && entry.succeeded && entry.contentUri != null
    /** The History section this row belongs to. */
    val day: String = dayLabel(entry.completedAt, now, zone)

    init {
        val peer = if (entry.isOutgoing) "To ${entry.peerName}" else "From ${entry.peerName}"
        val time = detailTime.format(Instant.ofEpochMilli(entry.completedAt).atZone(zone))
        detail = "$peer · ${formatBytes(entry.bytes)} · $time"
        direction = when {
            !entry.succeeded -> HistoryDirection.FAILED
            entry.isOutgoing -> HistoryDirection.SENT
            else -> HistoryDirection.RECEIVED
        }
    }
}

/** "Today", "Yesterday", or the date ("21 Oct") of [completedAt] as seen in [zone]. */
fun dayLabel(completedAt: Long, now: Long, zone: ZoneId): String {
    val day = localDate(completedAt, zone)
    val today = localDate(now, zone)
    return when (day) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> dayDate.format(day)
    }
}
