package com.linkbridge.android

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConnectionGateTest {
    private val server = ServerSocket(0)
    private val clients = mutableListOf<Socket>()
    private var gate: ConnectionGate? = null

    @After
    fun tearDown() {
        gate?.shutdown()
        clients.forEach { runCatching { it.close() } }
        server.close()
    }

    /** Connects a client and returns the server's side of the socket. */
    private fun connect(): Socket {
        val client = Socket("127.0.0.1", server.localPort)
        client.soTimeout = 5_000
        clients += client
        return server.accept()
    }

    /** True if the server closed the client's connection within the client's read timeout. */
    private fun closedByServer(client: Socket): Boolean = try {
        client.getInputStream().read() == -1
    } catch (reset: SocketException) {
        true
    }

    private fun readForever(socket: Socket): (() -> Unit)? {
        while (socket.getInputStream().read() >= 0) { /* keep reading */ }
        return null
    }

    @Test
    fun silentClientIsClosedAtTheDeadline() {
        gate = ConnectionGate(handshakeDeadlineMs = 300)
        assertTrue(gate!!.submit(connect(), ::readForever))
        assertTrue(closedByServer(clients.last()))
    }

    @Test
    fun tricklingClientIsClosedAtTheDeadline() {
        // A byte every 100 ms beats any per-read timeout; the deadline is for the whole handshake.
        gate = ConnectionGate(handshakeDeadlineMs = 400)
        val ended = CountDownLatch(1)
        gate!!.submit(connect()) { socket ->
            try { readForever(socket) } finally { ended.countDown() }
        }
        val output = clients.last().getOutputStream()
        val started = System.nanoTime()
        while (ended.count > 0 && System.nanoTime() - started < 3_000_000_000L) {
            runCatching { output.write(1); output.flush() }
            Thread.sleep(100)
        }
        assertTrue("handshake ended", ended.await(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun oneAddressCannotHoldEveryHandshakeThread() {
        gate = ConnectionGate(handshakeThreads = 8, maxPerSource = 2, handshakeDeadlineMs = 5_000)
        assertTrue(gate!!.submit(connect(), ::readForever))
        assertTrue(gate!!.submit(connect(), ::readForever))
        assertFalse("third from the same address refused", gate!!.submit(connect(), ::readForever))
        assertTrue(closedByServer(clients.last()))
    }

    @Test
    fun fullQueueRefusesInsteadOfPilingUp() {
        gate = ConnectionGate(handshakeThreads = 1, handshakeQueue = 1, maxPerSource = 10, handshakeDeadlineMs = 5_000)
        assertTrue(gate!!.submit(connect(), ::readForever))
        assertTrue(gate!!.submit(connect(), ::readForever))
        assertFalse("queue full", gate!!.submit(connect(), ::readForever))
        assertTrue(closedByServer(clients.last()))
    }

    @Test
    fun transferWaitingForApprovalDoesNotBlockHandshakes() {
        gate = ConnectionGate(handshakeThreads = 1, transferThreads = 2, handshakeDeadlineMs = 5_000)
        val approval = CountDownLatch(1)
        val secondHandshake = CountDownLatch(1)
        gate!!.submit(connect()) { { approval.await(5, TimeUnit.SECONDS) } }
        gate!!.submit(connect()) { secondHandshake.countDown(); null }
        assertTrue("second handshake ran while the first waited", secondHandshake.await(2, TimeUnit.SECONDS))
        approval.countDown()
    }

    @Test
    fun finishedHandshakeFreesItsAddressSlot() {
        gate = ConnectionGate(maxPerSource = 1, handshakeDeadlineMs = 5_000)
        val done = CountDownLatch(1)
        assertTrue(gate!!.submit(connect()) { done.countDown(); null })
        assertTrue(done.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue("slot released", gate!!.submit(connect()) { null })
        assertEquals(2, clients.size)
    }
}
