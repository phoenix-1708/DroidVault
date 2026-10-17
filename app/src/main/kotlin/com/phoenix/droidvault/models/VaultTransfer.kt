package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * Moving a vault between phones.
 *
 * There is no `INTERNET` permission — that is a design commitment, not an
 * oversight — so "transfer" means a single archive file the user moves with the
 * system file manager, then imports on the destination. The engine side is root
 * shell; everything here is the decision-making around it.
 *
 * The dangerous direction is the import. A naive extract overwrites whatever the
 * destination already holds, and a mistimed or half-finished transfer can
 * destroy a working backup set on the new phone before the old one has been
 * checked. So the policy is explicit and defaults to the safe side.
 */
object VaultTransfer {

    const val EXTENSION = "dvbackup.tar"

    /**
     * The extension every export used before compression was dropped.
     *
     * Still recognised on import, and still produced by the file name a part
     * carries (`x.dvbackup.tar.gz.part-aa` → `x.dvbackup.tar.gz`). Nothing
     * rewrites or reinterprets those archives — the engine reads them by their
     * magic number, not by this string — but a user with an older transfer on
     * a PC must still be able to pick it.
     */
    const val LEGACY_EXTENSION = "dvbackup.tar.gz"

    /**
     * Vault-relative subtrees worth carrying.
     *
     * This used to be `Apps` and `Device` and nothing else, which meant a clean
     * install on a new phone got the archives and none of the CONFIGURATIONS —
     * no labels, no per-app assignments, no backup configs, no schedules. The
     * folder registry went with them: `BackupFolderStore` lives in prefs and only
     * recovers by healing from the state file, so a transfer without one left the
     * app a viewer with nothing configured.
     *
     * The mirrors are deliberately NOT here. The engine and launcher are
     * re-deployed from the bundled assets under the freshness gate
     *; carrying them would restore a possibly older
     * engine over the one the gate exists to insist on. The APK mirror is
     * different — it is the reinstall kit, and it is carried.
     */
    val CARRIED_TREES = listOf("Apps", "Device")

    /** Vault-relative files worth carrying, each only when it actually exists. */
    val CARRIED_FILES = listOf(
        "DroidVault.apk"
    )

    /**
     * Staging directory created inside the vault for the duration of an export.
     *
     * toybox `tar` has no working `-C`: `tar -czf out -C /a Apps -C /b meta`
     * archives only `/b`'s tree and prints "tar: Apps: No such file or directory".
     * So everything has to be reachable under ONE root, and the vault is that
     * root. A dot-directory, joining `.trash` and `.droidvault_dir`, so it is
     * invisible to the Apps/Device scans that walk the vault.
     */
    const val META_DIR = ".export-meta"

    /**
     * Where an import expands the archive before moving it into the vault.
     *
     * On the vault's own filesystem on purpose: the move out of it is then a
     * rename rather than a copy. Measured on device — a 400 MB move inside
     * shared storage took 44 ms, and the same move from internal storage costs
     * a full read and write of every byte.
     */
    const val IMPORT_STAGING = ".dvimport"

    /** What an import does with a destination archive that already exists. */
    enum class Merge { KEEP_DESTINATION, REPLACE }

    /** Suffix split adds to each piece: `<archive>.part-aa`, `<archive>.part-ab`, … */
    const val PART_SEP = ".part-"

    /** toybox `split` has no numeric-suffix flag; suffixes are two letters. */
    private val PART_SUFFIX = Regex("\\.part-[a-z]{2}$")

    /** True for a piece of a split transfer, never for a whole archive. */
    fun isPartName(name: String): Boolean = PART_SUFFIX.containsMatchIn(name)

    /**
     * The archive a piece came from: `x.dvbackup.tar.gz.part-aa` → `x.dvbackup.tar.gz`.
     * Null when the name is not a piece.
     */
    fun splitPrefix(name: String): String? =
        if (isPartName(name)) name.substring(0, name.length - 8) else null

    /**
     * The piece names a set of [count] parts must have, in stream order.
     *
     * Two base-26 letters, fixed length, so a plain string sort is the stream
     * order — which is what the importer concatenates in. More than 676 pieces
     * cannot be named this way and is refused rather than misnamed.
     */
    fun expectedPartNames(base: String, count: Int): List<String>? {
        if (count <= 0 || count > 676) return null
        return (0 until count).map { i ->
            base + PART_SEP + "${'a' + i / 26}${'a' + i % 26}"
        }
    }

    /**
     * Which pieces of an expected set are absent from [present].
     *
     * Sorted first, because directory listings arrive in whatever order the
     * filesystem feels like. An empty result means the set is complete — it
     * says nothing about whether the pieces themselves are intact, which is
     * what the byte sums are for.
     */
    fun missingParts(base: String, count: Int, present: Collection<String>): List<String> {
        val expected = expectedPartNames(base, count) ?: return listOf(base)
        val have = present.map { it.substringAfterLast('/') }.toSet()
        return expected.filter { it.substringAfterLast('/') !in have }
    }

    /**
     * What a split set holds, as recorded beside the pieces.
     *
     * Without a recorded count, pieces missing off the end are undetectable on
     * import: every present piece is in order, so the set looks complete. That
     * is a truncated archive that verifies as fine — the precise failure PR-10
     * removed from the single-file path.
     */
    @Immutable
    data class PartsManifest(
        val base: String,
        val parts: Int,
        val totalBytes: Long,
        val partBytes: Long
    ) {
        companion object {
            /** Tolerant: garbage reads as absent, never as a crash mid-import. */
            fun fromJson(json: String): PartsManifest? = try {
                val o = org.json.JSONObject(json)
                PartsManifest(
                    base = o.optString("base", ""),
                    parts = o.optInt("parts", 0),
                    totalBytes = o.optLong("totalBytes", 0L),
                    partBytes = o.optLong("partBytes", 0L)
                ).takeIf { it.parts > 0 && it.totalBytes > 0 && it.partBytes > 0 }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Check [sizes] — one entry per piece, in stream order — against this
         * manifest. Null means the set is complete and whole; anything else
         * names what is wrong, in words the importer can report verbatim.
         */
        fun check(sizes: List<Long>): String? {
            if (sizes.size != parts) {
                return "this transfer has ${sizes.size} of $parts parts — " +
                    "copy every ${base}${PART_SEP} file back first"
            }
            sizes.forEachIndexed { i, size ->
                val want = if (i == sizes.size - 1) totalBytes - partBytes * (parts - 1) else partBytes
                if (size != want) {
                    return "part ${i + 1} of $parts is damaged (wrong size) — " +
                        "copy it to this phone again"
                }
            }
            if (sizes.sum() != totalBytes) {
                return "the parts do not add up to the file they came from — " +
                    "copy them to this phone again"
            }
            return null
        }
    }

    /**
     * KB an import needs free: the staging expansion, plus headroom.
     *
     * This used to be `staged + unpack * 2 + headroom`, budgeting three copies
     * at once. Two of them do not exist, and the gate refused a 29.7 GB import
     * on a phone with 71 GB free because of it — while the same import had run
     * minutes earlier on a phone with marginally more room.
     *
     *  - The archive or its pieces **already exist** and are only ever READ.
     *    Counting them again charged the user for space they were already
     *    using, and it is space they must keep: the transfer is the only copy
     *    until the vault is verified.
     *  - The move into the vault is a **`rename(2)`**, not a copy. Staging is
     *    `$vault/.dvimport` — inside the vault — so both paths are on one
     *    filesystem by construction, and the engine measured 29 GB placed in
     *    two seconds. `transfer_place` keeps a copy fallback for a vault on
     *    adopted storage, but staging lives inside that vault too, so the
     *    fallback is for a failure case, not the expected one.
     *
     * So the peak additional footprint is the expansion itself. Getting this
     * wrong is not symmetric: too small and a large import dies as ENOSPC with
     * the disk full, too large and it refuses work that would have fitted.
     */
    fun importNeedsKb(unpackKb: Long, headroomKb: Long): Long = unpackKb + headroomKb

    /**
     * KB an export needs free, on the one filesystem it now writes to.
     *
     * This used to return a pair, because the archive was packed into app
     * staging and then COPIED into Downloads — two filesystems, so two gates,
     * and the copy is now gone entirely (§2.36). The pack is written straight
     * into the export's own folder and renamed into place.
     *
     * Peak is the archive, plus its pieces when splitting: the rename is free,
     * and the single file is deleted only after the pieces have been verified
     * against it, so both exist at once by design.
     */
    fun exportNeedsKb(vaultKb: Long, split: Boolean, headroomKb: Long): Long =
        vaultKb * (if (split) 2 else 1) + headroomKb

    /**
     * The headroom both gates charge, in KB.
     *
     * These were `private const` inside [com.phoenix.droidvault.utils.VaultTransferManager],
     * which made them unreachable from the confirmation sheets. The sheets quote
     * a need so the user can see the consequence before starting, and a second
     * copy of this number is exactly two answers waiting to disagree — the
     * dialog says 40 GB is fine and the gate then refuses 40 GB. So they live
     * here, beside the pure functions that consume them, and both the sheet and
     * the gate name these.
     */
    const val IMPORT_HEADROOM_KB = 512L * 1024L
    const val EXPORT_HEADROOM_KB = 512L * 1024L

    /**
     * Floor throughput a transfer bound may assume: 2 MB/s.
     *
     * Chosen from what this storage actually does, on the reference device:
     * packing 29.7 GB (tar + gzip, the slow part) ran at about 7.4 MB/s, and
     * splitting the same archive ran at about 480 MB/s. A 2 MB/s floor leaves
     * roughly 3.7x headroom over the slowest thing the app does, so ordinary
     * slowness is never mistaken for a wedge, while the ceiling still lands
     * hours rather than days. Erring long costs nothing — the engine watches
     * its own byte counter and the Cancel button is always there — and erring
     * short is exactly what manufactured the "export didn't split" failure.
     */
    const val MIN_RATE_BPS = 2L * 1024 * 1024

    /**
     * KB charged for an app whose size was never measured, so a failed `du`
     * cannot quietly reduce a whole run's bound back to the floor.
     */
    const val MIN_APP_KB = 64L * 1024L

    /**
     * How long a transfer engine step may run before the app stops waiting.
     *
     * This was a flat 60 minutes, which is a bound on *speed* rather than on
     * liveness, and a large backup is legitimately slower than that. Packing a
     * 29.7 GB vault on the reference device took 67 minutes, so the split that
     * followed was killed at the 60-minute mark every time: the engine reported
     * a timeout, the app deleted the parts it had already written, and the user
     * got a failure toast and a single 29.7 GB file where they had asked for
     * parts (found on device, 2026-10-11).
     *
     * It now scales with the bytes involved, so slow storage is never mistaken
     * for a hung run. It stays a real ceiling: the engine watches its own byte
     * counter and gives up once nothing has moved for `STALL`, and Cancel ends
     * any step immediately.
     */
    fun transferWatchdogMs(bytes: Long, baseMs: Long): Long =
        baseMs + (bytes.coerceAtLeast(0L) / MIN_RATE_BPS) * 1000L

    /**
     * Floor for a backup/restore run, under which the byte-scaled term adds on
     * top. Never below the bound the app used before it scaled at all, so
     * scaling can only ever make the ceiling more generous, never tighter.
     */
    const val RUN_WATCHDOG_MS = 30L * 60L * 1000L

    /**
     * Bytes a run over [apps] may write, as an upper bound.
     *
     * This sums EVERY component an app could be asked for rather than the ones
     * currently selected, because the effective part set per app is
     * `activeComponents ?: <the global default>` and that default is not visible
     * here. The error only ever runs the bound higher, which costs nothing: the
     * ceiling still lands in hours rather than days at the 2 MB/s floor. The
     * alternative — deriving it from the live selection — can under-count, and
     * an under-counted bound is what kills a legitimate run mid-write.
     */
    fun runWriteBytesKb(apps: List<AppInfo>): Long =
        apps.sumOf { app ->
            val live = app.appSizeKb.coerceAtLeast(0L) +
                app.dataSizeKb.coerceAtLeast(0L) +
                app.mediaSizeKb.coerceAtLeast(0L) +
                app.diskSizeKb.coerceAtLeast(0L)
            // A size of 0 usually means "not measured", not "empty". Charge the
            // floor for such an app rather than assuming it writes nothing,
            // which would return to a flat bound whenever measurement failed.
            maxOf(live, MIN_APP_KB)
        }

    /**
     * The app-side bound for a backup or restore of [apps], in ms.
     *
     * This call site used to inherit [ShellEngine]'s flat 15-minute default,
     * which is a bound on speed rather than on liveness — the same defect 2.32
     * fixed for the transfer steps, on the one path that matters most. A large
     * vault is legitimately slower than 15 minutes, and `ShellEngine` enforces
     * its bound with `future.cancel(true)`, which SIGTERMs the engine mid-run:
     * the run dies having written only some of its parts, and the user sees a
     * timeout rather than the size of their own library. The engine's own stall
     * detector only watches the TRANSFER steps, so nothing else covered this.
     */
    fun runWatchdogMs(apps: List<AppInfo>): Long =
        transferWatchdogMs(runWriteBytesKb(apps) * 1024L, RUN_WATCHDOG_MS)

    /**
     * Where exports live: `<Downloads>/DroidVault/`.
     *
     * ONE definition, shared by the code that writes there and the code that
     * reads back out of it. The importer used to reconstruct this path itself,
     * hard-coded to the Downloads root — correct until exports moved into a
     * folder of their own, after which the import could no longer find the
     * file it had just written. A path both sides must agree on belongs where
     * both sides can see it, not copied into each.
     *
     * This is the PARENT. Every export gets a folder of its own inside it, so
     * two exports never interleave: a 30 GB transfer is fourteen pieces and a
     * manifest, and a flat folder holding every export ever made is unreadable
     * and easy to half-delete. One folder per export is one thing to move to a
     * PC and one thing to delete.
     */
    const val EXPORT_DIR = "/storage/emulated/0/Download/DroidVault"

    /**
     * The folder one export goes in: `DV-20261011-1047`.
     *
     * Named for when the export STARTED, which is the same stamp in the file
     * names, so the folder and its contents agree without anyone having to
     * open anything. ASCII and colon-free so it survives MTP and FAT32 media on
     * the way to a PC, where `:` is not a legal filename character.
     */
    fun exportBatchName(
        timestampMillis: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): String = exportFileName(timestampMillis, zone).removeSuffix(".$EXTENSION")

    /** Full path of one export's folder, under [EXPORT_DIR]. */
    fun exportBatchDir(batch: String): String = "$EXPORT_DIR/$batch"

    /**
     * What to do with the configurations a transfer file carries.
     *
     * A transfer carries them for the clean-install case, which is the whole
     * point of the file. Restoring them over a phone that is already configured
     * would silently reconfigure the device the user is holding, so that case
     * has to be an explicit choice.
     */
    enum class ConfigsAction {
        /** This phone has none, and the transfer has some: restore them. Nothing to lose. */
        RESTORE,

        /** Both sides have some: do not touch anything, ask the user which set wins. */
        ASK,

        /** The transfer carried none, or this phone already has what it needs. */
        NONE
    }

    /**
     * Decide what happens to the carried configurations.
     *
     * Pure so it can be tested without a device: the distinction that matters is
     * whether the DESTINATION is empty, not whether the transfer has anything,
     * and getting that backwards either wipes a configured phone or refuses to
     * help the empty one.
     */
    fun planConfigs(incomingCounts: ConfigCounts?, destinationCounts: ConfigCounts): ConfigsAction {
        if (incomingCounts == null || incomingCounts.isEmpty) return ConfigsAction.NONE
        return if (destinationCounts.isEmpty) ConfigsAction.RESTORE else ConfigsAction.ASK
    }

    /**
     * How much configuration a side actually has.
     *
     * Counts, not bytes and not a boolean, because the user is told what they are
     * about to replace — "restore the 3 configurations and 2 labels from this
     * transfer over the 1 configuration on this phone" is a decision they can
     * make; "some settings" is not.
     */
    @Immutable
    data class ConfigCounts(
        val labels: Int = 0,
        val configs: Int = 0,
        val schedules: Int = 0,
        val folders: Int = 0
    ) {
        /** "Nothing configured" — the clean-install case, safe to restore into. */
        val isEmpty: Boolean
            get() = labels == 0 && configs == 0 && schedules == 0 && folders == 0

        /**
         * Count what a state file actually holds.
         *
         * Tolerant by design: a truncated or partially-written file must read as
         * "fewer", never as a crash mid-import. A wrong count here decides
         * whether the user's existing configuration is offered for replacement.
         */
        companion object {
        fun fromJson(json: String): ConfigCounts = try {
            val o = org.json.JSONObject(json)
            fun n(key: String) = o.optJSONArray(key)?.length() ?: 0
            ConfigCounts(
                labels = n("labels"),
                // "configurations", NOT "configs". The key is whatever
                // AutomationStore.exportJson writes, and getting it wrong makes
                // this silently 0 — which is not a cosmetic slip: `isEmpty` drives
                // the overwrite decision, so a phone holding configurations but
                // no labels would read as bare and have them replaced without
                // being asked. The ConventionGuardTest pins this key to the
                // serialiser so the two cannot drift again.
                configs = n("configurations"),
                schedules = n("schedules"),
                folders = n("folders")
            )
        } catch (_: Exception) {
            ConfigCounts()
        }
        }

        /** One line for the UI, omitting the parts that are zero. */
        fun describe(): String {
            val parts = buildList {
                if (labels > 0) add(if (labels == 1) "1 label" else "$labels labels")
                if (configs > 0) add(if (configs == 1) "1 configuration" else "$configs configurations")
                if (schedules > 0) add(if (schedules == 1) "1 schedule" else "$schedules schedules")
                if (folders > 0) add(if (folders == 1) "1 folder" else "$folders folders")
            }
            return if (parts.isEmpty()) "nothing" else parts.joinToString(", ")
        }
    }

    @Immutable
    data class ImportOutcome(
        val added: List<String> = emptyList(),
        val keptExisting: List<String> = emptyList(),
        val replaced: List<String> = emptyList(),
        val failed: List<String> = emptyList()
    ) {
        val addedCount: Int get() = added.size
        val isEmpty: Boolean get() = added.isEmpty() && replaced.isEmpty()
    }

    /**
     * Decide, per app directory, whether to copy it.
     *
     * Pure so the clobber question is settled by tests rather than by whatever a
     * shell loop happens to do.
     *
     * [existingOnDestination] are the app directory names the destination vault
     * already has. `KEEP_DESTINATION` leaves those alone entirely, which is the
     * default: importing a phone's backup onto a phone that already has one must
     * never be the thing that destroys the newer data.
     */
    fun planImport(
        incoming: List<String>,
        existingOnDestination: Set<String>,
        merge: Merge
    ): ImportOutcome {
        val added = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val replaced = mutableListOf<String>()
        for (dir in incoming.sorted()) {
            when {
                dir !in existingOnDestination -> added += dir
                merge == Merge.KEEP_DESTINATION -> kept += dir
                else -> replaced += dir
            }
        }
        return ImportOutcome(added = added, keptExisting = kept, replaced = replaced)
    }

    /**
     * A filesystem-safe, sortable export name.
     *
     * The timestamp is generated on the device and the folder name is
     * user-configurable, so both are untrusted here: a `/` or a newline in the
     * folder name would otherwise reach a shell command. Anything outside
     * `[A-Za-z0-9._-]` becomes `_`, and the result is length-capped so it cannot
     * run past a filename limit.
     */
    /**
     * Short, recognizable transfer names: `DV-20261011-1430.dvbackup.tar.gz`.
     *
     * The old scheme was `<vault>-<13-digit epoch millis>` — unique but
     * unreadable, so a folder of them could not be told apart without opening
     * each one. Date + time is what a person recognizes; uniqueness within
     * the minute comes from the caller, which appends `-2`, `-3`, … when the
     * name is taken. No vault name in it: the folder already says DroidVault,
     * and user folder names reach shells, where every extra character is a
     * quoting liability. ASCII, no spaces, no colons — safe across MTP and
     * FAT32 media on the way to a PC.
     */
    fun exportFileName(
        timestampMillis: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault()
    ): String {
        val dt = java.time.Instant.ofEpochMilli(timestampMillis).atZone(zone)
        val stamp = dt.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        return "DV-$stamp.$EXTENSION"
    }

    /**
     * Is this a transfer file we produced?
     *
     * Checked before anything is handed to the shell — an arbitrary file from
     * the user's Downloads folder must not be extracted by the app.
     */
    /**
     * Both the current extension and the pre-3.5.6 gzip one.
     *
     * The name is only a hint that this is a transfer; the engine then decides
     * how to read it from the file's magic number, so a `.tar.gz` written by an
     * older build opens without conversion and a `.tar` written by this one
     * does too.
     */
    fun looksLikeTransferFile(name: String): Boolean =
        !name.contains('\n') && !name.contains('\r') &&
            (name.endsWith(".$EXTENSION", ignoreCase = true) ||
                name.endsWith(".$LEGACY_EXTENSION", ignoreCase = true))
}
/** Which part of a transfer is running, so the UI can say something true. */
enum class TransferPhase(val label: String) {
    /** Writing the vault into one archive file. */
    PACKING("Packing your backups"),

    /**
     * Listing what is inside the archive.
     *
     * Its own phase because it is genuinely slow — `tar -tvf` on a compressed
     * file has to inflate the whole thing, which for a 34 GB transfer is minutes
     * of work — and because it used to happen with the UI still claiming the
     * PREVIOUS phase. On device the sheet sat on "Copying the file to a place
     * this app can read (33.6 GB of 34.0 GB)" for six minutes after the copy
     * had provably finished, because nothing said what was happening next.
     */
    SURVEY("Checking what's inside"),

    /** Expanding a transfer file into staging. */
    READING("Reading the transfer file"),

    /** Placing restored archives into the vault, one app at a time. */
    COPYING("Restoring backups into your vault"),

    /**
     * Cutting a finished archive into fixed-size pieces.
     *
     * Its own phase because on a large vault it takes minutes, and it used to
     * have nowhere to report: the bar sat at 100% while tens of gigabytes were
     * still being written.
     */
    SPLIT("Splitting into parts")
}

/**
 * Progress arithmetic for a cross-device transfer.
 *
 * Every rule here exists because the obvious version of it lies:
 *
 *  - **Unknown total is 0, never 100.** A failed `du` must not produce a bar
 *    that reads "complete".
 *  - **Never 100 until the work actually finished.** A byte count that reaches
 *    the total only means the bytes were *written*; the file is not a backup
 *    until the command exits successfully. While running the bar caps at 99.
 *  - **Never goes backwards.** The byte count can dip when tar rewrites a
 *    header, and a bar that stutters reads as a hang.
 *  - **Never exceeds 100.** tar output is slightly larger than its input (512
 *    bytes of header per file), so a raw ratio would overshoot.
 */
object TransferProgress {

    const val MAX_RUNNING_PERCENT = 99

    /**
     * @param doneBytes work completed so far
     * @param totalBytes known total, or <= 0 when it could not be measured
     * @param finished true only once the command has exited successfully
     * @param last the previous value, so the result can be held monotonic
     */
    fun percent(
        doneBytes: Long,
        totalBytes: Long,
        finished: Boolean,
        last: Int = 0
    ): Int {
        if (finished) return 100
        if (totalBytes <= 0L || doneBytes <= 0L) return last.coerceIn(0, MAX_RUNNING_PERCENT)
        val raw = (doneBytes.toDouble() / totalBytes.toDouble() * 100.0).toInt()
        val bounded = raw.coerceIn(0, MAX_RUNNING_PERCENT)
        return maxOf(last, bounded).coerceIn(0, MAX_RUNNING_PERCENT)
    }

    /** "312 MB of 1.4 GB" — always the real figures, whatever the bar says. */
    fun sizeLabel(doneBytes: Long, totalBytes: Long): String {
        val done = humanBytes(doneBytes)
        if (totalBytes <= 0L) return "$done so far"
        return "$done of ${humanBytes(totalBytes)}"
    }

    fun humanBytes(bytes: Long): String {
        val abs = bytes.coerceAtLeast(0L)
        return when {
            abs >= 1_000_000_000_000L -> String.format(java.util.Locale.US, "%.1f TB", abs / 1e12)
            abs >= 1_000_000_000L -> String.format(java.util.Locale.US, "%.1f GB", abs / 1e9)
            abs >= 1_000_000L -> String.format(java.util.Locale.US, "%.0f MB", abs / 1e6)
            abs >= 1_000L -> String.format(java.util.Locale.US, "%.0f KB", abs / 1e3)
            else -> "$abs B"
        }
    }

    /**
     * Merge per-item sizes into one weighted progress figure.
     *
     * Import copies a known list of directories, so its progress can be exact —
     * a 90 MB app and a 2 MB app must not each count as "one step".
     */
    class Weighted(
        private val sizes: Map<String, Long>,
        private val total: Long = sizes.values.sum()
    ) {
        private var done = 0L
        private var last = 0
        private val counted = HashSet<String>()

        /**
         * Count each item once: a repeated key must not inflate progress.
         *
         * The comment claimed this and the code did not — `done +=` ran every
         * time, so a second call for the same item double-counted it and could
         * report 100% with half the bytes copied. The import loops happen to pass
         * distinct keys today, which is exactly why it survived; the guarantee is
         * now real rather than aspirational.
         */
        fun complete(key: String): Int {
            val size = sizes[key]
            if (size == null) return percent(done, total, false, last)
            if (!counted.add(key)) return percent(done, total, false, last)
            done += size
            last = percent(done, total, false, last)
            return last
        }

        /**
         * Bytes actually accounted for so far.
         *
         * The copy phase used to report a real percentage against a label that
         * was hard-coded to `sizeLabel(0, total)` — so the bar climbed to 39%
         * beside "0 B of 3.6 GB", which is the app claiming two different things
         * about the same work at once.
         */
        val doneBytes: Long get() = done

        /** Percentage for everything counted so far, without counting anything new. */
        val lastPercent: Int
            get() = TransferProgress.percent(done, total, false, last)

        fun finish(): Int = 100

        /** Recompute from disk, for items whose real size is only known now. */
        fun sync(doneBytes: Long, finished: Boolean): Int {
            done = maxOf(done, doneBytes)
            last = percent(done, total, finished, last)
            return last
        }

        val isEmpty: Boolean get() = sizes.isEmpty()
    }

    /**
     * The "how long is this going to take" arithmetic, kept honest.
     *
     * Invariant 31: *an estimate that moves the wrong way is worse than none.*
     * Every figure below is derived from a counter that can stall — the install
     * copy is a weighted average that stops moving while one big app is being
     * written, and the survey has no byte counter at all — so these rules are
     * about refusing to speak rather than about being clever.
     *
     * Two specific lies this exists to prevent:
     *
     *  - **A rate computed over a stalled window reads as zero**, and "0 min
     *    left" is worse than saying nothing. So the rate is measured over a
     *    wall-clock window, and the estimate is withheld until one exists.
     *  - **An estimate derived from a fraction that is itself a part count.**
     *    §2.25 measured "24.6 GB done" that was `appSize x mean(part
     *    percentages)` — a count wearing a byte label. [label] only ever sees
     *    real byte totals, and refuses when there are none.
     *
     * Pure and static, so a test can put a clock in and get the same answer
     * twice.
     */
    object Estimate {

        /**
         * Below this the answer is noise: a few hundred MB over two seconds
         * extrapolates wildly either way.
         */
        const val MIN_ELAPSED_MS = 8_000L

        /**
         * How far back the rate is measured. Long enough to ride out one app's
         * install and the writeback burst after a big file, short enough to
         * notice the run actually speeding up or slowing down.
         */
        const val RATE_WINDOW_MS = 30_000L

        /** Held inside the window, the rate is measurable but not trustworthy. */
        data class Sample(val atMs: Long, val percent: Int)

        /**
         * Percent per second over the window, or null when there is no sample far
         * enough back — which is exactly the "too early to say" case, and the
         * only honest answer for it is silence.
         *
         * Measured from `percent`, not from a byte count, and that is not a
         * compromise. [TransferProgress.percent] is monotonic and computed
         * against a real recorded total, so percent-of-total is a genuine
         * fraction — unlike §2.25's `appSize x mean(part percentages)`, which
         * was a count wearing a byte label. The alternative was to re-parse the
         * "312 MB of 1.4 GB" display string to recover the bytes, which is
         * deriving an estimate from a presentation of a number instead of from
         * the number.
         *
         * An indeterminate phase reports `-1`, so it can never form a positive
         * rate: the survey phase, where the engine is inflating the whole
         * archive just to count it, produces no estimate at all. That is the
         * point — there is no honest answer during the survey, and a bar that
         * claims one is the lie invariant 31 is about.
         */
        fun windowedRate(samples: List<Sample>): Double? {
            if (samples.size < 2) return null
            val newest = samples.last()
            val oldest = samples.firstOrNull { newest.atMs - it.atMs >= RATE_WINDOW_MS }
                ?: return null
            val dt = newest.atMs - oldest.atMs
            val dp = newest.percent - oldest.percent
            if (dt <= 0L || dp <= 0) return null
            return dp.toDouble() / (dt.toDouble() / 1000.0)
        }

        /**
         * "about 4 min left", or null when no honest answer exists.
         *
         * Rounds UP to whole minutes: rounding down turns 61 seconds into
         * "1 min left" and then it is still going.
         */
        fun label(percent: Int, percentPerSecond: Double): String? {
            if (percentPerSecond <= 0.0 || percent !in 0..99) return null
            val remainingPercent = (100 - percent).toDouble()
            val seconds = remainingPercent / percentPerSecond
            if (!seconds.isFinite() || seconds <= 0.0) return null
            val minutes = kotlin.math.ceil(seconds / 60.0).toInt()
            return when {
                minutes <= 1 -> "about a minute left"
                minutes < 60 -> "about $minutes min left"
                else -> {
                    val hours = minutes / 60
                    val rest = minutes % 60
                    if (rest == 0) "about ${AppParts.count(hours, "hour")} left"
                    else "about ${AppParts.count(hours, "hour")} $rest min left"
                }
            }
        }

        /**
         * "11 min elapsed" — how long this has been going. A measured fact
         * rather than an estimate, so it is never withheld. Capped at an hour
         * because a run that has been going that long is better described by
         * the phase than by a bigger number.
         */
        fun elapsedLabel(elapsedMs: Long): String? {
            if (elapsedMs < MIN_ELAPSED_MS) return null
            val totalMinutes = (elapsedMs / 60_000L).toInt()
            if (totalMinutes < 1) return "under a minute elapsed"
            if (totalMinutes > 60) return "over an hour elapsed"
            return "${totalMinutes} min elapsed"
        }

        /**
         * "12.4 MB/s", so the estimate has something visible behind it.
         *
         * Needs the archive's real total, which the caller has from its own
         * manifest; without it the rate is simply not shown rather than being
         * guessed from a default.
         */
        fun rateLabel(percentPerSecond: Double, totalBytes: Long): String? {
            if (percentPerSecond <= 0.0 || !percentPerSecond.isFinite()) return null
            if (totalBytes <= 0L) return null
            val bytesPerSecond = totalBytes.toDouble() * percentPerSecond / 100.0
            if (bytesPerSecond <= 0.0 || !bytesPerSecond.isFinite()) return null
            return "${humanBytes(bytesPerSecond.toLong())}/s"
        }
    }
}
