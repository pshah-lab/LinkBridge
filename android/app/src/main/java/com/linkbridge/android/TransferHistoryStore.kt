package com.linkbridge.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TransferHistoryEntry(
    val fileName: String,
    val bytes: Long,
    val peerName: String,
    val isOutgoing: Boolean,
    val completedAt: Long = System.currentTimeMillis()
)

class TransferHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun add(entry: TransferHistoryEntry) {
        val history = load().toMutableList()
        history.add(0, entry)
        preferences.edit().putString(KEY_HISTORY, JSONArray().apply {
            history.take(MAX_ENTRIES).forEach { put(it.toJson()) }
        }.toString()).apply()
    }

    fun load(): List<TransferHistoryEntry> {
        val raw = preferences.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val json = JSONArray(raw)
            buildList {
                for (index in 0 until json.length()) {
                    val item = json.getJSONObject(index)
                    add(
                        TransferHistoryEntry(
                            fileName = item.getString("fileName"),
                            bytes = item.getLong("bytes"),
                            peerName = item.getString("peerName"),
                            isOutgoing = item.getBoolean("isOutgoing"),
                            completedAt = item.getLong("completedAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun TransferHistoryEntry.toJson() = JSONObject()
        .put("fileName", fileName)
        .put("bytes", bytes)
        .put("peerName", peerName)
        .put("isOutgoing", isOutgoing)
        .put("completedAt", completedAt)

    private companion object {
        const val PREFERENCES = "linkbridge_transfer_history"
        const val KEY_HISTORY = "entries"
        const val MAX_ENTRIES = 50
    }
}
