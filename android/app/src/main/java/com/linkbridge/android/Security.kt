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

object Security {
    const val ENCRYPTION_SCHEME = "aes-gcm-chunked-v1"
    const val CHUNK_SIZE = 1024 * 1024

    fun hmacSha256Base64(secret: String, message: String): String {
        val key = Base64.getDecoder().decode(secret)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray()))
    }

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

    fun encryptChunked(
        secret: String,
        input: InputStream,
        output: OutputStream,
        metadata: ByteArray? = null,
        onProgress: (bytesRead: Long) -> Unit = {}
    ): Long {
        val key = SecretKeySpec(Base64.getDecoder().decode(secret), "AES")
        val random = SecureRandom()
        val buffer = ByteArray(CHUNK_SIZE)
        var encryptedBytes = 0L
        var plainBytes = 0L

        if (metadata != null) {
            encryptedBytes += writeEncryptedRecord(key, random, output, metadata, metadata.size)
        }

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break

            encryptedBytes += writeEncryptedRecord(key, random, output, buffer, read)
            plainBytes += read
            onProgress(plainBytes)
        }

        return encryptedBytes
    }

    fun decryptChunkedRecords(
        secret: String,
        input: InputStream,
        encryptedLength: Long,
        onChunk: (plainBytes: ByteArray) -> Unit
    ): Long {
        val key = SecretKeySpec(Base64.getDecoder().decode(secret), "AES")
        var remaining = encryptedLength
        var plainBytes = 0L

        while (remaining > 0) {
            val nonce = input.readExact(12)
            remaining -= 12
            val sealedLength = ByteBuffer.wrap(input.readExact(4)).int
            remaining -= 4
            require(sealedLength > 0 && sealedLength <= CHUNK_SIZE + 16) { "Invalid encrypted chunk length" }
            require(remaining >= sealedLength) { "Encrypted body ended before chunk completed" }

            val sealed = input.readExact(sealedLength)
            remaining -= sealedLength
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
            val plain = cipher.doFinal(sealed)

            onChunk(plain)
            plainBytes += plain.size
        }

        return plainBytes
    }

    private fun writeEncryptedRecord(
        key: SecretKeySpec,
        random: SecureRandom,
        output: OutputStream,
        plain: ByteArray,
        plainLength: Int
    ): Long {
        val nonce = ByteArray(12)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val sealed = cipher.doFinal(plain, 0, plainLength)
        val lengthBytes = ByteBuffer.allocate(4).putInt(sealed.size).array()

        output.write(nonce)
        output.write(lengthBytes)
        output.write(sealed)
        return (nonce.size + lengthBytes.size + sealed.size).toLong()
    }
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
