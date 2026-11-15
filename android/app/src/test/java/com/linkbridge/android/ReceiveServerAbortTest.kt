package com.linkbridge.android

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A real paired upload over loopback TLS-PSK, cut off by `abortActive()` while the file is arriving. */
class ReceiveServerAbortTest {
    private val senderId = "11111111-2222-3333-4444-555555555555"
    private val authToken = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="

    private class FakeFile(private val onFirstWrite: () -> Unit) : IncomingFile {
        @Volatile var written = 0L
        @Volatile var published = false
        @Volatile var discarded = false
        override val locator: String = "fake://file"
        override val output = object : OutputStream() {
            private var first = true
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                written += len
                if (first) {
                    first = false
                    onFirstWrite()
                }
            }
        }
        override fun publish() { published = true }
        override fun discard() { discarded = true }
    }

    private var server: ReceiveServer? = null
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        server?.stop()
        executor.shutdownNow()
    }

    private fun upload(port: Int, sizeBytes: Int) {
        val peer = DiscoveredPeer("svc", "mac-1", "Test Mac", "android", "127.0.0.1", port, tlsPort = port)
        val local = DeviceIdentity(deviceId = senderId, deviceName = "Sender")
        val trusted = TrustedPeer("mac-1", "Test Mac", "android", "127.0.0.1", port, authToken)
        executor.execute {
            ControlClient().uploadFile(
                peer = peer, fileName = "big.bin", size = sizeBytes.toLong(), localIdentity = local,
                trustedPeer = trusted, openInputStream = { ByteArrayInputStream(ByteArray(sizeBytes) { 7 }) },
            )
        }
    }

    @Test
    fun abortActiveMidReceiveFailsTheTransferAndDeletesThePartialFile() {
        val port = ServerSocket(0).use { it.localPort }
        val listening = CountDownLatch(1)
        val failed = CountDownLatch(1)
        var failedName: String? = null
        var failedReason: String? = null
        var completed = false
        val files = mutableListOf<FakeFile>()
        server = ReceiveServer(
            context = ContextWrapper(null),
            port = port,
            onListening = { listening.countDown() },
            onProgress = { _, _, _ -> },
            onFailed = { },
            onTransferCompleted = { completed = true },
            onTransferFailed = { name, reason -> failedName = name; failedReason = reason; failed.countDown() },
            onApprovalRequested = { true },
            availableBytes = { Long.MAX_VALUE },
            fileStore = { _ -> FakeFile { server!!.abortActive() }.also { files += it } },
            trustedPeers = { listOf(TrustedPeer(senderId, "Sender", "android", "127.0.0.1", 0, authToken)) },
        ).also { it.start() }
        assertTrue(listening.await(5, TimeUnit.SECONDS))

        upload(port, sizeBytes = 4 * 1024 * 1024)

        assertTrue("onTransferFailed should fire", failed.await(15, TimeUnit.SECONDS))
        assertEquals("big.bin", failedName)
        assertEquals(ReceiveServer.ABORTED_REASON, failedReason)
        val file = files.single()
        assertTrue("partial file should be deleted", file.discarded)
        assertFalse("partial file must not be published", file.published)
        assertFalse(completed)
    }
}
