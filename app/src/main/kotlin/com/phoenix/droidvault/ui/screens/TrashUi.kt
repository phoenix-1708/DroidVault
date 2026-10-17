package com.phoenix.droidvault.ui.screens

import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.phoenix.droidvault.models.Trash
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.dvAccent
import java.util.Locale

private fun formatAge(millis: Long): String {
    val hours = millis / 3_600_000
    return when {
        hours < 1 -> "just now"
        hours < 24 -> "${hours}h ago"
        else -> "${hours / 24}d ago"
    }
}

private fun formatMb(kb: Long): String = when {
    kb >= 1_048_576 -> String.format(Locale.US, "%.1f GB", kb / 1_048_576.0)
    kb >= 1024 -> String.format(Locale.US, "%.1f MB", kb / 1024.0)
    else -> "$kb KB"
}

/**
 * Recently deleted backups, and the way back.
 *
 * Every destructive action here moves rather than erases. This surface is where
 * that promise is cashed in — and where the user takes the irreversible step
 * deliberately, because a trash that can never be emptied is just a slower
 * leak.
 */
@Composable
fun TrashCard(
    batches: List<Trash.Batch>,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onOpen: () -> Unit
) {
    if (batches.isEmpty()) return
    val heldKb = Trash.heldKb(batches)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Undo,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = dvAccent(DvRole.Restore)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    // A `}` OUTSIDE `${…}` is an ordinary character, so the
                    // compiler had no opinion and every test that renders this
                    // card read "1 deleted backup recoverable". Found on device
                    // 2026-10-15, on the Manage screen — the same defect §2.67
                    // recorded for the per-run subtitle, in the one place the
                    // property guard below did not reach.
                    com.phoenix.droidvault.models.AppParts.count(
                        batches.sumOf { it.count },
                        "deleted backup"
                    ) + " recoverable",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "${formatMb(heldKb)} held • recoverable for 7 days",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpen) { Text("Review") }
        }
    }
}

@Composable
fun TrashDialog(
    batches: List<Trash.Batch>,
    onRestore: (String) -> Unit,
    onPurge: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var confirming by remember { mutableStateOf<Trash.Batch?>(null) }
    val now = System.currentTimeMillis()

    // The dialog closes itself once the last batch is gone. Reported on device:
    // purging the only batch left an "Recently deleted" popup with nothing in
    // it — the confirm dialog was answered, and a second, meaningless explainer
    // took its place. Purging is the end of the interaction, not a step in it.
    LaunchedEffect(batches) {
        if (batches.isEmpty()) {
            confirming = null
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null) },
        title = { Text("Recently deleted") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Deleted backups are moved, not erased. Put them back, or empty " +
                            "the trash to free the space for good.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                batches.forEach { batch ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        // Name, not just a count. "1 app" with
                                        // no name is not an answer to "what did
                                        // I just delete?" — and two identical
                                        // "1 app" rows are indistinguishable.
                                        Trash.entrySummary(batch),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        Trash.batchLabel(batch.count, batch.kind) +
                                                " • " + formatMb(batch.totalKb) + " • " +
                                                formatAge(batch.ageMillis(now)) +
                                                if (batch.isRecoverable(now)) "" else " • expired",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { onRestore(batch.id) },
                                    enabled = batch.isRecoverable(now)
                                ) {
                                    Icon(
                                        Icons.Default.Restore,
                                        contentDescription = "Put ${Trash.batchLabel(batch.count, batch.kind)} back"
                                    )
                                }
                                IconButton(
                                    onClick = { confirming = batch }
                                ) {
                                    Icon(
                                        Icons.Default.DeleteForever,
                                        contentDescription =
                                            "Erase ${Trash.batchLabel(batch.count, batch.kind)} permanently",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        shape = MaterialTheme.shapes.large
    )

    // Confirm the irreversible step explicitly. An "empty trash" that happens
    // on a single tap is the same footgun this whole feature removes.
    confirming?.let { batch ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Erase permanently?") },
            text = {
                Text(
                    "${Trash.batchLabel(batch.count, batch.kind)} (${formatMb(batch.totalKb)}) " +
                            "deleted for good. This cannot be undone, and the space is freed.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onPurge(batch.id)
                    confirming = null
                }) { Text("Erase for good", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = null }) { Text("Keep") }
            },
            shape = MaterialTheme.shapes.large
        )
    }
}