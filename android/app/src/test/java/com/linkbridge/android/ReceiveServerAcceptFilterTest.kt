package com.linkbridge.android

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ReceiveServerAcceptFilterTest {
    private var server: ReceiveServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun startServer(filter: (InetAddress) -> Boolean): Int {
        val port = freePort()
        val listening = CountDownLatch(1)
        server = ReceiveServer(
            context = ContextWrapper(null),
            port = port,
            onListening = { listening.countDown() },
            onProgress = { _, _, _ -> },
            onFailed = { reason -> fail("server failed: $reason") },
            onTransferCompleted = { },
            onTransferFailed = { _, _ -> },
            onApprovalRequested = { false },
            acceptFilter = filter,
        ).also { it.start() }
        assertTrue(listening.await(5, TimeUnit.SECONDS))
        return port
    }

    private fun loopbackClient(port: Int) =
        Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 1000 }

    @Test
    fun acceptFilterRejectsOtherLocalAddress() {
        val port = startServer { false }
        loopbackClient(port).use { client ->
            // Closed with no response: end of stream, not a timeout.
            assertEquals(-1, client.getInputStream().read())
        }
    }

    @Test
    fun acceptFilterAdmitsMatchingLocalAddress() {
        val port = startServer { it.isLoopbackAddress }
        loopbackClient(port).use { client ->
            try {
                client.getInputStream().read()
                fail("an admitted connection should stay open waiting for the handshake")
            } catch (expected: SocketTimeoutException) {
            }
        }
    }

    @Test
    fun filterCanBeReplacedWhileRunning() {
        val port = startServer { true }
        server!!.acceptFilter = { false }
        loopbackClient(port).use { client -> assertEquals(-1, client.getInputStream().read()) }
    }

    @Test
    fun abortActiveClosesConnectionsInFlight() {
        val port = startServer { true }
        loopbackClient(port).use { client ->
            client.soTimeout = 5000
            Thread.sleep(200) // let the server accept it
            server!!.abortActive()
            assertEquals(-1, client.getInputStream().read())
        }
        // Still listening afterwards.
        loopbackClient(port).use { assertTrue(it.isConnected) }
    }
}
