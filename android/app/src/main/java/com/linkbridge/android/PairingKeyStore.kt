package com.linkbridge.android

import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.AlgorithmParameters
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPoint
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Where the pairing key pair lives. Generating replaces any stored key, as AndroidKeyStore does. */
interface PairingKeyBackend {
    fun load(): java.security.KeyPair?
    fun generate(): java.security.KeyPair
}

/** The app's backend: an EC key in AndroidKeyStore that never leaves it. */
object AndroidKeyStoreBackend : PairingKeyBackend {
    private const val ALIAS = "linkbridge.pairing"

    override fun load(): java.security.KeyPair? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(ALIAS)) return null
        return java.security.KeyPair(store.getCertificate(ALIAS).publicKey, store.getKey(ALIAS, null) as java.security.PrivateKey)
    }

    override fun generate(): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
        )
        return generator.generateKeyPair()
    }
}

class PairingKeyStore(private val backend: PairingKeyBackend = AndroidKeyStoreBackend) {
    fun publicKeyBase64(): String = Base64.encodeToString(publicKeyBytes(), Base64.NO_WRAP)

    /** The 65-byte uncompressed point of the stored key. */
    fun publicKeyBytes(): ByteArray {
        val point = (keyPair().public as ECPublicKey).w
        return byteArrayOf(4) + point.affineX.toFixedBytes() + point.affineY.toFixedBytes()
    }

    /** Pairing v1 secret: SHA-256 of the ECDH output. Kept for Macs that don't speak v3. */
    fun deriveSecret(peerPublicKeyBase64: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sharedSecret(peerPublicKeyBase64))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    /** The raw ECDH output (the x-coordinate), the input to the v3 HKDF. */
    fun sharedSecret(peerPublicKeyBase64: String): ByteArray {
        val peerBytes = normalizePublicKey(peerPublicKeyBase64)
        val parameters = AlgorithmParameters.getInstance("EC").apply {
            init(ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(java.security.spec.ECParameterSpec::class.java)
        val point = ECPoint(
            java.math.BigInteger(1, peerBytes.copyOfRange(1, 33)),
            java.math.BigInteger(1, peerBytes.copyOfRange(33, 65))
        )
        val peerKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair().private)
        agreement.doPhase(peerKey, true)
        return agreement.generateSecret()
    }

    /** Returns the 65-byte uncompressed point; accepts the legacy 64-byte Mac encoding. */
    fun normalizePublicKey(base64: String): ByteArray {
        val bytes = Base64.decode(base64, Base64.NO_WRAP)
        return when {
            bytes.size == 65 && bytes[0].toInt() == 4 -> bytes
            bytes.size == 64 -> byteArrayOf(4) + bytes
            else -> throw IllegalArgumentException("Invalid public key")
        }
    }

    /**
     * Check-then-generate under one process-wide lock. The service and the view model both
     * reach this on first launch; without the lock each generated a key, the Keystore kept the
     * last, and a caller that read the first public key paired with a key it no longer held.
     */
    private fun keyPair(): java.security.KeyPair = synchronized(LOCK) { backend.load() ?: backend.generate() }

    private companion object {
        val LOCK = Any()
    }
}

private fun java.math.BigInteger.toFixedBytes(): ByteArray {
    val bytes = toByteArray()
    return if (bytes.size == 32) bytes else if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else ByteArray(32 - bytes.size) + bytes
}
