package com.linkbridge.android.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linkbridge.android.LiveRow
import com.linkbridge.android.formatBytes
import com.linkbridge.android.ui.percentText
import com.linkbridge.android.ui.theme.LocalAccents

/**
 * A transfer in progress, pinned above History: Sending (progress), Waiting for you (inline
 * Accept/Decline), Receiving (progress, indeterminate when the total is unknown). Solid.
 */
@Composable
fun LiveTransferRow(row: LiveRow, onResolve: (id: Int, accept: Boolean) -> Unit, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = colors.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (row) {
                is LiveRow.Sending -> {
                    Header(Icons.Filled.ArrowUpward, "Sending", row.fileName, accents.action)
                    Detail(
                        if (row.peerName.isEmpty()) "Sending · ${percentText(row.progress)}"
                        else "Sending to ${row.peerName} · ${percentText(row.progress)}",
                    )
                    LinearProgressIndicator(
                        progress = { row.progress },
                        color = accents.action,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is LiveRow.Waiting -> {
                    val request = row.approval.request
                    Header(Icons.Filled.HourglassTop, "Waiting", request.fileName, accents.attention)
                    Detail("Waiting for you · from ${request.senderName} · ${formatBytes(request.bytes)}")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedButton(onClick = { onResolve(row.approval.id, false) }) { Text("Decline") }
                        Button(onClick = { onResolve(row.approval.id, true) }) { Text("Accept") }
                    }
                }
                is LiveRow.Receiving -> {
                    Header(Icons.Filled.ArrowDownward, "Receiving", row.fileName, accents.signal)
                    if (row.total > 0) {
                        val progress = (row.received.toFloat() / row.total).coerceIn(0f, 1f)
                        Detail("Receiving · ${formatBytes(row.received)} of ${formatBytes(row.total)}")
                        LinearProgressIndicator(
                            progress = { progress },
                            color = accents.signal,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Detail("Receiving · ${formatBytes(row.received)}")
                        LinearProgressIndicator(color = accents.signal, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(icon: ImageVector, iconDescription: String, title: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = iconDescription, tint = tint)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Detail(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
