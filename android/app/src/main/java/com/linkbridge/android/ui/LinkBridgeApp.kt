package com.linkbridge.android.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linkbridge.android.BuildConfig
import com.linkbridge.android.MainViewModel
import com.linkbridge.android.PairingStep
import com.linkbridge.android.TransferHistoryEntry
import com.linkbridge.android.UiState
import com.linkbridge.android.presentation.BannerAction
import com.linkbridge.android.ui.components.ApprovalSheet
import com.linkbridge.android.ui.components.ConnectionChip
import com.linkbridge.android.ui.components.GlassNavBar
import com.linkbridge.android.ui.devices.DevicesActions
import com.linkbridge.android.ui.devices.DevicesScreen
import com.linkbridge.android.ui.devices.PairingActions
import com.linkbridge.android.ui.devices.PairingSheet
import com.linkbridge.android.ui.devices.PreviewData
import com.linkbridge.android.ui.devices.ScreenPreviews
import com.linkbridge.android.ui.glass.LocalBlurEnabled
import com.linkbridge.android.ui.history.HistoryActions
import com.linkbridge.android.ui.history.HistoryScreen
import com.linkbridge.android.ui.send.SendActions
import com.linkbridge.android.ui.send.SendScreen
import com.linkbridge.android.ui.send.rememberFilePicker
import com.linkbridge.android.ui.settings.SettingsActions
import com.linkbridge.android.ui.settings.SettingsScreen
import com.linkbridge.android.ui.glass.LocalHazeState
import com.linkbridge.android.ui.glass.glassSurface
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.backdrop
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** How long discovery may take to find a sendable device before the start tab stays Devices. */
private const val START_TAB_SETTLE_MS = 3_000L

/** How long "Paired with <Mac>" shows before the sheet closes and the app switches to Send. */
private const val PAIRED_CLOSE_MS = 1_000L

/** Everything the frame can ask for, so the frame itself needs no view model. */
class AppActions(
    val devices: DevicesActions,
    val pairing: PairingActions,
    val clearNotice: () -> Unit,
    /** Opens the file picker; a picked file is staged. */
    val onPick: () -> Unit = {},
    val onSend: () -> Unit = {},
    val onRetry: (TransferHistoryEntry) -> Unit = {},
    val onResolveApproval: (id: Int, accept: Boolean) -> Unit = { _, _ -> },
    /** Opens a received file in another app; false when no app can open it. */
    val openFile: (uri: String) -> Boolean = { false },
    /** Settings rows; its onBack is ignored (the frame owns navigation). */
    val settings: SettingsActions = SettingsActions(),
    val versionName: String = "",
)

/**
 * The app root: wires [vm] to the frame. [onRequestNotifications] runs the notification
 * permission flow (ask, or open app settings once permanently denied).
 */
@Composable
fun LinkBridgeApp(vm: MainViewModel, onRequestNotifications: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pick = rememberFilePicker(onPicked = vm::stage)
    val actions = remember(vm, pick, context) {
        AppActions(
            devices = DevicesActions(
                onSelect = vm::select,
                onPair = vm::pair,
                onRescan = vm::rescan,
                onBannerAction = { action ->
                    when (action) {
                        BannerAction.START -> vm.setReceiving(true)
                        BannerAction.RETRY -> vm.restartReceiving()
                        BannerAction.ENABLE_NOTIFICATIONS -> onRequestNotifications()
                    }
                },
            ),
            pairing = PairingActions(
                onContinue = vm::continuePairing,
                onCancel = vm::cancelPairing,
                onTryAgain = vm::pair,
                onDismiss = vm::dismissPairing,
            ),
            clearNotice = vm::clearNotice,
            onPick = pick,
            onSend = vm::send,
            onRetry = vm::retry,
            onResolveApproval = vm::resolveApproval,
            openFile = { uri -> openReceivedFile(context, uri) },
            settings = SettingsActions(
                onReceiving = vm::setReceiving,
                onEnableNotifications = onRequestNotifications,
                onUnpair = vm::unpair,
            ),
            versionName = BuildConfig.VERSION_NAME,
        )
    }
    AppFrame(state, actions)
}

/**
 * Tabs under a floating glass nav bar, a top bar with the connection chip and Settings, the
 * pairing sheet, and the notice snackbar. Settings replaces the frame; back returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppFrame(state: UiState, actions: AppActions) {
    // Null until decided: Send if a sendable device turns up shortly after launch, else Devices.
    var chosenTab by rememberSaveable { mutableStateOf<Tab?>(null) }
    var inSettings by rememberSaveable { mutableStateOf(false) }
    val currentState by rememberUpdatedState(state)

    LaunchedEffect(Unit) {
        if (chosenTab != null) return@LaunchedEffect
        val found = withTimeoutOrNull(START_TAB_SETTLE_MS) {
            snapshotFlow { startTab(currentState.devices) }.first { it == Tab.SEND }
        }
        if (chosenTab == null) chosenTab = found ?: Tab.DEVICES
    }
    val tab = chosenTab ?: Tab.DEVICES
    val selectTab: (Tab) -> Unit = { chosenTab = it }

    val pairing = state.pairing
    LaunchedEffect(pairing is PairingStep.Paired) {
        if (pairing is PairingStep.Paired) {
            delay(PAIRED_CLOSE_MS)
            actions.pairing.onDismiss()
            chosenTab = Tab.SEND
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        // Show in a separate job: clearing the notice restarts this effect.
        scope.launch { snackbar.showSnackbar(notice) }
        actions.clearNotice()
    }

    BackHandler(enabled = inSettings) { inSettings = false }

    // One modal sheet at a time: pairing first, then the oldest incoming file.
    val approval = approvalToShow(pairing, state.approvals)

    // Sheet scrim: blur the app behind the sheet when blur is available.
    val blurBehind = (pairing != null || approval != null) && LocalBlurEnabled.current
    Box(Modifier.fillMaxSize().then(if (blurBehind) Modifier.blur(16.dp) else Modifier)) {
        AnimatedContent(
            targetState = inSettings,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "destination",
        ) { settings ->
            if (settings) {
                SettingsScreen(
                    state = state,
                    actions = SettingsActions(
                        onBack = { inSettings = false },
                        onReceiving = actions.settings.onReceiving,
                        onEnableNotifications = actions.settings.onEnableNotifications,
                        onUnpair = actions.settings.onUnpair,
                    ),
                    versionName = actions.versionName,
                )
            } else {
                TabsFrame(
                    state = state,
                    tab = tab,
                    onSelectTab = selectTab,
                    onOpenSettings = { inSettings = true },
                    snackbar = snackbar,
                    sendActions = SendActions(
                        onPick = actions.onPick,
                        onSend = actions.onSend,
                        onGoToDevices = { selectTab(Tab.DEVICES) },
                    ),
                    historyActions = HistoryActions(
                        onResolve = actions.onResolveApproval,
                        onOpen = { entry ->
                            val uri = entry.contentUri
                            if (uri == null || !actions.openFile(uri)) {
                                scope.launch { snackbar.showSnackbar("No app on this phone can open ${entry.fileName}.") }
                            }
                        },
                        onRetry = actions.onRetry,
                    ),
                    devicesActions = DevicesActions(
                        onSelect = { id ->
                            actions.devices.onSelect(id)
                            selectTab(Tab.SEND)
                        },
                        onPair = actions.devices.onPair,
                        onRescan = actions.devices.onRescan,
                        onBannerAction = actions.devices.onBannerAction,
                    ),
                )
            }
        }
    }

    if (pairing != null) {
        PairingSheet(
            pairing,
            PairingActions(
                onContinue = actions.pairing.onContinue,
                onCancel = actions.pairing.onCancel,
                onTryAgain = actions.pairing.onTryAgain,
                onDismiss = {
                    // Swiping away "Paired" early still lands on Send, like the auto-close.
                    if (pairing is PairingStep.Paired) chosenTab = Tab.SEND
                    actions.pairing.onDismiss()
                },
            ),
        )
    }

    if (approval != null) {
        // Keyed by id: the next queued request gets a fresh sheet.
        key(approval.id) {
            ApprovalSheet(approval, queued = state.approvals.size, onResolve = actions.onResolveApproval)
        }
    }
}

/** ACTION_VIEW on [uri] with a read grant; false when no app can open it. */
private fun openReceivedFile(context: Context, uri: String): Boolean {
    val parsed = Uri.parse(uri)
    val type = runCatching { context.contentResolver.getType(parsed) }.getOrNull() ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(parsed, type)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsFrame(
    state: UiState,
    tab: Tab,
    onSelectTab: (Tab) -> Unit,
    onOpenSettings: () -> Unit,
    snackbar: SnackbarHostState,
    sendActions: SendActions,
    historyActions: HistoryActions,
    devicesActions: DevicesActions,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    LaunchedEffect(tab) { scrollBehavior.state.contentOffset = 0f }
    val hazeState = LocalHazeState.current
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        topBar = {
            TopBar(state, scrollBehavior, onChip = { onSelectTab(Tab.DEVICES) }, onOpenSettings = onOpenSettings)
        },
        bottomBar = {
            GlassNavBar(
                selected = tab,
                onSelect = onSelectTab,
                modifier = Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .backdrop()
                .then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier),
        ) {
            when (tab) {
                Tab.DEVICES -> DevicesScreen(state, devicesActions, contentPadding = padding)
                Tab.SEND -> SendScreen(state, sendActions, contentPadding = padding)
                Tab.HISTORY -> HistoryScreen(state, historyActions, contentPadding = padding)
            }
        }
    }
}

/** Transparent until content scrolls under it, then glass. Grows with the font scale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(
    state: UiState,
    scrollBehavior: TopAppBarScrollBehavior,
    onChip: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    // contentOffset tracks how far content has scrolled up under the bar (pinned behaviour);
    // overlappedFraction needs a TopAppBar to set heightOffsetLimit, which this custom bar is not.
    val scrolled = scrollBehavior.state.contentOffset < -1f
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (scrolled) Modifier.glassSurface(RectangleShape) else Modifier)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).padding(end = 8.dp)) {
            ConnectionChip(state.connection, onClick = onChip)
        }
        IconButton(onClick = onOpenSettings) {
            // The bar is a plain Row, not a Surface, so set the colour (LocalContentColor is black here).
            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Previews ----

@ScreenPreviews
@Composable
private fun FramePreview() {
    LinkBridgeTheme(dynamicColor = false) {
        AppFrame(
            PreviewData.devices,
            AppActions(PreviewData.noActions, PairingActions({}, {}, {}, {}), clearNotice = {}),
        )
    }
}
