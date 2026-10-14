package com.phoenix.droidvault.utils

import java.security.MessageDigest

/**
 * Shared formatting + hashing helpers (P6 dedupe). Previously triplicated:
 * size formatting in MigratorManager.formatSize / AppDetailLoader.formatBytes /
 * MainViewModel.formatPreciseSize, and SHA-256 in BackendInstaller /
 * AutomationSnapshot.
 */
object Formatters {

    /** Byte count -> "1.23 GB" (binary units), locale-stable. */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var group = 0
        var value = bytes.toDouble()
        while (value >= 1024.0 && group < units.size - 1) {
            value /= 1024.0
            group++
        }
        return if (group == 0) "$bytes B" else String.format(java.util.Locale.US, "%.2f %s", value, units[group])
    }

    /** KB count (backend convention) -> human string via [formatBytes]. */
    fun formatKb(kb: Long): String = formatBytes(kb * 1024)

    /**
     * How long ago [thenMs] was, in the words a person would use.
     *
     * `null` for a timestamp this app never wrote (0 or negative), because
     * "0 seconds ago" is a claim about 1970 and an unknown age must read as
     * unknown rather than as brand new. Pure, and deliberately coarse: the one
     * thing this figure has to do is separate "just now" from "before a run that
     * may already have repaired it", and a precise number of minutes would be
     * false confidence about a clock the user can change.
     */
    fun relativeAge(nowMs: Long, thenMs: Long): String? {
        // Both ends are required: an unknown "now" would floor the subtraction at
        // "just now" for a finding from last month, which is the one answer that
        // makes a user stop looking.
        if (thenMs <= 0L || nowMs <= 0L) return null
        val seconds = (nowMs - thenMs) / 1000L
        // A clock that moved backwards (an NTP correction, a manual change) must
        // not produce an age in the future; "just now" is the honest floor.
        if (seconds < 60L) return "just now"
        val minutes = seconds / 60L
        if (minutes < 60L) return if (minutes == 1L) "a minute ago" else "$minutes minutes ago"
        val hours = minutes / 60L
        if (hours < 24L) return if (hours == 1L) "an hour ago" else "$hours hours ago"
        val days = hours / 24L
        when {
            days == 1L -> return "yesterday"
            days < 30L -> return "$days days ago"
            else -> return "over a month ago"
        }
    }
}

object Hashing {
    /** SHA-256 hex of a stream, null on failure. */
    fun sha256Hex(stream: java.io.InputStream): String? {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(8192)
            var n = stream.read(buf)
            while (n != -1) {
                md.update(buf, 0, n)
                n = stream.read(buf)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun sha256Hex(file: java.io.File): String? = try {
        file.inputStream().use { sha256Hex(it) }
    } catch (_: Exception) {
        null
    }
}
