package com.linkbridge.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class TrustedPeerStore(context: Context) {
    private val credentials = CredentialStore(context, "trusted_peers")

    fun save(peer: TrustedPeer) {
        write(load().filterNot { it.deviceId == peer.deviceId } + peer)
    }

    fun load(): List<TrustedPeer> {
        val raw = credentials.getString(KEY_PEERS) ?: return emptyList()
        val json = JSONArray(raw)
        return buildList {
            for (index in 0 until json.length()) {
                add(json.getJSONObject(index).toTrustedPeer())
            }
        }
    }

    /** Forgets [deviceId]; backs Unpair. */
    fun remove(deviceId: String) {
        write(load().filterNot { it.deviceId == deviceId })
    }

    private fun write(peers: List<TrustedPeer>) {
        val json = JSONArray()
        peers.forEach { json.put(it.toJson()) }
        credentials.putString(KEY_PEERS, json.toString())
    }

    fun isTrusted(deviceId: String?): Boolean {
        if (deviceId == null) return false
        return load().any { it.deviceId == deviceId && it.authToken != null }
    }

    fun find(deviceId: String?): TrustedPeer? {
        if (deviceId == null) return null
        return load().firstOrNull { it.deviceId == deviceId }
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
    val port: Int,
    val authToken: String?,
    /** When pairing finished; null for peers paired before this was recorded. */
    val pairedAt: Long? = null,
    /** The Mac's pairing key (x9.63, base64) at pairing time; null for older entries. */
    val pairingKey: String? = null
)

/** Whether this phone already trusts a device ID it is pairing with. */
enum class ExistingPairing {
    NONE,
    /** Same key as before, or an older entry without one: pairing again replaces it. */
    SAME_PEER,
    /** Trusted under a different key: a reinstall, or another device posing as it. */
    KEY_CHANGED;

    companion object {
        fun of(trusted: TrustedPeer?, peerKey: String): ExistingPairing = when {
            trusted == null -> NONE
            trusted.pairingKey == null || trusted.pairingKey == peerKey -> SAME_PEER
            else -> KEY_CHANGED
        }
    }
}

private fun TrustedPeer.toJson(): JSONObject {
    return JSONObject()
        .put("deviceId", deviceId)
        .put("deviceName", deviceName)
        .put("platform", platform)
        .put("host", host)
        .put("port", port)
        .put("authToken", authToken)
        .put("pairedAt", pairedAt)
        .put("pairingKey", pairingKey)
}

private fun JSONObject.toTrustedPeer(): TrustedPeer {
    return TrustedPeer(
        deviceId = getString("deviceId"),
        deviceName = getString("deviceName"),
        platform = getString("platform"),
        host = getString("host"),
        port = getInt("port"),
        authToken = optString("authToken").ifBlank { null },
        pairedAt = if (isNull("pairedAt")) null else getLong("pairedAt"),
        pairingKey = optString("pairingKey").ifBlank { null }
    )
}
