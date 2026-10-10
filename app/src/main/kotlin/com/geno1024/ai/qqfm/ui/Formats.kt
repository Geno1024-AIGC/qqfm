package com.geno1024.ai.qqfm.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Renders sizes in binary units: B, KiB, MiB, GiB, TiB. */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble() / 1024.0
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return String.format(Locale.ROOT, "%.1f %s", value, units[index])
}

/**
 * Renders a Unix-millis timestamp in the device zone. [short] drops the year and
 * seconds for compact places such as grid-cell captions.
 */
fun formatTimestamp(epochMillis: Long, short: Boolean = false): String {
    val formatted = if (short) {
        shortFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    } else {
        longFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
    }
    return formatted
}

private val longFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
private val shortFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)