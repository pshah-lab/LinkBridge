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
                val json = JSONObject(stream.bufferedReader().readText())
                val level = ProtocolLevel.fromJson(
                    json.optInt("protocol").takeIf { json.has("protocol") }, json.optInt("minProtocol").takeIf { json.has("minProtocol") }
                )
                ProtocolCompat.message(ProtocolCompat.check(peer = level), peer.deviceName)?.let {
                    throw UpdateRequiredException("$it to pair.")
                }
                json.toDeviceIdentity()
            }
        }
    }

    /** Steps 1-2: exchange keys and nonces. Returns the code to show and the derived secret. */
    /** [responderId] is the Mac's device ID from [fetchDevice]; v3 puts it in the code. */
    fun beginPairing(peer: DiscoveredPeer, localIdentity: DeviceIdentity, responderId: String): Result<PairingSession> {
        return runCatching {
            // Read from the Keystore, not the cached identity: the key sent must be the one ECDH uses.
            val localKeyBase64 = pairingKeys.publicKeyBase64()
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
                    .put("protocol", ProtocolLevel.CURRENT)
                    .put("minProtocol", ProtocolLevel.MINIMUM)
            )
            // The reply is plain HTTP and the version isn't covered by the code, so a missing or
            // lower version may be an on-path downgrade. Refuse anything older than ours; the
            // version is also inside the confirmation MAC, so a rewritten one fails there.
            val replyVersion = request.optInt("pairingVersion", 1)
            if (replyVersion < PairingCode.PAIRING_VERSION) {
                error("Mac is running an older LinkBridge; update it to pair")
            }
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

            val secret = PairingCode.secret(shared, localNonce, macNonce, localIdentity.deviceId, responderId)
            PairingSession(
                code = PairingCode.code(localKey, macKey, localNonce, macNonce, localIdentity.deviceId, responderId),
                authToken = Base64.encodeToString(secret, Base64.NO_WRAP),
                peerKey = Base64.encodeToString(macKey, Base64.NO_WRAP),
                expectedConfirmation = Base64.encodeToString(
                    PairingCode.confirmation(
                        secret, replyVersion, localKey, macKey, localNonce, macNonce, localIdentity.deviceId, responderId
                    ), Base64.NO_WRAP
                )
            )
        }
    }

    /**
     * Step 3: wait for the Mac user to accept or decline, and check the Mac's confirmation.
     * Saves nothing: the caller asks the user whether the Mac lists this phone as paired.
     */
    fun finalizePairing(peer: DiscoveredPeer, localIdentity: DeviceIdentity, session: PairingSession): Result<Unit> {
        return runCatching {
            val reply = postJson(peer, "/pair/finalize", JSONObject().put("deviceId", localIdentity.deviceId), readTimeoutMs = Timeouts.PAIR_FINALIZE_READ_TIMEOUT_MS)
            val expected = runCatching { Base64.decode(session.expectedConfirmation, Base64.NO_WRAP) }.getOrNull()
            if (!PairingCode.isValidFinalizeReply(reply, expected)) {
                error("Pairing check failed: the Mac's confirmation doesn't match. Someone may be intercepting this connection.")
            }
        }
    }

    /** Cleartext is allowed app-wide, so refuse anything that isn't on the local network. */
    private fun localEndpoint(peer: DiscoveredPeer): String {
        require(LanAddress.isLocal(peer.host)) { "Refusing plain HTTP to a non-local address" }
        return peer.endpoint
    }

    private fun updateRequired(body: String, peer: DiscoveredPeer): UpdateRequiredException? =
        ProtocolCompat.rejection(body)?.let { UpdateRequiredException("${ProtocolCompat.message(it, peer.deviceName)}.") }

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
            if (code == 426) ProtocolCompat.rejection(detail)?.let { compatibility ->
                throw UpdateRequiredException("${ProtocolCompat.message(compatibility, peer.deviceName)} to pair.")
            }
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
            ProtocolCompat.message(ProtocolCompat.check(peer = peer.level), peer.deviceName)?.let {
                throw UpdateRequiredException("$it.")
            }
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
            val transferId = UUID.randomUUID().toString()
            val encryptedMetadata = JSONObject()
                .put("transferId", transferId)
                .put("fileName", fileName)
                .put("bytes", size)
                .put("sha256", sha256)
                .toString()
                .toByteArray(Charsets.UTF_8)
            val contentLength = encryptedFile.outputStream().use { output ->
                (openInputStream() ?: error("Could not reopen selected file for encryption")).use { input ->
                    Security.encryptChunked(localToken, input, output, metadata = encryptedMetadata, transferId = transferId) { read ->
                        onProgress(read, size)
                    }
                }
            }
            val timestamp = System.currentTimeMillis().toString()
            val signaturePayload = listOf(
                "POST",
                "/transfer/upload",
                localIdentity.deviceId,
                timestamp,
                transferId,
                Security.ENCRYPTION_SCHEME,
                contentLength.toString()
            ).joinToString("\n")
            val signature = Security.uploadSignature(localToken, Security.ENCRYPTION_SCHEME, signaturePayload)

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
                "X-LinkBridge-Protocol: ${ProtocolLevel.CURRENT}",
                "X-LinkBridge-Min-Protocol: ${ProtocolLevel.MINIMUM}",
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
                    try {
                        connection.output.write(head.toByteArray())
                        encryptedFile.inputStream().use { it.copyTo(connection.output, 64 * 1024) }
                        connection.output.flush()
                    } catch (writeError: java.io.IOException) {
                        // A receiver that refuses early may close before the body is sent; its reply says why.
                        val early = runCatching { readResponse(connection.input) }.getOrNull()
                        if (early != null && early.first == 426) updateRequired(early.second, peer)?.let { throw it }
                        throw writeError
                    }
                    readResponse(connection.input)
                }
            } catch (error: java.io.IOException) {
                throw java.io.IOException("Secure connection to ${peer.deviceName} failed. If this keeps happening, pair again.", error)
            } finally {
                encryptedFile.delete()
            }

            if (status !in 200..299) {
                if (status == 426) updateRequired(body, peer)?.let { throw it }
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

/**
 * [peerKey]: the Mac's pairing key (x9.63, base64), saved with the peer to spot a changed key.
 * [expectedConfirmation]: the `confirm` value (base64) the Mac's finalize reply must carry.
 */
data class PairingSession(
    val code: String,
    val authToken: String,
    val peerKey: String = "",
    val expectedConfirmation: String = ""
)
