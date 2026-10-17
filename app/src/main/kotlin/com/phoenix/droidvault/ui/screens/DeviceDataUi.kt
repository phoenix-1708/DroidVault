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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Smartphone
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
import com.phoenix.droidvault.models.DeviceArchiveHealth
import com.phoenix.droidvault.models.DeviceArchiveRead
import com.phoenix.droidvault.models.DeviceArchiveRow
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.TransferProgress

/**
 * What device data is in this backup, and the way to act on it.
 *
 * This card answers a question the Manage screen could not: **is this backed up
 * at all?** The only place it could be answered was the "Erase Native" dialog,
 * which is a destructive dialog — a control whose first job is to tell you what
 * you have is a control someone will eventually open meaning to look rather than
 * to delete, and the failure is a tap too late rather than a wrong number.
 *
 * Three rules the rows here hold to, each of which has a way of going wrong:
 *
 *  - **All seven items, always.** A list that shows only what exists cannot
 *    answer "is Contacts backed up", because an absent item and an item nobody
 *    has heard of are the same row. The absent row IS the answer. The list is
 *    [DeviceDataItem.entries] — the same seven, same order, same labels the
 *    Automation tab's device selector renders — so this screen cannot claim an
 *    item that a restore would not select.
 *  - **"Not checked" until a check has run.** A row's health word comes from
 *    the verification report or it says nothing was checked. A tick for a check
 *    nobody ran is the failure this app's verdict rules exist to prevent, and
 *    this card is the third place that has been tempted to fake it.
 *  - **Presence is not health.** The file being in `Device/` is a fact about the
 *    folder; whether those bytes read back is a different question with a
 *    different answer, and the row keeps the two apart.
 *
 * Collapsed by default. Manage's job is the app archive, and seven device rows
 * expanded above it would push the app list — the screen's actual content — off
 * the first screenful.
 */
@Composable
fun DeviceDataCard(
    read: DeviceArchiveRead,
    rows: List<DeviceArchiveRow>,
    onDelete: (DeviceDataItem) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<DeviceDataItem?>(null) }

    // A folder this app could not read is NOT a folder with nothing in it, and
    // the two must not look alike. Rendering the rows anyway would put seven
    // "Not backed up" lines under a header that reads as fact — the app
    // asserting that a user has no device backups because it could not open the
    // directory (invariant 16: absence is not failure). Say what happened.
    val unreadable = read as? DeviceArchiveRead.Unreadable
    if (unreadable != null) {
        DeviceDataUnreadableCard(unreadable.reason)
        return
    }
    if (rows.isEmpty()) return

    val backedUp = rows.count { it.present }
    val totalBytes = rows.filter { it.present }.sumOf { it.info.sizeBytes }

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
                    .padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
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
                        Icons.Default.Smartphone,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Device data",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        // "5 of 7 backed up" rather than "5 items of 7". The
                        // number is the answer and "backed up" is the verb, so
                        // there is no noun to pluralise and nothing to get wrong
                        // for a user who has exactly one of something. "0 of 7" is
                        // a real, useful answer and is said plainly: a user who
                        // has never backed up device data should be told so, not
                        // shown an empty card.
                        "$backedUp of ${rows.size} backed up" +
                            if (backedUp > 0 && totalBytes > 0L) {
                                " · " + TransferProgress.humanBytes(totalBytes)
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
            if (expanded) {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                rows.forEach { row ->
                    DeviceArchiveRowView(
                        row = row,
                        onDelete = { pendingDelete = row.item }
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }

    // Outside the card: a delete removes the row it was raised from, and a
    // dialog composed inside that row would unmount with it.
    pendingDelete?.let { item ->
        DeleteDeviceItemDialog(
            item = item,
            onConfirm = {
                onDelete(item)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/**
 * The vault folder exists but could not be read.
 *
 * Its own card, and not a variant of the full one, because the full card's whole
 * value is a count and a count here would be a lie. It says what failed, says
 * that the backups are probably fine because the app simply could not look, and
 * offers no delete control — a control that cannot act on files it has not read
 * is a control that lies about being available.
 *
 * The technical reason is in the diagnostics log under the `DeviceData` tag, so
 * a field report that says "it says it can't read the folder" is answerable
 * without asking the user to describe their path.
 */
@Composable
private fun DeviceDataUnreadableCard(reason: String) {
    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Device data — could not read",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "Droid Vault could not open the backup folder, so it does not " +
                        "know what is in it. Your backups are most likely fine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    reason,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DeviceArchiveRowView(row: DeviceArchiveRow, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                row.item.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                row.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        // No health tag on an absent archive. "Not backed up" already says
        // everything there is to say, and a tag beside it claims a check
        // applies to a file that is not on the phone.
        if (row.present) {
            DeviceHealthTag(row.health)
        }
        // Absent items have nothing to delete either. A dead bin is a control
        // that invites a tap and then does nothing, which reads as a broken app.
        if (row.present) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove the ${row.item.label} backup",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * The health word, as a pill.
 *
 * Four states and one rule: [DeviceArchiveHealth.NOT_CHECKED] is rendered as
 * itself, in the neutral colour. It is not a green tick, it is not an absence,
 * and it is not "fine" — it is the honest answer for an archive nobody has read
 * back, and it is the state this card will be in for most users most of the
 * time because verification is deliberately behind a tap.
 */
@Composable
private fun DeviceHealthTag(health: DeviceArchiveHealth) {
    val (tint, background) = when (health) {
        DeviceArchiveHealth.READABLE ->
            MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiary
        DeviceArchiveHealth.CHANGED ->
            MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.secondary
        DeviceArchiveHealth.DAMAGED ->
            MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error
        DeviceArchiveHealth.NOT_CHECKED ->
            MaterialTheme.colorScheme.onSurfaceVariant to
                    MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        health.label,
        style = MaterialTheme.typography.labelSmall,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

/**
 * Ask before removing one device archive.
 *
 * Names the item and says what the action actually is. A move to Recently
 * deleted, not an erase — and there is deliberately no "this cannot be brought
 * back", because that would be false: the vault and the trash are on one
 * filesystem, the archive is renamed rather than copied, and it comes back for
 * seven days from the card that already covers every other deletion. A dialog
 * that overstated the damage would train the user to ignore the one that does
 * not.
 *
 * Folders is called out separately because it is the one item that is not a
 * single file: it is an archive of the user's chosen folders, and on some
 * devices that archive is the only copy of photos that exist nowhere else.
 */
@Composable
private fun DeleteDeviceItemDialog(
    item: DeviceDataItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove the ${item.label} backup?") },
        text = {
            Text(
                if (item == DeviceDataItem.FOLDERS) {
                    "It moves to Recently deleted, so you can put it back for 7 " +
                        "days. This is the archive of the folders you chose to " +
                        "back up, so if this phone is the only place those files " +
                        "exist, deleting it is the end of them."
                } else {
                    "It moves to Recently deleted, so you get the space back and " +
                        "can put it back for 7 days. Your other backups and your " +
                        "app data are not touched."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Keep") }
        }
    )
}
