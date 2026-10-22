package com.linkbridge.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linkbridge.android.IncomingTransferRequest
import com.linkbridge.android.formatBytes
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.ui.declinesInText
import com.linkbridge.android.ui.devices.ScreenPreviews
import com.linkbridge.android.ui.moreWaitingText
import com.linkbridge.android.ui.secondsLeft
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.LocalAccents
import kotlinx.coroutines.delay

/**
 * Modal sheet for the oldest incoming file. Swipe, back and the scrim count as Decline.
 * [queued] is the number of pending requests including this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(approval: PendingApproval, queued: Int, onResolve: (id: Int, accept: Boolean) -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { onResolve(approval.id, false) },
        sheetState = sheetState,
    ) {
        val now by rememberNow()
        ApprovalSheetContent(approval, queued, now, onResolve, Modifier.navigationBarsPadding())
    }
}

/** The wall clock, ticking once a second. */
@Composable
private fun rememberNow(): State<Long> {
    val now = remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // Wake just after the next whole second so the countdown steps evenly.
            delay(1_000 - System.currentTimeMillis() % 1_000 + 5)
            now.longValue = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun ApprovalSheetContent(
    approval: PendingApproval,
    queued: Int,
    now: Long,
    onResolve: (id: Int, accept: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val request = approval.request
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Filled.Download,
            contentDescription = null,
            tint = LocalAccents.current.action,
            modifier = Modifier.size(48.dp),
        )
        Text(
            "${request.senderName} wants to send you a file",
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "${request.fileName} · ${formatBytes(request.bytes)}",
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            declinesInText(secondsLeft(approval.expiresAt, now)),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
        )
        moreWaitingText(queued)?.let {
            Text(it, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            OutlinedButton(onClick = { onResolve(approval.id, false) }) { Text("Decline") }
            Button(onClick = { onResolve(approval.id, true) }) { Text("Accept") }
        }
    }
}

// ---- Previews ----

private const val PREVIEW_NOW = 1_792_000_000_000L
private val previewApproval = PendingApproval(
    7, IncomingTransferRequest("Studio", "mac", "Holiday photos.zip", 48_000_000), PREVIEW_NOW + 42_000,
)

@Composable
private fun ApprovalPreview(queued: Int) {
    LinkBridgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ApprovalSheetContent(previewApproval, queued, PREVIEW_NOW, { _, _ -> })
        }
    }
}

@ScreenPreviews
@Composable
private fun ApprovalOnePreview() = ApprovalPreview(queued = 1)

@ScreenPreviews
@Composable
private fun ApprovalThreePreview() = ApprovalPreview(queued = 3)
