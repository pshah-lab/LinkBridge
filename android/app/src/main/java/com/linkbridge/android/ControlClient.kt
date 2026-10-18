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
            val url = URL("http://${localEndpoint(peer)}/device")
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
    /** [responderId] is the Mac's device ID from [fetchDevice]; v3 puts it in the code. */
    fun beginPairing(peer: DiscoveredPeer, localIdentity: DeviceIdentity, responderId: String): Result<PairingSession> {
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
                    .put("pairingVersion", PairingCode.PAIRING_VERSION)
            )
            val v3 = request.optInt("pairingVersion", 1) >= 3
            val macKeyBase64 = request.optString("publicKey").ifBlank { error("Mac did not provide a public key") }
            val commitment = Base64.decode(request.optString("commitment").ifBlank { error("Mac is running an older LinkBridge; update it to pair") }, Base64.NO_WRAP)
            val macKey = pairingKeys.normalizePublicKey(macKeyBase64)
            val localKey = pairingKeys.normalizePublicKey(localKeyBase64)
            val shared = pairingKeys.sharedSecret(macKeyBase64)

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

            if (v3) {
                PairingSession(
                    code = PairingCode.code(localKey, macKey, localNonce, macNonce, localIdentity.deviceId, responderId),
                    authToken = android.util.Base64.encodeToString(
                        PairingCode.secret(shared, localNonce, macNonce, localIdentity.deviceId, responderId), Base64.NO_WRAP
                    )
                )
            } else {
                PairingSession(
                    code = PairingCode.code(localKey, macKey, localNonce, macNonce),
                    authToken = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(shared), Base64.NO_WRAP)
                )
            }
        }
    }

    /** Step 3: wait for the Mac user to accept or decline. */
    fun finalizePairing(peer: DiscoveredPeer, localIdentity: DeviceIdentity): Result<Unit> {
        return runCatching {
            postJson(peer, "/pair/finalize", JSONObject().put("deviceId", localIdentity.deviceId), readTimeoutMs = 120_000)
            Unit
        }
    }

    /** Cleartext is allowed app-wide, so refuse anything that isn't on the local network. */
    private fun localEndpoint(peer: DiscoveredPeer): String {
        require(LanAddress.isLocal(peer.host)) { "Refusing plain HTTP to a non-local address" }
        return peer.endpoint
    }

    private fun postJson(peer: DiscoveredPeer, path: String, json: JSONObject, readTimeoutMs: Int = 5000): JSONObject {
        val body = json.toString().toByteArray()
        val connection = (URL("http://${localEndpoint(peer)}$path").openConnection() as HttpURLConnection).apply {
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

            val tlsPort = peer.tlsPort
                ?: run { encryptedFile.delete(); error("Update LinkBridge on ${peer.deviceName} to the secure transfer version.") }
            val head = listOf(
                "POST /transfer/upload HTTP/1.1",
                "Host: ${peer.host}",
                "Content-Type: application/octet-stream",
                "Content-Length: $contentLength",
                "X-LinkBridge-Device-Id: ${localIdentity.deviceId}",
                "X-LinkBridge-Timestamp: $timestamp",
                "X-LinkBridge-Transfer-Id: $transferId",
                "X-LinkBridge-Encryption: ${Security.ENCRYPTION_SCHEME}",
                "X-LinkBridge-Signature: $signature",
                "Connection: close",
                "",
                ""
            ).joinToString("\r\n")

            val (status, body) = try {
                SecureTransport.connect(
                    host = peer.host,
                    port = tlsPort,
                    identity = SecureTransport.identity(localIdentity.deviceId),
                    psk = SecureTransport.psk(localToken)
                ).use { connection ->
                    connection.output.write(head.toByteArray())
                    encryptedFile.inputStream().use { it.copyTo(connection.output, 64 * 1024) }
                    connection.output.flush()
                    readResponse(connection.input)
                }
            } catch (error: java.io.IOException) {
                throw java.io.IOException("Secure connection to ${peer.deviceName} failed. If this keeps happening, pair again.", error)
            } finally {
                encryptedFile.delete()
            }

            if (status !in 200..299) {
                error("Upload failed: HTTP $status $body")
            }
            val remoteSha256 = JSONObject(body).optString("sha256")
            if (remoteSha256.isNotBlank() && !remoteSha256.equals(sha256, ignoreCase = true)) {
                error("Checksum mismatch after upload")
            }
        }
    }

    /** Reads a `Connection: close` HTTP response: status code and body text. */
    private fun readResponse(input: InputStream): Pair<Int, String> {
        val bytes = input.readBytes()
        val text = bytes.toString(Charsets.UTF_8)
        val separator = text.indexOf("\r\n\r\n")
        if (separator < 0) error("Unreadable response from the Mac")
        val status = text.substringBefore("\r\n").split(" ").getOrNull(1)?.toIntOrNull()
            ?: error("Unreadable response from the Mac")
        return status to text.substring(separator + 4)
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
