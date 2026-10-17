package com.phoenix.droidvault.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.TransferCandidate
import com.phoenix.droidvault.models.TransferState
import com.phoenix.droidvault.utils.Formatters

/**
 * The transfer files this phone holds, and the way to get the space back.
 *
 * Manage Backups is where "what is on this phone" is answered, and an export is
 * a large part of that answer: seventeen 2 GB pieces is a quarter of the device.
 * It belongs on the screen that manages storage rather than only on the one that
 * imports, because the reason to delete one is usually space, and space is not
 * something the Import page is about.
 *
 * One row per EXPORT, never per piece — the same rule the Import page follows,
 * and for the same reason: seventeen rows that are one thing is a list nobody
 * can act on. The count of pieces is stated in the row instead.
 *
 * Collapsed by default. Manage's job is the app archive, and a list of every
 * export expanded above it would push the app list — the screen's actual
 * content — off the first screenful.
 */
@Composable
fun ExportsCard(
    candidates: List<TransferCandidate>,
    scanning: Boolean,
    onDelete: (TransferCandidate) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TransferCandidate?>(null) }

    // Silent when there is nothing to manage AND nothing to say. An
    // always-present "0 exports" card is a permanent reminder of a feature the
    // user may have no use for.
    if (candidates.isEmpty() && !scanning) return

    val totalBytes = candidates.sumOf { it.totalBytes }

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CloudUpload,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Exports",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        when {
                            scanning && candidates.isEmpty() ->
                                "Looking in Download/DroidVault..."
                            candidates.isEmpty() -> "None on this phone"
                            else ->
                                AppParts.count(candidates.size, "export") +
                                    " · " + Formatters.formatBytes(totalBytes) +
                                    " in Download/DroidVault"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (candidates.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand"
                        )
                    }
                }
            }
            if (expanded && candidates.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                candidates.forEach { c ->
                    ExportRow(c, onDelete = { pendingDelete = c })
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    // Outside the card: a delete removes the row it was raised from, and a dialog
    // composed inside that row would unmount with it.
    pendingDelete?.let { target ->
        DeleteExportDialog(
            candidate = target,
            onConfirm = {
                onDelete(target)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@Composable
private fun ExportRow(candidate: TransferCandidate, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                Formatters.exportStamp(candidate.modifiedMs),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                candidate.sizeLabel + " · " + candidate.partsLabel +
                    (if (candidate.state == TransferState.INCOMPLETE) " · incomplete" else ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (candidate.state == TransferState.INCOMPLETE) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
        // A distinct hit area at the row's edge, away from the row body: AGENTS.md
        // records a blind tap near a restore control landing on "Erase Native".
        // Nothing destructive sits where a thumb lands while scrolling this list.
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Delete this export",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Ask before deleting an export.
 *
 * The question is not "are you sure" — it is "which one", because a row carries a
 * timestamp, a size and a part count and nothing else that names it, and rows sit
 * a finger's width apart. So the dialog restates the export by date, size and
 * part count, and says what the delete actually is: a move to Recently deleted,
 * not an erase.
 *
 * There is deliberately no "this cannot be brought back" warning, because it
 * would be false. The vault and Download share a filesystem — measured, device id
 * 163 on both, on the reference phone — so the export is renamed into the trash
 * and comes back for seven days from the card that already covers every other
 * deletion. A dialog that overstated the damage would train the user to ignore
 * the one that does not.
 */
@Composable
fun DeleteExportDialog(
    candidate: TransferCandidate,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this export?") },
        text = {
            Column {
                Text(
                    Formatters.exportStamp(candidate.modifiedMs) + " · " +
                        candidate.sizeLabel + " · " + candidate.partsLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    (if (candidate.state == TransferState.INCOMPLETE) {
                        "This export is missing pieces, so it cannot be restored anyway. "
                    } else {
                        ""
                    }) +
                        "It moves to Recently deleted, so you get the space back and can " +
                        "put it back for 7 days. Your backups in the vault are not " +
                        "touched — this only removes the copy made for transferring.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep") }
        }
    )
}