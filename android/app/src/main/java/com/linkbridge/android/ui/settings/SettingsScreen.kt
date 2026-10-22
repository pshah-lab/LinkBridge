package com.linkbridge.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.linkbridge.android.DeviceIdentity
import com.linkbridge.android.TrustedPeer
import com.linkbridge.android.UiState
import com.linkbridge.android.presentation.DevicePlatform
import com.linkbridge.android.ui.devices.ScreenPreviews
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.backdrop
import kotlinx.coroutines.launch

/** What Settings can ask for. */
class SettingsActions(
    val onBack: () -> Unit = {},
    val onReceiving: (on: Boolean) -> Unit = {},
    /** The notification permission flow: ask, or open app settings once permanently denied. */
    val onEnableNotifications: () -> Unit = {},
    val onUnpair: (deviceId: String) -> Unit = {},
)

/** Unpair confirmation, per the spec. */
internal fun unpairQuestion(name: String): String = "Unpair $name? You'll need to pair again to send files."

/**
 * Full-screen Settings: this phone, receiving, notifications, paired devices and the
 * version. Rows are solid; only the sheets' scrims are glass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: UiState, actions: SettingsActions, versionName: String, modifier: Modifier = Modifier) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var unpairing by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.backdrop(),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = actions.onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        SettingsContent(
            state = state,
            versionName = versionName,
            contentPadding = padding,
            onCopyId = { id ->
                copyToClipboard(context, id)
                scope.launch { snackbar.showSnackbar("Device ID copied") }
            },
            onReceiving = actions.onReceiving,
            onEnableNotifications = actions.onEnableNotifications,
            onUnpair = { unpairing = it },
        )
    }

    val peer = unpairing?.let { id -> state.trusted.firstOrNull { it.deviceId == id } }
    if (peer != null) {
        UnpairDialog(
            name = peer.deviceName,
            onConfirm = {
                unpairing = null
                actions.onUnpair(peer.deviceId)
            },
            onDismiss = { unpairing = null },
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("LinkBridge device ID", text))
}

@Composable
private fun SettingsContent(
    state: UiState,
    versionName: String,
    contentPadding: PaddingValues,
    onCopyId: (String) -> Unit,
    onReceiving: (Boolean) -> Unit,
    onEnableNotifications: () -> Unit,
    onUnpair: (deviceId: String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "phone") {
            Section("This phone") {
                ThisPhoneRow(state.thisDevice, onCopyId)
            }
        }
        item(key = "receiving") {
            Section(null) {
                ListItem(
                    headlineContent = { Text("Receiving") },
                    supportingContent = { Text("Files are saved to Downloads/LinkBridge.") },
                    trailingContent = { Switch(checked = state.receivingOn, onCheckedChange = null) },
                    colors = rowColors(),
                    modifier = Modifier.toggleable(
                        value = state.receivingOn,
                        role = Role.Switch,
                        onValueChange = onReceiving,
                    ),
                )
                HorizontalDivider(color = colors.outlineVariant)
                ListItem(
                    headlineContent = { Text("Notifications") },
                    supportingContent = {
                        Text(
                            if (state.notificationsOn) {
                                "On"
                            } else {
                                "Off. Files sent while LinkBridge is in the background are declined."
                            },
                        )
                    },
                    trailingContent = if (state.notificationsOn) {
                        null
                    } else {
                        { TextButton(onClick = onEnableNotifications) { Text("Turn on") } }
                    },
                    colors = rowColors(),
                )
            }
        }
        item(key = "paired") {
            Section("Paired devices") {
                if (state.trusted.isEmpty()) {
                    ListItem(
                        headlineContent = { Text("No paired devices yet.", color = colors.onSurfaceVariant) },
                        colors = rowColors(),
                    )
                }
                state.trusted.forEachIndexed { index, peer ->
                    if (index > 0) HorizontalDivider(color = colors.outlineVariant)
                    PairedRow(peer, onUnpair = { onUnpair(peer.deviceId) })
                }
            }
        }
        item(key = "about") {
            Section("About") {
                ListItem(
                    headlineContent = { Text("LinkBridge") },
                    supportingContent = { Text("Version $versionName") },
                    colors = rowColors(),
                )
            }
        }
    }
}

@Composable
private fun rowColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)

/** A titled group of rows on one solid rounded card. */
@Composable
private fun Section(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
            )
        }
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column { content() }
        }
    }
}

@Composable
private fun ThisPhoneRow(device: DeviceIdentity?, onCopyId: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(device?.deviceName ?: "…") },
        supportingContent = {
            Text(device?.deviceId ?: "", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        },
        trailingContent = device?.let {
            {
                TextButton(onClick = { onCopyId(it.deviceId) }) { Text("Copy") }
            }
        },
        colors = rowColors(),
    )
}

@Composable
private fun PairedRow(peer: TrustedPeer, onUnpair: () -> Unit) {
    val platform = when (DevicePlatform.from(peer.platform)) {
        DevicePlatform.MAC -> "Mac"
        DevicePlatform.ANDROID -> "Android"
        DevicePlatform.IPHONE -> "iPhone"
        DevicePlatform.UNKNOWN -> null
    }
    ListItem(
        headlineContent = { Text(peer.deviceName) },
        supportingContent = platform?.let { { Text(it) } },
        trailingContent = {
            TextButton(onClick = onUnpair) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
        },
        colors = rowColors(),
    )
}

/** A confirmation dialog in the M3 alert style. */
@Composable
private fun UnpairDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(
                    unpairQuestion(name),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onConfirm) { Text("Unpair", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

// ---- Previews ----

@ScreenPreviews
@Composable
private fun SettingsPreview() {
    LinkBridgeTheme(dynamicColor = false) {
        SettingsScreen(
            UiState(
                thisDevice = DeviceIdentity(deviceId = "11111111-2222-3333-4444-555555555555", deviceName = "Pixel 9"),
                trusted = listOf(
                    TrustedPeer("mac", "Studio", "macos", "192.168.1.5", 52000, authToken = null),
                ),
                notificationsOn = false,
            ),
            SettingsActions(),
            versionName = "0.2.0",
        )
    }
}

@ScreenPreviews
@Composable
private fun UnpairDialogPreview() {
    LinkBridgeTheme(dynamicColor = false) {
        UnpairDialog("Studio", {}, {})
    }
}
