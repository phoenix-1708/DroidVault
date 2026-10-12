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
    running: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        icon = {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    if (report?.healthy == true) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = if (report?.healthy == true) dvAccent(DvRole.Backup)
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
                        if (report.apps.isNotEmpty()) {
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
                                            if (app.ok) Icons.Default.CheckCircle
                                            else Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = if (app.ok) dvAccent(DvRole.Backup)
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
                                            if (app.ok) com.phoenix.droidvault.models.AppParts.count(app.partsChecked, "part")
                                            else app.corruptParts.joinToString(", "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (app.ok) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                        if (report.hasCorrupt) {
                            Text(
                                "Restoring a damaged archive is refused rather than " +
                                        "started, so an app is never left half-restored. " +
                                        "Back these apps up again, or drop them from " +
                                        "this restore.",
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
fun VerificationBanner(report: VerificationReport, onOpen: () -> Unit) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = if (report.healthy) MaterialTheme.colorScheme.surfaceContainerLow
        else MaterialTheme.colorScheme.errorContainer,
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
                if (report.healthy) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = if (report.healthy) dvAccent(DvRole.Backup)
                else MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.width(10.dp))
            Text(
                report.summaryLine(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (report.healthy) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}