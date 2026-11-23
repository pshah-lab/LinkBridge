package com.linkbridge.android

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON for [TransferHistoryStore], kept free of Android so it can be tested on the JVM.
 * Entries written before `succeeded`, `detail`, `contentUri` and `deviceId` existed load with their defaults;
 * anything unreadable loads as an empty history rather than crashing.
 */
object TransferHistoryCodec {
    fun encode(entries: List<TransferHistoryEntry>): String =
        JSONArray().apply { entries.forEach { put(it.toJson()) } }.toString()

    fun decode(raw: String?): List<TransferHistoryEntry> {
        if (raw == null) return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.getJSONObject(index).toEntry() }
        }.getOrDefault(emptyList())
    }

    private fun TransferHistoryEntry.toJson() = JSONObject()
        .put("fileName", fileName)
        .put("bytes", bytes)
        .put("peerName", peerName)
        .put("isOutgoing", isOutgoing)
        .put("completedAt", completedAt)
        .put("succeeded", succeeded)
        .put("detail", detail)
        .put("contentUri", contentUri)
        .put("deviceId", deviceId)

    private fun JSONObject.toEntry() = TransferHistoryEntry(
        fileName = getString("fileName"),
        bytes = getLong("bytes"),
        peerName = getString("peerName"),
        isOutgoing = getBoolean("isOutgoing"),
        completedAt = getLong("completedAt"),
        succeeded = optBoolean("succeeded", true),
        detail = optionalString("detail"),
        contentUri = optionalString("contentUri"),
        deviceId = optionalString("deviceId")
    )

    // isNull is true for a missing key too, so older entries load with null.
    private fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else getString(key)
}
