package com.linkbridge.android

import android.content.Context
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

/** Encrypts secrets at rest with a key held by the Android Keystore. */
class CredentialStore(context: Context, private val name: String) {
    private val preferences = context.getSharedPreferences("linkbridge_secure_$name", Context.MODE_PRIVATE)
    private val alias = "linkbridge.$name"

    fun getString(key: String): String? {
        val stored = preferences.getString(key, null) ?: return null
        if (!stored.startsWith(VERSION)) {
            // Migrate a legacy plaintext value on first read.
            putString(key, stored)
            return stored
        }
        return decrypt(stored.removePrefix(VERSION))
    }

    fun putString(key: String, value: String) {
        preferences.edit().putString(key, VERSION + encrypt(value)).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(ByteBuffer.allocate(4 + iv.size + ciphertext.size).apply {
            putInt(iv.size)
            put(iv)
            put(ciphertext)
        }.array(), Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val packed = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP))
        val iv = ByteArray(packed.int).also(packed::get)
        val ciphertext = ByteArray(packed.remaining()).also(packed::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val VERSION = "v1:"
    }
}
