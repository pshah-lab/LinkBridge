package com.linkbridge.android

/**
 * Same rules as the Mac's `ControlServer.sanitizeFileName`: no path separators, no control or
 * format characters (bidi overrides could make `x‮fdp.command` look like a PDF), no leading
 * dots (hidden files), and at most [MAX_BYTES] UTF-8 bytes with the extension kept.
 */
object FileNames {
    const val MAX_BYTES = 200
    private const val FALLBACK = "received-file"
    private val separators = Regex("""[/\\:]""")
    private val invisible = Regex("""[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]""")

    fun sanitize(fileName: String): String {
        var name = fileName
            .replace(separators, "-")
            .replace(invisible, "")
            .trim()
            .trimStart('.')
            .trim()
        if (name.utf8Size() > MAX_BYTES) {
            val ext = name.substringAfterLast('.', "")
            val keep = if (ext.isEmpty() || ext.utf8Size() > 20 || ext == name) "" else ".$ext"
            var stem = if (keep.isEmpty()) name else name.dropLast(keep.length)
            while (stem.utf8Size() + keep.utf8Size() > MAX_BYTES) {
                stem = stem.dropLastCodePoint()
            }
            name = stem + keep
        }
        return name.ifEmpty { FALLBACK }
    }

    private fun String.utf8Size() = toByteArray(Charsets.UTF_8).size

    private fun String.dropLastCodePoint(): String =
        if (length >= 2 && this[length - 1].isLowSurrogate() && this[length - 2].isHighSurrogate()) dropLast(2) else dropLast(1)
}
