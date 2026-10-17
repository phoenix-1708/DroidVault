package com.phoenix.droidvault.utils

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * In-app diagnostics logger (Settings → Diagnostics logger).
 *
 * Built for field debugging, SwiftBackup-style: every notable app/backend
 * event lands here with a millisecond timestamp, the last chunk stays in a
 * memory ring for the live view, and the full text appends to a per-day file
 * under the app's external files dir (`Android/data/com.phoenix.droidvault/
 * files/logs/`) — visible with any file manager, survives app updates, wiped
 * with the app, needs no storage permission.
 *
 * Hard rules:
 *  - NEVER throw: logging must not be able to crash a run. Every failure of
 *    the logger itself is swallowed.
 *  - NEVER log PII: callers pass event markers, counts, package names and
 *    paths — no message bodies, no phone numbers, no SSIDs (the §7 rule;
 *    hook sites are written to respect it).
 *  - Off-main-thread only: the ring update is a synchronized add, the file
 *    append is serialized on one worker thread.
 *
 * Rotation: one file per day + the previous day kept as `.old`; the current
 * file rotates at [MAX_FILE_BYTES] so a runaway loop cannot fill the disk.
 */
object DroidVaultLogger {

    const val V = 'V'
    const val D = 'D'
    const val I = 'I'
    const val W = 'W'
    const val E = 'E'

    private const val RING_CAP = 2000
    private const val MAX_FILE_BYTES = 3L * 1024 * 1024
    private const val DIR_NAME = "logs"
    private const val FILE_PREFIX = "droidvault-"
    private const val FILE_SUFFIX = ".log"

    /** One formatted line per entry — the ring stores the final text. */
    private val ring = ArrayDeque<String>(RING_CAP)

    /** Bumped on every append; the live view collects it to re-read the ring. */
    @Volatile
    var updates: kotlinx.coroutines.flow.MutableStateFlow<Long> =
        kotlinx.coroutines.flow.MutableStateFlow(0L)
        private set

    @Volatile
    private var appContext: Context? = null
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "DroidVaultLogger").apply { isDaemon = true }
    }
    private val lock = Any()

    // ---------- lifecycle ----------

    /** Call once from [com.phoenix.droidvault.DroidVaultApp]. Installs the crash hook. */
    fun install(context: Context) {
        appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Uncaught crashes are the whole point of a field logger: record
            // the thread and full stack before the process dies, then chain.
            try {
                log(E, "CRASH", "Uncaught on ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}")
                log(E, "CRASH", throwable.stackTraceToString().take(8000))
            } catch (_: Exception) {
            }
            previous?.uncaughtException(thread, throwable)
        }
        i("Logger", "Logger installed")
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences("droidvault_prefs", Context.MODE_PRIVATE)
            .getBoolean("diag_logging_enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences("droidvault_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("diag_logging_enabled", enabled).apply()
        if (enabled) i("Logger", "Logging enabled")
        else i("Logger", "Logging disabled (file writes paused)")
    }

    // ---------- logging API ----------

    fun v(tag: String, message: String) = log(V, tag, message)
    fun d(tag: String, message: String) = log(D, tag, message)
    fun i(tag: String, message: String) = log(I, tag, message)
    fun w(tag: String, message: String) = log(W, tag, message)
    fun e(tag: String, message: String) = log(E, tag, message)

    fun log(level: Char, tag: String, message: String) {
        val ctx = appContext ?: return
        val line = formatLine(System.currentTimeMillis(), level, tag, message)
        synchronized(lock) {
            if (ring.size >= RING_CAP) ring.pollFirst()
            ring.addLast(line)
        }
        updates.value = updates.value + 1
        if (!isEnabled(ctx)) return
        writer.execute {
            try {
                appendToFile(ctx, line)
            } catch (_: Exception) {
            }
        }
    }

    // ---------- live view ----------

    /** Snapshot of the ring, oldest → newest (already formatted lines). */
    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }

    /** The last [n] lines joined — for copy-to-clipboard. */
    fun recent(n: Int): String = synchronized(lock) {
        ring.toList().takeLast(n).joinToString("\n")
    }

    fun clearMemory() = synchronized(lock) { ring.clear() }

    // ---------- files ----------

    /** Pure: the per-day log file name. */
    fun logFileName(date: Date): String =
        FILE_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date) + FILE_SUFFIX

    private fun logsDir(context: Context): File {
        val ext = context.getExternalFilesDir(null)
        val base = ext ?: context.cacheDir
        return File(base, DIR_NAME)
    }

    fun currentFile(context: Context): File {
        val dir = logsDir(context)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, logFileName(Date()))
    }

    private fun appendToFile(context: Context, line: String) {
        val file = currentFile(context)
        // Size rotation: keep exactly one previous generation.
        if (file.exists() && file.length() > MAX_FILE_BYTES) {
            val old = File(file.parentFile, file.name + ".old")
            if (old.exists()) old.delete()
            file.renameTo(old)
        }
        file.appendText(line + "\n")
    }

    /**
     * Stage the full log text (current + previous generation) into the
     * FileProvider-shared staging dir and return it for a share intent.
     * Returns null when there is nothing to export.
     */
    fun stageExport(context: Context): File? {
        return try {
            val dir = logsDir(context)
            val current = File(dir, logFileName(Date()))
            val old = File(dir, current.name + ".old")
            if (!current.exists() && !old.exists()) return null
            val staging = File(context.cacheDir, "automation-exports").apply { mkdirs() }
            val out = File(staging, "droidvault-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
            out.bufferedWriter().use { w ->
                if (old.exists()) old.forEachLine { w.appendLine(it) }
                if (current.exists()) current.forEachLine { w.appendLine(it) }
            }
            if (out.length() == 0L) null else out
        } catch (_: Exception) {
            null
        }
    }

    /** Delete all log files (current, .old) and the memory ring. Returns files removed. */
    fun clearAll(context: Context): Int {
        clearMemory()
        var removed = 0
        try {
            val dir = logsDir(context)
            val current = File(dir, logFileName(Date()))
            listOf(current, File(dir, current.name + ".old")).forEach { f ->
                if (f.exists() && f.delete()) removed++
            }
        } catch (_: Exception) {
        }
        i("Logger", "Logs cleared ($removed file(s))")
        return removed
    }

    /**
     * Human summary for the Settings row: file size + in-memory line count.
     *
     * The two figures used to be joined as "82.00 KB on disk · 6 lines in
     * memory", which reads as a contradiction — and it is one to anyone who has
     * not internalised that the ring is per-launch while the file is not. The
     * file is everything written since midnight INCLUDING the rotated
     * generation, and it survives the app being killed; the ring is the last
     * [RING_CAP] lines of THIS process. Naming each one is the whole fix; the
     * numbers were never wrong.
     */
    fun statusSummary(context: Context): String {
        val bytes = try {
            val dir = logsDir(context)
            val current = File(dir, logFileName(Date()))
            val old = File(dir, current.name + ".old")
            (if (current.exists()) current.length() else 0L) +
                (if (old.exists()) old.length() else 0L)
        } catch (_: Exception) {
            0L
        }
        val count = synchronized(lock) { ring.size }
        return "${Formatters.formatBytes(bytes)} in today's log · " +
            "$count ${if (count == 1) "line" else "lines"} since launch"
    }

    // ---------- formatting (pure, unit-tested) ----------

    /**
     * SimpleDateFormat is not thread-safe and was being constructed on EVERY
     * log line — thousands per run, from the engine's callback thread plus the
     * UI thread. ThreadLocal keeps the (expensive to build) instance per thread
     * while staying correct.
     */
    private val LINE_FORMAT = ThreadLocal.withInitial {
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    /** Pure: one canonical log line. */
    fun formatLine(timeMs: Long, level: Char, tag: String, message: String): String {
        val ts = LINE_FORMAT.get()!!.format(Date(timeMs))
        val safeTag = tag.take(24).replace('\n', ' ')
        val safeMsg = message.replace('\n', ' ').take(2000)
        return "$ts $level/$safeTag: $safeMsg"
    }
}
