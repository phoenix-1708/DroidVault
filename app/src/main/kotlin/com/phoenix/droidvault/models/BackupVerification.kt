package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * Archive verification — parsing the engine's report, and deciding what a
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
    val bytesReadable: Long
) {
    val ok: Boolean get() = corruptParts.isEmpty()
}

/**
 * What a verification actually checked.
 *
 * The engine emits `VERIFY_DONE` for apps and `VERIFY_DEVICE_DONE` for device
 * archives, so the kind is known from the report's own output rather than from
 * a flag the caller has to remember to pass. Found on device: checking device
 * data with no archives reported "No app backups found", because the summary
 * was written for apps only.
 */
enum class VerifyScope { APPS, DEVICE }

@Immutable
data class VerificationReport(
    val apps: List<AppVerification> = emptyList(),
    val total: Int = 0,
    val okCount: Int = 0,
    val corruptCount: Int = 0,
    val bytesReadable: Long = 0L,
    val cancelled: Boolean = false,
    val error: String = "",
    val scope: VerifyScope = VerifyScope.APPS
) {
    val hasCorrupt: Boolean get() = corruptCount > 0

    /**
     * True when the report cannot be trusted as a go-ahead.
     *
     * An empty vault and a cancelled or errored run are both "we do not know",
     * and must never be reported as "verified". `verifyReportAllowsRestore`
     * handles the difference.
     */
    val conclusive: Boolean
        get() = !cancelled && error.isEmpty() && (total > 0 || apps.isNotEmpty())

    val healthy: Boolean get() = conclusive && !hasCorrupt
}

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
                    bytesReadable = f["BYTES"]?.toLongOrNull() ?: 0L
                )
                report.copy(apps = report.apps + app)
            }

            line.startsWith("VERIFY_DONE|") || line.startsWith("VERIFY_DEVICE_DONE|") -> {
                report.copy(
                    total = f["TOTAL"]?.toIntOrNull() ?: report.apps.size,
                    okCount = f["OK"]?.toIntOrNull() ?: 0,
                    corruptCount = f["CORRUPT"]?.toIntOrNull()
                        ?: report.apps.count { !it.ok },
                    bytesReadable = f["BYTES"]?.toLongOrNull() ?: report.bytesReadable,
                    cancelled = f["CANCELLED"] == "1",
                    scope = if (line.startsWith("VERIFY_DEVICE_DONE|")) {
                        VerifyScope.DEVICE
                    } else {
                        VerifyScope.APPS
                    }
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
    return relevant.none { !it.ok }
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
    }
    !hasCorrupt -> if (scope == VerifyScope.DEVICE) {
        "All $okCount device data archives are readable"
    } else {
        "All $okCount app backups are readable"
    }
    else -> if (scope == VerifyScope.DEVICE) {
        "$okCount of $total device data archives readable, $corruptCount damaged"
    } else {
        "$okCount of $total app backups readable, $corruptCount damaged"
    }
}

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
    return when (report?.verifyReportAllowsRestore(packages)) {
        false -> {
            val damaged = report.apps.filter { !it.ok }
                .joinToString(", ") { it.label.ifEmpty { it.packageName } }
            "Cannot restore: these backups are damaged and cannot be read back \u2014 " +
                    "$damaged. Back them up again, or remove them from this restore."
        }

        else -> null
    }
}
