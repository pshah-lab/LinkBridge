package com.linkbridge.android

import android.util.Base64
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

    /** Steps 1-2: exchange keys and nonces. Returns the code to show and the derived secret. */
    fun beginPairing(peer: DiscoveredPeer, localIdentity: DeviceIdentity): Result<PairingSession> {
        return runCatching {
            val localKeyBase64 = localIdentity.publicKey ?: pairingKeys.publicKeyBase64()
            val request = postJson(
                peer, "/pair/request",
                JSONObject()
                    .put("deviceId", localIdentity.deviceId)
                    .put("deviceName", localIdentity.deviceName)
                    .put("platform", localIdentity.platform)
                    .put("protocolVersion", localIdentity.protocolVersion)
                    .put("features", JSONArray(localIdentity.features))
                    .put("publicKey", localKeyBase64)
            )
            val macKeyBase64 = request.optString("publicKey").ifBlank { error("Mac did not provide a public key") }
            val commitment = Base64.decode(request.optString("commitment").ifBlank { error("Mac is running an older LinkBridge; update it to pair") }, Base64.NO_WRAP)
            val macKey = pairingKeys.normalizePublicKey(macKeyBase64)
            val localKey = pairingKeys.normalizePublicKey(localKeyBase64)
            val secret = pairingKeys.deriveSecret(macKeyBase64)

            val localNonce = PairingCode.randomNonce()
            val confirm = postJson(
                peer, "/pair/confirm",
                JSONObject()
                    .put("deviceId", localIdentity.deviceId)
                    .put("nonce", Base64.encodeToString(localNonce, Base64.NO_WRAP))
            )
            val macNonce = Base64.decode(confirm.optString("nonce"), Base64.NO_WRAP)
            if (!MessageDigest.isEqual(PairingCode.commitment(macKey, macNonce), commitment)) {
                error("Pairing check failed: the Mac's reply doesn't match its commitment. Someone may be intercepting this connection.")
            }

            PairingSession(
                code = PairingCode.code(localKey, macKey, localNonce, macNonce),
                authToken = secret
            )
        }
    }

    /** Step 3: wait for the Mac user to accept or decline. */
    fun finalizePairing(peer: DiscoveredPeer, localIdentity: DeviceIdentity): Result<Unit> {
        return runCatching {
            postJson(peer, "/pair/finalize", JSONObject().put("deviceId", localIdentity.deviceId), readTimeoutMs = 120_000)
            Unit
        }
    }

    private fun postJson(peer: DiscoveredPeer, path: String, json: JSONObject, readTimeoutMs: Int = 5000): JSONObject {
        val body = json.toString().toByteArray()
        val connection = (URL("http://${peer.endpoint}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 3000
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Content-Length", body.size.toString())
        }
        connection.outputStream.use { it.write(body) }
        val code = connection.responseCode
        if (code !in 200..299) {
            val detail = connection.errorStream?.use { it.bufferedReader().readText() }.orEmpty()
            when {
                detail.contains("pair_declined") -> error("Pairing was declined on the Mac")
                detail.contains("no_pending_pairing") -> error("Pairing expired. Start again.")
                else -> error("$path failed: HTTP $code $detail")
            }
        }
        return connection.inputStream.use { JSONObject(it.bufferedReader().readText()) }
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

data class PairingSession(val code: String, val authToken: String)
