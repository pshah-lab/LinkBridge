package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as the Mac "Update check" checks. */
class UpdateCheckTest {
    private val sample = """
        {"mac": {"version": "0.3.0", "build": 4, "url": "https://linkbridge.pratham.si/download/mac", "notes": ""},
         "android": {"version": "0.3.0", "versionCode": 4, "url": "https://linkbridge.pratham.si/download/android", "notes": "Faster pairing"}}
    """.trimIndent()
    private val home = "https://linkbridge.pratham.si/"

    @Test
    fun parsesTheAndroidEntry() {
        assertEquals(
            LatestRelease("0.3.0", 4, "https://linkbridge.pratham.si/download/android", "Faster pairing"),
            UpdateCheck.parse(sample, "android")
        )
    }

    @Test
    fun malformedFeedsGiveNothing() {
        assertNull(UpdateCheck.parse("""{"mac": {"version": "0.3.0", "build": 4}}""", "android"))
        assertNull(UpdateCheck.parse("""{"android": {"version": "0.3.0", "versionCode": "4"}}""", "android"))
        assertNull(UpdateCheck.parse("<html>Not found</html>", "android"))
        assertNull(UpdateCheck.parse("""{"android": {"version": "", "versionCode": 4}}""", "android"))
    }

    @Test
    fun notesAreOneShortLineAndUrlFallsBack() {
        val long = UpdateCheck.parse("""{"android": {"version": "1", "versionCode": 9, "notes": "${"a".repeat(300)}\nsecond"}}""", "android")
        assertEquals(120, long?.notes?.length)
        assertFalse(long!!.notes.contains("\n"))
        assertEquals(home, long.url)
    }

    @Test
    fun onlyTheSiteIsOpened() {
        assertEquals("https://linkbridge.pratham.si/download/android", UpdateCheck.safeUrl("https://linkbridge.pratham.si/download/android"))
        assertEquals(home, UpdateCheck.safeUrl("http://linkbridge.pratham.si/x"))
        assertEquals(home, UpdateCheck.safeUrl("https://evil.example/x"))
        assertEquals(home, UpdateCheck.safeUrl("https://linkbridge.pratham.si.evil.example/x"))
        assertEquals(home, UpdateCheck.safeUrl("https://user@linkbridge.pratham.si/x"))
        assertEquals(home, UpdateCheck.safeUrl(null))
    }

    @Test
    fun whenToShow() {
        val r4 = LatestRelease("0.3.0", 4, home, "")
        val r5 = LatestRelease("0.3.1", 5, home, "")
        assertTrue(UpdateCheck.shouldShow(r4, currentBuild = 3, dismissedBuild = null))
        assertFalse(UpdateCheck.shouldShow(r4, currentBuild = 3, dismissedBuild = 4))
        assertTrue(UpdateCheck.shouldShow(r5, currentBuild = 3, dismissedBuild = 4))
        assertFalse(UpdateCheck.shouldShow(r4, currentBuild = 4, dismissedBuild = null))
        assertFalse(UpdateCheck.shouldShow(null, currentBuild = 3, dismissedBuild = null))
    }

    @Test
    fun whenToCheck() {
        val now = 1_800_000_000_000L
        val hour = 3_600_000L
        assertTrue(UpdateCheck.isDue(null, now))
        assertFalse(UpdateCheck.isDue(now - 23 * hour, now))
        assertTrue(UpdateCheck.isDue(now - 25 * hour, now))
        assertTrue(UpdateCheck.isDue(now + hour, now))
    }
}
