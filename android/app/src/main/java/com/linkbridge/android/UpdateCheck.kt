package com.linkbridge.android

import org.json.JSONObject
import java.net.URI

/** The newest website build, from https://linkbridge.pratham.si/latest.json. */
data class LatestRelease(val version: String, val build: Int, val url: String, val notes: String)

/**
 * Pure logic for the "new version available" banner in website builds (`BuildConfig.UPDATE_CHECK`).
 * It only links to the site: Play builds don't contain the check, and nothing is ever downloaded.
 */
object UpdateCheck {
    const val FEED_URL = "https://linkbridge.pratham.si/latest.json"
    const val SITE_URL = "https://linkbridge.pratham.si/"
    private const val SITE_HOST = "linkbridge.pratham.si"
    const val INTERVAL_MS = 24 * 60 * 60 * 1000L
    private const val MAX_NOTES = 120

    /** `platform` is "android" (build from `versionCode`) or "mac" (from `build`). Null for anything malformed. */
    fun parse(json: String, platform: String): LatestRelease? {
        val entry = runCatching { JSONObject(json) }.getOrNull()?.optJSONObject(platform) ?: return null
        val version = entry.optString("version").takeIf { it.isNotEmpty() } ?: return null
        val build = entry.opt(if (platform == "android") "versionCode" else "build") as? Number ?: return null
        val notes = entry.optString("notes").lineSequence().firstOrNull().orEmpty().take(MAX_NOTES)
        return LatestRelease(version.take(20), build.toInt(), safeUrl(entry.optString("url").ifEmpty { null }), notes)
    }

    /** Only `https` links on the LinkBridge site are opened; anything else opens the site's home page. */
    fun safeUrl(url: String?): String {
        val uri = url?.let { runCatching { URI(it) }.getOrNull() } ?: return SITE_URL
        val ok = uri.scheme == "https" && uri.host?.lowercase() == SITE_HOST && uri.userInfo == null && uri.port == -1
        return if (ok) url else SITE_URL
    }

    fun shouldShow(latest: LatestRelease?, currentBuild: Int, dismissedBuild: Int?): Boolean {
        if (latest == null || latest.build <= currentBuild) return false
        return dismissedBuild == null || latest.build > dismissedBuild
    }

    /** Due once a day. A last check in the future (clock moved back) counts as due. */
    fun isDue(lastCheckMillis: Long?, nowMillis: Long): Boolean {
        if (lastCheckMillis == null) return true
        val elapsed = nowMillis - lastCheckMillis
        return elapsed < 0 || elapsed >= INTERVAL_MS
    }
}
