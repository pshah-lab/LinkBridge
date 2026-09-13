package com.linkbridge.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class ControlClient {
    private val pairingKeys = PairingKeyStore()
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

    fun pair(peer: DiscoveredPeer, localIdentity: DeviceIdentity): Result<String?> {
        return runCatching {
            val url = URL("http://${peer.endpoint}/pair/request")
            val body = JSONObject()
                .put("deviceId", localIdentity.deviceId)
                .put("deviceName", localIdentity.deviceName)
                .put("platform", localIdentity.platform)
                .put("protocolVersion", localIdentity.protocolVersion)
                .put("features", JSONArray(localIdentity.features))
                .put("publicKey", localIdentity.publicKey ?: pairingKeys.publicKeyBase64())
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
            connection.inputStream.use { stream ->
                val response = JSONObject(stream.bufferedReader().readText())
                val peerPublicKey = response.optString("publicKey").ifBlank { error("Peer did not provide a public key") }
                pairingKeys.deriveSecret(peerPublicKey)
            }
        }
    }

    fun uploadFile(
        peer: DiscoveredPeer,
        fileName: String,
        size: Long,
        localIdentity: DeviceIdentity,
        trustedPeer: TrustedPeer?,
        openInputStream: () -> InputStream?,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<Unit> {
        return runCatching {
            require(size >= 0) { "Selected file size is unknown; choose a file provider that exposes file size." }
            trustedPeer?.authToken
                ?: error("This Mac must be paired again before secure transfer.")
            val localToken = trustedPeer.authToken
                ?: error("This Android device must be paired again before secure transfer.")
            val sha256 = (openInputStream() ?: error("Could not open selected file")).use { input ->
                val preHash = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    preHash.update(buffer, 0, read)
                }
                preHash.digest().toHexString()
            }

            val encryptedFile = File.createTempFile("linkbridge-upload-", ".enc")
            val encryptedMetadata = JSONObject()
                .put("transferId", UUID.randomUUID().toString())
                .put("fileName", fileName)
                .put("bytes", size)
                .put("sha256", sha256)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val contentLength = encryptedFile.outputStream().use { output ->
                (openInputStream() ?: error("Could not reopen selected file for encryption")).use { input ->
                    Security.encryptChunked(localToken, input, output, metadata = encryptedMetadata) { read ->
                        onProgress(read, size)
                    }
                }
            }
            val timestamp = System.currentTimeMillis().toString()
            val transferId = JSONObject(String(encryptedMetadata, Charsets.UTF_8)).getString("transferId")
            val signaturePayload = listOf(
                "POST",
                "/transfer/upload",
                localIdentity.deviceId,
                timestamp,
                transferId,
                Security.ENCRYPTION_SCHEME,
                contentLength.toString()
            ).joinToString("\n")
            val signature = Security.hmacSha256Base64(localToken, signaturePayload)

            val url = URL("http://${peer.endpoint}/transfer/upload")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 5000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/octet-stream")
                setRequestProperty("X-LinkBridge-Device-Id", localIdentity.deviceId)
                setRequestProperty("X-LinkBridge-Timestamp", timestamp)
                setRequestProperty("X-LinkBridge-Transfer-Id", transferId)
                setRequestProperty("X-LinkBridge-Encryption", Security.ENCRYPTION_SCHEME)
                setRequestProperty("X-LinkBridge-Signature", signature)
                setRequestProperty("Content-Length", contentLength.toString())
                setFixedLengthStreamingMode(contentLength)
            }

            try {
                connection.outputStream.use { output ->
                    encryptedFile.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
            } finally {
                encryptedFile.delete()
            }

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
            features = features,
            publicKey = optString("publicKey").ifBlank { null }
        )
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(separator = "") { byte -> "%02x".format(byte) }
}
