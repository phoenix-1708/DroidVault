package com.phoenix.droidvault.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoenix.droidvault.models.VerdictStaleness
import com.phoenix.droidvault.models.VerificationReport
import com.phoenix.droidvault.models.summaryLine
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.dvAccent
import java.util.Locale

/**
 * The result of reading every archive in the vault back.
 *
 * Shows per-app detail rather than a single pass/fail: "your backups are broken"
 * is useless when the user cannot tell which ones, and the useful action
 * (re-backup those apps) is per-app.
 */
@Composable
fun VerificationDialog(
    report: VerificationReport?,
    /**
     * How much authority the verdict still has. No default on purpose: a caller
     * that forgets it would silently re-arm the reassuring reading this parameter
     * exists to withdraw.
     */
    staleness: VerdictStaleness,
    running: Boolean,
    onDismiss: () -> Unit
) {
    // Good news expires; a finding does not. A withdrawn report keeps saying what
    // it found and loses its tick.
    val withdrawn = report?.healthy == true && staleness != VerdictStaleness.CURRENT
    val reassuring = report?.healthy == true && !withdrawn
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        icon = {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    if (reassuring) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (reassuring) dvAccent(DvRole.Backup)
                    else if (report?.healthy == true) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        title = { Text(if (running) "Verifying backups" else "Backup health") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    running -> Text(
                        "Reading every archive back to confirm it can be restored. " +
                                "This takes a moment for large vaults.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    report == null -> Text(
                        "Verification did not run.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    else -> {
                        Text(
                            report.summaryLine(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (report.healthy) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error
                        )
                        if (report.bytesReadable > 0) {
                            Text(
                                String.format(
                                    Locale.US, "%.2f GB readable", report.bytesReadable / 1_073_741_824.0
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // A finding is not withdrawn by a later run, so an old one
                        // is still a finding — and how long it has stood is the
                        // difference between "something just happened" and "this
                        // has been wrong for a week". Age from the row's first
                        // sighting, never from when this dialog was opened.
                        val age = com.phoenix.droidvault.utils.Formatters
                            .relativeAge(System.currentTimeMillis(), report.oldestFindingMs() ?: 0L)
                        if (age != null && !report.healthy) {
                            // "First seen", not "has stood since": the age is
                            // already a finished phrase ("3 days ago", "just
                            // now"), and "since 3 days ago" is not a sentence
                            // anyone writes.
                            Text(
                                "First seen $age.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // The vault carries two records of every archive's digest:
                        // the `.sha256` beside it and the index's read-back marker.
                        // Where they disagreed, this run rewrote the one written by
                        // hand or by another tool — which is why `sha256sum -c` had
                        // been failing on a file the app called fine. Reported
                        // because it happened to their vault, not to this check.
                        if (report.reconciledRecords > 0) {
                            // Two whole sentences rather than a count glued to a
                            // noun: this one has to agree in three places (the
                            // noun, the pronoun and the verb), and `AppParts.count`
                            // only answers for the first.
                            Text(
                                if (report.reconciledRecords == 1) {
                                    "A `.sha256` sidecar disagreed with the bytes it " +
                                            "describes, so it was rewritten from them."
                                } else {
                                    // The count is placed after the noun so the sentence
                                    // stays a sentence: "N archives' records" cannot be
                                    // written by `AppParts.count`, which answers for a
                                    // noun and not for a noun phrase.
                                    "`.sha256` sidecars disagreed with the bytes they " +
                                            "describe on ${report.reconciledRecords} of the " +
                                            "vault's archives, so each was rewritten from them."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // Say what was NOT read. A re-check of an unchanged vault
                        // reads almost nothing, and "all readable" alone would
                        // let the user believe every byte was just proved again.
                        if (report.skippedParts > 0) {
                            Text(
                                "${report.skippedParts} part" +
                                        (if (report.skippedParts == 1) "" else "s") +
                                        " unchanged since the last read-back, " +
                                        "${report.readParts} read now",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // The report is a record of a moment, not a verdict on the
                        // vault in front of the user — either because a run has
                        // moved the vault on since, or because the record came
                        // back from disk and nothing in this launch has looked.
                        // Said here because this dialog is the detail view for
                        // that record, and "all readable" read from it is exactly
                        // the reassurance staleness exists to withdraw.
                        if (withdrawn) {
                            Text(
                                staleness.withdrawal,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (report.error.isNotEmpty()) {
                            Text(
                                report.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (report.cancelled) {
                            Text(
                                "Stopped before finishing, so this result is incomplete.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (report.apps.isNotEmpty() || report.deviceItems.isNotEmpty()) {
                            Text(
                                "Tap for detail",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                                // The engine keys the archive directory by LABEL (engine.rs: base.join(kind).join(label)),
                                // while VerificationReport appends one record per VERIFY_APP line with no
                                // dedupe. An app that changed its launcher label, or moved between User and
                                // System across a ROM change, therefore yields two rows with the SAME package
                                // -> duplicate LazyColumn key -> crash when the user opens this dialog.
                                // MigratorManager already .distinctBy { packageName } for the restore list.
                                items(report.apps, key = { "${it.packageName}|${it.label}" }) { app ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (app.trustworthy) Icons.Default.CheckCircle
                                            else Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (app.trustworthy) dvAccent(DvRole.Backup)
                                            else MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            app.label.ifEmpty { app.packageName },
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            when {
                                                !app.ok -> app.corruptParts.joinToString(", ")
                                                app.changed -> "changed: " +
                                                        app.hashMismatchedParts.joinToString(", ")
                                                else -> com.phoenix.droidvault.models.AppParts
                                                    .count(app.partsChecked, "part")
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (app.trustworthy) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                // The device half, one row per archive the run read.
                                // Without these a damaged Messages.shift reached this
                                // dialog as part of a count with nothing to tap: the
                                // summary said "1 of 7 damaged" and no row named it,
                                // which is the one thing the detail view exists for.
                                items(report.deviceItems, key = { "device|${it.archiveName}" }) { row ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (row.trustworthy) Icons.Default.CheckCircle
                                            else Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (row.trustworthy) dvAccent(DvRole.Backup)
                                            else MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            row.label,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            when {
                                                !row.ok -> row.corruptParts.joinToString(", ")
                                                row.changed -> "changed: " +
                                                        row.hashMismatchedParts.joinToString(", ")
                                                // The file name is the archive; there are
                                                // no parts to count inside a device archive.
                                                else -> row.archiveName
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (row.trustworthy) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        if (report.hasCorrupt) {
                            // "apps" was the wording while only app archives could be
                            // damaged; a device archive lands here too, and the repair
                            // for both is to back the archive up again.
                            Text(
                                "Restoring a damaged archive is refused rather than " +
                                        "started, so nothing is left half-restored. Back " +
                                        "it up again, or drop it from this restore.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (report.hasChanged && !report.hasCorrupt) {
                            Text(
                                "A backup whose bytes no longer match the digest recorded " +
                                        "with it was altered or written incompletely, so " +
                                        "restoring it is refused. Back it up again, or drop " +
                                        "it from this restore.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !running) { Text("Close") }
        },
        shape = MaterialTheme.shapes.large
    )
}

/** Compact inline result for the Manage screen, above the app list. */
@Composable
fun VerificationBanner(
    report: VerificationReport,
    /** How much authority this verdict still has, and why not more. */
    staleness: VerdictStaleness,
    onOpen: () -> Unit
) {
    val withdrawn = report.healthy && staleness != VerdictStaleness.CURRENT
    val reassuring = report.healthy && !withdrawn
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = when {
            reassuring -> MaterialTheme.colorScheme.surfaceContainerLow
            // Neutral, not red: a stale verdict has found nothing wrong — it has
            // simply stopped being about the vault in front of the user, and
            // colouring it as an error would raise an alarm nobody rang.
            report.healthy -> MaterialTheme.colorScheme.surfaceContainerHighest
            else -> MaterialTheme.colorScheme.errorContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (reassuring) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = when {
                    reassuring -> dvAccent(DvRole.Backup)
                    report.healthy -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onErrorContainer
                }
            )
            Spacer(Modifier.width(10.dp))
            Text(
                // Past tense for a withdrawn verdict: the check happened and this
                // is what it found. "are readable" would be a claim about now.
                if (withdrawn) {
                    "${staleness.headline} \u2014 check again"
                } else {
                    report.summaryLine()
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = when {
                    reassuring -> MaterialTheme.colorScheme.onSurface
                    report.healthy -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onErrorContainer
                }
            )
        }
    }
}