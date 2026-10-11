package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class SecureTransportTest {
    private val secret = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=" // bytes 0x01..0x20

    @Test
    fun pskMatchesMacAndPythonVector() {
        assertEquals(
            "f2a4ca2992eb3c84f5f991f285f26f6d40bd530fdaf194a4a7e3b44051da76a1",
            SecureTransport.psk(secret).joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun identityIsLowercased() {
        assertEquals("c9bbd429-a655-4891-9965-5c24d4c0ec98", SecureTransport.identity("C9BBD429-A655-4891-9965-5C24D4C0EC98"))
    }

    @Test
    fun clientAndServerExchangeDataWithTheRightKey() {
        val psk = SecureTransport.psk(secret)
        val (reply, serverIdentity) = roundTrip(clientIdentity = "phone-1", clientPsk = psk) { id -> if (id == "phone-1") psk else null }
        assertEquals("echo:hello", reply)
        assertEquals("phone-1", serverIdentity)
    }

    @Test
    fun wrongKeyIsRejected() {
        val psk = SecureTransport.psk(secret)
        assertRejected(clientIdentity = "phone-1", clientPsk = ByteArray(32) { 9 }) { id -> if (id == "phone-1") psk else null }
    }

    @Test
    fun unknownIdentityIsRejected() {
        val psk = SecureTransport.psk(secret)
        assertRejected(clientIdentity = "stranger", clientPsk = psk) { id -> if (id == "phone-1") psk else null }
    }

    private fun assertRejected(clientIdentity: String, clientPsk: ByteArray, pskFor: (String) -> ByteArray?) {
        try {
            val (reply, identity) = roundTrip(clientIdentity, clientPsk, pskFor)
            fail("handshake should fail, got reply=$reply identity=$identity")
        } catch (expected: Exception) {
        }
    }

    /** Runs an echo server for one connection; returns the client's reply and the server-side identity. */
    private fun roundTrip(clientIdentity: String, clientPsk: ByteArray, pskFor: (String) -> ByteArray?): Pair<String, String?> {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0).use { listener ->
            val server: Future<String?> = executor.submit<String?> {
                listener.accept().let { socket ->
                    SecureTransport.accept(socket, pskFor).use { connection ->
                        val buffer = ByteArray(64)
                        val read = connection.input.read(buffer)
                        connection.output.write(("echo:" + String(buffer, 0, read)).toByteArray())
                        connection.output.flush()
                        connection.peerIdentity
                    }
                }
            }
            try {
                SecureTransport.connect("127.0.0.1", listener.localPort, clientIdentity, clientPsk).use { connection ->
                    connection.output.write("hello".toByteArray())
                    connection.output.flush()
                    val buffer = ByteArray(64)
                    val read = connection.input.read(buffer)
                    return String(buffer, 0, read) to server.get(5, TimeUnit.SECONDS)
                }
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
