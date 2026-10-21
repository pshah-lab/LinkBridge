package com.linkbridge.android.presentation

/** What the receive side is doing. The service maps `ReceiveServer` callbacks to this. */
sealed interface ReceiveState {
    data object Starting : ReceiveState
    data object Listening : ReceiveState
    data class Receiving(val fileName: String, val received: Long, val total: Long) : ReceiveState
    data object Stopped : ReceiveState
    data class Failed(val message: String) : ReceiveState
}

enum class BannerAction { START, RETRY, ENABLE_NOTIFICATIONS }

data class Banner(val text: String, val action: BannerAction)

/** The one Devices-screen banner to show, in the spec's priority order, or null. */
fun bannerFor(receive: ReceiveState, notificationsAllowed: Boolean): Banner? = when {
    receive is ReceiveState.Stopped ->
        Banner("LinkBridge isn't receiving — tap to start.", BannerAction.START)
    receive is ReceiveState.Failed ->
        Banner("Receiving failed: ${receive.message}", BannerAction.RETRY)
    !notificationsAllowed -> Banner(
        "Notifications are off, so files sent while LinkBridge is in the background are declined.",
        BannerAction.ENABLE_NOTIFICATIONS
    )
    else -> null
}
