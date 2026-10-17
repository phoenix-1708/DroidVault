package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/** Archive verification — parsing the engine's report, and deciding what a
 * restore should do about it.
 *
 * Pure Kotlin so it is unit-testable without a device or a root shell.
 *
 * Why this exists: `archive_valid` has been in the engine all along but nothing
 * ever called it, so a corrupt archive was discovered by the user at restore
 * time — after a wipe or a ROM flash. A backup you cannot read back is worse
 * than no backup, because it costs you the confidence to keep using the device.
 */
@Immutable
data class AppVerification(
    val label: String,
    val packageName: String,
    val partsChecked: Int,
    val corruptParts: List<String>,
    val bytesReadable: Long,
    /**
     * Parts that read back fine but whose bytes no longer match the digest the
     * vault recorded beside them.
     *
     * This is a different finding from [corruptParts] and must not be folded
     * into it: an archive that cannot be read is gone and has to be made again,
     * while an archive whose bytes changed under a recorded digest was altered,
     * or written by something that did not finish. Either way the archive in
     * front of us is not the one that was made, which is exactly what a restore
     * needs to know before it starts.
     */
    val hashMismatchedParts: List<String> = emptyList(),
    /**
     * When THIS finding was first seen, in wall-clock milliseconds; 0 when this
     * row is not a finding, or when its age is not known.
     *
     * The usefulness of a finding depends on its age: "Messages.shift changed"
     * read seconds after a tamper test and the same sentence a week and four runs
     * later are different facts. A finding is not withdrawn by a run, so without
     * an age the card states something that may have been repaired long ago in
     * the present tense. Carried forward by [withFindingAges] while the same row
     * keeps failing, so it is the age of the FINDING and not of the check that
     * happened to last observe it.
     */
    val firstSeenMs: Long = 0L
) {
    val ok: Boolean get() = corruptParts.isEmpty()

    /** Readable, but not the bytes the vault recorded. */
    val changed: Boolean get() = hashMismatchedParts.isNotEmpty()

    /** Both questions answered: it reads back, and it is the archive we made. */
    val trustworthy: Boolean get() = ok && !changed
}

/** What a verification actually checked.
 *
 * The engine emits `VERIFY_DONE` for apps and `VERIFY_DEVICE_DONE` for device
 * archives, so the kind is known from the report's own output rather than from
 * a flag the caller has to remember to pass. Found on device: checking device
 * data with no archives reported "No app backups found", because the summary
 * was written for apps only.
 */
enum class VerifyScope {
    APPS,
    DEVICE,

    /**
     * Both halves of one user-facing verdict, assembled by
     * [VerificationReport.mergedWith].
     *
     * A merged report carries app rows and the device counts, so wording that
     * names only one half describes work the run never did — the defect §2.44
     * already cost once.
     */
    BOTH
}

/** How much of a verdict's authority survives, and what it may therefore say.
 *
 * Two different ages, both ending in the same place — good news withdrawn, the
 * check offered again — because the user's next action is identical, and the
 * sentence they read should name the real reason:
 *
 *  - [CHANGED_SINCE]: a run released the gate after this read, so the report
 *    describes a vault that may no longer exist (§2.46).
 *  - [FROM_LAST_SESSION]: the report was read back from disk, so nothing in THIS
 *    launch ever looked at the vault (§2.47). A process that was not running
 *    cannot vouch for what happened while it did not exist; a reboot, a fresh
 *    install or a root file manager in between all land here.
 *
 * A FINDING is deliberately unaffected by either: a damaged archive is still
 * damaged unless something replaced it, so staleness withdraws good news only.
 */
enum class VerdictStaleness {
    CURRENT,
    CHANGED_SINCE,
    FROM_LAST_SESSION;

    /** Headline for the health row. Empty when the verdict is current. */
    val headline: String
        get() = when (this) {
            CURRENT -> ""
            CHANGED_SINCE -> "Backups changed since the last check"
            FROM_LAST_SESSION -> "Checked before this launch"
        }

    /** What to do about it, under the headline. */
    val advice: String
        get() = when (this) {
            CURRENT -> ""
            CHANGED_SINCE -> "Run a check to confirm the archives are still readable"
            FROM_LAST_SESSION -> "Check again to confirm the archives are still readable"
        }

    /** The full sentence, in the dialog and over the banner. */
    val withdrawal: String
        get() = when (this) {
            CURRENT -> ""
            CHANGED_SINCE -> "A run has changed the vault since this check, so it no " +
                    "longer describes what is on the device. Check again."
            FROM_LAST_SESSION -> "This check was taken before the app restarted, so it " +
                    "does not describe what is on the device now. Check again."
        }
}

/** Where this report came from, for the screens that state it.
 *
 * The epoch alone cannot answer this after a restart: a restored report and a
 * fresh one can carry the same counter value (nothing bumped it while the app
 * was dead), and equal counters are exactly what [stillCurrent] reads as "still
 * describes the vault". Provenance is the missing half of the question — a
 * restored report was not observed in this launch at all.
 */
fun VerificationReport.staleness(currentEpoch: Long): VerdictStaleness = when {
    restoredFromDisk -> VerdictStaleness.FROM_LAST_SESSION
    takenAtEpoch == currentEpoch -> VerdictStaleness.CURRENT
    else -> VerdictStaleness.CHANGED_SINCE
}

@Immutable
data class VerificationReport(
    val apps: List<AppVerification> = emptyList(),
    val total: Int = 0,
    val okCount: Int = 0,
    val corruptCount: Int = 0,
    val bytesReadable: Long = 0L,
    val cancelled: Boolean = false,
    val error: String = "",
    val scope: VerifyScope = VerifyScope.APPS,
    /**
     * Device archives whose bytes no longer match their recorded digest, as the
     * device run's own summary reported it.
     *
     * A count, in the unit its sentence needs: `VERIFY_DEVICE_DONE|HASH_CHANGED`
     * counts ARCHIVES, while `VERIFY_DONE|HASH_CHANGED` on the app half counts
     * mismatched PARTS — which is why the two are never summed. See
     * [deviceChangedArchives], which is what the wording reads.
     */
    val deviceChanged: Int = 0,
    /**
     * The device archives this run checked, one row per archive.
     *
     * The engine reports device data with one `VERIFY_DEVICE_ITEM` line per file
     * and a summary, exactly as the app half reports one line per app. Keeping the
     * rows — not just the summary counts — is what lets a finding be NAMED: "1 of
     * 7 device data archives damaged" tells the user nothing they can act on, and
     * the pre-restore gate cannot refuse the one archive the plan actually
     * restores without knowing which item it is. Until these rows existed the
     * device half of a verdict was counts only, so a damaged `Messages.shift`
     * reached the restore gate as an anonymous number and the gate let it
     * through.
     */
    val deviceItems: List<DeviceVerification> = emptyList(),
    /**
     * Parts actually read in this run, and parts left alone because the vault
     * index recorded the same bytes as read back before.
     *
     * Reported separately for one reason: a verification of an unchanged vault
     * reads almost nothing, and a result that said only "all readable" would let
     * the user believe it had just re-read every byte and proved it.
     */
    val readParts: Int = 0,
    val skippedParts: Int = 0,
    /**
     * The gate epoch the vault was at while these bytes were read.
     *
     * Compared with the current epoch by [stillCurrent]. A report is a
     * measurement of a moment, and anything that held the run gate since may
     * have written to the vault — new archives the report never saw, or an app
     * re-backed-up after the report called it damaged. Default 0 is "not
     * stamped", which is "taken before anything ran in this session" and stays
     * true until the first run.
     */
    val takenAtEpoch: Long = 0L,
    /**
     * When these bytes were read, in wall-clock milliseconds; 0 when unknown.
     *
     * Deliberately NOT the same thing as [takenAtEpoch]: the epoch counts runs by
     * THIS process, while this is a date the user can read. The epoch answers
     * "is this still about the vault in front of me"; this answers "how long ago
     * did anyone look", which is the question a finding that stands across
     * restarts leaves open. Not used to decide anything — a clock can be changed
     * by hand, so every staleness decision stays on the epoch (invariant 41).
     */
    val takenAtMs: Long = 0L,
    /**
     * `.sha256` records this check rewrote because they disagreed with bytes the
     * vault's own read-back marker had already vouched for.
     *
     * Carried with the verdict because it is something that HAPPENED to the vault
     * rather than a step of the check: until this run, `sha256sum -c` failed on
     * those archives while the app called them fine (§2.48). Two records of one
     * digest, and the disagreement was in the record the app does not read.
     */
    val reconciledRecords: Int = 0,
    /**
     * Read back from disk this launch, rather than taken during it.
     *
     * Set only by the loader (`VaultVerdict.forVault`), never by a run: a report
     * that a run just produced was, by definition, observed in this process. It
     * is not serialised — it describes the reader, not the measurement.
     */
    val restoredFromDisk: Boolean = false
) {
    val hasCorrupt: Boolean get() = corruptCount > 0

    /**
     * Whether this report still describes the vault as it is now.
     *
     * ## What staleness does, and what it must not do
     *
     * It withdraws the GOOD news and keeps the bad. A healthy verdict that
     * predates a run cannot be shown as healthy — nothing observed the archives
     * that run wrote, so a tick would claim work nobody did. A damaged verdict,
     * though, still names what it found, and the app it named is still in the
     * vault unless a backup replaced it; dropping it would silently unblock a
     * restore from a damaged archive because the user happened to delete
     * something in between. A finding stands until something replaces it.
     */
    fun stillCurrent(currentEpoch: Long): Boolean = takenAtEpoch == currentEpoch

    /**
     * Fold a device-archives run into an app-archives one, so one verdict covers
     * the work of both.
     *
     * Every count that the two runs produced is added rather than chosen: the
     * first version of this merge kept `apps`, `total`, `okCount`, `corruptCount`
     * and `error`, and dropped `bytesReadable`, `deviceChanged`, `readParts`,
     * `skippedParts` and `cancelled` on the floor — so a device archive whose
     * digest no longer matched vanished from the verdict, and the health row read
     * "all readable" over it (§2.47). A field added to [VerificationReport] from
     * now on has to be summed here too, which is why the whole object is built in
     * one call and pinned by a test rather than assembled field by field at the
     * call site.
     *
     * `restoredFromDisk` is deliberately carried over from THIS report: a half
     * that was read back from disk cannot be laundered into a fresh verdict by
     * merging a freshly-read other half into it, and the app half is the one that
     * carries the app rows a restore is gated on.
     */
    fun mergedWith(device: VerificationReport): VerificationReport = copy(
        apps = apps + device.apps,
        total = total + device.total,
        okCount = okCount + device.okCount,
        corruptCount = corruptCount + device.corruptCount,
        bytesReadable = bytesReadable + device.bytesReadable,
        // Either half being cut short makes the whole verdict incomplete.
        cancelled = cancelled || device.cancelled,
        // The DEVICE half's number, not the sum. The same field name carries two
        // different units: `VERIFY_DEVICE_DONE|HASH_CHANGED` counts device
        // archives that changed, while `VERIFY_DONE|HASH_CHANGED` counts mismatched
        // PARTS across all apps (`verify.rs`: `digest.mismatched.len()` summed) —
        // and the app rows already name those apps one by one. Adding them would
        // put a part count and a backup count in one number, which is how the
        // summary would claim more changed backups than exist. Dropping the device
        // number entirely — what this merge used to do — reported a changed device
        // archive as healthy, the one thing this class of check exists to catch.
        deviceChanged = device.deviceChanged,
        // The device rows travel with their counts. Dropping them here is how a
        // device archive that changed would keep its number but lose its name,
        // leaving the card and the restore gate unable to say which file it was.
        deviceItems = deviceItems + device.deviceItems,
        readParts = readParts + device.readParts,
        skippedParts = skippedParts + device.skippedParts,
        // Summed, and safe to sum: both halves count the same unit — one
        // `.sha256` record rewritten — so unlike `HASH_CHANGED` there is no second
        // meaning hiding behind the same name.
        reconciledRecords = reconciledRecords + device.reconciledRecords,
        // Whichever half was read later; the caller stamps the merge anyway.
        takenAtMs = maxOf(takenAtMs, device.takenAtMs),
        error = error.ifBlank { device.error },
        scope = VerifyScope.BOTH
    )

    /**
     * Give every finding an age, taking it from [previous] when the same archive
     * was already failing there.
     *
     * Called once, on the report a check just produced, with the verdict that was
     * on screen before it. Rows that are fine again get 0, because an archive that
     * was re-backed-up is no longer a finding — and rows that are newly failing
     * start their age now rather than inheriting the age of a different problem.
     *
     * The identity is the archive, not the row: an app is matched by package name
     * and a device finding by its file name, so an app that moved between User and
     * System (two rows for one package, see the dialog's key comment) does not
     * restart its own age.
     *
     * Pure and total: no previous report, an unknown previous age, or a changed
     * set of findings all resolve to "age starts here", which is the honest
     * answer — the app cannot know when a problem it is seeing for the first time
     * began.
     */
    fun withFindingAges(previous: VerificationReport?, nowMs: Long): VerificationReport {
        val seenApps = previous?.apps.orEmpty()
            .filter { !it.trustworthy && it.firstSeenMs > 0L }
            .associateBy { it.packageName }
        val seenDevice = previous?.deviceItems.orEmpty()
            .filter { !it.trustworthy && it.firstSeenMs > 0L }
            .associateBy { it.archiveName }
        return copy(
            apps = apps.map { app ->
                if (app.trustworthy) app.copy(firstSeenMs = 0L)
                else app.copy(firstSeenMs = seenApps[app.packageName]?.firstSeenMs ?: nowMs)
            },
            deviceItems = deviceItems.map { row ->
                if (row.trustworthy) row.copy(firstSeenMs = 0L)
                else row.copy(firstSeenMs = seenDevice[row.archiveName]?.firstSeenMs ?: nowMs)
            },
            takenAtMs = nowMs
        )
    }

    /**
     * When the oldest finding in this verdict was first seen, or null when there
     * is none with a known age.
     *
     * The OLDEST, not the newest: the card's question is "how long has this been
     * wrong", and a verdict holding one finding from today and one from last month
     * has been wrong since last month.
     */
    fun oldestFindingMs(): Long? = (
        apps.filter { !it.trustworthy }.map { it.firstSeenMs } +
            deviceItems.filter { !it.trustworthy }.map { it.firstSeenMs }
        ).filter { it > 0L }.minOrNull()
        ?: takenAtMs.takeIf { it > 0L && (hasCorrupt || hasChanged) }

    /** Apps the vault recorded and something has since altered. */
    val changedApps: List<AppVerification> get() = apps.filter { it.changed }

    /**
     * Device archives this verdict found altered.
     *
     * The rows are authoritative when the run reported them; the summary count is
     * the fallback for a report that carries only the done-line (a stored verdict
     * from a build that did not keep rows, or a caller that fed the summary alone).
     * Both are counts of ARCHIVES, so taking the larger is taking the honest one:
     * a row that says "changed" and a summary that says "none" describe the same
     * run, and the row is the finding.
     */
    val deviceChangedArchives: Int get() = maxOf(deviceChanged, deviceItems.count { it.changed })

    val hasChanged: Boolean get() = changedApps.isNotEmpty() || deviceChangedArchives > 0

    /**
     * How many backups this verdict found ALTERED.
     *
     * Counted per half, because the two engine runs put different things in the
     * same field name: an app run's done-line `HASH_CHANGED` counts mismatched
     * PARTS (its rows are the count of apps), and a device run's counts archives.
     * Both halves are counted in a merged verdict — using only the rows described
     * a run that also read device archives it never mentioned.
     */
    val changedCount: Int get() = when (scope) {
        VerifyScope.APPS -> changedApps.size
        VerifyScope.DEVICE -> deviceChangedArchives
        VerifyScope.BOTH -> changedApps.size + deviceChangedArchives
    }

    /**
     * True when the report cannot be trusted as a go-ahead.
     *
     * An empty vault and a cancelled or errored run are both "we do not know",
     * and must never be reported as "verified". `verifyReportAllowsRestore`
     * handles the difference.
     */
    val conclusive: Boolean
        get() = !cancelled && error.isEmpty() && (total > 0 || apps.isNotEmpty())

    /**
     * A verdict a restore can act on: everything that was checked reads back AND
     * is still the archive that was written. A changed archive is not healthy —
     * calling it that would be the same class of lie as reporting success for a
     * backup that never ran.
     */
    val healthy: Boolean get() = conclusive && !hasCorrupt && !hasChanged

    /** Whether this verdict is an app verdict. */
    fun includesAppArchives(): Boolean = scope == VerifyScope.APPS || scope == VerifyScope.BOTH

    /** Whether this verdict is a device verdict. */
    fun includesDeviceArchives(): Boolean = scope == VerifyScope.DEVICE || scope == VerifyScope.BOTH

    /** Whether this verdict is a merged verdict. */
    fun isMerged(): Boolean = scope == VerifyScope.BOTH

    /**
     * Whether this verdict names any damaged device archives.
     *
     * Read from the rows, not from `corruptCount`: a merged verdict sums the two
     * halves' corrupt counts into one number, so the count alone cannot say which
     * half is damaged — and a card that has to name a damaged device archive needs
     * to know whether there is one to name.
     */
    fun hasDamagedDeviceArchives(): Boolean = deviceItems.any { !it.ok }

    /** Whether this verdict names any damaged archives of any kind. */
    fun hasAnyDamagedArchives(): Boolean = hasCorrupt || hasDamagedDeviceArchives()

    /** Whether this verdict names any changed archives of any kind. */
    fun hasAnyChangedArchives(): Boolean = hasChanged

    /** Whether this verdict is healthy: nothing damaged, nothing changed, and the
     * verdict is conclusive. */
    fun isHealthy(): Boolean = healthy

    /** Whether this verdict is conclusive. */
    fun isConclusive(): Boolean = conclusive

    /** Whether this verdict is cancelled. */
    fun isCancelled(): Boolean = cancelled

    /** Whether this verdict has an error. */
    fun hasError(): Boolean = error.isNotEmpty()

    /** The device archives a report actually checked, as the items its rows name. */
    fun selectedDeviceItems(): Set<DeviceDataItem> =
        deviceItems.mapNotNull { it.item }.toSet()

    /** The device archives a report found unreadable, by file name. */
    fun deviceDamagedArchives(): List<String> = deviceItems.filter { !it.ok }.map { it.archiveName }

    /** The device archives a report found altered, by file name. */
    fun deviceChangedArchivesNamed(): List<String> =
        deviceItems.filter { it.changed }.map { it.archiveName }
}

/**
 * The findings of a verification, NAMES, for the diagnostics log.
 *
 * The log line that already existed is [summaryLine], and it is counts: "1 of 7
 * device data archives damaged, 6 of 7 readable". That is a fine sentence for a
 * dialog and a bad one for a bug report, because the question a field report
 * asks is *which file* — and the log is the artefact that actually travels.
 * Invariant 41 requires a finding to name the archive it is about; on screen
 * that was true, and here it was not.
 *
 * Capped at [MAX_NAMES]. A vault with 110 damaged apps would otherwise produce
 * one line nobody reads to the end, and an unreadable log is the same as no
 * log. The overflow is stated rather than silently dropped, so a reader can tell
 * "these are all of them" from "there are more".
 *
 * Pure, and so testable: the behaviour worth pinning is the cap and the
 * "nothing found" answer, neither of which needs a device.
 */
fun VerificationReport.findingsLine(maxNames: Int = MAX_NAMES): String {
    val damagedApps = apps.filter { !it.ok }.map { it.packageName }
    val damaged = deviceDamagedArchives()
    val changed = deviceChangedArchivesNamed()

    fun cap(names: List<String>): String = when {
        names.isEmpty() -> ""
        names.size <= maxNames -> names.joinToString(", ")
        else -> names.take(maxNames).joinToString(", ") +
            " and ${names.size - maxNames} more"
    }

    val parts = buildList {
        if (damagedApps.isNotEmpty()) add("unreadable apps: ${cap(damagedApps)}")
        if (damaged.isNotEmpty()) add("unreadable device archives: ${cap(damaged)}")
        if (changed.isNotEmpty()) add("altered since written: ${cap(changed)}")
    }
    return if (parts.isEmpty()) "findings: none" else "findings — " + parts.joinToString("; ")
}

/** How many names a single findings line may carry before it overflows. */
const val MAX_NAMES = 12

object VerificationParser {

    /**
     * Split on `|` then on the FIRST `:` of each segment.
     *
     * Values may themselves contain colons (`Name=com.x:1.2`, a path), so only
     * the first is a separator. The engine already replaces `|` inside values
     * with `/` via its `field()` helper.
     */
    private fun fields(line: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (segment in line.split('|')) {
            val idx = segment.indexOf(':')
            if (idx > 0) out[segment.substring(0, idx)] = segment.substring(idx + 1)
        }
        return out
    }

    /**
     * Fold one engine line into [report].
     *
     * Lines the verifier does not recognise are ignored rather than treated as
     * errors: the engine also emits `INFO:` steps, and a new informational line
     * must never turn a completed verification into a failed one.
     */
    fun parse(line: String, report: VerificationReport): VerificationReport {
        val f = fields(line)
        return when {
            line.startsWith("VERIFY_APP|") -> {
                val corrupt = f["CORRUPT"].orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val app = AppVerification(
                    label = f["LABEL"].orEmpty(),
                    packageName = f["PKG"].orEmpty(),
                    partsChecked = f["PARTS"]?.toIntOrNull() ?: 0,
                    corruptParts = corrupt,
                    bytesReadable = f["BYTES"]?.toLongOrNull() ?: 0L,
                    hashMismatchedParts = f["HASH_MISMATCH"].orEmpty()
                        .split(',')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    // `firstSeenMs` is not the engine's to report: it is this
                    // app's record of how long the finding has stood, stamped by
                    // `withFindingAges` against the verdict it is replacing.
                )
                report.copy(apps = report.apps + app)
            }

            line.startsWith("VERIFY_DEVICE_ITEM|") -> {
                // One device archive. Parsed into the same report as the app rows
                // rather than into a parallel type: two carriers of one verdict is
                // how a finding ends up visible on one screen and absent on the
                // next (invariant 18 — one gate, one implementation).
                val name = f["NAME"].orEmpty()
                val ok = f["STATUS"].orEmpty() == "OK"
                val change = f["HASH"].orEmpty()
                val item = DeviceVerification(
                    item = DeviceDataItem.entries.firstOrNull { it.archiveFile == name },
                    archiveName = name,
                    ok = ok,
                    corruptParts = if (ok) emptyList() else listOf(name),
                    bytesReadable = if (ok) f["BYTES"]?.toLongOrNull() ?: 0L else 0L,
                    // `HASH:changed` is the engine saying these are not the bytes
                    // the vault recorded; `HASH:unrecorded` is "no digest to
                    // compare", which is unknown rather than changed.
                    hashMismatchedParts = if (change == "changed") listOf(name) else emptyList()
                )
                report.copy(deviceItems = report.deviceItems + item)
            }

            line.startsWith("VERIFY_DONE|") || line.startsWith("VERIFY_DEVICE_DONE|") -> {
                // The engine emits one summary line per half, and the two put
                // different things in the same field name. `HASH_CHANGED` on an app
                // line counts mismatched PARTS across every app; on a device line it
                // counts ARCHIVES. Reading it on both lines put a part count into
                // the device number, and the health card then named "device data"
                // as changed on the strength of one app's `Permissions.txt`.
                val device = line.startsWith("VERIFY_DEVICE_DONE|")
                val mismatchNames = f["HASH_MISMATCH"].orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                // A device verdict that carries only its summary still knows WHICH
                // archives changed, because the done-line names them. One row per
                // name, so the card and the restore gate can say which file it is;
                // when the per-item lines were seen, those rows already exist and
                // are the better record (they know which ones would not READ).
                val rows = if (device && report.deviceItems.isEmpty() && mismatchNames.isNotEmpty()) {
                    mismatchNames.map { name ->
                        DeviceVerification(
                            item = DeviceDataItem.entries.firstOrNull { it.archiveFile == name },
                            archiveName = name,
                            ok = true,
                            bytesReadable = 0L,
                            hashMismatchedParts = listOf(name)
                        )
                    }
                } else {
                    report.deviceItems
                }
                report.copy(
                    total = f["TOTAL"]?.toIntOrNull() ?: report.apps.size,
                    okCount = f["OK"]?.toIntOrNull() ?: 0,
                    corruptCount = f["CORRUPT"]?.toIntOrNull()
                        ?: report.apps.count { !it.ok },
                    bytesReadable = f["BYTES"]?.toLongOrNull() ?: report.bytesReadable,
                    cancelled = f["CANCELLED"] == "1",
                    deviceChanged = if (device) {
                        f["HASH_CHANGED"]?.toIntOrNull() ?: report.deviceChanged
                    } else {
                        report.deviceChanged
                    },
                    deviceItems = rows,
                    readParts = f["READ"]?.toIntOrNull() ?: report.readParts,
                    skippedParts = f["SKIPPED"]?.toIntOrNull() ?: report.skippedParts,
                    // Stale `.sha256` records the engine rewrote this run. Both
                    // halves count the same unit, so the two summaries add up to
                    // the vault's total rather than to a mixture of things.
                    reconciledRecords = report.reconciledRecords +
                        (f["HASH_RECONCILED"]?.toIntOrNull() ?: 0),
                    scope = if (device) VerifyScope.DEVICE else VerifyScope.APPS
                )
            }

            // Parse as fields like every other line. Taking the text after the
            // first colon returned "ERROR|MSG:engine not installed" — the whole
            // prefix, which the user would read as a broken engine message.
            line.startsWith("ACTION:ERROR") -> report.copy(
                error = (f["MSG"] ?: line.substringAfter(':', "").substringAfter(':', "")).trim()
            )

            else -> report
        }
    }
}

/**
 * Whether a completed [report] clears a restore of [selectedPackages].
 *
 * Returns `null` for "nothing was checked" — an empty vault, a run that was
 * cancelled, or an errored invocation. The caller must treat `null` as "no
 * reason to block" rather than as "verified", because refusing every restore on
 * a folder with no archives yet would make the app unusable.
 *
 * Only CORRUPT apps block. Unknown packages are skipped: the list may be stale
 * relative to what is on disk, and blocking on that would be a dead end.
 */
fun VerificationReport.verifyReportAllowsRestore(selectedPackages: Set<String>): Boolean? {
    if (!conclusive) return null
    if (selectedPackages.isEmpty()) return null
    val relevant = apps.filter { it.packageName.isNotEmpty() && it.packageName in selectedPackages }
    // None of the selected apps were in the report — cannot vouch either way.
    if (relevant.isEmpty()) return null
    // `trustworthy`, not `ok`: a readable archive whose bytes changed is not the
    // archive this restore was planned from, and restoring it would overwrite an
    // install with unknown data instead of the known one.
    return relevant.none { !it.trustworthy }
}

/** One-line summary for a notification or a dialog. */
fun VerificationReport.summaryLine(): String = when {
    error.isNotEmpty() -> "Verification failed: $error"
    cancelled -> "Verification cancelled"
    // Name the thing that was checked. This ran against device archives and
    // said "app backups", which is a sentence about something that was never
    // examined (found on device, 2026-10-11).
    total == 0 -> when (scope) {
        VerifyScope.DEVICE -> "No device data archives found"
        VerifyScope.APPS -> "No app backups found"
        VerifyScope.BOTH -> "No backups found"
    }
    hasCorrupt -> "$okCount of $total ${subjects()} readable, $corruptCount damaged"
    // Readable but altered: "all readable" would be true and useless. Say what
    // is actually wrong with them.
    hasChanged -> {
        "$changedCount ${if (changedCount == 1) subject() else subjects()} changed since " +
                "they were written, $okCount of $total readable"
    }
    else -> "All $okCount ${subjects()} are readable"
}

/** The subject of this verdict, in the singular, for a sentence about backups. */
internal fun VerificationReport.subject(): String = when (scope) {
    VerifyScope.DEVICE -> "device data archive"
    VerifyScope.APPS -> "app backup"
    VerifyScope.BOTH -> "backup"
}

/**
 * The same subject, plural. Regular noun phrases, so one `s` is the whole rule —
 * and it is applied here rather than at each use so a count of one cannot pick a
 * plural form by hand (`AppParts.count`'s lesson, one layer down).
 */
internal fun VerificationReport.subjects(): String = subject() + "s"


/**
 * Why this restore must not run, or null when it may.
 *
 * Shared by the manual Migrate flow and the scheduler, which previously had the
 * rule in only one of them: a scheduled restore checked that the archive FILE
 * existed and never checked that it could be READ, so a run known to be damaged
 * still restored from it — headlessly, with nobody watching.
 *
 * Null (may proceed) covers both "verified healthy" and "no conclusive verdict",
 * because an empty vault, a cancelled verify and an engine that never started
 * all report "unknown", and treating that as "damaged" would make Restore
 * permanently dead on a fresh install.
 */
fun restoreBlockReason(
    report: VerificationReport?,
    packages: Set<String>
): String? {
    if (packages.isEmpty()) return null
    if (report?.verifyReportAllowsRestore(packages) != false) return null
    val selected = report.apps.filter { it.packageName.isNotEmpty() && it.packageName in packages }
    val damaged = selected.filter { !it.ok }
    // Not filtered on `ok`: an app can hold one part that will not read back and
    // another that does but no longer matches its digest, and naming only the
    // first would hide half of what is wrong with it.
    val changed = selected.filter { it.changed }
    val reasons = mutableListOf<String>()
    if (damaged.isNotEmpty()) {
        reasons += "Cannot restore: these backups are damaged and cannot be read back \u2014 " +
                "${damaged.joinToString(", ") { it.label.ifEmpty { it.packageName } }}. " +
                "Back them up again, or remove them from this restore."
    }
    // A separate sentence, because it is a separate problem with a different
    // fix: the archive reads back perfectly, and it is not the one that was
    // written.
    if (changed.isNotEmpty()) {
        val names = changed.joinToString(", ") { it.label.ifEmpty { it.packageName } }
        val parts = changed.flatMap { it.hashMismatchedParts }.distinct().joinToString(", ")
        reasons += "Cannot restore: these backups have changed since they were written \u2014 " +
                "$names ($parts). Back them up again, or remove them from this restore."
    }
    return reasons.joinToString(" ").ifEmpty { null }
}

/**
 * The same rule for device-data archives.
 *
 * A damaged device archive is the same class of bug as a damaged app archive:
 * restoring it writes unreadable or altered data into the provider and reports
 * success for work that did not happen. The provider holds the only copy of SMS,
 * calls and contacts on the device, so the repair is the same shape: refuse
 * BEFORE restoring, not at restore time.
 *
 * The UI names one row per device archive the same way it names one row per app,
 * so the gate returns the same shape of reason — item names, not bare counts.
 */
fun restoreBlockReasonDevice(
    report: VerificationReport?,
    items: Set<DeviceDataItem>
): String? {
    if (items.isEmpty()) return null
    if (report?.conclusive != true) return null
    // Matched on the item, because that is what a restore plan selects; a row for
    // a file this build has no item for cannot be in anyone's plan, and is still
    // named by the card so the finding is not lost.
    val relevant = report.deviceItems.filter { it.item != null && it.item in items }
    if (relevant.isEmpty()) return null
    val damaged = relevant.filter { !it.ok }
    val changed = relevant.filter { it.changed }
    val reasons = mutableListOf<String>()
    if (damaged.isNotEmpty()) {
        reasons += "Cannot restore: these device backups are damaged and cannot be read back \u2014 " +
                "${damaged.joinToString(", ") { it.archiveName }}. " +
                "Back them up again, or remove them from this restore."
    }
    if (changed.isNotEmpty()) {
        val names = changed.joinToString(", ") { it.archiveName }
        val parts = changed.flatMap { it.hashMismatchedParts }.distinct().joinToString(", ")
        reasons += "Cannot restore: these device backups have changed since they were written \u2014 " +
                "$names ($parts). Back them up again, or remove them from this restore."
    }
    return reasons.joinToString(" ").ifEmpty { null }
}


