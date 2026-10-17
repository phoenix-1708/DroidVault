package com.phoenix.droidvault.utils

import android.content.Context
import android.util.Log
import com.phoenix.droidvault.models.EngineEvent
import com.phoenix.droidvault.models.EngineTransferEvent
import com.phoenix.droidvault.models.TransferEngine
import com.phoenix.droidvault.models.TransferPhase
import com.phoenix.droidvault.models.TransferProgress
import com.phoenix.droidvault.models.VaultTransfer
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * Cross-device transfer: one archive file, moved by the user.
 *
 * No `INTERNET` permission, by design, so the app never
 * phones anywhere. It writes a single `.dvbackup.tar` into the shared
 * Downloads folder and reads one back; the system file manager does the moving.
 *
 * Everything here goes through the root shell because java.io cannot read or
 * write the vault under scoped storage — the same constraint that produced
 * A-09, U-02 and U-03.
 *
 * Two rules this file exists to enforce:
 *  - **Never report success for work that did not happen.** Every command's exit
 *    status is checked and the outcome lists what actually moved.
 *  - **An import must not clobber the destination.** `KEEP_DESTINATION` is the
 *    default; replacing existing archives is opt-in.
 */
object VaultTransferManager {

    private const val TAG = "VaultTransfer"

    /**
     * Where exports land: a folder of their own inside Downloads.
     *
     * Loose in Downloads, a 34 GB archive plus sixteen parts plus a manifest
     * sat mixed in with APKs and zips, unrecognizable as one set — and the
     * user moves the whole set to a PC between ROM flashes, so it must read
     * as one thing. Still under Downloads (never anywhere SAF-invisible), so
     * MTP, file managers and the import picker all keep working, and old
     * loose exports keep importing exactly as before.
     *
     * The path itself lives in [VaultTransfer] because the importer has to
     * find it again; see the note there.
     */
    private val EXPORT_DIR = VaultTransfer.EXPORT_DIR

    /** App-private staging, wiped with the app so an orphan cannot outlive it. */
    private const val STAGING_DIR = "/data/data/com.phoenix.droidvault/cache/vaulttransfer"

    /**
     * Reported when the user stops an import.
     *
     * A distinct string, not a failure dressed up: the UI shows it plainly and
     * nothing about the vault is touched by a cancel — only staging is removed.
     */
    const val CANCELLED = "Import cancelled"

    /** Slop so the copy into the vault never runs the disk to zero either. */

    /** The engine entry point every transfer command runs through. */
    private const val ENGINE = "sh /data/adb/DroidVault/DroidVault.sh"

    /** Outcome of one engine invocation, collected off its stdout stream. */
    private data class TransferRun(var cancelled: Boolean = false, var error: String? = null)

    /**
     * Run one transfer-engine command and feed its progress lines to [onEvent].
     *
     * Streaming, not polled: the engine prints measured bytes as they land, so
     * no second thread needs to stat files behind the work — which is what used
     * to queue behind it on libsu's serialised session and report once, at the
     * end. Cancellation arrives two ways: the engine's own flag file (written by
     * [AutomationRunner.requestCancel]) stops the native side, and closing this
     * flow stops the collector.
     */
    private suspend fun runTransferEngine(
        command: String,
        timeoutMs: Long,
        onEvent: (EngineTransferEvent) -> Unit
    ): TransferRun {
        val run = TransferRun()
        ShellEngine.executeEngineCommand(command, timeoutMs).collect { event ->
            when (event) {
                is EngineEvent.RawLog -> TransferEngine.parse(event.line)?.let(onEvent)
                is EngineEvent.Cancelled -> run.cancelled = true
                is EngineEvent.Error -> run.error = run.error ?: event.msg
                is EngineEvent.BackendExited ->
                    if (!event.success && run.error == null && !run.cancelled) {
                        run.error = "transfer engine exited early"
                    }
                else -> Unit
            }
        }
        return run
    }

    /**
     * Prefix for the in-progress archive inside an export's folder.
     *
     * Hidden and reaped at the start of every export, so a killed run leaves
     * something that reads as nothing rather than a plausible archive.
     */
    /**
     * Delete every in-progress archive anywhere under the export root.
     *
     * Includes files left loose in the root by builds that predate the
     * per-export folders. `find -type f -delete` rather than a shell glob,
     * because the orphans live one level down in their batch folder and
     * `*` does not cross a directory boundary.
     */
    private fun reapInProgressExports() {
        Shell.cmd(
            "find ${ShellQuote.q(EXPORT_DIR)} -maxdepth 2 -type f " +
                "-name '${ShellQuote.q(EXPORT_TMP_GLOB.substringBefore("*"))}*' -delete 2>/dev/null"
        ).exec()
        // An emptied batch folder is left behind by that reap, and a folder
        // named after an export that no longer exists reads as data that went
        // missing. Only ever removes a directory with nothing left in it, and
        // the run gate means no export can be mid-write into one.
        val semicolon = ";"
        Shell.cmd(
            "find ${ShellQuote.q(EXPORT_DIR)} -mindepth 1 -maxdepth 1 -type d -empty " +
                "-exec rmdir {} $semicolon 2>/dev/null"
        ).exec()
    }

    private const val EXPORT_TMP_PREFIX = ".dv-export-"

    /** Suffix shared by every such in-progress file, for the reap. */
    private const val EXPORT_TMP_GLOB = ".dv-export-*.part"



    /**
     * Where a carried configuration is held between reading it and applying it.
     *
     * The app's own cache, not a staging directory: staging is deleted by the
     * copy phase mid-import, so a payload parked there was gone by the time the
     * question could be answered.
     */
    private const val CARRIED_CACHE = "carried-configs.json"

    sealed class Result {
        data class Exported(
            val path: String,
            val appCount: Int,
            val bytesKb: Long,
            /** 1 for a single file; more when the archive was split for moving. */
            val partCount: Int = 1
        ) : Result()
        data class Imported(
            val outcome: VaultTransfer.ImportOutcome,
            val path: String,
            /** Applied because this phone had nothing configured. Null otherwise. */
            val configsRestored: VaultTransfer.ConfigCounts? = null,
            /** Carried by the transfer but NOT applied: this phone is configured. */
            val configsPending: VaultTransfer.ConfigCounts? = null,
            /** Where this phone's previous state file was moved, when replaced. */
            val configsReplacedTo: String? = null
        ) : Result()
        data class Failed(val reason: String) : Result()
    }

    /**
     * Write the carried trees to `<Downloads>/DroidVault/<name>`.
     *
     * The trees, the configurations, and the APK mirror. The APK rides along
     * because a transfer to a wiped phone is only self-restoring if the app
     * itself is in it; the engine and launcher mirrors deliberately do not,
     * because the freshness gate owns those and would overwrite them anyway
     */
    /**
     * Free KB on the partition that will hold the staging file, or null if it
     * cannot be read. A failed probe must NOT block the export — that would turn
     * an unknown into a refusal.
     */
    private fun weightedTotal(sizes: Map<String, Long>): Long = sizes.values.sum()

    /**
     * KB of one carried tree.
     *
     * The split MUST be `toRegex()`. Kotlin's `String.split(vararg String)` takes
     * LITERAL delimiters, so `split("\\s+")` looks for the four characters
     * backslash-s-plus, finds none, and returns the whole line — which then fails
     * `toLongOrNull()` and silently yields 0. That is exactly what happened: the
     * shell returned `3666177<TAB>/storage/...` and the total came out 0, freezing
     * the bar at "0 B so far" while the archive grew past 2 GB.
     *
     * ONE parse for every `du` read in this file, because the rule was applied to
     * this function and missed on the export gate's own read — which then held the
     * whole gate inert, the same defect one layer up. A second reader of the same
     * shell output is a second chance to get the parse wrong.
     */
    private fun probeKb(tree: String, vaultPath: String): Long =
        duKb("$vaultPath/$tree")

    /**
     * KB used by one path, from `du -sk`.
     *
     * `du -sk` answers `<kb><TAB><path>` (verified on device: `413008<TAB>
     * /storage/emulated/0/DroidVault`). `trim()` removes whitespace at the ENDS of
     * a string and never the one in the middle, so trimming the whole line leaves
     * `"413008\t/storage/…"` — which is not a number, and `toLongOrNull()` returns
     * null. Take the first field.
     *
     * Returns 0 when the size cannot be read, because every caller treats 0 as
     * "unknown" and gates itself on `> 0`; a failed probe must not invent a
     * refusal. See [weightedTotal] for the same rule on the other side.
     */
    private fun duKb(path: String): Long =
        Shell.cmd("du -sk ${ShellQuote.q(path)} 2>/dev/null").exec()
            .out.firstOrNull()?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.toLongOrNull() ?: 0L

    /**
     * Write the configurations into `$vaultPath/.export-meta`, for the duration
     * of this export only.
     *
     * They go inside the vault rather than beside it because toybox `tar` has no
     * working `-C` — one root is the only way to get two directories into one
     * archive. Written through the root shell because direct file I/O into the
     * vault returns EPERM once a root `mv` has touched the directory (U-02).
     *
     * Returns the meta directory when it holds a usable state file, else null.
     * The caller removes it either way: a killed export must not leave the
     * previous run's configurations lying in the vault looking authoritative.
     */
    private fun stageTransferMeta(context: Context, vaultPath: String): String? {
        val meta = "$vaultPath/${VaultTransfer.META_DIR}"
        return try {
            // Fresh every time. A leftover from a killed export must not be able
            // to stand in for this device's current configuration.
            Shell.cmd("rm -rf ${ShellQuote.q(meta)} && mkdir -p ${ShellQuote.q(meta)}").exec()
            val state = File(context.cacheDir, AutomationSnapshot.STATE_FILE)
            val folders = File(context.cacheDir, "folders.json")
            try {
                state.writeText(AutomationStore.exportJson(context).toString())
                folders.writeText(BackupFolderStore.snapshotJson())
                Shell.cmd(
                    "cp ${ShellQuote.q(state.absolutePath)} ${ShellQuote.q(meta)}/" +
                        AutomationSnapshot.STATE_FILE +
                        " && cp ${ShellQuote.q(folders.absolutePath)} ${ShellQuote.q(meta)}/folders.json" +
                        // 600 for the same reason the vault copy is: a package list
                        // is fingerprintable and shared storage is world-readable
                        // to any app holding the storage permission.
                        " && chmod 600 ${ShellQuote.q(meta)}/*"
                ).exec()
            } finally {
                state.delete()
                folders.delete()
            }
            val ok = Shell.cmd(
                "[ -s ${ShellQuote.q("$meta/${AutomationSnapshot.STATE_FILE}")} ]"
            ).exec().isSuccess
            if (ok) meta else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Apply the configuration an import offered and the user accepted.
     *
     * The only irreversible thing an import can do, so the phone's current state
     * file is moved into `.trash` first and the path is returned for the message
     * that tells the user where it went. Returns null when there is nothing held
     * or the move could not be made.
     */
    fun adoptCarriedConfigs(context: Context, vaultPath: String): String? {
        val held = File(context.cacheDir, CARRIED_CACHE)
        if (!held.isFile) return null
        val text = runCatching { held.readText() }.getOrDefault("")
        held.delete()
        return applyCarriedConfigs(context, vaultPath, text)
    }

    /** Drop the export staging directory. Always called, success or failure. */
    private fun clearTransferMeta(vaultPath: String) {
        Shell.cmd("rm -rf ${ShellQuote.q("$vaultPath/${VaultTransfer.META_DIR}")}").exec()
    }

    /**
     * Read the configuration files a transfer carried, out of [metaDir].
     *
     * Through the shell, not `File.readText`: root created these and chmod 600
     * them, and a root-created file carries an SELinux label the app cannot read
     * even at mode 644. `cat` sidesteps the whole question — the shell already
     * has it open.
     */
    private fun readCarriedPayload(context: Context, metaDir: String): String? {
        // Through a file the APP creates, then read with Kotlin — not
        // `Shell.exec().out`. libsu's exec() is non-blocking and `.out` comes back
        // empty for the first command after the session wakes, which is exactly
        // where this runs: straight after a multi-gigabyte extraction. Verified on
        // device as "imported 0 new, 60 kept" with the configurations silently
        // skipped. A root-created file also carries a label the app cannot read,
        // so the app has to create it — the same fix as the export staging file.
        val local = File(context.cacheDir, "carried-configs.json")
        val ok = try {
            local.createNewFile()
            // An explicit newline between them: `cat a b` runs them together with
            // no separator, which would leave `…}["/storage/…` to split on.
            Shell.cmd(
                "{ cat ${ShellQuote.q("$metaDir/${AutomationSnapshot.STATE_FILE}")}; echo; " +
                    "cat ${ShellQuote.q("$metaDir/folders.json")}; } " +
                    "> ${ShellQuote.q(local.absolutePath)} 2>/dev/null"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            local.delete()
            return null
        }
        val text = runCatching { local.readText() }.getOrDefault("")
        local.delete()
        // Held in the app's own cache so it survives staging's deletion, and so
        // an unanswered question still has something to act on.
        val held = File(context.cacheDir, CARRIED_CACHE)
        if (splitCarried(text) == null) {
            held.delete()
            return null
        }
        runCatching { held.writeText(text) }
        return text
    }

    /** The two documents a carried payload holds: the state object, then the folder array. */
    private fun splitCarried(text: String): Pair<String, String>? {
        val split = text.indexOf("\n[")
        if (split <= 0) return null
        val json = text.substring(0, split).trim()
        return if (json.startsWith("{")) json to text.substring(split).trim() else null
    }

    /** What a carried payload contains. Null when it carries nothing usable. */
    private fun carriedCounts(text: String): VaultTransfer.ConfigCounts? {
        val (json, folders) = splitCarried(text) ?: return null
        val n = try {
            org.json.JSONArray(folders).length()
        } catch (_: Exception) {
            0
        }
        return VaultTransfer.ConfigCounts.fromJson(json).copy(folders = n)
    }

    /**
     * Put carried configurations into the vault.
     *
     * The previous state file is MOVED, never overwritten: replacing a user's
     * configuration with another device's is the one irreversible thing an
     * import can do, so the old one goes to `.trash` where it can be found
     *
     * Returns where the previous file went, or null when there was none.
     */
    private fun applyCarriedConfigs(
        context: Context,
        vaultPath: String,
        payload: String
    ): String? {
        val (json, foldersJson) = splitCarried(payload) ?: return null
        val dest = "$vaultPath/${AutomationSnapshot.STATE_FILE}"
        val kept = if (Shell.cmd("[ -f ${ShellQuote.q(dest)} ]").exec().isSuccess) {
            val dir = "$vaultPath/.trash/configs-${System.currentTimeMillis()}"
            val moved = Shell.cmd(
                "mkdir -p ${ShellQuote.q(dir)} && mv ${ShellQuote.q(dest)} ${ShellQuote.q(dir)}/"
            ).exec().isSuccess
            if (moved) dir else null
        } else {
            null
        }
        val tmp = File(context.cacheDir, "adopting-configs.json")
        val ok = try {
            tmp.writeText(json)
            Shell.cmd(
                "cp ${ShellQuote.q(tmp.absolutePath)} ${ShellQuote.q(dest)}" +
                    " && chmod 600 ${ShellQuote.q(dest)}"
            ).exec().isSuccess
        } catch (_: Exception) {
            false
        } finally {
            runCatching { tmp.delete() }
        }
        if (!ok) {
            // Put back what was moved. A failed restore must leave the phone
            // exactly as it was, not with its configuration in the trash.
            if (kept != null) {
                Shell.cmd(
                    "cp ${ShellQuote.q("$kept/${AutomationSnapshot.STATE_FILE}")} ${ShellQuote.q(dest)}"
                ).exec()
            }
            return null
        }
        // The folder registry is prefs-only, so it cannot be left to the
        // bind-time heal alone: union the carried paths in now, while we have
        // them. Paths that do not exist on this phone are kept anyway — a folder
        // the user syncs to later must still be in the registry when it arrives.
        runCatching { BackupFolderStore.restoreFromJson(context, foldersJson) }
        return kept
    }

    /**
     * What this export will actually put in the archive.
     *
     * Built from what is on disk, not from [VaultTransfer.CARRIED_TREES]: passing
     * `tar` a name that does not exist is an error that fails the whole export,
     * and the APK mirror legitimately does not exist before the first run has
     * written it.
     */
    private fun carryList(vaultPath: String, metaDir: String?): List<String> {
        val wanted = (VaultTransfer.CARRIED_TREES + VaultTransfer.CARRIED_FILES).toSet()
        val present = Shell.cmd("ls -1 ${ShellQuote.q(vaultPath)} 2>/dev/null").exec().out
            .map { it.trim() }
            .filter { it in wanted }
        return if (metaDir != null) present + VaultTransfer.META_DIR else present
    }

    /**
     * Names of the pieces split from [archivePath], in stream order.
     *
     * One `ls`, sorted in Kotlin: the suffixes are fixed-length base-26, so a
     * plain sort is the concatenation order the importer streams in.
     */
    private fun listDownloadParts(archivePath: String): List<String> =
        listSplitParts(archivePath)

    private fun listSplitParts(prefix: String): List<String> =
        Shell.cmd("ls -1 ${ShellQuote.q("$prefix${VaultTransfer.PART_SEP}")}* 2>/dev/null")
            .exec().out.map { it.trim() }.filter { it.isNotEmpty() }.sorted()

    /** Summed bytes of the pieces split from [archivePath], via one `du -cb`. */
    private fun downloadPartsBytes(archivePath: String): Long =
        splitPartsBytes(archivePath)

    /**
     * Summed bytes of the pieces split from [prefix].
     *
     * Via `stat`, NOT `du -cb`: toybox `du -cb` reported `0 total` for a 4.2 GB
     * file on device, so the byte-sum check at the end of a split would have
     * compared 4 GB against 0 and deleted the only good copy as "mismatched".
     * Verified on device before relying on either.
     */
    private fun splitPartsBytes(prefix: String): Long =
        Shell.cmd("stat -c %s ${ShellQuote.q("$prefix${VaultTransfer.PART_SEP}")}* 2>/dev/null")
            .exec().out.mapNotNull { it.trim().toLongOrNull() }.sum()

    /**
     * One size per piece, in stream order, via one `stat`.
     *
     * The manifest check needs per-piece sizes, not just the sum: a set whose
     * bytes add up can still hold a short middle piece padded by nothing —
     * well, nothing pads it, so the sum would differ too, but naming WHICH
     * piece is damaged is what lets the message say "copy part 3 again".
     */
    private fun splitPartSizes(parts: List<String>): List<Long> {
        if (parts.isEmpty()) return emptyList()
        val out = Shell.cmd(
            "stat -c %s " + parts.joinToString(" ") { ShellQuote.q(it) } + " 2>/dev/null"
        ).exec().out.mapNotNull { it.trim().toLongOrNull() }
        return if (out.size == parts.size) out else emptyList()
    }

    /**
     * Record what a split set holds, beside the pieces.
     *
     * `<archive>.parts.json`: part count, total bytes, piece size. Without a
     * recorded count, pieces missing off the end are undetectable on import —
     * the set looks complete because every present piece is in order. Written
     * through the shell like everything else in shared storage (U-02).
     */
    private fun writePartsManifest(archivePath: String, count: Int, totalBytes: Long, partBytes: Long) {
        val json = "{\"parts\":$count,\"totalBytes\":$totalBytes,\"partBytes\":$partBytes," +
            "\"base\":\"${File(archivePath).name}\"}"
        Shell.cmd(
            "printf '%s' ${ShellQuote.q(json)} > ${ShellQuote.q("$archivePath.parts.json")}"
        ).exec()
    }

    /**
     * Read `<prefix>.parts.json` through the root shell.
     *
     * Shared storage is not directly readable under scoped storage — the same
     * reason the SAF copy path exists — so this goes through the shell like
     * every other vault-adjacent read. Empty when absent or unreadable; the
     * caller treats that as "no manifest", never as a crash.
     */
    fun readTransferManifest(prefix: String): String =
        Shell.cmd("cat ${ShellQuote.q("$prefix.parts.json")} 2>/dev/null").exec()
            .out.joinToString("").trim()

    /** Piece names under [prefix], in stream order. Empty when there are none. */
    fun listTransferParts(prefix: String): List<String> = listSplitParts(prefix)

    /** One size per piece, in stream order. Empty unless every piece answered. */
    fun transferPartSizes(parts: List<String>): List<Long> = splitPartSizes(parts)

    /**
     * Size of the staging file, read from Kotlin — deliberately NOT through the
     * shell.
     *
     * libsu serialises commands per session, so a `stat` issued while `tar` is
     * mid-stream queues behind it and returns only when tar finishes. Polling the
     * shell therefore produced one reading, at the end, and the bar sat at 0 for
     * the whole run. `File.length()` is a plain stat(2): no session, no queue, and
     * it works because the staging dir is created by the app (group-readable) and
     * the archive is mode 644.
     *
     * Falls back to the shell only if the direct read is unavailable, because a
     * fallback that blocks is still better than no progress at all.
     */
    /**
     * The tar listing flag this archive needs, decided by its first bytes.
     *
     * Export stopped compressing, so a transfer is a plain tar — but every
     * transfer written before that is gzip, and the user may well be importing
     * one of those. A hardcoded `-tzvf` lists a new archive as an empty one; a
     * hardcoded `-tvf` fails on an old one. The engine sniffs the same magic for
     * the split path; this is the single-file survey, which runs its own `tar`.
     */
    private fun tarListFlag(archivePath: String): String =
        if (shellIsGzip(archivePath)) "-tzvf" else "-tvf"

    /** True when the file really begins with the gzip magic `1f 8b`. */
    private fun shellIsGzip(path: String): Boolean =
        Shell.cmd("head -c 2 ${ShellQuote.q(path)} 2>/dev/null | od -An -tx1")
            .exec().out.joinToString("").trim().split("\\s+".toRegex())
            .filter { it.isNotEmpty() }.take(2).joinToString("") == "1f8b"

    private fun statBytes(path: String): Long {
        val direct = runCatching { java.io.File(path).length() }.getOrDefault(0L)
        if (direct > 0L) return direct
        return Shell.cmd("stat -c %s ${ShellQuote.q(path)} 2>/dev/null").exec()
            .out.firstOrNull()?.trim()?.toLongOrNull() ?: 0L
    }

    /**
     * Free KB on the filesystem holding [path], or null if it cannot be read.
     *
     * Walks up to the nearest existing ancestor first: `StatFs` throws on a
     * path that does not exist yet, and the old code turned that into a null
     * that silently DISABLED the caller's space gate. That is how a 34 GB
     * import sailed through with 96 GB free against a ~103 GB need — the probe
     * ran against the app-cache staging dir, which nothing had created yet on
     * that install, so the gate measured nothing and refused nothing. A probe
     * that cannot answer must still be distinguishable from "plenty of room",
     * so callers treat null as unknown (log it) rather than as zero need.
     */
    fun freeSpaceKb(path: String): Long? = try {
        var dir = java.io.File(path)
        while (!dir.exists()) {
            dir = dir.parentFile ?: return null
        }
        android.os.StatFs(dir.absolutePath).availableBytes / 1024
    } catch (_: Exception) {
        null
    }

    /**
     * Export refuses to start when it plainly cannot finish.
     *
     * Measured on device: the vault held ~31 GB and the export staged a single
     * `.tar.gz` into /data/local/tmp. The phone had ~31 GB free, the archive
     * needed roughly the vault's size, and `tar -czf` spent minutes filling the
     * disk before failing — with **no UI feedback at all**, because export never
     * touches `isRunning`, the run gate, or any progress surface. The result was
     * a device 85% full, a 33.5 GB orphan tarball nobody asked to keep, and a
     * user staring at a chip that appeared to do nothing.
     *
     * gzip on already-compressed data lands near 1:1, so the vault's size is the
     * honest figure to compare against. The headroom is deliberate: the archive
     * is a little larger than its input, and the user still needs room to use
     * the phone afterwards.
     */

    /**
     * The floor an export step is allowed before size is taken into account.
     *
     * This used to BE the whole bound. A flat 60 minutes is a speed limit, not
     * a liveness check, and a large backup is legitimately slower than that:
     * packing 29.7 GB on the reference device took 67 minutes, so the split
     * that followed was killed at the 60-minute mark every single time — the
     * engine reported a timeout, the app deleted the parts it had already
     * written, and the user was left with one 29.7 GB file where they had
     * asked for parts. Every engine step now asks
     * `VaultTransfer.transferWatchdogMs(bytes, EXPORT_WATCHDOG_MS)`, so the
     * ceiling grows with the work.
     */
    const val EXPORT_WATCHDOG_MS = 60L * 60L * 1000L

    /**
     * The import side needs the same bound, for the same reason: unpacking tens
     * of gigabytes is real work, and a wedged tar is caught by the engine's own
     * stall detector and by Cancel rather than by a stopwatch.
     */
    const val IMPORT_WATCHDOG_MS = 60L * 60L * 1000L

    /**
     * Export, reporting progress as it goes.
     *
     * [onProgress] receives (percent, sizeLabel, phase) on every tick.
     *
     * How the percentage is derived, and why it is honest: toybox `tar` reports
     * nothing per-file on create (`-v` only works on *list*), and `zapdos` is a
     * prebuilt binary we do not control, so the only signal available is the
     * staged file's size. That is a real measurement of real work — bytes already
     * read and written — measured against the carried trees' exact size. It is
     * not a guess and not a timer.
     *
     * Two consequences are handled rather than hidden: the compressed output can
     * be *smaller* than the input, which would stall the bar short of 100 (capped
     * at 99 until the command exits), and it can be slightly *larger* because tar
     * writes a 512-byte header per file, which is clamped. The size label always
     * carries the true figures, so the user can see the real state regardless.
     */
    suspend fun export(
        context: Context,
        vaultPath: String,
        cancel: java.util.concurrent.atomic.AtomicBoolean? = null,
        /**
         * Cut the finished archive into pieces of this many bytes. 0 means one
         * file. A single 34 GB file cannot be moved to a PC in one shot over
         * MTP reliably, and FAT32 media cannot hold it at all — pieces of a
         * few GB each can. The pieces stream back together on import, so they
         * are never reassembled into a second full copy.
         */
        splitBytes: Long = 0,
        onProgress: ((Int, String, TransferPhase) -> Unit)? = null
    ): Result {
        /**
         * The in-progress archive THIS run owns, so `finally` can remove that
         * one file by name instead of sweeping the tree.
         *
         * The sweep is correct at the START of a run, when none of our files
         * exist yet. It is not correct at the END: an export and an automation
         * run do not exclude each other — this path never took the run gate —
         * so a blanket delete in `finally` could remove a file a *live* export
         * is still writing into, and that writer would carry on into a file
         * that no longer exists. Deleting by name is the only version of this
         * that cannot take someone else's work with it.
         */
        var ownedPart: String? = null
        return try {
            ShellQuote.requireAbsPath(vaultPath)
            // The pack step below runs in the native engine now. A stale engine
            // would answer "Unknown command" and the export would fail closed
            // here rather than halfway through a multi-GB pack.
            if (!BackendInstaller.isEngineUpToDate(context) &&
                !BackendInstaller.installEngine(context)
            ) {
                return Result.Failed("Backup engine unavailable — cannot pack the transfer")
            }
            // The engine watches this flag file natively; a leftover from a
            // previous cancel would stop this run before it starts.
            AutomationRunner.clearCancel()
            // Reap a previous run's pack staging, if the run died after packing
            // but before placing. Seen on device: a 34 GB dvexport tarball
            // orphaned in app-private storage when the run ended between pack
            // and placement, invisible to every cleanup (the import stager is
            // reaped; this one was not). App-private staging is never resumed
            // — only removed — because a staging file from a dead run carries
            // no proof of what produced it.
            // Nothing is staged in app-private storage any more — the pack
            // lands in the export's own folder — so there is no orphan here to
            // reap. The reaping that matters is the in-progress dot-file, which
            // is handled below where the batch folder is created.
            // Read the size with the ONE reader, not a second hand-rolled parse.
            // `du -sk` prints `<kb><TAB><path>` (verified on device:
            // `413008\t/storage/emulated/0/DroidVault`), and `trim()` removes the
            // tabs at the ENDS of the line, never the one inside it — so
            // `"413008\t/storage/…".toLongOrNull()` is null and this read has
            // been returning 0 for the whole vault on every export. The gate
            // below is `vaultKb > 0 && …`, so it never refused anything: the
            // export packed until the storage filled as root, the engine
            // discarded the partial, and the app blamed root for what was a
            // full disk. This is the same parse `probeKb` was written to fix
            // (§2.31) — the fix was applied to one of the two readers.
            val vaultKb = duKb(vaultPath)
            // ONE filesystem: the archive is written straight into its export
            // folder and renamed there. It used to be packed into app staging and
            // copied across, which meant two gates and two budgets for one
            // result; the copy is gone, so the gate is too.
            val needKb = VaultTransfer.exportNeedsKb(vaultKb, splitBytes > 0, VaultTransfer.EXPORT_HEADROOM_KB)
            val downloadKb = freeSpaceKb(EXPORT_DIR)
            if (downloadKb == null) {
                Log.w(TAG, "free-space probe failed; exporting without a gate")
            }
            if (vaultKb > 0 && downloadKb != null && downloadKb < needKb) {
                return Result.Failed(
                    "Not enough free space to make a copy of your backups. This needs " +
                        "about ${(needKb / 1024 / 1024)} GB free in Downloads/DroidVault" +
                        (if (splitBytes > 0) " — the file is split into parts afterwards" else "") +
                        " — and there is only ${downloadKb / 1024 / 1024} GB available. Free up " +
                        "space, or move the vault folder itself to the SD card."
                )
            }
            Shell.cmd("mkdir -p ${ShellQuote.q(EXPORT_DIR)}").exec()
            // One folder per export, named for when it started.
            //
            // They used to share a single folder, so a second export landed
            // fourteen pieces among the first export's fourteen and every set
            // had to be told apart by reading names. One folder
            // per export is one thing to copy to a PC, one thing to delete, and
            // one thing that cannot be half-deleted by accident.
            var batch = VaultTransfer.exportBatchName(System.currentTimeMillis())
            var dup = 2
            while (Shell.cmd("[ -e ${ShellQuote.q(VaultTransfer.exportBatchDir(batch))} ]")
                    .exec().isSuccess
            ) {
                batch = "${VaultTransfer.exportBatchName(System.currentTimeMillis())}-$dup"
                if (++dup > 100) {
                    return Result.Failed("Could not pick a fresh export folder in Downloads/DroidVault.")
                }
            }
            val batchDir = VaultTransfer.exportBatchDir(batch)
            Shell.cmd("mkdir -p ${ShellQuote.q(batchDir)}").exec()
            // Reap across the WHOLE export tree, not this batch folder.
            //
            // Scoping it to the folder just created matches nothing, because
            // that folder is empty by definition — so an export killed
            // mid-pack (crash, battery pull, OOM kill) left its in-progress
            // archive behind, and the next export made a NEW folder and never
            // looked at it again. That is thirty gigabytes per attempt, and
            // the reaping is the only thing standing between an interrupted
            // export and a full disk.
            reapInProgressExports()
            val name = VaultTransfer.exportFileName(System.currentTimeMillis())
            val dest = "$batchDir/$name"

            // The configurations are staged inside the vault first, so the
            // archive can hold them alongside the trees. Everything here is
            // removed again in the `finally` below.
            val metaDir = stageTransferMeta(context, vaultPath)
            val carry = carryList(vaultPath, metaDir)
            if (carry.isEmpty()) {
                clearTransferMeta(vaultPath)
                return Result.Failed("There is nothing in the backup folder to transfer yet.")
            }
            val trees = carry.joinToString(" ") { ShellQuote.q(it) }

            // Packed DIRECTLY into the export's own folder, as a hidden name,
            // and renamed into place at the end.
            //
            // It used to be packed into the app's cache and then copied across —
            // 30 GB read and 30 GB written for nothing. Measured on the device:
            // that copy ran at ~54 MB/s against ~340 MB/s for the same bytes in
            // a single `dd`, because it was competing with the writeback of the
            // pack that had just finished. The rename it ended with is free,
            // so the whole second pass was pure cost.
            //
            // The hidden name is what makes this safe: an interrupted run leaves
            // a dot-file that reads as nothing, never a plausible archive, and
            // the same name is reaped at the start of every export.
            val staging = "$batchDir/$EXPORT_TMP_PREFIX${System.nanoTime()}.part"
            ownedPart = staging
            // The APP creates the staging file, then root's tar writes into it.
            //
            // Root-created files carry the shell's SELinux label, which the app
            // cannot read even at mode 644 — so File.length() returned 0 and the
            // shell fallback blocked behind tar. Creating it here gives the file
            // the app's own label, so the growing size is readable with no shell
            // round-trip at all. `tar -czf` truncates the existing file, so the
            // archive content is unaffected.
            // NOT created by the app any more: it lives under shared storage,
            // which a scoped-storage app cannot write to directly. Root's tar
            // creates it, and progress comes from the engine's own stat, so no
            // app-side file handle is needed.
            Shell.cmd("touch ${ShellQuote.q(staging)} 2>/dev/null").exec()
            //
            // `cd` into the vault rather than `tar -C vault <abs path>`: tar
            // stores member names EXACTLY as given, so passing
            // "/storage/…/DroidVault/Apps" produced an archive rooted at
            // "storage/emulated/0/DroidVault/Apps/…" instead of "Apps/…".
            // Import then extracted into staging/<that path> and found no apps
            // at all — found on device by listing the exported tarball.
            // Exact size of the trees actually being carried, which is what the
            // bar is measured against — not the whole vault, which would include
            // the per-install mirrors that are deliberately excluded.
            // ONE `du` with both trees as arguments. This was
            // joinToString(" ") over per-tree commands, which produced
            // `du -sk A 2>/dev/null du -sk B 2>/dev/null` — the second du became an
            // ARGUMENT to the first, so the total came back as one tree's worth
            // (or zero) and every sample divided by it. Caught on device: the bar
            // read "0 B so far" while the file grew past 2 GB.
            // One exec per tree, exactly like the proven size probe further down.
            // A single `du -sk A B` call returned nothing on device even though the
            // command is valid, which left the denominator at zero and froze the
            // label on "0 B so far" for the whole run. Summing separate reads uses
            // the one shape this shell reliably returns output for.
            // execOut(), NOT exec().out. libsu's exec() is NON-blocking: it returns
            // the Job and `.out` is still empty when you read it. That is why the
            // denominator kept coming back 0 while the archive grew — the very
            // first command after the shell wakes has no drained output yet.
            // execOut() blocks and hands back the lines. The size probe lower down
            // appeared to work only because it runs after `tar`, by which time the
            // shell has drained.
            val totalBytes = carry.sumOf { tree ->
                probeKb(tree, vaultPath)
            } * 1024L

            // Packed by the native engine, which reports measured bytes on its
            // own stdout as they land. This replaces the packer thread plus the
            // polling loop: the poll used to queue behind tar on libsu's
            // serialised session and deliver one reading, at the end. The engine
            // owns the tar handle directly, so cancel kills exactly that process
            // instead of pattern-matching the process table.
            var lastPct = 0
            onProgress?.invoke(0, TransferProgress.sizeLabel(0L, totalBytes), TransferPhase.PACKING)
            val engineEntries = carry.joinToString(" ") { ShellQuote.q(it) }
            val pack = runTransferEngine(
                "$ENGINE --transfer-export ${ShellQuote.q(vaultPath)} " +
                    "${ShellQuote.q(staging)} $totalBytes $engineEntries",
                VaultTransfer.transferWatchdogMs(totalBytes, EXPORT_WATCHDOG_MS)
            ) { event ->
                if (event is EngineTransferEvent.Progress && event.op == "EXPORT") {
                    val pct = event.pct.coerceIn(0, 99)
                    if (pct != lastPct) {
                        lastPct = pct
                        onProgress?.invoke(
                            pct,
                            TransferProgress.sizeLabel(event.done, totalBytes),
                            TransferPhase.PACKING
                        )
                    }
                }
            }
            if (pack.cancelled || cancel?.get() == true) {
                Shell.cmd("rm -f ${ShellQuote.q(staging)}").exec()
                return Result.Failed(CANCELLED)
            }
            val built = pack.error == null && statBytes(staging) > 0L
            if (pack.error != null) {
                Log.w(TAG, "transfer engine pack failed: ${pack.error}")
            }
            if (built) {
                lastPct = TransferProgress.percent(totalBytes, totalBytes, true, lastPct)
                onProgress?.invoke(
                    lastPct,
                    TransferProgress.sizeLabel(totalBytes, totalBytes),
                    TransferPhase.PACKING
                )
            }
            if (!built) {
                Shell.cmd("rm -f ${ShellQuote.q(staging)}").exec()
                return Result.Failed("Could not read the vault — is root granted?")
            }

            // Placing the archive.
            //
            // `mv` was used to be a rename, and a rename is atomic — which is the
            // whole point of building in a staging file first. It is NOT a rename
            // here: the cache and Downloads are different filesystems, so `mv`
            // silently became a full second copy. The consequences were all bad:
            // the bar read 100% and then sat still for minutes with nothing shown,
            // a copy of a 34 GB file ran the disk to 97% full, and — worst — the
            // FINAL name was being written incrementally, so an interruption left
            // a truncated file that looked complete. Every one of those is the
            // failure the staging file existed to prevent.
            //
            // The archive was already written into the export's own folder, so
            // putting it under its real name is a RENAME within one filesystem —
            // atomic, instant, and no second copy of thirty gigabytes.
            val moved = Shell.cmd(
                "mv -f ${ShellQuote.q(staging)} ${ShellQuote.q(dest)} && " +
                    "chmod 644 ${ShellQuote.q(dest)}"
            ).exec().isSuccess
            if (!moved) {
                Shell.cmd("rm -f ${ShellQuote.q(staging)}").exec()
                return Result.Failed("Could not write to $EXPORT_DIR")
            }

            // The archive's SIZE, not its disk usage. This was `du -sk * 1024`,
            // and `du` reports allocated blocks — on the shared-storage FUSE
            // mount that runs ~0.1% over the apparent size. The split's own
            // integrity check adds up its parts with `stat`, so the two sides
            // were measuring different things: a 29.7 GB export computed
            // 29,795,213,312 against parts summing 29,765,935,266, the 28 MB
            // gap was declared a corruption, and every good part was deleted.
            // The user got a single file where they had asked for parts and a
            // failure that named a problem that did not exist (found on device,
            // 2026-10-11). Measure one thing, in one way, on both sides.
            val realBytes = statBytes(dest)
            // Close the bar on the archive that was ACTUALLY produced, not on the
            // vault it was made from. gzip on already-compressed media lands near
            // 1:1 and on text lands far from it, so ending on the input size
            // reports a file size the user never got.
            val sizeKb = realBytes / 1024L

            // Split for moving. Done here, in Downloads, after the single file
            // is verified on disk — never streamed during the pack, so a split
            // failure cannot be mistaken for a pack failure and the good single
            // file is still there to fall back on.
            var partCount = 1
            if (splitBytes > 0 && realBytes > splitBytes) {
                val count = ((realBytes + splitBytes - 1) / splitBytes).toInt()
                if (count > 676) {
                    Shell.cmd("rm -f ${ShellQuote.q(dest)}").exec()
                    return Result.Failed(
                        "That split size would make $count parts, which cannot be " +
                            "named. Choose a bigger part size."
                    )
                }
                var splitPct = 0
                val split = runTransferEngine(
                    "$ENGINE --transfer-split ${ShellQuote.q(dest)} " +
                        "${ShellQuote.q("$dest${VaultTransfer.PART_SEP}")} $splitBytes",
                    // Sized by the archive, not the part count: this writes the
                    // file's whole weight a second time, which is what ran past
                    // the old flat bound.
                    VaultTransfer.transferWatchdogMs(realBytes, EXPORT_WATCHDOG_MS)
                ) { event ->
                    if (event is EngineTransferEvent.Progress && event.op == "SPLIT") {
                        val pct = event.pct.coerceIn(0, 99)
                        if (pct != splitPct) {
                            splitPct = pct
                            onProgress?.invoke(
                                pct,
                                TransferProgress.sizeLabel(event.done, realBytes),
                                TransferPhase.SPLIT
                            )
                        }
                    }
                }
                if (split.cancelled || cancel?.get() == true) {
                    Shell.cmd("rm -f ${ShellQuote.q(dest)}${VaultTransfer.PART_SEP}*").exec()
                    Shell.cmd("rm -f ${ShellQuote.q(dest)}").exec()
                    return Result.Failed(CANCELLED)
                }
                if (split.error != null) {
                    Log.w(TAG, "transfer engine split failed: ${split.error}")
                    Shell.cmd("rm -f ${ShellQuote.q(dest)}${VaultTransfer.PART_SEP}*").exec()
                    return Result.Failed("Could not split the transfer file: ${split.error}")
                }
                // Verify before deleting the only good copy: every expected
                // piece present, and their bytes adding up to the file they
                // came from. A short set that "looks complete" is PR-10 again,
                // one directory up.
                val base = File(dest).name
                val missing = VaultTransfer.missingParts(base, count, listDownloadParts(dest))
                if (missing.isNotEmpty()) {
                    Shell.cmd("rm -f ${ShellQuote.q(dest)}${VaultTransfer.PART_SEP}*").exec()
                    return Result.Failed(
                        "Splitting stopped early — missing ${missing.size} part(s). " +
                            "The single file was kept in Downloads."
                    )
                }
                val partsBytes = downloadPartsBytes(dest)
                if (partsBytes != realBytes) {
                    Shell.cmd("rm -f ${ShellQuote.q(dest)}${VaultTransfer.PART_SEP}*").exec()
                    return Result.Failed(
                        "The parts do not add up to the file they came from. " +
                            "The single file was kept in Downloads."
                    )
                }
                // The manifest is what makes the set checkable on the way back:
                // without a recorded count, trailing missing pieces are
                // undetectable. Written only after the bytes agree.
                writePartsManifest(dest, count, realBytes, splitBytes)
                Shell.cmd("rm -f ${ShellQuote.q(dest)}").exec()
                partCount = count
                onProgress?.invoke(
                    100,
                    TransferProgress.sizeLabel(realBytes, realBytes),
                    TransferPhase.SPLIT
                )
            }
            onProgress?.invoke(
                100,
                TransferProgress.sizeLabel(realBytes, realBytes),
                TransferPhase.PACKING
            )
            val apps = Shell.cmd(
                "ls -1 ${ShellQuote.q("$vaultPath/Apps/User")} ${ShellQuote.q("$vaultPath/Apps/System")} " +
                    "2>/dev/null | wc -l"
            ).exec().out.joinToString("").trim().toLongOrNull() ?: 0L

            Log.i(TAG, "exported ${apps} app archive(s) to $dest (${sizeKb} KB, $partCount part(s))")
            Result.Exported(dest, apps.toInt(), sizeKb, partCount)
        } catch (e: Exception) {
            Log.w(TAG, "export failed", e)
            Result.Failed(e.message ?: "Export failed")
        } finally {
            // Every exit from the try above lands here, including the early
            // returns. The staged configurations must not survive the export:
            // a dot-directory holding another device's configs would sit in the
            // vault looking authoritative. The cancel flag goes too: a leftover
            // would wrongly cancel the NEXT backup run, whose engine watches it.
            clearTransferMeta(vaultPath)
            AutomationRunner.clearCancel()
            // Our own in-progress archive, by name. A thrown exception unwinds
            // here without ever reaching the cancel branch that removes it.
            // Only ever this run's file: a sweep here could delete an archive a
            // concurrent export is still writing. A SIGKILL runs no `finally`
            // at all, which is what the start-of-run sweep exists for.
            ownedPart?.let { Shell.cmd("rm -f ${ShellQuote.q(it)}").exec() }
            // And nothing else. The tree-wide sweep belongs at the START of a
            // run, where nothing of ours exists yet; running it here as well
            // was redundant and wrong in principle — a sweep is only safe when
            // you cannot name what you are deleting, and by this point the gate
            // guarantees there is no other transfer to delete it out from
            // under. Belt and braces is how a cleanup ends up removing somebody
            // else's work.
        }
    }

    /**
     * Import a transfer archive into [vaultPath].
     *
     * Extracts into a staging directory, then copies each app directory that
     * the plan admits. Extraction is never done straight over the live vault:
     * a truncated or hostile archive would otherwise be able to delete files it
     * does not contain.
     */
    /**
     * Import a transfer file, reporting progress.
     *
     * Unlike export, import progress can be **exact** rather than estimated,
     * because both halves have known denominators:
     *
     *  - READING: `tar -tzvf` lists every member with its size before a byte is
     *    written, so the total is the archive's real uncompressed content, and
     *    the staging directory's growth is the real progress against it.
     *  - COPYING: each app's size in staging is known, so a 90 MB app and a 2 MB
     *    app contribute in proportion instead of each counting as one step.
     */
    suspend fun import(
        context: Context,
        destCounts: VaultTransfer.ConfigCounts,
        vaultPath: String,
        archivePath: String,
        merge: VaultTransfer.Merge,
        cancel: java.util.concurrent.atomic.AtomicBoolean? = null,
        /**
         * Pieces of a split transfer, in stream order, instead of one file.
         * Verified (manifest + sizes) by the caller before this runs: by the
         * time anything is written, the set is known complete and whole.
         */
        sourceParts: List<String> = emptyList(),
        onProgress: ((Int, String, TransferPhase) -> Unit)? = null
    ): Result {
        // Staging is removed here, once, on EVERY exit — success, failure,
        // cancellation, and any thrown exception along the way.
        //
        // It used to be six separate `rm -rf` calls scattered through the
        // paths below. That works right up until someone adds a seventh early
        // return and forgets, and "forgot" leaves thirty gigabytes in the vault
        // with nothing scheduled to remove it. A cleanup that depends on
        // remembering is not a cleanup.
        //
        // Only `$vault/.dvimport` is touched. It is created by the extraction
        // and is the ONLY thing an import writes before the place step, so
        // this can never reach the live archives beside it — which is the
        // property the whole staging design exists to provide.
        val stagingDir = "$vaultPath/${VaultTransfer.IMPORT_STAGING}"
        return try {
            importInternal(
                context, destCounts, vaultPath, archivePath, merge, cancel, sourceParts, onProgress
            )
        } finally {
            Shell.cmd("rm -rf ${ShellQuote.q(stagingDir)}").exec()
        }
    }

    /**
     * The import body, run under [import]'s staging cleanup.
     *
     * Separate so the `finally` above cannot be bypassed by a new return
     * statement added in here.
     */
    private suspend fun importInternal(
        context: Context,
        destCounts: VaultTransfer.ConfigCounts,
        vaultPath: String,
        archivePath: String,
        merge: VaultTransfer.Merge,
        cancel: java.util.concurrent.atomic.AtomicBoolean?,
        sourceParts: List<String>,
        onProgress: ((Int, String, TransferPhase) -> Unit)?
    ): Result {
        return try {
            ShellQuote.requireAbsPath(vaultPath)
            val multipart = sourceParts.isNotEmpty()
            if (!multipart && !VaultTransfer.looksLikeTransferFile(File(archivePath).name)) {
                return Result.Failed("Not a Droid Vault transfer file")
            }
            if (!BackendInstaller.isEngineUpToDate(context) &&
                !BackendInstaller.installEngine(context)
            ) {
                return Result.Failed("Backup engine unavailable — cannot read the transfer")
            }
            AutomationRunner.clearCancel()

            // Reap a previous run's staging up front.
            //
            // Staging lives on the vault's filesystem so the final move is a
            // rename, which means it is OUTSIDE app data and Android cannot
            // reclaim it if this app is killed. A force-stop mid-import left
            // 32 GB sitting in the vault forever. Clear it before doing anything
            // expensive, so an abandoned run costs nothing but the time to notice.
            Shell.cmd("rm -rf ${ShellQuote.q("$vaultPath/${VaultTransfer.IMPORT_STAGING}")}").exec()

            // Say what is about to happen before doing it. `tar -tvf` below has
            // to inflate the entire archive to list it, and that was happening
            // with the UI still showing the copy phase's last frame.
            // The sheet already prints the phase name, so the detail line must
            // not repeat it: it rendered as "Checking what's inside — Reading
            // what's inside".
            onProgress?.invoke(-1, "Working out what this file contains", TransferPhase.SURVEY)

            // Space, checked before anything is written.
            //
            // Import needs the archive THREE times over at its peak: the copy the
            // picker made, the expanded staging tree, and the finished copies in
            // the vault. Export grew a gate for exactly this reason; import never
            // had one, so the failure arrived as ENOSPC halfway through a 34 GB
            // expansion — after the disk was already full and the user had no way
            // to have known. Refusing up front, with numbers, is the whole point.
            //
            // The archive's own uncompressed listing is used rather than the
            // compressed file size, because that is what actually gets written.
            // On a watchdog like every other long shell job here. A bare exec()
            // that wedges would leave the SURVEY label up forever, which is the
            // same class of silent stall as the 2-second join this replaced.
            // ONE pass, on a watchdog. Collecting the listing inside the worker
            // rather than re-running the command afterwards matters: inflating a
            // 34 GB archive takes minutes, and a second `tar -tzvf` would double
            // the most expensive step in the import to satisfy a timeout.
            // Split transfers are surveyed by the engine, which streams the
            // concatenation straight through `tar -tzvf` and sums in-process:
            // one pass, no intermediate file, and the single-file path below is
            // left exactly as it was verified rather than unified for elegance.
            val listedBytes: Long
            if (multipart) {
                // A transfer written by this build is a plain tar, so its
                // uncompressed size IS its file size — measured on a 29.97 GB
                // export: the full member listing said 29,967,118,523 bytes
                // against a file of 29,967,136,768. The difference is tar's
                // 512-byte block padding, 0.00006% of the total.
                //
                // Reading the whole archive to establish a number we already
                // have costs a full pass over 30 GB, measured at 2m56s on the
                // reference device. For a gzip archive it is worth it, because
                // the compressed size says nothing about the expansion — which
                // is exactly why it is kept for that case rather than dropped.
                if (!shellIsGzip(sourceParts.first())) {
                    listedBytes = sourceParts.sumOf { statBytes(it) }
                    onProgress?.invoke(
                        -1,
                        "Reading ${TransferProgress.humanBytes(listedBytes)} of transfer",
                        TransferPhase.SURVEY
                    )
                } else {
                var surveyBytes = -1L
                val survey = runTransferEngine(
                    "$ENGINE --transfer-survey " +
                        sourceParts.joinToString(" ") { ShellQuote.q(it) },
                    IMPORT_WATCHDOG_MS
                ) { event ->
                    if (event is EngineTransferEvent.SurveyDone) surveyBytes = event.bytes
                }
                if (survey.cancelled || cancel?.get() == true) return Result.Failed(CANCELLED)
                if (survey.error != null || surveyBytes < 0) {
                    Log.w(TAG, "transfer engine survey failed: ${survey.error}")
                    return Result.Failed(
                        "Could not read what is inside the transfer file. It may be damaged."
                    )
                }
                listedBytes = surveyBytes
                }
            } else if (!shellIsGzip(archivePath)) {
                // Same reasoning as the split path: a plain tar's uncompressed
                // size is its file size, so listing it to find out would be a
                // full pass over the whole archive to learn a number we can
                // read in one stat.
                listedBytes = statBytes(archivePath)
                onProgress?.invoke(
                    -1,
                    "Reading ${TransferProgress.humanBytes(listedBytes)} of transfer",
                    TransferPhase.SURVEY
                )
            } else {
            val listing = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
            val lister = Thread {
                listing.set(
                    Shell
                        .cmd("tar ${tarListFlag(archivePath)} ${ShellQuote.q(File(archivePath).absolutePath)} 2>/dev/null")
                        .exec().out
                )
            }
            lister.isDaemon = true
            lister.start()
            val listDeadline = System.currentTimeMillis() + IMPORT_WATCHDOG_MS
            while (lister.isAlive && System.currentTimeMillis() < listDeadline) {
                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    break
                }
                // Cancelled while still working out what is inside.
                if (cancel?.get() == true) {
                    lister.interrupt()
                    Shell.cmd(
                        "pkill -f 'tar ${tarListFlag(archivePath)} ${ShellQuote.q(File(archivePath).absolutePath)}' 2>/dev/null"
                    ).exec()
                    return Result.Failed(CANCELLED)
                }
            }
            if (cancel?.get() == true) return Result.Failed(CANCELLED)
            if (lister.isAlive) {
                lister.interrupt()
                Shell.cmd(
                    "pkill -f 'tar ${tarListFlag(archivePath)} ${ShellQuote.q(File(archivePath).absolutePath)}' 2>/dev/null"
                ).exec()
                return Result.Failed(
                    "Could not read what is inside the transfer file. It may be damaged."
                )
            }
            // ONE listing, reused for the space gate AND the progress label
            // further down. It used to run twice, and each pass has to inflate
            // the whole archive — measured at 2m48s each on a 34 GB file, so the
            // duplicate cost three minutes of the user's time to recompute a
            // number already in memory.
            listedBytes = listing.get().sumOf { line ->
                line.trim().split("\\s+".toRegex()).getOrNull(2)?.toLongOrNull() ?: 0L
            }
            }
            val unpackKb = listedBytes / 1024L
            // Split pieces already sit on this phone (same filesystem as the
            // vault), so they cost nothing extra to keep — but the expansion
            // and the vault copies still need the same room as ever.
            val stagedBytes = if (multipart) {
                splitPartsBytes(archivePath)
            } else {
                File(archivePath).length()
            }
            // Probed on the VAULT's filesystem, where the staging tree and the
            // vault copies actually land. This used to probe the app-cache dir
            // — a different filesystem, and one that might not even exist yet,
            // in which case the probe failed silently and the gate measured
            // nothing: a 34 GB import sailed through with 96 GB free against a
            // ~103 GB need.
            val freeKb = freeSpaceKb(vaultPath)
            val needKb = VaultTransfer.importNeedsKb(unpackKb, VaultTransfer.IMPORT_HEADROOM_KB)
            if (freeKb == null) {
                Log.w(TAG, "free-space probe failed; importing without a gate")
            }
            if (freeKb != null && unpackKb > 0 && freeKb < needKb) {
                val needGb = needKb / 1024 / 1024
                return Result.Failed(
                    "Not enough free space to unpack this transfer. It needs about " +
                        "${needGb} GB free — " +
                        (if (multipart) "the parts are kept, expanded, and then copied "
                        else "the file is kept, expanded, and then copied ") +
                        "into your backup folder — and there is only " +
                        "${freeKb / 1024 / 1024} GB available. Free up space and try again."
                )
            }

            // Staging lives on the VAULT's filesystem, not the app's.
            //
            // The final step moves each app into the vault. With staging on
            // internal storage those are different filesystems, so the move
            // degraded to a full read+write of every archive — 34 GB in and 34 GB
            // out, about six minutes of copying files that were already in the
            // right place. On the vault's own filesystem the same move is a
            // rename: measured on device, 400 MB in 44 ms.
            //
            // A dot-directory beside `.trash`, because it is visible to file
            // managers for the duration and must not look like vault content.
            // Cleared at the start of every import, so an abandoned one from a
            // killed run cannot be mistaken for this transfer's staging.
            val staging = "$vaultPath/${VaultTransfer.IMPORT_STAGING}"
            val archive = File(archivePath).absolutePath
            Shell.cmd("mkdir -p ${ShellQuote.q(STAGING_DIR)}").exec()

            // Reused from the survey above — no second pass.
            //
            // `tar -tv`'s size column is ALREADY in bytes. An earlier version
            // multiplied by 1024 a second time, so a 3.72 GB transfer was
            // announced as "3.8 TB to read".
            val extractTotalBytes = listedBytes

            // Indeterminate from the very first frame, and re-stated on every
            // tick. This used to report `0 B of 34.2 GB` at `0%` for the whole
            // extraction, which for a multi-minute read of a large file is
            // indistinguishable from a hang. The size is known and is shown; the
            // fraction is not measurable while tar holds the shell, and a bar
            // that says so beats one that says zero.
            val readingLabel = TransferProgress.humanBytes(extractTotalBytes) + " to read"
            onProgress?.invoke(-1, readingLabel, TransferPhase.READING)

            // Extracted by the native engine, which measures the staging tree
            // as it grows and reports real percentages. This is the phase that
            // used to be deliberately indeterminate: the extractor held the
            // shell session, so any probe queued behind it and the bar sat at 0
            // for the whole multi-minute read. The engine sees the bytes as they
            // land, so "Reading the transfer file" finally has numbers.
            var extractPct = 0
            // Split pieces stream straight through: `cat` feeds `tar` and the
            // staging tree is measured as it grows, so parts are NEVER
            // reassembled into a second full copy first. Same progress lines
            // either way, so the UI cannot tell which shape it is reading.
            val extractCommand = if (multipart) {
                "$ENGINE --transfer-concat-extract ${ShellQuote.q(staging)} " +
                    "$extractTotalBytes " +
                    sourceParts.joinToString(" ") { ShellQuote.q(it) }
            } else {
                "$ENGINE --transfer-extract ${ShellQuote.q(archive)} " +
                    "${ShellQuote.q(staging)} $extractTotalBytes"
            }
            val run = runTransferEngine(
                extractCommand,
                VaultTransfer.transferWatchdogMs(extractTotalBytes, IMPORT_WATCHDOG_MS)
            ) { event ->
                if (event is EngineTransferEvent.Progress && event.op == "EXTRACT") {
                    val pct = event.pct.coerceIn(0, 99)
                    if (pct != extractPct) {
                        extractPct = pct
                        onProgress?.invoke(
                            pct,
                            TransferProgress.sizeLabel(event.done, extractTotalBytes),
                            TransferPhase.READING
                        )
                    }
                }
            }
            if (run.cancelled || cancel?.get() == true) {
                Shell.cmd("rm -rf ${ShellQuote.q(staging)}").exec()
                AutomationRunner.clearCancel()
                return Result.Failed(CANCELLED)
            }
            val extracted = run.error == null
            if (run.error != null) {
                Log.w(TAG, "transfer engine extract failed: ${run.error}")
            }
            if (!extracted) {
                Shell.cmd("rm -rf ${ShellQuote.q(staging)}").exec()
                // Name what is missing. The bare "Could not read the transfer
                // file" was reported for a file that had been staged seconds
                // earlier, so the message could not be acted on and looked like
                // the app's own bug.
                // Kotlin, not the shell. The app itself just wrote this file into
                // its own filesDir, so `File` is the authority — and asking the
                // shell for a boolean here means a second round-trip on a session
                // that has just been through a multi-gigabyte extraction.
                // Through the shell, not File: shared storage is not directly
                // readable under scoped storage, which is the whole reason the
                // SAF copy path exists for single files.
                val present = if (multipart) {
                    sourceParts.isNotEmpty() && sourceParts.all {
                        Shell.cmd("[ -s ${ShellQuote.q(it)} ]").exec().isSuccess
                    }
                } else {
                    File(archive).isFile && File(archive).length() > 0L
                }
                return Result.Failed(
                    if (!present) {
                        "The transfer file was removed before it could be read. " +
                            "Pick it again."
                    } else {
                        "Could not open the transfer file. It may be damaged, or " +
                            "there may not be enough free space to unpack it."
                    }
                )
            }

            val incoming = Shell.cmd(
                "find ${ShellQuote.q("$staging/Apps/User")} ${ShellQuote.q("$staging/Apps/System")} " +
                    "-mindepth 1 -maxdepth 1 -type d -printf '%f\\n' 2>/dev/null"
            ).exec().out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

            if (incoming.isEmpty()) {
                Shell.cmd("rm -rf ${ShellQuote.q(staging)}").exec()
                return Result.Failed("The transfer file contains no app backups")
            }

            // Read the carried configurations NOW, while staging still exists.
            // Staging is removed at the end of the copy, so a read placed after it
            // silently finds nothing — which is exactly what happened on device:
            // "imported 0 new, 60 kept" and not one configuration carried over.
            // Applying them stays late, after the copy succeeds.
            val metaDir = "$staging/${VaultTransfer.META_DIR}"
            // Read ONCE, into memory, and keep it there. Staging is deleted by
            // the copy phase that follows, so anything that re-reads it later
            // finds nothing — which is how a clean restore once reported
            // "restored your setups" while writing an empty file.
            val payload = readCarriedPayload(context, metaDir)
            val counts = payload?.let { carriedCounts(it) }

            val existing = Shell.cmd(
                "ls -1 ${ShellQuote.q("$vaultPath/Apps/User")} ${ShellQuote.q("$vaultPath/Apps/System")} " +
                    "2>/dev/null"
            ).exec().out.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

            val plan = VaultTransfer.planImport(incoming, existing, merge)
            val actuallyAdded = mutableListOf<String>()
            val actuallyReplaced = mutableListOf<String>()
            val failed = mutableListOf<String>()

            val toCopy = plan.added + plan.replaced

            // Every directory's real size, in ONE fork rather than one per app:
            // this is what makes the copy phase's percentage true rather than
            // "count of steps", and it also stops a run from appearing stalled for
            // the duration of the largest single app.
            val sizes = HashMap<String, Long>()
            if (toCopy.isNotEmpty()) {
                val probe = Shell.cmd(
                    "du -sk " + toCopy.joinToString(" ") { dir ->
                        "${ShellQuote.q("$staging/Apps/User/$dir")} " +
                            ShellQuote.q("$staging/Apps/System/$dir")
                    } + " 2>/dev/null"
                ).exec().out
                probe.forEach { line ->
                    val f = line.trim().split("\\s+".toRegex())
                    val kb = f.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    // `du` prints "size<TAB>path"; take the last path field.
                    val path = f.lastOrNull() ?: return@forEach
                    val name = path.trimEnd('/').substringAfterLast('/')
                    if (name.isNotEmpty()) sizes[name] = maxOf(sizes[name] ?: 0L, kb * 1024L)
                }
            }
            val weighted = TransferProgress.Weighted(sizes)
            onProgress?.invoke(
                0,
                TransferProgress.sizeLabel(0L, weightedTotal(sizes)),
                TransferPhase.COPYING
            )

            // Placed by the native engine: each staged app is renamed into the
            // vault, which is atomic on one filesystem and measured at 44 ms
            // for 400 MB on device. This replaces one root shell per app — each
            // of which queued on libsu's serialised session — with one engine
            // call that reports every file as it lands. The plan (which dirs,
            // keep or replace) stays here; the engine only moves what it is
            // told to, and a kept archive is reported, never deleted.
            val mode = if (merge == VaultTransfer.Merge.REPLACE) "replace" else "keep"
            val dirArgs = toCopy.joinToString(" ") { ShellQuote.q(it) }
            // The engine can report "kept" for a dir the plan marked added if
            // the destination appeared between planning and placing. Those must
            // still be counted somewhere, or the outcome under-reports.
            val engineKept = mutableListOf<String>()
            val place = runTransferEngine(
                "$ENGINE --transfer-place ${ShellQuote.q(staging)} " +
                    "${ShellQuote.q(vaultPath)} $mode $dirArgs",
                IMPORT_WATCHDOG_MS
            ) { event ->
                if (event is EngineTransferEvent.FilePlaced) {
                    when (event.state) {
                        "added" ->
                            if (event.name in plan.replaced) actuallyReplaced += event.name
                            else actuallyAdded += event.name
                        "kept" -> engineKept += event.name
                        else -> failed += event.name
                    }
                    val pct = weighted.complete(event.name)
                    // The label tracks the same counter the bar does. They were
                    // reading different things, and the bar was right.
                    onProgress?.invoke(
                        pct,
                        TransferProgress.sizeLabel(weighted.doneBytes, weightedTotal(sizes)),
                        TransferPhase.COPYING
                    )
                }
            }
            if (place.cancelled || cancel?.get() == true) {
                Shell.cmd("rm -rf ${ShellQuote.q(staging)}").exec()
                AutomationRunner.clearCancel()
                return Result.Failed(CANCELLED)
            }
            if (place.error != null) {
                Log.w(TAG, "transfer engine place failed: ${place.error}")
                // The engine reports per-file states, so a transport failure
                // means the dirs it never reported on did not land.
                failed += toCopy.filter { it !in actuallyAdded && it !in actuallyReplaced }
            }

            // Device data is a fixed set of names, so it merges file-by-file.
            //
            // One command for the whole set, as it used to be, meant the bar sat
            // frozen at the last app's percentage for the whole of it and then
            // jumped straight to 100. Several hundred megabytes of messages and
            // call logs with no movement at all reads as a hang, and a jump to
            // 100 is exactly the kind of "done" the rest of this app refuses to
            // report (invariant 22). Driven per file, weighted by real size.
            val deviceNames = Shell.cmd(
                "ls -1 ${ShellQuote.q("$staging/Device")} 2>/dev/null"
            ).exec().out.map { it.trim() }.filter { it.isNotEmpty() }
            val deviceSizes = HashMap<String, Long>()
            if (deviceNames.isNotEmpty()) {
                val probe = Shell.cmd(
                    "du -sk " + deviceNames.joinToString(" ") {
                        ShellQuote.q("$staging/Device/$it")
                    } + " 2>/dev/null"
                ).exec().out
                probe.forEach { line ->
                    val f = line.trim().split("\\s+".toRegex())
                    val kb = f.getOrNull(0)?.toLongOrNull() ?: return@forEach
                    val name = (f.lastOrNull() ?: return@forEach).trimEnd('/').substringAfterLast('/')
                    if (name.isNotEmpty()) deviceSizes[name] = maxOf(deviceSizes[name] ?: 0L, kb * 1024L)
                }
            }
            val deviceWeighted = TransferProgress.Weighted(deviceSizes)
            var deviceAdded = 0
            Shell.cmd("mkdir -p ${ShellQuote.q("$vaultPath/Device")}").exec()
            for (name in deviceNames) {
                if (cancel?.get() == true) {
                    Shell.cmd("rm -rf ${ShellQuote.q(staging)}").exec()
                    return Result.Failed(CANCELLED)
                }
                val already = Shell.cmd(
                    "[ -e ${ShellQuote.q("$vaultPath/Device/$name")} ]"
                ).exec().isSuccess
                if (!already) {
                    val ok = Shell.cmd(
                        "cp ${ShellQuote.q("$staging/Device/$name")} " +
                            "${ShellQuote.q("$vaultPath/Device/")}"
                    ).exec().isSuccess
                    if (ok) deviceAdded++
                }
                // Both counters move together. Averaging the two sides' percentages
                // keeps one bar across two different sets of bytes, instead of the
                // device copy being invisible until it finished.
                val devicePct = deviceWeighted.complete(name)
                onProgress?.invoke(
                    (weighted.lastPercent + devicePct) / 2,
                    TransferProgress.sizeLabel(
                        weighted.doneBytes + deviceWeighted.doneBytes,
                        weightedTotal(sizes) + weightedTotal(deviceSizes)
                    ),
                    TransferPhase.COPYING
                )
            }

            Shell.cmd("rm -rf ${ShellQuote.q(staging)}").exec()
            onProgress?.invoke(
                100,
                TransferProgress.sizeLabel(
                    weightedTotal(sizes) + weightedTotal(deviceSizes),
                    weightedTotal(sizes) + weightedTotal(deviceSizes)
                ),
                TransferPhase.COPYING
            )

            val outcome = VaultTransfer.ImportOutcome(
                added = actuallyAdded,
                keptExisting = plan.keptExisting + engineKept,
                replaced = actuallyReplaced,
                failed = failed
            )
            Log.i(
                TAG,
                "imported ${outcome.addedCount} new, ${outcome.replaced.size} replaced, " +
                    "${outcome.keptExisting.size} kept, ${failed.size} failed, $deviceAdded device file(s)"
            )
            // The configurations, and only once the archives are safely in
            // place: a copy that failed halfway must not also have swapped the
            // user's configuration for another device's.
            var configsRestored: VaultTransfer.ConfigCounts? = null
            var configsPending: VaultTransfer.ConfigCounts? = null
            var configsReplacedTo: String? = null
            if (payload != null && counts != null) {
                when (VaultTransfer.planConfigs(counts, destCounts)) {
                    VaultTransfer.ConfigsAction.RESTORE -> {
                        // Reported ONLY if it actually landed. A verdict computed
                        // from the plan rather than the work is how a clean
                        // restore told the user their setups were back when the
                        // vault had been handed an empty file instead.
                        configsReplacedTo = applyCarriedConfigs(context, vaultPath, payload)
                        if (configsReplacedTo != null || !Shell.cmd(
                                "[ -f ${ShellQuote.q("$vaultPath/${AutomationSnapshot.STATE_FILE}")} ]"
                            ).exec().isSuccess
                        ) {
                            configsRestored = counts
                        } else {
                            Log.w(TAG, "carried configuration did not land")
                        }
                    }
                    VaultTransfer.ConfigsAction.ASK -> configsPending = counts
                    VaultTransfer.ConfigsAction.NONE -> Unit
                }
            }
            Result.Imported(outcome, archive, configsRestored, configsPending, configsReplacedTo)
        } catch (e: Exception) {
            Log.w(TAG, "import failed", e)
            Result.Failed(e.message ?: "Import failed")
        } finally {
            // A leftover flag would wrongly cancel the NEXT backup run, whose
            // engine watches the same file. The engine clears it at each start
            // too, but the clear here is what protects a run that never starts.
            AutomationRunner.clearCancel()
        }
    }
}