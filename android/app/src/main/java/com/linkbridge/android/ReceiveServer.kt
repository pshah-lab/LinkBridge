package com.linkbridge.android

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ReceiveServer(
    private val context: Context,
    private val port: Int,
    private val onStatus: (String) -> Unit,
    private val onApprovalRequested: (IncomingTransferRequest) -> Boolean = { true }
) {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val clientExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_CLIENTS)
    private val replayCache = ConcurrentHashMap<String, Long>()
    private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return

        executor.execute {
            runCatching {
                ServerSocket(port).use { socket ->
                    serverSocket = socket
                    onStatus("Ready to receive files on port $port.")
                    while (running.get()) {
                        val client = socket.accept()
                        clientExecutor.execute {
                            runCatching { client.soTimeout = SOCKET_TIMEOUT_MS }
                            client.use { handleClient(it) }
                        }
                    }
                }
            }.onFailure { error ->
                if (running.get()) {
                    Log.e(TAG, "Receive server failed", error)
                    onStatus("Receive server failed: ${error.localizedMessage}")
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
        clientExecutor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val headerBytes = readHeaderBytes(input)
        val headerText = headerBytes.decodeToString()
        val request = HttpRequestHeader.parse(headerText)

        if (!request.startLine.startsWith("POST /transfer/upload")) {
            socket.writeJsonResponse(404, """{"error":"not_found"}""")
            return
        }

        val contentLength = request.headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0) {
            socket.writeJsonResponse(400, """{"error":"missing_content_length"}""")
            return
        }
        if (contentLength > MAX_TRANSFER_BYTES) {
            socket.writeJsonResponse(413, """{"error":"transfer_too_large"}""")
            return
        }
        val senderId = request.headers["x-linkbridge-device-id"]
        val timestamp = request.headers["x-linkbridge-timestamp"]
        val transferId = request.headers["x-linkbridge-transfer-id"]
        val encryption = request.headers["x-linkbridge-encryption"]
        val suppliedSignature = request.headers["x-linkbridge-signature"]
        val trustedPeer = TrustedPeerStore(context).find(senderId)
        val authToken = trustedPeer?.authToken

        if (senderId == null || timestamp == null || transferId == null || suppliedSignature == null || authToken == null) {
            socket.writeJsonResponse(401, """{"error":"unpaired_or_unsigned"}""")
            return
        }
        val timestampMillis = timestamp.toLongOrNull()
        val transferUUID = runCatching { UUID.fromString(transferId) }.getOrNull()
        if (timestampMillis == null || transferUUID == null || kotlin.math.abs(System.currentTimeMillis() - timestampMillis) > TIMESTAMP_WINDOW_MS) {
            socket.writeJsonResponse(401, """{"error":"stale_transfer"}""")
            return
        }
        if (encryption != Security.ENCRYPTION_SCHEME) {
            socket.writeJsonResponse(400, """{"error":"encryption_required"}""")
            return
        }

        val signaturePayload = listOf(
            "POST",
            "/transfer/upload",
            senderId,
            timestamp,
            transferId,
            Security.ENCRYPTION_SCHEME,
            contentLength.toString()
        ).joinToString("\n")
        val expectedSignature = Security.hmacSha256Base64(authToken, signaturePayload)
        if (!Security.timingSafeEquals(expectedSignature, suppliedSignature)) {
            socket.writeJsonResponse(401, """{"error":"invalid_signature"}""")
            return
        }
        val replayKey = "$senderId:$transferId"
        val now = System.currentTimeMillis()
        replayCache.entries.removeIf { it.value < now }
        if (replayCache.putIfAbsent(replayKey, now + REPLAY_WINDOW_MS) != null) {
            socket.writeJsonResponse(409, """{"error":"replayed_transfer"}""")
            return
        }

        var savedUri: android.net.Uri? = null
        runCatching {
            val resolver = context.contentResolver
            val digest = MessageDigest.getInstance("SHA-256")
            var metadata: IncomingFileMetadata? = null
            var output: OutputStream? = null
            var received = 0L

            try {
                Security.decryptChunkedRecords(authToken, input, contentLength) { plain ->
                    val currentMetadata = metadata
                    if (currentMetadata == null) {
                        val parsed = IncomingFileMetadata.fromEncryptedJson(plain)
                        if (parsed.transferId != transferId) error("Transfer ID mismatch")
                        val approval = IncomingTransferRequest(
                            senderName = trustedPeer.deviceName,
                            senderId = senderId,
                            fileName = parsed.fileName,
                            bytes = parsed.bytes
                        )
                        if (!onApprovalRequested(approval)) {
                            throw TransferDeclinedException()
                        }

                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, parsed.fileName)
                            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LinkBridge")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }

                        savedUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                            ?: error("Could not create Downloads item")
                        output = resolver.openOutputStream(savedUri!!)
                            ?: error("Could not open output stream")
                        metadata = parsed
                        return@decryptChunkedRecords
                    }

                    output?.write(plain) ?: error("Output stream was not opened")
                    digest.update(plain, 0, plain.size)
                    received += plain.size
                    onStatus("Receiving ${currentMetadata.fileName}\n${formatBytes(received)} / ${formatBytes(currentMetadata.bytes)}")
                }
            } finally {
                output?.close()
            }

            val finalMetadata = metadata ?: error("Missing encrypted transfer metadata")
            if (received != finalMetadata.bytes) {
                error("Plaintext length mismatch")
            }

            // Verify before publishing: IS_PENDING keeps the file hidden from other apps until then.
            val sha256 = digest.digest().toHexString()
            if (!sha256.equals(finalMetadata.sha256, ignoreCase = true)) {
                savedUri?.let { resolver.delete(it, null, null) }
                socket.writeJsonResponse(400, """{"error":"checksum_mismatch"}""")
                return
            }

            val values = ContentValues()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            savedUri?.let { resolver.update(it, values, null, null) }
            onStatus("Received ${finalMetadata.fileName}\nSaved to Downloads/LinkBridge.")
            socket.writeJsonResponse(200, """{"status":"received","bytes":$received,"sha256":"$sha256"}""")
        }.onFailure { error ->
            savedUri?.let { context.contentResolver.delete(it, null, null) }
            if (error is TransferDeclinedException) {
                socket.writeJsonResponse(403, """{"error":"transfer_declined"}""")
                return
            }
            Log.e(TAG, "Receive failed", error)
            onStatus("Receive failed\n${error.localizedMessage}")
            socket.writeJsonResponse(500, """{"error":"save_failed"}""")
        }
    }

    private fun readHeaderBytes(input: BufferedInputStream): ByteArray {
        val bytes = mutableListOf<Byte>()
        var matched = 0
        val marker = byteArrayOf(13, 10, 13, 10)

        while (true) {
            val next = input.read()
            if (next < 0) error("Connection closed before headers completed")
            val byte = next.toByte()
            bytes.add(byte)
            if (bytes.size > MAX_HEADER_BYTES) error("Request headers exceed limit")
            matched = if (byte == marker[matched]) matched + 1 else if (byte == marker[0]) 1 else 0
            if (matched == marker.size) return bytes.toByteArray()
        }
    }

    private fun Socket.writeJsonResponse(status: Int, body: String) {
        val reason = if (status == 200) "OK" else "Error"
        val payload = body.toByteArray()
        val header = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        getOutputStream().use { output ->
            output.write(header.toByteArray())
            output.write(payload)
        }
    }

    private fun sanitizeFileName(fileName: String): String {
        return fileName
            .replace(Regex("""[/\\:]"""), "-")
            .trim()
            .ifBlank { "received-file" }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes.toDouble() / 1024.0
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex += 1
        }
        return "%.1f %s".format(value, units[unitIndex])
    }

    companion object {
        private const val TAG = "LinkBridgeReceive"
        private const val MAX_CLIENTS = 4
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_TRANSFER_BYTES = Long.MAX_VALUE - 1024
        private const val TIMESTAMP_WINDOW_MS = 5 * 60 * 1000L
        private const val REPLAY_WINDOW_MS = 10 * 60 * 1000L
        private const val SOCKET_TIMEOUT_MS = 15_000
    }
}

data class IncomingTransferRequest(
    val senderName: String,
    val senderId: String,
    val fileName: String,
    val bytes: Long
)

private data class IncomingFileMetadata(
    val transferId: String,
    val fileName: String,
    val bytes: Long,
    val sha256: String
) {
    companion object {
        fun fromEncryptedJson(bytes: ByteArray): IncomingFileMetadata {
            val json = org.json.JSONObject(bytes.toString(Charsets.UTF_8))
            return IncomingFileMetadata(
                transferId = json.getString("transferId"),
                fileName = sanitizeMetadataFileName(json.getString("fileName")),
                bytes = json.getLong("bytes"),
                sha256 = json.getString("sha256")
            )
        }

        private fun sanitizeMetadataFileName(fileName: String): String {
            return fileName
                .replace(Regex("""[/\\:]"""), "-")
                .trim()
                .ifBlank { "received-file" }
        }
    }
}

private class TransferDeclinedException : RuntimeException("Transfer declined")

private data class HttpRequestHeader(
    val startLine: String,
    val headers: Map<String, String>
) {
    companion object {
        fun parse(headerText: String): HttpRequestHeader {
            val lines = headerText.split("\r\n")
            val headers = lines.drop(1)
                .mapNotNull { line ->
                    val parts = line.split(":", limit = 2)
                    if (parts.size != 2) null else parts[0].lowercase() to parts[1].trim()
                }
                .toMap()
            return HttpRequestHeader(lines.firstOrNull().orEmpty(), headers)
        }
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}
