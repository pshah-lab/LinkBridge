package com.linkbridge.android.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.linkbridge.android.HistorySection
import com.linkbridge.android.IncomingTransferRequest
import com.linkbridge.android.LiveRow
import com.linkbridge.android.TransferHistoryEntry
import com.linkbridge.android.UiState
import com.linkbridge.android.presentation.HistoryRowContent
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.ui.HISTORY_EMPTY_TEXT
import com.linkbridge.android.ui.components.EmptyState
import com.linkbridge.android.ui.devices.ScreenPreviews
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.backdrop
import java.time.ZoneId

/** What the History screen can ask for. */
class HistoryActions(
    val onResolve: (id: Int, accept: Boolean) -> Unit,
    val onOpen: (TransferHistoryEntry) -> Unit,
    val onRetry: (TransferHistoryEntry) -> Unit,
)

/** Live transfers pinned first, then finished ones by day; an empty state when there are none. */
@Composable
fun HistoryScreen(
    state: UiState,
    actions: HistoryActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
            end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "title") { Heading("History", MaterialTheme.typography.headlineMedium) }
        if (state.live.isEmpty() && state.history.isEmpty()) {
            item(key = "empty") { EmptyState(HISTORY_EMPTY_TEXT) }
            return@LazyColumn
        }
        itemsIndexed(state.live, key = { index, row -> liveKey(index, row) }) { _, row ->
            LiveTransferRow(row, actions.onResolve)
        }
        state.history.forEach { section ->
            item(key = "day-${section.label}") { Heading(section.label, MaterialTheme.typography.titleSmall) }
            // No keys: two entries may share every field, and duplicate keys crash a LazyColumn.
            items(section.rows) { row ->
                HistoryRow(row, onOpen = { actions.onOpen(row.entry) }, onRetry = { actions.onRetry(row.entry) })
            }
        }
    }
}

private fun liveKey(index: Int, row: LiveRow): String = when (row) {
    is LiveRow.Sending -> "live-sending"
    is LiveRow.Waiting -> "live-waiting-${row.approval.id}"
    is LiveRow.Receiving -> "live-receiving-$index"
}

@Composable
private fun Heading(text: String, style: TextStyle) {
    Text(
        text,
        style = style,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).semantics { heading() },
    )
}

// ---- Previews ----

private val previewActions = HistoryActions({ _, _ -> }, {}, {})
private const val PREVIEW_NOW = 1_792_000_000_000L
private val previewZone = ZoneId.of("UTC")

private fun row(entry: TransferHistoryEntry) = HistoryRowContent(entry, PREVIEW_NOW, previewZone)

private val received = TransferHistoryEntry(
    "Scan.pdf", 820_000, "Studio", isOutgoing = false, completedAt = PREVIEW_NOW - 60_000,
    contentUri = "content://preview/scan",
)
private val sent = TransferHistoryEntry("Notes.txt", 4_200, "Studio", isOutgoing = true, completedAt = PREVIEW_NOW - 3_600_000)
private val failed = TransferHistoryEntry(
    "Holiday.mov", 512_000_000, "Studio", isOutgoing = true, completedAt = PREVIEW_NOW - 90_000_000,
    succeeded = false, detail = "Connection reset", contentUri = "content://preview/holiday",
)

@Composable
private fun HistoryPreview(state: UiState) {
    LinkBridgeTheme(dynamicColor = false) {
        HistoryScreen(state, previewActions, modifier = Modifier.backdrop())
    }
}

@ScreenPreviews
@Composable
private fun HistoryEmptyPreview() = HistoryPreview(UiState())

@ScreenPreviews
@Composable
private fun HistoryMixedPreview() = HistoryPreview(
    UiState(
        live = listOf(
            LiveRow.Sending("Report.pdf", 0.42f, "Studio"),
            LiveRow.Waiting(
                PendingApproval(1, IncomingTransferRequest("Studio", "mac", "Photos.zip", 48_000_000), PREVIEW_NOW + 42_000),
            ),
            LiveRow.Receiving("Draft.key", 1_000_000, 0),
        ),
        history = listOf(
            HistorySection("Today", listOf(row(received), row(sent))),
            HistorySection("Yesterday", listOf(row(failed))),
        ),
    ),
)

@ScreenPreviews
@Composable
private fun HistoryFailedPreview() = HistoryPreview(
    UiState(history = listOf(HistorySection("Yesterday", listOf(row(failed))))),
)
