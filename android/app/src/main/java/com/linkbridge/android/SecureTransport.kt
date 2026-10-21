package com.linkbridge.android

import org.bouncycastle.tls.BasicTlsPSKIdentity
import org.bouncycastle.tls.CipherSuite
import org.bouncycastle.tls.PSKTlsClient
import org.bouncycastle.tls.PSKTlsServer
import org.bouncycastle.tls.ProtocolVersion
import org.bouncycastle.tls.TlsClientProtocol
import org.bouncycastle.tls.TlsExtensionsUtils
import org.bouncycastle.tls.TlsPSKIdentityManager
import org.bouncycastle.tls.TlsProtocol
import org.bouncycastle.tls.TlsServerProtocol
import org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.Hashtable
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TLS between paired devices, keyed from the pairing secret (TLS-PSK). Mirrors
 * macos/Sources/LinkBridgeCore/SecureTransport.swift; settings verified against Apple's
 * Network.framework: TLS 1.2 only, TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256.
 */
object SecureTransport {
    private const val CIPHER_SUITE = CipherSuite.TLS_ECDHE_PSK_WITH_CHACHA20_POLY1305_SHA256
    private val random = SecureRandom()

    /** HKDF-SHA256(secret, salt "linkbridge-tls-v1", info "psk"), 32 bytes. */
    fun psk(fromSecret: String): ByteArray {
        val ikm = Base64.getDecoder().decode(fromSecret)
        val prk = hmac("linkbridge-tls-v1".toByteArray(), ikm)
        return hmac(prk, "psk".toByteArray() + byteArrayOf(1))
    }

    /** Device IDs are compared lowercased: the Mac writes UUIDs in uppercase. */
    fun identity(deviceId: String): String = deviceId.lowercase()

    class Connection(private val protocol: TlsProtocol, private val socket: Socket, val peerIdentity: String?) : AutoCloseable {
        val input: InputStream get() = protocol.inputStream
        val output: OutputStream get() = protocol.outputStream

        override fun close() {
            runCatching { protocol.close() }
            runCatching { socket.close() }
        }
    }

    /**
     * Server side of an accepted socket. [pskFor] returns the key for a client identity, or
     * null to reject it. Throws if the handshake fails (unknown identity, wrong key).
     */
    fun accept(socket: Socket, pskFor: (identity: String) -> ByteArray?): Connection {
        var authenticated: String? = null
        val server = object : PSKTlsServer(BcTlsCrypto(random), object : TlsPSKIdentityManager {
            override fun getHint(): ByteArray? = null
            override fun getPSK(identity: ByteArray): ByteArray? {
                val name = String(identity, Charsets.UTF_8)
                return pskFor(name)?.also { authenticated = name }
            }
        }) {
            override fun getSupportedCipherSuites() = intArrayOf(CIPHER_SUITE)
            override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()

            // PSK has no certificate, so never acknowledge OCSP stapling: Apple clients
            // reject a ServerHello that does (-9859 "decode failed").
            override fun getServerExtensions(): Hashtable<*, *>? {
                @Suppress("UNCHECKED_CAST")
                val extensions = super.getServerExtensions() as Hashtable<Int, ByteArray>?
                extensions?.remove(TlsExtensionsUtils.EXT_status_request)
                return extensions
            }
        }
        val protocol = TlsServerProtocol(socket.getInputStream(), socket.getOutputStream())
        protocol.accept(server)
        return Connection(protocol, socket, authenticated)
    }

    fun connect(host: String, port: Int, identity: String, psk: ByteArray, timeoutMs: Int = 5000): Connection {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = Timeouts.UPLOAD_SOCKET_TIMEOUT_MS // the receiver may wait for the user to accept the file
            val client = object : PSKTlsClient(BcTlsCrypto(random), BasicTlsPSKIdentity(identity, psk)) {
                override fun getSupportedCipherSuites() = intArrayOf(CIPHER_SUITE)
                override fun getSupportedVersions(): Array<ProtocolVersion> = ProtocolVersion.TLSv12.only()
            }
            val protocol = TlsClientProtocol(socket.getInputStream(), socket.getOutputStream())
            protocol.connect(client)
            return Connection(protocol, socket, identity)
        } catch (error: Exception) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
