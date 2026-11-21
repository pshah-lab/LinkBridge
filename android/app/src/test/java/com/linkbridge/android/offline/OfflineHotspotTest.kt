package com.linkbridge.android.offline

import android.net.wifi.WifiManager.LocalOnlyHotspotCallback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineHotspotTest {
    private class FakePort : HotspotPort {
        var callback: HotspotPortCallback? = null
        var starts = 0
        var stops = 0
        var startThrows: Throwable? = null

        override fun start(callback: HotspotPortCallback) {
            starts++
            startThrows?.let { throw it }
            this.callback = callback
        }

        override fun stop() {
            stops++
        }
    }

    /** A fake clock plus a scheduler that runs due blocks as time advances. */
    private class FakeTime {
        var now = 0L
        private class Task(val at: Long, val block: () -> Unit) { var cancelled = false }
        private val tasks = mutableListOf<Task>()

        fun schedule(delayMs: Long, block: () -> Unit): Cancelable {
            val task = Task(now + delayMs, block)
            tasks += task
            return Cancelable { task.cancelled = true }
        }

        fun advanceTo(target: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = maxOf(now, next.at)
                next.block()
            }
            now = target
        }

        fun advanceBy(ms: Long) = advanceTo(now + ms)
    }

    private val minute = 60_000L

    private fun hotspot(port: FakePort, time: FakeTime, sdk: Int = 33) =
        OfflineHotspot(port, sdk, { time.now }, time::schedule)

    private fun activeHotspot(port: FakePort, time: FakeTime): OfflineHotspot {
        val h = hotspot(port, time)
        h.start()
        port.callback!!.onStarted("DIRECT-lb", "secret123")
        return h
    }

    @Test
    fun startsAndBecomesActive() {
        val port = FakePort()
        val h = hotspot(port, FakeTime())
        assertEquals(HotspotState.Idle, h.state.value)
        h.start()
        assertEquals(HotspotState.Starting, h.state.value)
        port.callback!!.onStarted("DIRECT-lb", "secret123")
        assertEquals(HotspotState.Active("DIRECT-lb", "secret123"), h.state.value)
    }

    @Test
    fun idleStopsAfterTenMinutes() {
        val port = FakePort()
        val time = FakeTime()
        val h = activeHotspot(port, time)
        time.advanceTo(599_999)
        assertTrue(h.state.value is HotspotState.Active)
        assertEquals(0, port.stops)
        time.advanceTo(600_000)
        assertEquals(HotspotState.Stopped(bySystem = false), h.state.value)
        assertEquals(1, port.stops)
    }

    @Test
    fun busyPausesIdleTimer() {
        val port = FakePort()
        val time = FakeTime()
        val h = activeHotspot(port, time)
        time.advanceBy(9 * minute)
        h.setBusy(true)
        time.advanceBy(20 * minute)
        assertTrue(h.state.value is HotspotState.Active)
        h.setBusy(false)
        time.advanceBy(9 * minute)
        assertTrue(h.state.value is HotspotState.Active)
        time.advanceBy(1 * minute)
        assertEquals(HotspotState.Stopped(bySystem = false), h.state.value)
        assertEquals(1, port.stops)
    }

    @Test
    fun mapsEveryFailureCode() {
        val expected = mapOf(
            LocalOnlyHotspotCallback.ERROR_NO_CHANNEL to HotspotFailure.NO_CHANNEL,
            LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED to HotspotFailure.NOT_ALLOWED,
            LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE to HotspotFailure.INCOMPATIBLE_MODE,
            LocalOnlyHotspotCallback.ERROR_GENERIC to HotspotFailure.GENERIC,
            9999 to HotspotFailure.GENERIC,
        )
        for ((code, failure) in expected) {
            val port = FakePort()
            val h = hotspot(port, FakeTime())
            h.start()
            port.callback!!.onFailed(code)
            assertEquals("code $code", HotspotState.Failed(failure), h.state.value)
        }
    }

    @Test
    fun securityExceptionMapsToPermissionDenied() {
        val port = FakePort().apply { startThrows = SecurityException("no") }
        val h = hotspot(port, FakeTime())
        h.start()
        assertEquals(HotspotState.Failed(HotspotFailure.PERMISSION_DENIED), h.state.value)
    }

    @Test
    fun systemStopReportsBySystem() {
        val port = FakePort()
        val time = FakeTime()
        val h = activeHotspot(port, time)
        port.callback!!.onStopped()
        assertEquals(HotspotState.Stopped(bySystem = true), h.state.value)
        // The idle timer must not fire a second stop afterwards.
        time.advanceBy(20 * minute)
        assertEquals(0, port.stops)
    }

    @Test
    fun userStopIsNotBySystem() {
        val port = FakePort()
        val h = activeHotspot(port, FakeTime())
        h.stop()
        assertEquals(HotspotState.Stopped(bySystem = false), h.state.value)
        assertEquals(1, port.stops)
        port.callback!!.onStopped() // the port echoes the close; must not flip to bySystem
        assertEquals(HotspotState.Stopped(bySystem = false), h.state.value)
    }

    @Test
    fun belowApi33IsUnsupported() {
        val port = FakePort()
        val h = hotspot(port, FakeTime(), sdk = 32)
        h.start()
        assertEquals(HotspotState.Failed(HotspotFailure.UNSUPPORTED_OS), h.state.value)
        assertEquals(0, port.starts)
    }

    @Test
    fun stopResetsBusyForTheNextSession() {
        val port = FakePort()
        val time = FakeTime()
        val h = activeHotspot(port, time)
        h.setBusy(true)
        h.stop()
        h.start()
        port.callback!!.onStarted("DIRECT-lb", "secret123")
        time.advanceBy(10 * minute)
        assertEquals(HotspotState.Stopped(bySystem = false), h.state.value)
    }

    @Test
    fun systemStopResetsBusyForTheNextSession() {
        val port = FakePort()
        val time = FakeTime()
        val h = activeHotspot(port, time)
        h.setBusy(true)
        port.callback!!.onStopped()
        h.start()
        port.callback!!.onStarted("DIRECT-lb", "secret123")
        time.advanceBy(10 * minute)
        assertEquals(HotspotState.Stopped(bySystem = false), h.state.value)
    }

    @Test
    fun idleDeadlineTracksTheTimer() {
        val port = FakePort(); val time = FakeTime()
        val h = hotspot(port, time)
        assertEquals(null, h.idleDeadline.value)
        time.now = 1_000
        h.start()
        port.callback!!.onStarted("DIRECT-lb", "secret123")
        assertEquals(1_000 + IDLE_TIMEOUT_MS, h.idleDeadline.value)

        time.advanceBy(3 * minute)
        h.setBusy(true)
        assertEquals(null, h.idleDeadline.value)

        time.advanceBy(2 * minute)
        h.setBusy(false)
        assertEquals(time.now + IDLE_TIMEOUT_MS, h.idleDeadline.value)

        h.stop()
        assertEquals(null, h.idleDeadline.value)
    }

    @Test
    fun idleDeadlineClearsWhenTheTimerStopsIt() {
        val port = FakePort(); val time = FakeTime()
        val h = activeHotspot(port, time)
        time.advanceBy(IDLE_TIMEOUT_MS + 1)
        assertEquals(null, h.idleDeadline.value)
    }

    @Test
    fun failStopsTheHotspotAndAllowsARetry() {
        val port = FakePort()
        val time = FakeTime()
        val h = activeHotspot(port, time)
        h.fail(HotspotFailure.NO_ADDRESS)
        assertEquals(HotspotState.Failed(HotspotFailure.NO_ADDRESS), h.state.value)
        assertEquals(1, port.stops)
        assertEquals(null, h.idleDeadline.value)
        h.start()
        assertEquals(HotspotState.Starting, h.state.value)
        assertEquals(2, port.starts)
    }
}
