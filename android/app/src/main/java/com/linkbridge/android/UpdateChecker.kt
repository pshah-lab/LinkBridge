package com.linkbridge.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Website builds only (`BuildConfig.UPDATE_CHECK`): checks latest.json once a day and offers a link
 * to the download page. Never downloads or installs anything; Play builds never construct it.
 */
class UpdateChecker private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("linkbridge.updates", Context.MODE_PRIVATE)
    private var latest: LatestRelease? = prefs.getString(KEY_LATEST, null)?.let { UpdateCheck.parse(it, "android") }

    private val _available = MutableStateFlow<LatestRelease?>(null)
    val available: StateFlow<LatestRelease?> = _available.asStateFlow()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
        refresh()
    }

    /** "Not now": hide until a higher build appears. */
    fun dismiss() {
        val release = _available.value ?: return
        prefs.edit().putInt(KEY_DISMISSED, release.build).apply()
        refresh()
    }

    /** Fetches latest.json if a day has passed. Failures are silent and retried at the next call. */
    suspend fun checkIfDue(nowMillis: Long = System.currentTimeMillis()) {
        val last = prefs.getLong(KEY_LAST_CHECK, -1L).takeIf { it >= 0 }
        if (_enabled.value && UpdateCheck.isDue(last, nowMillis)) {
            val body = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() }
            if (body != null && _enabled.value) {
                prefs.edit().putLong(KEY_LAST_CHECK, nowMillis).apply()
                UpdateCheck.parse(body, "android")?.let {
                    latest = it
                    prefs.edit().putString(KEY_LATEST, body.take(16 * 1024)).apply()
                }
            }
        }
        refresh()
    }

    private fun fetch(): String? {
        val connection = (URL(UpdateCheck.FEED_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "LinkBridge")
        }
        return try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { it.reader().readText().take(64 * 1024) }
        } finally {
            connection.disconnect()
        }
    }

    private fun refresh() {
        val dismissed = prefs.getInt(KEY_DISMISSED, -1).takeIf { it >= 0 }
        _available.value = latest.takeIf {
            _enabled.value && UpdateCheck.shouldShow(it, BuildConfig.VERSION_CODE, dismissed)
        }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_LAST_CHECK = "lastCheck"
        private const val KEY_DISMISSED = "dismissedBuild"
        private const val KEY_LATEST = "latestFeed"
        const val TICK_MS = 60 * 60 * 1000L

        /** Null in Play and debug builds: only the website release build sets `UPDATE_CHECK`. */
        fun createIfWebsiteBuild(context: Context): UpdateChecker? =
            if (BuildConfig.UPDATE_CHECK) UpdateChecker(context) else null
    }
}
