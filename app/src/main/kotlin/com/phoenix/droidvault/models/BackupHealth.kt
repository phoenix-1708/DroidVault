package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

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
enum class HealthSeverity { UNKNOWN, HEALTHY, DAMAGED, ERROR }

/** What the dashboard shows about the state of the vault. */
@Immutable
data class BackupHealth(
    val severity: HealthSeverity,
    /** One line, states the fact. Never implies more than is known. */
    val title: String,
    /** Secondary line: scope, or what to do about it. Null when there is none. */
    val detail: String? = null,
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
        fun from(report: VerificationReport?): BackupHealth {
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
                return BackupHealth(
                    severity = HealthSeverity.DAMAGED,
                    title = "${report.corruptCount} of ${report.total} app backups damaged",
                    detail = names.take(3).joinToString(", ") +
                            if (names.size > 3) " and ${names.size - 3} more" else "",
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
            return BackupHealth(
                severity = HealthSeverity.HEALTHY,
                title = "All ${AppParts.count(report.total, "backup")} readable",
                detail = "Verified — every app archive and device archive (messages, " +
                        "calls, contacts, Wi-Fi, wallpaper, Bluetooth, folders) can be " +
                        "restored."
            )
        }
    }
}