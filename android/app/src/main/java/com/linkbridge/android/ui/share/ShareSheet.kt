package com.linkbridge.android.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linkbridge.android.ShareStep
import com.linkbridge.android.ShareText
import com.linkbridge.android.ShareUiState
import com.linkbridge.android.presentation.DevicePlatform
import com.linkbridge.android.presentation.DeviceListItem
import com.linkbridge.android.ui.devices.ScreenPreviews
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.LocalAccents

/** What the share sheet can ask for. */
class ShareActions(
    val onSend: (itemId: String) -> Unit,
    /** Hide while sending (the task moves back), otherwise close. Also swipe, back and scrim. */
    val onHide: () -> Unit,
    val onOpenApp: () -> Unit,
)

/** The share target's modal sheet, shown over the sending app's window. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(state: ShareUiState, actions: ShareActions) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = actions.onHide, sheetState = sheetState) {
        ShareSheetContent(state, actions, Modifier.navigationBarsPadding())
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareSheetContent(state: ShareUiState, actions: ShareActions, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            if (state.step == ShareStep.Unsupported) "LinkBridge" else state.header,
            style = MaterialTheme.typography.titleLarge,
            color = colors.onSurface,
            modifier = Modifier.semantics { heading() },
        )
        when (val step = state.step) {
            ShareStep.Unsupported -> Message(ShareText.UNSUPPORTED)
            ShareStep.Choose -> Targets(state, actions)
            is ShareStep.Sending -> Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                Text(step.text, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
                val progress = step.progress
                if (progress == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
            }
            is ShareStep.Done -> Outcome(
                icon = { Icon(Icons.Filled.CheckCircle, null, tint = LocalAccents.current.signal, modifier = Modifier.size(40.dp)) },
                title = step.text,
                message = null,
            )
            is ShareStep.Failed -> Outcome(
                icon = { Icon(Icons.Filled.ErrorOutline, null, tint = LocalAccents.current.attention, modifier = Modifier.size(40.dp)) },
                title = step.title,
                message = step.message,
            )
        }
        val label = when (state.step) {
            is ShareStep.Sending -> "Hide"
            is ShareStep.Done -> "Done"
            ShareStep.Choose -> "Cancel"
            else -> "Close"
        }
        OutlinedButton(onClick = actions.onHide, modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Targets(state: ShareUiState, actions: ShareActions) {
    if (state.targets.isEmpty()) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.hasPaired) {
                // A paired Mac may just not be discovered yet.
                LoadingIndicator()
                Message(LOOKING_TEXT, center = true)
            } else {
                Message(PAIR_FIRST_TEXT, center = true)
            }
            Button(onClick = actions.onOpenApp) { Text("Open LinkBridge") }
        }
        return
    }
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.targets.forEach { item ->
            ListItem(
                headlineContent = { Text(item.name) },
                supportingContent = { Text(item.subtitle) },
                leadingContent = { Icon(Icons.Filled.Laptop, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = colors.surfaceContainerHigh),
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "Send to ${item.name}") { actions.onSend(item.id) },
            )
        }
    }
}

@Composable
private fun Outcome(icon: @Composable () -> Unit, title: String, message: String?) {
    Column(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon()
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        message?.takeIf { it.isNotBlank() }?.let { Message(it, center = true) }
    }
}

@Composable
private fun Message(text: String, center: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
    )
}

private const val PAIR_FIRST_TEXT = "Pair a Mac in LinkBridge first"
private const val LOOKING_TEXT = "Looking for your Mac on this Wi-Fi…"

// ---- Previews ----

private val previewMac = DeviceListItem(
    id = "mac", deviceId = "mac", name = "Studio", platform = DevicePlatform.MAC, isPaired = true,
    isNearby = true, isSendable = true, canPair = false, subtitle = "Paired · Nearby", peer = null,
)
private val noActions = ShareActions({}, {}, {})

@Composable
private fun SharePreview(state: ShareUiState) {
    LinkBridgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) { ShareSheetContent(state, noActions) }
    }
}

@ScreenPreviews
@Composable
private fun ShareChoosePreview() = SharePreview(ShareUiState(3, targets = listOf(previewMac), hasPaired = true))

@ScreenPreviews
@Composable
private fun ShareNoMacPreview() = SharePreview(ShareUiState(1))

@ScreenPreviews
@Composable
private fun ShareSendingPreview() =
    SharePreview(ShareUiState(3, step = ShareStep.Sending(ShareText.sending(2, 3, "photo.jpg"), 0.4f)))

@ScreenPreviews
@Composable
private fun ShareFailedPreview() =
    SharePreview(ShareUiState(3, step = ShareStep.Failed(ShareText.failedTitle(1, 3, "Studio"), "Connection reset")))
