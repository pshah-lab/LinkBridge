package com.linkbridge.android

import android.content.Context
import java.util.UUID

data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val platform: String = "android",
    val protocolVersion: Int = 1,
    val features: List<String> = listOf("file-transfer", "display-send", "display-receive")
)

class IdentityStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("linkbridge_identity", Context.MODE_PRIVATE)

    fun loadOrCreate(): DeviceIdentity {
        val existingId = preferences.getString("device_id", null)
        val deviceId = existingId ?: UUID.randomUUID().toString().also {
            preferences.edit().putString("device_id", it).apply()
        }

        return DeviceIdentity(
            deviceId = deviceId,
            deviceName = android.os.Build.MODEL ?: "Android"
        )
    }
}

