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

class PairingKeyStore {
    private val alias = "linkbridge.pairing"

    fun publicKeyBase64(): String {
        val point = (keyPair().public as ECPublicKey).w
        val raw = byteArrayOf(4) + point.affineX.toFixedBytes() + point.affineY.toFixedBytes()
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    fun deriveSecret(peerPublicKeyBase64: String): String {
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
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(agreement.generateSecret())
        return Base64.encodeToString(digest, Base64.NO_WRAP)
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

    private fun keyPair(): java.security.KeyPair {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) {
            return java.security.KeyPair(store.getCertificate(alias).publicKey, store.getKey(alias, null) as java.security.PrivateKey)
        }
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
        )
        return generator.generateKeyPair()
    }
}

private fun java.math.BigInteger.toFixedBytes(): ByteArray {
    val bytes = toByteArray()
    return if (bytes.size == 32) bytes else if (bytes.size > 32) bytes.copyOfRange(bytes.size - 32, bytes.size) else ByteArray(32 - bytes.size) + bytes
}
