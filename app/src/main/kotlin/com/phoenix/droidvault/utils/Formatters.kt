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
