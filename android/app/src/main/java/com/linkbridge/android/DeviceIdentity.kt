package com.linkbridge.android

import android.content.Context
import android.util.Base64
import java.security.SecureRandom
import java.util.UUID

data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val protocolVersion: Int = 1,
    val features: List<String> = listOf("file-transfer", "display-send", "display-receive"),
    val authToken: String? = null,
    val publicKey: String? = null
)

class IdentityStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("linkbridge_identity", Context.MODE_PRIVATE)
    private val credentials = CredentialStore(context, "identity")
    private val pairingKeys = PairingKeyStore()

    fun loadOrCreate(): DeviceIdentity {
        val existingId = preferences.getString("device_id", null)
        val deviceId = existingId ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }
        val existingToken = credentials.getString("auth_token")
        val authToken = existingToken ?: generateToken().also {
            credentials.putString("auth_token", it)
        }

        return DeviceIdentity(
            deviceId = deviceId,
            deviceName = android.os.Build.MODEL ?: "Android",
            authToken = authToken,
            publicKey = pairingKeys.publicKeyBase64()
        )
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
