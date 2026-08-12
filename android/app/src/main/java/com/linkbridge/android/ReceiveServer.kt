package com.linkbridge.android

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ReceiveServer(
    private val context: Context,
    private val port: Int,
    private val onStatus: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
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
                        Executors.newSingleThreadExecutor().execute {
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

        val fileName = sanitizeFileName(
            URLDecoder.decode(request.headers["x-file-name"] ?: "received-file", "UTF-8")
        )
        val contentLength = request.headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0) {
            socket.writeJsonResponse(400, """{"error":"missing_content_length"}""")
            return
        }

        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/LinkBridge")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
            }

            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create Downloads item")

            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = contentLength
            var received = 0L
            val buffer = ByteArray(64 * 1024)

            resolver.openOutputStream(uri)?.use { output ->
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("Connection closed before file completed")
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    remaining -= read
                    received += read
                    onStatus("Receiving $fileName\n${formatBytes(received)} / ${formatBytes(contentLength)}")
                }
            } ?: error("Could not open output stream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            val sha256 = digest.digest().toHexString()
            onStatus("Received $fileName\nSaved to Downloads/LinkBridge.")
            socket.writeJsonResponse(200, """{"status":"received","fileName":"$fileName","bytes":$received,"sha256":"$sha256"}""")
        }.onFailure { error ->
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
    }
}

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

