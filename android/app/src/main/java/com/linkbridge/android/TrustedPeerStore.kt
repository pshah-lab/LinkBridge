package com.linkbridge.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TrustedPeerStore(context: Context) {
    private val preferences = context.getSharedPreferences("linkbridge_trusted_peers", Context.MODE_PRIVATE)

    fun save(peer: TrustedPeer) {
        val peers = load().filterNot { it.deviceId == peer.deviceId } + peer
        val json = JSONArray()
        peers.forEach { json.put(it.toJson()) }
        preferences.edit().putString(KEY_PEERS, json.toString()).apply()
    }

    fun load(): List<TrustedPeer> {
        val raw = preferences.getString(KEY_PEERS, null) ?: return emptyList()
        val json = JSONArray(raw)
        return buildList {
            for (index in 0 until json.length()) {
                add(json.getJSONObject(index).toTrustedPeer())
            }
        }
    }

    fun isTrusted(deviceId: String?): Boolean {
        if (deviceId == null) return false
        return load().any { it.deviceId == deviceId }
    }

    companion object {
        private const val KEY_PEERS = "peers"
    }
}

data class TrustedPeer(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val host: String,
    val port: Int
)

private fun TrustedPeer.toJson(): JSONObject {
    return JSONObject()
        .put("deviceId", deviceId)
        .put("deviceName", deviceName)
        .put("platform", platform)
        .put("host", host)
        .put("port", port)
}

private fun JSONObject.toTrustedPeer(): TrustedPeer {
    return TrustedPeer(
        deviceId = getString("deviceId"),
        deviceName = getString("deviceName"),
        platform = getString("platform"),
        host = getString("host"),
        port = getInt("port")
    )
}

