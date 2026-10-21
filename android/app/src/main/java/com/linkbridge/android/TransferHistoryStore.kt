package com.linkbridge.android

import android.content.Context

data class TransferHistoryEntry(
    val fileName: String,
    val bytes: Long,
    val peerName: String,
    val isOutgoing: Boolean,
    val completedAt: Long = System.currentTimeMillis(),
    val succeeded: Boolean = true,
    /** The error message on a failed transfer. */
    val detail: String? = null,
    /** A content URI the app can still read: the received file, or a send it can retry. */
    val contentUri: String? = null
)

class TransferHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    fun add(entry: TransferHistoryEntry) {
        val history = listOf(entry) + load()
        preferences.edit().putString(KEY_HISTORY, TransferHistoryCodec.encode(history.take(MAX_ENTRIES))).apply()
    }

    fun load(): List<TransferHistoryEntry> = TransferHistoryCodec.decode(preferences.getString(KEY_HISTORY, null))

    private companion object {
        const val PREFERENCES = "linkbridge_transfer_history"
        const val KEY_HISTORY = "entries"
        const val MAX_ENTRIES = 50
    }
}
