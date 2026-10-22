package com.linkbridge.android

import android.content.Context
import android.content.SharedPreferences

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

/** Where the encoded history lives; SharedPreferences in the app, a plain field in tests. */
interface HistoryBacking {
    fun read(): String?
    fun write(encoded: String)
}

class TransferHistoryStore internal constructor(private val backing: HistoryBacking) {
    constructor(context: Context) : this(PreferencesBacking(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)))

    /**
     * Prepends [entry]. Sends and receives finish on different threads and each caller may
     * build its own store, so the read-modify-write holds one process-wide lock.
     */
    fun add(entry: TransferHistoryEntry) {
        synchronized(LOCK) {
            val history = listOf(entry) + load()
            backing.write(TransferHistoryCodec.encode(history.take(MAX_ENTRIES)))
        }
    }

    fun load(): List<TransferHistoryEntry> = TransferHistoryCodec.decode(backing.read())

    private class PreferencesBacking(private val preferences: SharedPreferences) : HistoryBacking {
        override fun read(): String? = preferences.getString(KEY_HISTORY, null)
        // apply() updates the in-memory map before returning, so the next read sees it.
        override fun write(encoded: String) = preferences.edit().putString(KEY_HISTORY, encoded).apply()
    }

    internal companion object {
        private val LOCK = Any()
        private const val PREFERENCES = "linkbridge_transfer_history"
        private const val KEY_HISTORY = "entries"
        const val MAX_ENTRIES = 50
    }
}
