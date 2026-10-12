package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.ErrorCode

/**
 * Pre-run disk-space guard (P3). Pure decision logic is unit-testable; the
 * shell stat itself lives in [freeSpaceKb].
 *
 * Why here: the backend streams tar|zapdos and only fails per-file with
 * cryptic "No space left on device" lines. A pre-flight check catches the
 * common case (disk full before anything ran) and surfaces the typed
 * [ErrorCode.STORAGE_FULL] that was defined but never emitted.
 */
object AutomationSafety {

    /** Below this free space (KB) a backup run is refused outright. */
    const val MIN_FREE_KB = 100L * 1024L // 100 MB

    /** Comfortable margin message threshold shown in the run summary. */
    const val WARN_FREE_KB = 512L * 1024L // 512 MB

    data class SpaceStatus(val availableKb: Long, val ok: Boolean, val warn: Boolean) {
        val error: ErrorCode get() = if (ok) ErrorCode.NONE else ErrorCode.STORAGE_FULL
    }

    /** Pure gate: callers pass the measured free KB so tests need no shell. */
    fun evaluateSpace(freeKb: Long): SpaceStatus = SpaceStatus(
        availableKb = freeKb,
        ok = freeKb >= MIN_FREE_KB,
        warn = freeKb in MIN_FREE_KB until WARN_FREE_KB
    )

    /**
     * Free space (KB) of the filesystem containing [path], via `df -k -P`.
     * POSIX output line: <filesystem> <1024-blocks> <used> <available> <cap%> <mounted-on>.
     * Returns -1 when unavailable (never blocks the run on a df failure —
     * the backend's own ENOSPC handling remains the backstop).
     */
    fun freeSpaceKb(path: String): Long {
        return try {
            ShellQuote.requireAbsPath(path)
            val out = com.topjohnwu.superuser.Shell.cmd("df -k -P ${ShellQuote.q(path)}")
                .exec().out
            val line = out.lastOrNull { it.contains('%') && it.split("\\s+".toRegex()).size >= 4 }
                ?: return -1L
            line.trim().split("\\s+".toRegex()).getOrNull(3)?.toLongOrNull() ?: -1L
        } catch (_: Exception) {
            -1L
        }
    }
}
