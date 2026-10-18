package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test
    fun matchesTheMacRules() {
        // Same cases as the Mac's LinkBridgeCoreChecks.
        val cases = listOf(
            "report‮fdp.command" to "reportfdp.command",
            "a⁦b⁩c​.txt" to "abc.txt",
            "nul\u0000and\ttab\n.txt" to "nulandtab.txt",
            ".hidden" to "hidden",
            "...env" to "env",
            "../../etc/passwd" to "-..-etc-passwd",
            "a:b\\c" to "a-b-c",
            "  .  " to "received-file",
            "‮" to "received-file",
            "photo.jpg" to "photo.jpg",
            "café ☕️.png" to "café ☕️.png",
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, FileNames.sanitize(input))
        }
    }

    @Test
    fun longNamesAreCappedKeepingTheExtension() {
        val name = FileNames.sanitize("é".repeat(300) + ".pdf")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= FileNames.MAX_BYTES)
        assertTrue(name.endsWith(".pdf"))
    }
}
