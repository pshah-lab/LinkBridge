package com.linkbridge.android.offline

import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun interface Cancelable {
    fun cancel()
}

/** Seam over `WifiManager.startLocalOnlyHotspot` so the state machine is unit-testable. */
interface HotspotPort {
    /** May throw [SecurityException] when the Wi-Fi permissions are missing. */
    fun start(callback: HotspotPortCallback)
    fun stop()
}

interface HotspotPortCallback {
    fun onStarted(ssid: String, passphrase: String)
    fun onStopped()
    fun onFailed(code: Int)
}

enum class HotspotFailure {
    NO_CHANNEL, TETHERING_ON, INCOMPATIBLE_MODE, NOT_ALLOWED, PERMISSION_DENIED, UNSUPPORTED_OS, GENERIC,
}

sealed interface HotspotState {
    data object Idle : HotspotState
    data object Starting : HotspotState
    data class Active(val ssid: String, val passphrase: String) : HotspotState
    data class Failed(val reason: HotspotFailure) : HotspotState
    data class Stopped(val bySystem: Boolean) : HotspotState
}

const val IDLE_TIMEOUT_MS = 600_000L

private const val MIN_SDK_FOR_OFFLINE_LINK = 33

/**
 * Owns the local-only hotspot lifecycle. Android has no idle timeout for it, so this stops it after
 * [IDLE_TIMEOUT_MS] without activity; [setBusy] (a transfer in progress) pauses that timer.
 * Call from one thread (the main thread); port callbacks must arrive on it too.
 */
class OfflineHotspot(
    private val port: HotspotPort,
    private val sdkInt: Int,
    private val clock: () -> Long,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Cancelable,
) {
    private val _state = MutableStateFlow<HotspotState>(HotspotState.Idle)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    private var busy = false
    private var lastActivityAt = 0L
    private var idleTimer: Cancelable? = null

    private val portCallback = object : HotspotPortCallback {
        override fun onStarted(ssid: String, passphrase: String) {
            if (_state.value != HotspotState.Starting) {
                port.stop() // stopped or restarted while the system was bringing it up
                return
            }
            _state.value = HotspotState.Active(ssid, passphrase)
            lastActivityAt = clock()
            if (!busy) armIdleTimer(IDLE_TIMEOUT_MS)
        }

        override fun onStopped() {
            if (_state.value is HotspotState.Active || _state.value == HotspotState.Starting) {
                cancelIdleTimer()
                _state.value = HotspotState.Stopped(bySystem = true)
            }
        }

        override fun onFailed(code: Int) {
            if (_state.value != HotspotState.Starting) return
            _state.value = HotspotState.Failed(failureFor(code))
        }
    }

    fun start() {
        when (_state.value) {
            HotspotState.Starting, is HotspotState.Active -> return
            else -> Unit
        }
        if (sdkInt < MIN_SDK_FOR_OFFLINE_LINK) {
            _state.value = HotspotState.Failed(HotspotFailure.UNSUPPORTED_OS)
            return
        }
        _state.value = HotspotState.Starting
        try {
            port.start(portCallback)
        } catch (_: SecurityException) {
            _state.value = HotspotState.Failed(HotspotFailure.PERMISSION_DENIED)
        }
    }

    fun stop() {
        val current = _state.value
        if (current != HotspotState.Starting && current !is HotspotState.Active) return
        cancelIdleTimer()
        _state.value = HotspotState.Stopped(bySystem = false)
        port.stop()
    }

    /** True while a transfer runs; the idle clock restarts from zero when it ends. */
    fun setBusy(busy: Boolean) {
        if (this.busy == busy) return
        this.busy = busy
        if (_state.value !is HotspotState.Active) return
        if (busy) {
            cancelIdleTimer()
        } else {
            lastActivityAt = clock()
            armIdleTimer(IDLE_TIMEOUT_MS)
        }
    }

    private fun armIdleTimer(delayMs: Long) {
        cancelIdleTimer()
        idleTimer = schedule(delayMs) { onIdleTimer() }
    }

    private fun cancelIdleTimer() {
        idleTimer?.cancel()
        idleTimer = null
    }

    private fun onIdleTimer() {
        idleTimer = null
        if (busy || _state.value !is HotspotState.Active) return
        val remaining = IDLE_TIMEOUT_MS - (clock() - lastActivityAt)
        if (remaining > 0) armIdleTimer(remaining) else stop()
    }

    private fun failureFor(code: Int): HotspotFailure = when (code) {
        LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> HotspotFailure.NO_CHANNEL
        LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> HotspotFailure.NOT_ALLOWED
        LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> HotspotFailure.INCOMPATIBLE_MODE
        else -> HotspotFailure.GENERIC // includes ERROR_GENERIC
    }
}
