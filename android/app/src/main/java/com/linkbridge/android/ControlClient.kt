package com.linkbridge.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class ControlClient {
    fun fetchDevice(peer: DiscoveredPeer): Result<DeviceIdentity> {
        return runCatching {
            val url = URL("http://${peer.endpoint}/device")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }

            connection.inputStream.use { stream ->
                val json = stream.bufferedReader().readText()
                JSONObject(json).toDeviceIdentity()
            }
        }
    }

    fun pair(peer: DiscoveredPeer, localIdentity: DeviceIdentity): Result<Unit> {
        return runCatching {
            val url = URL("http://${peer.endpoint}/pair/request")
            val body = JSONObject()
                .put("deviceId", localIdentity.deviceId)
                .put("deviceName", localIdentity.deviceName)
                .put("platform", localIdentity.platform)
                .put("protocolVersion", localIdentity.protocolVersion)
                .put("features", JSONArray(localIdentity.features))
                .toString()
                .toByteArray()

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Length", body.size.toString())
            }

            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) {
                error("Pair request failed: HTTP ${connection.responseCode}")
            }
        }
    }

    fun uploadFile(
        peer: DiscoveredPeer,
        fileName: String,
        size: Long,
        inputStream: InputStream,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Unit> {
        return runCatching {
            require(size >= 0) { "Selected file size is unknown; choose a file provider that exposes file size." }
            val digest = MessageDigest.getInstance("SHA-256")
            val contentLength = size
            val url = URL("http://${peer.endpoint}/transfer/upload")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-File-Name", URLEncoder.encode(fileName, "UTF-8"))
                setRequestProperty("Content-Length", contentLength.toString())
                setFixedLengthStreamingMode(contentLength)
            }

            connection.outputStream.use { output ->
                inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        sent += read
                        onProgress(sent, contentLength)
                    }
                }
            }

            val sha256 = digest.digest().toHexString()
            if (connection.responseCode !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.readText()
                error("Upload failed: HTTP ${connection.responseCode}${message?.let { " $it" } ?: ""}")
            }

            connection.inputStream.use { stream ->
                val response = JSONObject(stream.bufferedReader().readText())
                val remoteSha256 = response.optString("sha256")
                if (remoteSha256.isNotBlank() && !remoteSha256.equals(sha256, ignoreCase = true)) {
                    error("Checksum mismatch after upload")
                }
            }
        }
    }

    private fun JSONObject.toDeviceIdentity(): DeviceIdentity {
        val featuresJson = optJSONArray("features") ?: JSONArray()
        val features = buildList {
            for (index in 0 until featuresJson.length()) {
                add(featuresJson.getString(index))
            }
        }

        return DeviceIdentity(
            deviceId = getString("deviceId"),
            deviceName = getString("deviceName"),
            platform = getString("platform"),
            protocolVersion = getInt("protocolVersion"),
            features = features
        )
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}
