package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CleartextTest {
    @Test
    fun onlyLocalNetworkLiteralsAreAllowed() {
        listOf("192.168.1.20", "10.0.0.5", "172.16.3.4", "127.0.0.1", "169.254.10.1",
            "fe80::1%wlan0", "[fe80::1]", "fd12:3456::1", "::1").forEach {
            assertTrue(it, LanAddress.isLocal(it))
        }
        listOf("8.8.8.8", "172.32.0.1", "example.com", "mac.local", "2001:4860::8888", "", "192.168.1.300")
            .forEach { assertFalse(it, LanAddress.isLocal(it)) }
    }

    /** The app allows cleartext app-wide; any new http:// URL in code must be reviewed here. */
    @Test
    fun noOtherCleartextUrlsInCode() {
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
        val uses = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                if (line.contains("\"http://")) "${file.name}:${index + 1}" else null
            }
        }.map { it.substringBefore(':') }.toList()
        assertEquals("http:// only in the LAN pairing client", listOf("ControlClient.kt", "ControlClient.kt"), uses)
    }
}
