package com.linkbridge.android

import java.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Upload encryption inside the TLS session. Must match macos/.../Security.swift.
 *
 * v2 (what senders use): separate encryption and MAC keys from HKDF-SHA256 of the pairing secret
 * (salt "linkbridge-chunk-v2", info "enc" / "mac"). Each record's AAD is transferId (16 bytes) ||
 * record index (big-endian long) || the record's 4-byte length field, whose top bit marks the
 * last record, so records can't be reordered, dropped, spliced or cut off at a boundary.
 *
 * v1 (still accepted from older senders): the raw secret for both, no AAD.
 */
object Security {
    const val ENCRYPTION_SCHEME = "aes-gcm-chunked-v2"
    const val LEGACY_ENCRYPTION_SCHEME = "aes-gcm-chunked-v1"
    val SUPPORTED_SCHEMES = setOf(ENCRYPTION_SCHEME, LEGACY_ENCRYPTION_SCHEME)
    const val CHUNK_SIZE = 1024 * 1024
    private const val LAST_RECORD_FLAG = 0x8000_0000.toInt()

    fun hmacSha256Base64(secret: String, message: String): String =
        hmacBase64(Base64.getDecoder().decode(secret), message)

    /** The upload head's signature for [scheme]. */
    fun uploadSignature(secret: String, scheme: String, message: String): String =
        hmacBase64(keys(secret, scheme).second, message)

    /** (encryption key, MAC key) for [scheme]. */
    fun keys(secret: String, scheme: String): Pair<ByteArray, ByteArray> {
        val raw = Base64.getDecoder().decode(secret)
        return when (scheme) {
            LEGACY_ENCRYPTION_SCHEME -> raw to raw
            ENCRYPTION_SCHEME -> {
                val salt = "linkbridge-chunk-v2".toByteArray()
                Hkdf.sha256(raw, salt, "enc".toByteArray()) to Hkdf.sha256(raw, salt, "mac".toByteArray())
            }
            else -> throw IllegalArgumentException("Unknown encryption scheme")
        }
    }

    fun recordAad(transferId: String, index: Long, lengthField: Int): ByteArray =
        PairingCode.uuidBytes(transferId) + ByteBuffer.allocate(12).putLong(index).putInt(lengthField).array()

    fun timingSafeEquals(left: String, right: String): Boolean {
        val a = left.toByteArray()
        val b = right.toByteArray()
        if (a.size != b.size) return false
        var result = 0
        for (index in a.indices) {
            result = result or (a[index].toInt() xor b[index].toInt())
        }
        return result == 0
    }

    /** Writes the metadata record (if any) and the input's chunks as v2 records. */
    fun encryptChunked(
        secret: String,
        input: InputStream,
        output: OutputStream,
        metadata: ByteArray? = null,
        transferId: String,
        onProgress: (bytesRead: Long) -> Unit = {}
    ): Long {
        val key = SecretKeySpec(keys(secret, ENCRYPTION_SCHEME).first, "AES")
        val random = SecureRandom()
        var encryptedBytes = 0L
        var plainBytes = 0L
        var index = 0L

        fun write(plain: ByteArray, last: Boolean) {
            val nonce = ByteArray(12).also(random::nextBytes)
            val record = record(plain, key, nonce, transferId, index, last)
            output.write(record)
            encryptedBytes += record.size
            index += 1
        }

        // Read one chunk ahead so the last record can be marked.
        var next = input.readChunk()
        if (metadata != null) write(metadata, last = next.isEmpty())
        while (next.isNotEmpty()) {
            val chunk = next
            next = input.readChunk()
            write(chunk, last = next.isEmpty())
            plainBytes += chunk.size
            onProgress(plainBytes)
        }
        if (index == 0L) write(ByteArray(0), last = true)
        return encryptedBytes
    }

    /** One v2 record: nonce || length field || ciphertext || tag. */
    fun record(plain: ByteArray, key: SecretKeySpec, nonce: ByteArray, transferId: String, index: Long, last: Boolean): ByteArray {
        val lengthField = (plain.size + 16) or (if (last) LAST_RECORD_FLAG else 0)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(recordAad(transferId, index, lengthField))
        val sealed = cipher.doFinal(plain)
        return nonce + ByteBuffer.allocate(4).putInt(lengthField).array() + sealed
    }

    fun decryptChunkedRecords(
        secret: String,
        input: InputStream,
        encryptedLength: Long,
        scheme: String = ENCRYPTION_SCHEME,
        transferId: String,
        onChunk: (plainBytes: ByteArray) -> Unit
    ): Long {
        val v2 = scheme == ENCRYPTION_SCHEME
        val key = SecretKeySpec(keys(secret, scheme).first, "AES")
        var remaining = encryptedLength
        var plainBytes = 0L
        var index = 0L
        var sawLast = false

        while (remaining > 0) {
            // Nothing may follow the record marked last.
            require(!sawLast) { "Data after the last encrypted record" }
            val nonce = input.readExact(12)
            remaining -= 12
            val lengthField = ByteBuffer.wrap(input.readExact(4)).int
            remaining -= 4
            val last = v2 && (lengthField and LAST_RECORD_FLAG) != 0
            val sealedLength = if (v2) lengthField and LAST_RECORD_FLAG.inv() else lengthField
            require(sealedLength >= 16 && sealedLength <= CHUNK_SIZE + 16) { "Invalid encrypted chunk length" }
            require(remaining >= sealedLength) { "Encrypted body ended before chunk completed" }

            val sealed = input.readExact(sealedLength)
            remaining -= sealedLength
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            if (v2) cipher.updateAAD(recordAad(transferId, index, lengthField))
            val plain = cipher.doFinal(sealed)
            index += 1
            sawLast = last

            onChunk(plain)
            plainBytes += plain.size
        }
        require(!v2 || sawLast) { "Encrypted body ended before the last record" }

        return plainBytes
    }

    private fun hmacBase64(key: ByteArray, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray()))
    }
}

/** Up to [Security.CHUNK_SIZE] bytes, fewer only at the end; empty at end of stream. */
private fun InputStream.readChunk(): ByteArray {
    val buffer = ByteArray(Security.CHUNK_SIZE)
    var filled = 0
    while (filled < buffer.size) {
        val read = read(buffer, filled, buffer.size - filled)
        if (read < 0) break
        filled += read
    }
    return buffer.copyOf(filled)
}

private fun InputStream.readExact(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read < 0) error("Connection closed before encrypted chunk completed")
        offset += read
    }
    return bytes
}
