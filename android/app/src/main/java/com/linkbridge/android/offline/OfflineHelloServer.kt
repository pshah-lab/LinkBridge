package com.linkbridge.android.offline

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

const val OFFLINE_HELLO_PORT = 49154

/** RFC 1918 IPv4 only: 10/8, 172.16/12, 192.168/16. */
fun isPrivateIpv4(address: InetAddress): Boolean {
    if (address !is Inet4Address) return false
    val b = address.address
    val first = b[0].toInt() and 0xFF
    val second = b[1].toInt() and 0xFF
    return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
}

/**
 * `POST /offline/hello` on the hotspot address: a joining device sends its [DiscoveryRecord] and
 * gets [localRecord] back, so each side finds the other without Bonjour. Plain HTTP, no auth; it
 * only exchanges discovery records. Wire format: docs/protocol.md ("Offline link"). At most
 * [MAX_REQUESTS_PER_MINUTE] requests a minute (then 429); callers outside [isAllowedSource] are
 * dropped without a reply. [onPeer] runs on a server thread.
 */
class OfflineHelloServer(
    private val bindAddress: InetAddress,
    private val port: Int = OFFLINE_HELLO_PORT,
    private val localRecord: () -> DiscoveryRecord,
    private val isAllowedSource: (InetAddress) -> Boolean = ::isPrivateIpv4,
    private val clock: () -> Long,
    /** Total time one connection may take, from accept to close. */
    private val connectionDeadlineMs: Long = CONNECTION_DEADLINE_MS,
    private val onPeer: (DiscoveryRecord, Inet4Address) -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val active = AtomicInteger(0)
    private val executor = Executors.newCachedThreadPool { r -> Thread(r, "offline-hello").apply { isDaemon = true } }
    private val recentRequests = ArrayDeque<Long>()

    /** The bound port (differs from the constructor's when that was 0); 0 before [start]. */
    val localPort: Int get() = server?.localPort ?: 0

    /** False when the port is busy or cannot be bound. */
    @Synchronized
    fun start(): Boolean {
        if (server != null) return true
        val socket = try {
            ServerSocket().apply { bind(InetSocketAddress(bindAddress, port)) }
        } catch (_: IOException) {
            return false
        }
        server = socket
        executor.execute { acceptLoop(socket) }
        return true
    }

    @Synchronized
    fun stop() {
        val socket = server ?: return
        server = null
        runCatching { socket.close() }
        clients.forEach { runCatching { it.close() } }
        clients.clear()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: IOException) { return }
            if (active.incrementAndGet() > MAX_CONCURRENT) {
                active.decrementAndGet()
                runCatching { client.close() }
                continue
            }
            clients += client
            if (server !== socket) { // stop() ran between accept and here: it will not see this socket
                clients -= client
                active.decrementAndGet()
                runCatching { client.close() }
                return
            }
            executor.execute {
                try { handle(client) } finally {
                    clients -= client
                    active.decrementAndGet()
                    runCatching { client.close() }
                }
            }
        }
    }

    private fun handle(client: Socket) {
        val remote = client.inetAddress
        if (remote !is Inet4Address || !isAllowedSource(remote)) return
        val deadline = System.nanoTime() + connectionDeadlineMs * 1_000_000L
        try {
            if (!allowRequest()) return respond(client, deadline, 429, "Too Many Requests")
            val head = readHead(client, deadline) ?: return respond(client, deadline, 400, "Bad Request")
            val lines = head.split("\r\n")
            val request = lines[0].split(' ')
            if (request.size != 3 || !request[2].startsWith("HTTP/1.")) return respond(client, deadline, 400, "Bad Request")
            if (request[0] != "POST" || request[1] != PATH) return respond(client, deadline, 404, "Not Found")

            var length: Int? = null
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (name) {
                    "content-length" -> {
                        if (length != null || value.isEmpty() || !value.all { it in '0'..'9' } || value.length > 9) {
                            return respond(client, deadline, 400, "Bad Request")
                        }
                        length = value.toInt()
                    }
                    "transfer-encoding" -> return respond(client, deadline, 400, "Bad Request")
                }
            }
            if (length == null || length > MAX_BODY) return respond(client, deadline, 400, "Bad Request")
            val body = readBody(client, deadline, length) ?: return respond(client, deadline, 400, "Bad Request")
            val record = DiscoveryRecord.fromJson(String(body, Charsets.UTF_8)) ?: return respond(client, deadline, 400, "Bad Request")

            val reply = localRecord().toJson().toByteArray(Charsets.UTF_8)
            if (reply.size > MAX_BODY) return respond(client, deadline, 400, "Bad Request")
            runCatching { onPeer(record, remote) }
            respond(client, deadline, 200, "OK", reply)
        } catch (_: IOException) {
            // Peer went away or timed out; nothing to answer.
        }
    }

    /** Sliding one-minute window. */
    @Synchronized
    private fun allowRequest(): Boolean {
        val now = clock()
        while (recentRequests.isNotEmpty() && now - recentRequests.first() >= WINDOW_MS) recentRequests.removeFirst()
        if (recentRequests.size >= MAX_REQUESTS_PER_MINUTE) return false
        recentRequests.addLast(now)
        return true
    }

    /** The request head without the blank line, or null when it is too long or the stream ends first. */
    private fun readHead(client: Socket, deadline: Long): String? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (buffer.size() <= MAX_HEAD) {
            val one = ByteArray(1)
            if (readWithin(client, deadline, one, 0, 1) < 0) return null
            val b = one[0].toInt() and 0xFF
            buffer.write(b)
            matched = if (b == TERMINATOR[matched].code) matched + 1 else if (b == TERMINATOR[0].code) 1 else 0
            if (matched == TERMINATOR.length) return String(buffer.toByteArray(), 0, buffer.size() - TERMINATOR.length, Charsets.ISO_8859_1)
        }
        return null
    }

    private fun readBody(client: Socket, deadline: Long, length: Int): ByteArray? {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = readWithin(client, deadline, body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return body
    }

    /** One read that cannot outlast [deadline] (System.nanoTime based): the timeout is the time left. */
    private fun readWithin(client: Socket, deadline: Long, buf: ByteArray, off: Int, len: Int): Int {
        val leftMs = (deadline - System.nanoTime()) / 1_000_000L
        if (leftMs <= 0) throw java.net.SocketTimeoutException("deadline")
        client.soTimeout = leftMs.toInt()
        return client.getInputStream().read(buf, off, len)
    }

    private fun respond(client: Socket, deadline: Long, status: Int, reason: String, body: ByteArray = ByteArray(0)) {
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            if (body.isNotEmpty()) append("Content-Type: application/json\r\n")
            append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
        // Closing with unread request bytes would reset the connection and could eat the reply.
        runCatching {
            client.shutdownOutput()
            val sink = ByteArray(1024)
            var drained = 0
            while (drained < MAX_DRAIN) {
                val n = readWithin(client, deadline, sink, 0, sink.size)
                if (n < 0) break
                drained += n
            }
        }
    }

    companion object {
        const val PATH = "/offline/hello"
        const val MAX_BODY = 4096
        const val MAX_REQUESTS_PER_MINUTE = 20
        private const val WINDOW_MS = 60_000L
        private const val MAX_HEAD = 8192
        private const val MAX_CONCURRENT = 8
        private const val MAX_DRAIN = 16 * 1024
        const val CONNECTION_DEADLINE_MS = 5_000L
        private const val TERMINATOR = "\r\n\r\n"
    }
}
