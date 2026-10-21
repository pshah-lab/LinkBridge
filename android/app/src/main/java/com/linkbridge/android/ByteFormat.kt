package com.linkbridge.android

import java.text.DecimalFormat

/** Binary-unit size for display ("2.1 MB"). Pure, so JVM tests can use it. */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "unknown"
    if (bytes < 1024) return "$bytes B"

    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex += 1
    }

    return "${DecimalFormat("#,##0.#").format(value)} ${units[unitIndex]}"
}
