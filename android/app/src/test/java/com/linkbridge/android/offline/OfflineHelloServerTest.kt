package com.linkbridge.android.offline

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineHelloServerTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val mine = DiscoveryRecord("aa", "", "Pixel", "android", 2, 49153, 49153, "files")
    private val theirs = DiscoveryRecord("bb", "t1", "iPhone", "ios", 2, 49153, 49153, "files")
    private val peers = CopyOnWriteArrayList<Pair<DiscoveryRecord, Inet4Address>>()
    private val servers = mutableListOf<OfflineHelloServer>()
    private var now = 1_000_000L

    private fun server(allowed: (InetAddress) -> Boolean = { true }) =
        OfflineHelloServer(loopback, 0, { mine }, allowed, { now }) { r, a -> peers += r to a }
            .also { servers += it; assertTrue(it.start()) }

    @After fun tearDown() = servers.forEach { it.stop() }

    /** Sends [raw] and returns the whole reply, or "" when the server closed without one. */
    private fun send(port: Int, raw: String): String = try {
        Socket(loopback, port).use { s ->
            s.soTimeout = 3000
            s.getOutputStream().apply { write(raw.toByteArray()); flush() }
            runCatching { s.getInputStream().readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
        }
    } catch (_: java.io.IOException) { "" }

    private fun post(body: String, path: String = "/offline/hello", method: String = "POST") =
        "$method $path HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body"

    private fun status(reply: String) = reply.substringBefore("\r\n").split(' ').getOrNull(1)?.toIntOrNull()

    @Test fun exchangeReturnsLocalRecordAndReportsPeer() {
        val s = server()
        val reply = send(s.localPort, post(theirs.toJson()))
        assertEquals(200, status(reply))
        assertTrue(reply.contains("Content-Length: ${mine.toJson().toByteArray().size}\r\n"))
        assertEquals(mine, DiscoveryRecord.fromJson(reply.substringAfter("\r\n\r\n")))
        assertEquals(1, peers.size)
        assertEquals(theirs, peers[0].first)
        assertEquals(loopback, peers[0].second)
    }

    @Test fun rejectsBodyOver4KB() {
        val s = server()
        val big = theirs.copy(deviceName = "x".repeat(5000)).toJson()
        assertEquals(400, status(send(s.localPort, post(big))))
        assertTrue(peers.isEmpty())
    }

    @Test fun rejectsInvalidJson() {
        val s = server()
        assertEquals(400, status(send(s.localPort, post("{nope"))))
        assertEquals(400, status(send(s.localPort, post("""{"rid":"bb"}"""))))
        assertEquals(400, status(send(s.localPort, "POST /offline/hello HTTP/1.1\r\nHost: x\r\n\r\n")))
        assertTrue(peers.isEmpty())
    }

    @Test fun rejectsDisallowedSource() {
        val s = server { false }
        assertEquals("", send(s.localPort, post(theirs.toJson())))
        assertTrue(peers.isEmpty())
    }

    @Test fun rateLimits21stRequestPerMinute() {
        val s = server()
        repeat(20) { assertEquals(200, status(send(s.localPort, post(theirs.toJson())))) }
        assertEquals(429, status(send(s.localPort, post(theirs.toJson()))))
        now += 60_000
        assertEquals(200, status(send(s.localPort, post(theirs.toJson()))))
    }

    @Test fun rejectsOtherPaths() {
        val s = server()
        assertEquals(404, status(send(s.localPort, post("", method = "GET"))))
        assertEquals(404, status(send(s.localPort, post(theirs.toJson(), path = "/pair/request"))))
        assertTrue(peers.isEmpty())
    }

    @Test fun stopClosesPort() {
        val s = server()
        val port = s.localPort
        s.stop()
        assertFalse(runCatching { Socket().use { it.connect(InetSocketAddress(loopback, port), 500) } }.isSuccess)
    }

    @Test fun busyPortReturnsFalse() {
        ServerSocket(0, 1, loopback).use { busy ->
            val s = OfflineHelloServer(loopback, busy.localPort, { mine }, { true }, { now }) { _, _ -> }
            assertFalse(s.start())
        }
    }

    @Test fun privateIpv4Only() {
        assertTrue(isPrivateIpv4(InetAddress.getByName("192.168.43.20")))
        assertTrue(isPrivateIpv4(InetAddress.getByName("172.31.0.1")))
        assertFalse(isPrivateIpv4(InetAddress.getByName("172.32.0.1")))
        assertFalse(isPrivateIpv4(InetAddress.getByName("8.8.8.8")))
        assertNotNull(InetAddress.getByName("10.0.0.1"))
        assertTrue(isPrivateIpv4(InetAddress.getByName("10.0.0.1")))
    }
}
