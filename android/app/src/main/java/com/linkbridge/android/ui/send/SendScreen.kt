package com.linkbridge.android.ui.send

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linkbridge.android.LiveRow
import com.linkbridge.android.SendBatch
import com.linkbridge.android.StagedFile
import com.linkbridge.android.UiState
import com.linkbridge.android.formatBytes
import com.linkbridge.android.presentation.SendPhase
import com.linkbridge.android.ui.NO_TARGET_ACTION
import com.linkbridge.android.ui.NO_TARGET_TEXT
import com.linkbridge.android.ui.components.EmptyState
import com.linkbridge.android.ui.devices.PreviewData
import com.linkbridge.android.ui.devices.ScreenPreviews
import com.linkbridge.android.ui.pickZoneText
import com.linkbridge.android.ui.sendFailureText
import com.linkbridge.android.ui.sendTarget
import com.linkbridge.android.ui.showsSendScreen
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.LocalAccents
import com.linkbridge.android.ui.theme.backdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Send screen can ask for. */
class SendActions(
    /** Opens the system file picker. */
    val onPick: () -> Unit,
    /** Sends the staged file, or retries the failed one. */
    val onSend: () -> Unit,
    val onGoToDevices: () -> Unit,
)

/**
 * The pick zone (solid), the staged file, a failure message, and the glass Send FAB. With no
 * sendable target, an empty state pointing to Devices instead, and no FAB.
 */
@Composable
fun SendScreen(
    state: UiState,
    actions: SendActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    val target = sendTarget(state.devices, state.selectedId)
    val phase = state.sendPhase
    if (!showsSendScreen(target, phase)) {
        Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
            EmptyState(NO_TARGET_TEXT, action = { Button(onClick = actions.onGoToDevices) { Text(NO_TARGET_ACTION) } })
        }
        return
    }
    val macName = target?.name ?: state.live.firstNotNullOfOrNull { (it as? LiveRow.Sending)?.peerName }
        ?.takeIf { it.isNotEmpty() } ?: "your Mac"
    val layoutDirection = LocalLayoutDirection.current
    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
                    end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    top = contentPadding.calculateTopPadding() + 8.dp,
                    // Room for the FAB above the nav bar.
                    bottom = contentPadding.calculateBottomPadding() + 104.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Send",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).semantics { heading() },
            )
            PickZone(
                text = pickZoneText(phase, macName),
                staged = state.stagedFiles.takeIf { it.isNotEmpty() && (phase is SendPhase.Ready || phase is SendPhase.Failed) }
                    ?.let(SendBatch::summary),
                enabled = phase !is SendPhase.Sending,
                onClick = actions.onPick,
            )
            if (phase is SendPhase.Failed) {
                Text(
                    sendFailureText(macName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        SendFab(
            phase = phase,
            targetSendable = target != null,
            onPick = actions.onPick,
            onSend = actions.onSend,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                    start = 16.dp,
                ),
        )
    }
}

/** The large solid target for choosing a file; shows the staged file's name and size. */
@Composable
private fun PickZone(text: String, staged: StagedFile?, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = colors.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Icon(
                if (staged != null) Icons.AutoMirrored.Filled.InsertDriveFile else Icons.Filled.UploadFile,
                contentDescription = null,
                tint = if (enabled) LocalAccents.current.action else colors.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (staged != null) {
                Text(
                    if (staged.size >= 0) "${staged.name} · ${formatBytes(staged.size)}" else staged.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The system file picker, for up to [SendBatch.MAX_FILES] files. Each picked file gets a
 * persistable read grant when the provider allows it (so a failed send can be retried later),
 * then its name and size are read and the list is handed to [onPicked].
 */
@Composable
fun rememberFilePicker(onPicked: (List<StagedFile>) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.take(SendBatch.MAX_FILES).map { stagedFile(context, it) }
            }
            latest(files)
        }
    }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}

private fun stagedFile(context: Context, uri: Uri): StagedFile {
    val resolver = context.contentResolver
    val persistable = try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }
    var name: String? = null
    var size = -1L
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !c.isNull(nameIndex)) name = c.getString(nameIndex)
                if (sizeIndex >= 0 && !c.isNull(sizeIndex)) size = c.getLong(sizeIndex)
            }
        }
    }
    return StagedFile(
        uri = uri.toString(),
        name = name ?: uri.lastPathSegment ?: "File",
        size = size,
        persistable = persistable,
    )
}

// ---- Previews ----

private val previewActions = SendActions({}, {}, {})
private val previewFile = StagedFile("content://preview/report", "Report.pdf", 2_400_000, persistable = true)

@Composable
private fun SendPreview(state: UiState) {
    LinkBridgeTheme(dynamicColor = false) {
        SendScreen(state, previewActions, modifier = Modifier.backdrop())
    }
}

@ScreenPreviews
@Composable
private fun SendNoTargetPreview() = SendPreview(PreviewData.empty)

@ScreenPreviews
@Composable
private fun SendIdlePreview() = SendPreview(PreviewData.devices)

@ScreenPreviews
@Composable
private fun SendReadyPreview() =
    SendPreview(PreviewData.devices.copy(sendPhase = SendPhase.Ready("Report.pdf"), stagedFiles = listOf(previewFile)))

@ScreenPreviews
@Composable
private fun SendSendingPreview() =
    SendPreview(PreviewData.devices.copy(sendPhase = SendPhase.Sending("Report.pdf", 0.42f), stagedFiles = listOf(previewFile)))

@ScreenPreviews
@Composable
private fun SendSentPreview() = SendPreview(PreviewData.devices.copy(sendPhase = SendPhase.Sent("Report.pdf")))

@ScreenPreviews
@Composable
private fun SendFailedPreview() = SendPreview(
    PreviewData.devices.copy(sendPhase = SendPhase.Failed("Report.pdf", "Connection refused"), stagedFiles = listOf(previewFile)),
)
