package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * What to call the run that last changed the vault, from the gate's own label.
 *
 * The labels are the ones [RunCoordinator] takes the gate with (`manual:BACKUP_APPS`,
 * `device-data`, `delete:device`, `transfer`, `automation:<id>`), and they are
 * internal names: shown raw, `manual:RESTORE_APPS` reads as debug output, and
 * shown wrongly — "a backup ran" for a deletion — the card describes work that
 * did not happen. Unrecognised labels fall back to "a run" rather than to a
 * guess, and null (nothing has run in this process) stays null.
 */
internal fun runPhrase(owner: String?): String? {
    if (owner.isNullOrBlank()) return null
    if (owner.startsWith("automation:")) return "a scheduled run"
    if (owner.startsWith("manual:")) {
        return when (owner.removePrefix("manual:")) {
            "BACKUP_APPS" -> "an app backup"
            "RESTORE_APPS" -> "an app restore"
            "MANAGE" -> "a deletion"
            "DEBLOAT" -> "a debloat run"
            "SYSTEMIZE" -> "a systemize run"
            "FREEZE" -> "a freeze run"
            else -> "a run"
        }
    }
    return when (owner) {
        "device-data" -> "a device-data run"
        "delete:device" -> "a device-data deletion"
        "delete:folders" -> "a folder-backup deletion"
        "transfer" -> "a transfer"
        else -> "a run"
    }
}

/**
 * Backup health, as the dashboard states it.
 *
 * The point of this type is the distinction between "verified healthy" and "we
 * have not looked". A backup tool that shows a green tick because nothing has
 * been checked is worse than one that says nothing, because it answers a
 * question the user did not ask with a confidence they did not earn —
 * house rule: never report success for work that did not happen.
 *
 * Pure, so the wording and the severity are pinned by tests rather than left to
 * a Composable to decide.
 */
enum class HealthSeverity {
    UNKNOWN,
    HEALTHY,
    /** Readable, but not the archive that was written. */
    CHANGED,
    DAMAGED,
    ERROR
}

/** What the dashboard shows about the state of the vault. */
@Immutable
data class BackupHealth(
    val severity: HealthSeverity,
    /** One line, states the fact. Never implies more than is known. */
    val title: String,
    /** Secondary line: scope, or what to do about it. Null when there is none. */
    val detail: String? = null,
    /**
     * A third, quieter line for what the row cannot say in [detail]: how long a
     * finding has stood, and what has run since it was taken.
     *
     * Its own field rather than more prose appended to `detail`, which is the
     * line that names the archives: names are what the user acts on, so they keep
     * their line instead of being pushed past an ellipsis by a sentence about
     * timing.
     */
    val footnote: String? = null,
    /** Offer the "check now" action. */
    val canVerify: Boolean = false,
    /** Offer "show me" — only when there is something concrete to show. */
    val canReview: Boolean = false
) {
    companion object {

        /**
         * Map a verification result to what the dashboard says.
         *
         * Null, cancelled and errored results are all UNKNOWN or ERROR — never
         * HEALTHY. `conclusive` already encodes "we can trust this verdict", and
         * an empty vault is not a passing grade.
         */
        fun from(
            report: VerificationReport?,
            vaultEpoch: Long = 0L,
            /** Wall clock now, for the age of a finding; 0 to omit the age. */
            nowMs: Long = 0L,
            /** The gate label of the last run to release the gate, if any. */
            lastWriter: String? = null
        ): BackupHealth {
            if (report == null) {
                return BackupHealth(
                    severity = HealthSeverity.UNKNOWN,
                    title = "Backups not checked yet",
                    detail = "Verify to confirm every archive can still be read back",
                    canVerify = true
                )
            }
            if (report.error.isNotEmpty()) {
                return BackupHealth(
                    severity = HealthSeverity.ERROR,
                    title = "Could not check backups",
                    detail = report.error,
                    canVerify = true
                )
            }
            if (!report.conclusive) {
                return BackupHealth(
                    severity = HealthSeverity.UNKNOWN,
                    title = "Backups not checked yet",
                    detail = "The last check did not finish, so nothing is confirmed",
                    canVerify = true
                )
            }
            if (report.hasCorrupt) {
                val names = report.apps.filter { !it.ok }
                    .map { it.label.ifEmpty { it.packageName } }
                // A merged verdict names both halves: app archives by label, device
                // archives by the file the engine would verify. The card shows the
                // count the summary shows, and the detail names the ones the user
                // can act on — re-backup, or drop from restore.
                val deviceNames = report.deviceDamagedArchives()
                val allNames = names + deviceNames
                return BackupHealth(
                    severity = HealthSeverity.DAMAGED,
                    // The subject comes from the report, not from this file: a
                    // merged verdict covering app and device archives must not be
                    // described as "app backups" — §2.44's defect, one screen out.
                    title = "${report.corruptCount} of ${report.total} " +
                            report.subjects() + " damaged",
                    detail = if (allNames.isNotEmpty()) {
                        allNames.take(3).joinToString(", ") +
                            if (allNames.size > 3) " and ${allNames.size - 3} more" else ""
                    } else null,
                    footnote = findingFootnote(report, vaultEpoch, nowMs, lastWriter),
                    canVerify = true,
                    canReview = true
                )
            }
            // Readable is not the same as unchanged, and a restore refuses an
            // archive whose bytes no longer match the digest recorded with it —
            // so the card may not call it readable-and-fine. Found on device by
            // tampering a part by one byte (same length): the engine reported
            // `HASH_CHANGED:1|HASH_MISMATCH:Permissions.txt`, the dialog and the
            // restore gate both said "changed", and this card went on saying
            // "All 3 backups readable", because the branch below the damage case
            // only ever knew about readable-and-unreadable.
            //
            // Above the staleness guard, for the same reason damage is: a finding
            // is not withdrawn by a later run or by a restart.
            if (report.hasChanged) {
                val names = report.changedApps
                    .map { it.label.ifEmpty { it.packageName } }
                    .toMutableList()
                // Named when the run reported the archive it changed, with the
                // generic phrase only as a fallback for a summary that carried a
                // count and no rows — a card that has a name must use it, because
                // "device data" of seven possible archives is not actionable.
                val deviceNames = report.deviceChangedArchivesNamed()
                if (deviceNames.isNotEmpty()) {
                    names += deviceNames
                } else if (report.deviceChangedArchives > 0) {
                    names += "device data"
                }
                return BackupHealth(
                    severity = HealthSeverity.CHANGED,
                    title = "${report.changedCount} of ${report.total} " +
                            report.subjects() + " changed",
                    detail = names.take(3).joinToString(", ") +
                            if (names.size > 3) " and ${names.size - 3} more" else "",
                    footnote = findingFootnote(report, vaultEpoch, nowMs, lastWriter),
                    canVerify = true,
                    canReview = true
                )
            }
            // The claim must match what was checked. Both entry points used to call
            // `verifyVault()` with the default deviceData = false, i.e. --verify-apps
            // only, so Messages.shift, CallLogs.shift and Folders.shift were never
            // read and "each archive can be restored" was untrue. B-01 made one
            // check cover apps AND device data, so both are now genuinely verified
            // and this wording is earned. If a caller ever passes deviceData = false
            // again, this string must go back to saying so — a reassuring sentence
            // about a partial check is worse than no sentence.
            //
            // A device-only verdict names device archives one by one, so a damaged
            // device archive shows up here exactly like a damaged app does: the card
            // says how many, and the detail names them. Without that, a damaged
            // Messages.shift would only ever be reachable as "1 damaged" on the
            // restore gate, and the Home card would keep saying "All readable".
            // That was the damaged-backup bug for device data: the card was never
            // taught about device archives being damaged, only about them being
            // readable-and-unchanged. The same repair as the app half fixes it:
            // the verdict names the finding, the gate refuses it, and the card
            // says so.
            // A verdict that predates a run over the vault — or one carried over
            // from a previous launch — cannot be shown as healthy: neither was
            // measured by anything in front of the user. Neither is DAMAGED
            // either, because nothing here found damage, so both go back to
            // UNKNOWN, which is what they are, and offer the check again. The
            // headline names which of the two it is, so the user is told what
            // actually happened rather than a single vague hedge.
            //
            // The `DAMAGED` branch above deliberately has no such guard: a
            // finding stands until a backup replaces the file it is about.
            val staleness = report.staleness(vaultEpoch)
            if (staleness != VerdictStaleness.CURRENT) {
                return BackupHealth(
                    severity = HealthSeverity.UNKNOWN,
                    title = staleness.headline,
                    detail = staleness.advice,
                    canVerify = true
                )
            }
            return BackupHealth(
                severity = HealthSeverity.HEALTHY,
                title = "All ${AppParts.count(report.total, "backup")} readable",
                detail = "Verified — every app archive and device archive (messages, " +
                        "calls, contacts, Wi-Fi, wallpaper, Bluetooth, folders) can be " +
                        "restored.",
                // Offered even when healthy, because a check is a thing a user
                // does on purpose — after a ROM flash, after copying a vault in,
                // or because they want to know again. Hiding the control in the
                // good state left no way to ask without restarting the app: the
                // result is in memory only, so the button simply vanished (found
                // on device, 2026-10-12).
                canVerify = true
            )
        }

        /**
         * The quiet line under a FINDING: how long it has stood, and what has
         * happened to the vault since it was taken.
         *
         * A finding is not withdrawn by a later run — that is the rule (§2.46) —
         * which leaves the card stating "CallLogs.shift changed" in the present
         * tense about an archive four runs and a week old. The honest reading is
         * the one this line gives: when it was first seen, and that a run has been
         * over the vault since, so it may already be repaired. Both clauses come
         * from facts the app holds (the row's `firstSeenMs`, the gate's release
         * counter and the label of the last run to release it) — never from a
         * guess about which run wrote which archive, which nothing records.
         *
         * Null when there is nothing true to add: a finding with no age and no run
         * since is already fully described by the two lines above it.
         */
        private fun findingFootnote(
            report: VerificationReport,
            vaultEpoch: Long,
            nowMs: Long,
            lastWriter: String?
        ): String? {
            val parts = mutableListOf<String>()
            com.phoenix.droidvault.utils.Formatters
                .relativeAge(nowMs, report.oldestFindingMs() ?: 0L)
                ?.let { parts += "Found $it" }
            val ran = runPhrase(lastWriter)
            when (report.staleness(vaultEpoch)) {
                // The gate has been released since these bytes were read, so a run
                // has been over the vault. Naming it is the difference between
                // "this might be fixed" and "re-check to find out".
                VerdictStaleness.CHANGED_SINCE -> if (ran != null) {
                    parts += "$ran has run since, so these may already be replaced"
                } else {
                    parts += "A run has been over the vault since, so these may " +
                            "already be replaced"
                }
                // Read back from disk: this launch has measured nothing, whatever
                // the age says, so what happened while the app was closed cannot be
                // named — only admitted.
                VerdictStaleness.FROM_LAST_SESSION -> parts += "checked before this launch"
                VerdictStaleness.CURRENT -> Unit
            }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" \u00b7 ")
        }
    }
}