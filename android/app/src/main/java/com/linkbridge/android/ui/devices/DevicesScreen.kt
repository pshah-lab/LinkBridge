package com.linkbridge.android.ui.devices

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.linkbridge.android.UiState
import com.linkbridge.android.presentation.Banner
import com.linkbridge.android.presentation.BannerAction
import com.linkbridge.android.presentation.ConnectionState
import com.linkbridge.android.presentation.DeviceListItem
import com.linkbridge.android.presentation.DevicePlatform
import com.linkbridge.android.ui.components.EmptyState
import com.linkbridge.android.ui.components.StatusBanner
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.backdrop
import kotlinx.coroutines.delay

/** What the Devices screen can ask for. */
class DevicesActions(
    val onSelect: (id: String) -> Unit,
    val onPair: (id: String) -> Unit,
    val onRescan: () -> Unit,
    val onBannerAction: (BannerAction) -> Unit,
)

const val NO_DEVICES_TEXT = "No devices yet — open LinkBridge on your Mac on the same Wi-Fi."

/** Discovery has no "done" signal, so the refresh spinner shows for this long. */
private const val REFRESH_SPIN_MS = 1_500L

/**
 * The status banner, then the device list (or the empty state). Pull to refresh rescans.
 * [contentPadding] is the space under the top bar and above the nav bar; the list scrolls
 * beneath both.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DevicesScreen(
    state: UiState,
    actions: DevicesActions,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    modifier: Modifier = Modifier,
) {
    var refreshing by remember { mutableStateOf(false) }
    LaunchedEffect(refreshing) {
        if (refreshing) {
            delay(REFRESH_SPIN_MS)
            refreshing = false
        }
    }
    val pullState = rememberPullToRefreshState()
    val layoutDirection = LocalLayoutDirection.current
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            actions.onRescan()
        },
        state = pullState,
        modifier = modifier.fillMaxSize(),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = contentPadding.calculateTopPadding()),
            )
        },
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) + 16.dp,
                end = contentPadding.calculateEndPadding(layoutDirection) + 16.dp,
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Text(
                    "Devices",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp).semantics { heading() },
                )
            }
            state.banner?.let { banner ->
                item(key = "banner") { StatusBanner(banner, actions.onBannerAction) }
            }
            if (state.devices.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        NO_DEVICES_TEXT,
                        top = if (state.connection == ConnectionState.Searching) {
                            { LoadingIndicator() }
                        } else {
                            null
                        },
                    )
                }
            } else {
                items(state.devices, key = { it.id }) { item ->
                    DeviceRow(
                        item = item,
                        selected = item.id == state.selectedId,
                        onSelect = { actions.onSelect(item.id) },
                        onPair = { actions.onPair(item.id) },
                    )
                }
            }
        }
    }
}

// ---- Previews ----

@Preview(name = "Light", widthDp = 360)
@Preview(name = "Dark", widthDp = 360, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Font 2.0", widthDp = 360, fontScale = 2f)
internal annotation class ScreenPreviews

internal object PreviewData {
    val pairedMac = DeviceListItem(
        id = "mac", deviceId = "mac", name = "Studio", platform = DevicePlatform.MAC, isPaired = true,
        isNearby = true, isSendable = true, canPair = false, subtitle = "Paired · Nearby", peer = null,
    )
    val unpairedMac = DeviceListItem(
        id = "LinkBridge-Air", deviceId = null, name = "MacBook Air", platform = DevicePlatform.MAC,
        isPaired = false, isNearby = true, isSendable = false, canPair = true, subtitle = "Not paired", peer = null,
    )
    val devices = UiState(
        devices = listOf(pairedMac, unpairedMac),
        selectedId = pairedMac.id,
        connection = ConnectionState.Ready(pairedMac.name),
    )
    val searching = UiState(connection = ConnectionState.Searching)
    val empty = UiState(connection = ConnectionState.Error("Port 52000 is in use"))
    val banners = listOf(
        Banner("LinkBridge isn't receiving — tap to start.", BannerAction.START),
        Banner("Receiving failed: Port 52000 is in use", BannerAction.RETRY),
        Banner(
            "Notifications are off, so files sent while LinkBridge is in the background are declined.",
            BannerAction.ENABLE_NOTIFICATIONS,
        ),
    )
    val noActions = DevicesActions({}, {}, {}, {})
}

@Composable
private fun DevicesPreview(state: UiState) {
    LinkBridgeTheme(dynamicColor = false) {
        DevicesScreen(state, PreviewData.noActions, modifier = Modifier.backdrop())
    }
}

@ScreenPreviews
@Composable
private fun DevicesEmptyPreview() = DevicesPreview(PreviewData.empty)

@ScreenPreviews
@Composable
private fun DevicesSearchingPreview() = DevicesPreview(PreviewData.searching)

@ScreenPreviews
@Composable
private fun DevicesListPreview() = DevicesPreview(PreviewData.devices)

@ScreenPreviews
@Composable
private fun DevicesBannerStartPreview() = DevicesPreview(PreviewData.devices.copy(banner = PreviewData.banners[0]))

@ScreenPreviews
@Composable
private fun DevicesBannerRetryPreview() = DevicesPreview(PreviewData.devices.copy(banner = PreviewData.banners[1]))

@ScreenPreviews
@Composable
private fun DevicesBannerNotificationsPreview() =
    DevicesPreview(PreviewData.devices.copy(banner = PreviewData.banners[2]))
