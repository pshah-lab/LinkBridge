package com.linkbridge.android.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.linkbridge.android.presentation.HistoryDirection
import com.linkbridge.android.presentation.HistoryRowContent
import com.linkbridge.android.ui.theme.LocalAccents

/**
 * One finished transfer: direction icon (↑ sent, ↓ received, ! failed), title, detail, the
 * failure reason on failed rows, and Open (received) or Retry (failed send). Solid.
 */
@Composable
fun HistoryRow(
    row: HistoryRowContent,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAccents.current
    val colors = MaterialTheme.colorScheme
    val (icon, spoken, tint) = when (row.direction) {
        HistoryDirection.SENT -> Triple(Icons.Filled.ArrowUpward, "Sent", accents.action)
        HistoryDirection.RECEIVED -> Triple(Icons.Filled.ArrowDownward, "Received", accents.signal)
        HistoryDirection.FAILED -> Triple(Icons.Filled.PriorityHigh, "Failed", accents.attention)
    }
    ListItem(
        modifier = modifier.clip(RoundedCornerShape(20.dp)),
        colors = ListItemDefaults.colors(containerColor = colors.surfaceContainerLow),
        leadingContent = { Icon(icon, contentDescription = spoken, tint = tint) },
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(row.detail)
                row.failureReason?.let { Text(it, color = colors.onSurface) }
            }
        },
        trailingContent = when {
            row.canOpen -> {
                { TextButton(onClick = onOpen) { Text("Open") } }
            }
            row.canRetry -> {
                { TextButton(onClick = onRetry) { Text("Retry") } }
            }
            else -> null
        },
    )
}
