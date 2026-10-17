package com.phoenix.droidvault.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import com.phoenix.droidvault.ui.safeToast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ChangeCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoenix.droidvault.AutoMode
import com.phoenix.droidvault.MainViewModel
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.BackupHealth
import com.phoenix.droidvault.models.HealthSeverity
import com.phoenix.droidvault.models.HomeShortcut
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.models.ShortcutTarget
import com.phoenix.droidvault.ui.theme.DvMotion
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.DvSpacing
import com.phoenix.droidvault.ui.theme.DvShapes
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.ui.theme.dvContainer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Card height shared by the grid's geometry maths and the card itself. */
private val SHORTCUT_CARD_HEIGHT = 148.dp

// The floating navigation bar's height is reserved once by the shell, which
// measures the real bar and pads every page. This trailing spacer is only the
// breathing room between the last card and the bar.
private val CARD_BOTTOM_GAP = 20.dp

/** Icon and accent per shortcut, so cards stay visually distinct. */
private fun HomeShortcut.icon(): ImageVector = when (this) {
    HomeShortcut.BACKUP_APPS -> Icons.Default.Backup
    HomeShortcut.RESTORE_APPS -> Icons.Default.Restore
    HomeShortcut.MANAGE_BACKUPS -> Icons.Default.Storage
    HomeShortcut.DEBOOT -> Icons.Default.DeleteSweep
    HomeShortcut.SYSTEMIZE -> Icons.Default.SecurityUpdateGood
    HomeShortcut.DEVICE_BACKUP -> Icons.Default.PhoneAndroid
    // Was PhoneAndroid, the same icon as DEVICE_BACKUP directly above. Two
    // OPPOSITE actions on one screen, indistinguishable by icon, is not a
    // duplicate anyone can act on — the label is the only thing left telling
    // them apart. `Restore` is what every other restore in the app already uses,
    // so this is consistency as much as distinction.
    HomeShortcut.DEVICE_RESTORE -> Icons.Default.Restore
    HomeShortcut.PARTITION_TOOLS -> Icons.Default.FlashOn
    HomeShortcut.UTILITIES -> Icons.Default.Build
    HomeShortcut.AUTOMATION -> Icons.Default.Schedule
    HomeShortcut.APP_LABELS -> Icons.Default.Edit
    HomeShortcut.BACKUP_CONFIGS -> Icons.Default.Storage
    HomeShortcut.RESTORE_CONFIGS -> Icons.Default.Restore
    HomeShortcut.SCHEDULES -> Icons.Default.Schedule
    HomeShortcut.HISTORY -> Icons.Default.History
}

private fun HomeShortcut.role(): DvRole = when (kind) {
    ShortcutTarget.MIGRATOR -> when (this) {
        HomeShortcut.RESTORE_APPS -> DvRole.Restore
        else -> DvRole.Backup
    }
    ShortcutTarget.AUTOMATION -> DvRole.Auto
    ShortcutTarget.DEVICE_DATA -> DvRole.Device
    ShortcutTarget.TAB -> DvRole.Storage
}

private fun formatGb(bytes: Long): String =
    String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)

/** Backup-first landing page built from live vault and device-backup state. */
@Composable
fun DashboardScreen(
    viewModel: MainViewModel,
    appState: AppState,
    onOpenTab: (Int) -> Unit,
    onOpenMigrator: (MigratorMode) -> Unit,
    onOpenAutomation: (AutoMode) -> Unit = {},
    onOpenDeviceData: (backup: Boolean) -> Unit = {},
    // The shortcut edit toggle lives in the app bar beside the gear. It used to
    // be a pencil floating in the column, and that is what made the hole: a
    // 48dp touch target holding one 20dp icon, right-aligned, in a full-width
    // row, so the void above it was 20dp of column gap PLUS half a touch target
    // and the void below was another half. Two half-targets of nothing around a
    // single control, and the group's own comment claimed to have fixed it.
    editMode: Boolean = false
) {
    val context = LocalContext.current
    val savedPath by viewModel.savedPath.collectAsStateWithLifecycle()
    val deviceBackups by viewModel.availableDeviceBackups.collectAsStateWithLifecycle()
    val shortcuts by viewModel.homeShortcuts.collectAsStateWithLifecycle()
    val overview by viewModel.overview.collectAsStateWithLifecycle()
    // Backup health on the landing page. Shows the LAST verification result and
    // never runs one on its own — verifying reads every archive, so it belongs
    // behind a deliberate tap. "Unknown" is a real state here, not an absence:
    // the card says so rather than showing a reassuring tick for work nobody did.
    val verification by viewModel.verification.collectAsStateWithLifecycle()
    val isVerifying by viewModel.isVerifying.collectAsStateWithLifecycle()
    // The vault's change counter. A verdict is a measurement of a moment, and a
    // run over the vault may have replaced anything it measured — so the epoch
    // goes into the mapping and a report from before the last run is no longer
    // allowed to render as "all readable".
    val vaultEpoch by viewModel.vaultEpoch.collectAsStateWithLifecycle()
    // What last changed the vault, so a finding that has outlived a run can say
    // WHICH kind of run went over it — "an app backup has run since" rather than
    // the weaker "something has". Null in a fresh process, which is itself the
    // honest answer: nothing this launch saw wrote anything.
    val lastVaultWriter by viewModel.lastVaultWriter.collectAsStateWithLifecycle()
    // The clock, read when the verdict changes rather than on every frame: the
    // age of a finding does not need to tick, and a state read per recomposition
    // would make the row recompose for a number that has not changed since.
    val nowMs = remember(verification, vaultEpoch) { System.currentTimeMillis() }
    // Two DISTINCT modes. Conflating them was a real bug: one flag both revealed
    // the per-card remove buttons AND opened the picker over them, so the remove
    // buttons could never be tapped, and "Add shortcut" closed the dialog.
    var pickerOpen by remember { mutableStateOf(false) }
    // Leaving edit mode closes the picker. The pencil used to own both, and its
    // one tap had to remember to reset the other; now the sheet follows the mode
    // rather than the mode following the sheet.
    LaunchedEffect(editMode) { if (!editMode) pickerOpen = false }

    LaunchedEffect(Unit) {
        viewModel.refreshDeviceBackups()
        viewModel.refreshVaultContents()
        viewModel.refreshOverview()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(start = DvSpacing.xxl, end = DvSpacing.xxl, top = DvSpacing.xxl)
            // Reserves room for the floating nav bar plus this page's trailing
            // spacer, so the last card can never end up underneath it.
            .padding(bottom = CARD_BOTTOM_GAP),
        verticalArrangement = Arrangement.spacedBy(DvSpacing.xl)
    ) {
        VaultHero(
            overview = overview,
            appState = appState,
            health = BackupHealth.from(verification, vaultEpoch, nowMs, lastVaultWriter),
            verifying = isVerifying,
            hasDeviceArchives = deviceBackups.isNotEmpty(),
            deviceArchiveCount = deviceBackups.size,
            onVerify = { viewModel.verifyVault(deviceData = true) },
            onReview = { onOpenMigrator(MigratorMode.MANAGE) }
        )

    if (shortcuts.isEmpty()) {
        EmptyShortcutsCard(onPick = { pickerOpen = true })
    } else {
        ShortcutGrid(
            shortcuts = shortcuts,
            editing = editMode,
            enabledFor = { item -> appState.hasRoot || item.kind != ShortcutTarget.MIGRATOR },
            onOpen = { item ->
                when (item.kind) {
                    ShortcutTarget.MIGRATOR -> onOpenMigrator(item.mode)
                    ShortcutTarget.AUTOMATION -> onOpenAutomation(item.autoMode)
                    ShortcutTarget.DEVICE_DATA -> onOpenDeviceData(item.deviceBackup)
                    ShortcutTarget.TAB -> onOpenTab(item.tab)
                }
            },
            onRemove = { item ->
                // A silently dead X is worse than a refused action: report it.
                if (!viewModel.removeHomeShortcut(item)) {
                    context.safeToast("Keep at least one shortcut on the Home screen.")
                }
            },
            onMove = { from, to -> viewModel.moveHomeShortcut(from, to) }
        )
        if (editMode) {
            OutlinedButton(
                onClick = { pickerOpen = true },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DvSpacing.md)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(DvSpacing.sm))
                Text("Add shortcut")
            }
        }
    }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = DvShapes.card,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
        ) {
            Column(
                modifier = Modifier.padding(DvSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(DvSpacing.md)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Storage, null, tint = dvAccent(DvRole.Storage))
                    Spacer(Modifier.width(DvSpacing.sm))
                    Text(
                        "Backup location",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    savedPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = dvContainer(DvRole.Device)) {
                        Text(
                            "${deviceBackups.size}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            color = dvAccent(DvRole.Device),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Spacer(Modifier.width(DvSpacing.sm))
                    Text(
                        when (deviceBackups.size) {
                            0 -> "No device data archives yet"
                            1 -> "1 device data archive available"
                            else -> "${deviceBackups.size} device data archives available"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (pickerOpen) {
        ShortcutPickerDialog(
            selected = shortcuts,
            onToggle = { item ->
                if (shortcuts.any { it == item }) {
                    // Same refusal as the tile's X — say so rather than fail quietly.
                    if (!viewModel.removeHomeShortcut(item)) {
                        context.safeToast("Keep at least one shortcut on the Home screen.")
                    }
                }
                else viewModel.addHomeShortcut(item)
            },
            onReset = { viewModel.resetHomeShortcuts() },
            onDismiss = { pickerOpen = false }
        )
    }
}

/**
 * Storage and root status, in the compact two-stat form.
 *
 * Replaces the old full-width root card and the two big action buttons: those
 * pushed the vault information below the fold, so the one thing that changes
 * most often (how much room is left) was the least visible. The primary backup
 * and restore actions moved into the quick-access row instead of disappearing.
 */

/**
 * The landing page's one answer: **are my backups safe, and how much of them
 * are there?**
 *
 * The previous page led with free space on the device — a number about the
 * phone, not about the user's backups — and put the health verdict in a card of
 * its own, floating between two others with no container, so it read as a
 * rendering fault rather than a fact. Storage is a *constraint* on backups, so
 * it is a stat tile now; the backups are the subject, so they are the headline.
 *
 * Deliberately passive about verification: it reports the last result and
 * offers a check. It never verifies on entry, because verification reads every
 * archive in the vault and the landing page is opened constantly.
 *
 * The vault occupancy bar is the only loud element on the page. Everything
 * around it is quiet text, so the eye lands on the one number that decides
 * whether a ROM flash is safe to attempt.
 */
@Composable
private fun VaultHero(
    overview: MainViewModel.HomeOverview?,
    appState: AppState,
    health: BackupHealth,
    verifying: Boolean,
    /** True when the vault holds device archives, so a device-only backup still counts. */
    hasDeviceArchives: Boolean,
    deviceArchiveCount: Int,
    onVerify: () -> Unit,
    onReview: () -> Unit
) {
    val storage = overview?.storage
    val root = overview?.root
    val count = overview?.backupCount
    val vaultKb = overview?.vaultKb

    // Warn only when the vault is genuinely close to full. During a restore a
    // burst of temp writes legitimately eats the space, and warning then would
    // be noise rather than signal.
    val vaultFill = if (storage != null && storage.totalBytes > 0L) {
        ((vaultKb ?: 0L).toDouble() / storage.totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    } else {
        null
    }
    val barColor by animateColorAsState(
        targetValue = when {
            vaultFill != null && vaultFill >= 0.9f -> MaterialTheme.colorScheme.error
            else -> dvAccent(DvRole.Backup)
        },
        animationSpec = DvMotion.settle(DvMotion.emphasised),
        label = "vaultBar"
    )

    Column(verticalArrangement = Arrangement.spacedBy(DvSpacing.xl)) {
        Column {
            // Headline: a count of what is saved, in plain words. This replaced
            // "Your data, protected", which asserted a benefit without naming a
            // single fact about the user's data.
            Text(
                when {
                    count == null -> "Reading your backups"
                    // Device archives are BACKUPS. A user whose messages, call
                    // logs and contacts are the only thing they have was told
                    // "No backups yet" in the largest font on this screen — while
                    // the same screen listed their device archives underneath and
                    // rendered a live "Check" button for backups it had just
                    // denied existed.
                    count == 0 && hasDeviceArchives ->
                        "${com.phoenix.droidvault.models.AppParts.count(deviceArchiveCount, "device backup")}"
                    count == 0 -> "No backups yet"
                    count == 1 -> "1 app backed up"
                    else -> "${com.phoenix.droidvault.models.AppParts.count(count, "app")} backed up"
                },
                style = MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.height(DvSpacing.xs))
            Text(
                when {
                    vaultKb == null -> "Pick what to save and it will live here"
                    count == 0 && !hasDeviceArchives -> "Pick what to save and it will live here"
                    else -> "${formatVaultSize(vaultKb)} in the vault folder"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

        }

        // Health is a row, not a card: it is one line of state with one action,
        // and giving it its own surface made it look like a separate feature
        // instead of the answer to the question above it.
        // Suppressed outright when the vault holds nothing. "Backups not
        // checked yet / verify every archive can still be read back" over an
        // empty vault describes zero archives, and a Check button that can only
        // ever return "nothing to check" is a dead control on a fresh install.
        // It is NOT suppressed when the only backups are device data: a user whose
        // messages are their sole backup must still be able to check them.
        // The headline above already carries the empty state as an invitation.
        if (count != 0 || hasDeviceArchives) {
            HealthRow(
                health = health,
                verifying = verifying,
                onVerify = onVerify,
                onReview = onReview
            )
        }

        // Prerequisites side by side. Root is not a storage fact and does not
        // belong inside the storage card behind a divider — it is equally the
        // thing that decides whether this app can work at all.
        Row(horizontalArrangement = Arrangement.spacedBy(DvSpacing.md)) {
            StatTile(
                modifier = Modifier.weight(1f),
                icon = if (root?.granted ?: appState.hasRoot) Icons.Default.CheckCircle
                else Icons.Default.ErrorOutline,
                tint = if (root?.granted ?: appState.hasRoot) dvAccent(DvRole.Tools)
                else MaterialTheme.colorScheme.error,
                label = if (root?.granted ?: appState.hasRoot) "Root granted" else "Root needed",
                value = rootLabel(root, appState.hasRoot)
            )
            StatTile(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.SdStorage,
                tint = if (storage != null && storage.usedFraction >= 0.9f)
                    MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "Free space",
                value = storage?.let { formatGb(it.freeBytes) } ?: "—"
            )
        }
    }
}

/** One prerequisite, stated once. */
@Composable
private fun StatTile(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: androidx.compose.ui.graphics.Color,
    label: String,
    value: String
) {
    Surface(
        shape = DvShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(DvSpacing.lg)) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(DvSpacing.sm))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                // Two lines, because at a 2x font "Magisk 30.7.5" no longer fits
                // on one in a half-width stat tile and was cut to "Magisk 3…".
                // The tile is a fixed height, so this grows the card rather than
                // clipping inside it — which is the right trade: a version number
                // you cannot read is worse than a tile that is a line taller.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Backup health as a single actionable row.
 *
 * Tappable only when there is something concrete behind it (a damaged app to
 * look at); the check itself is always offered when a check is meaningful.
 * A permanently lit control the user cannot press is a dead control, so
 * "nothing to do" shows no affordance rather than a disabled one.
 */
@Composable
private fun HealthRow(
    health: BackupHealth,
    verifying: Boolean,
    onVerify: () -> Unit,
    onReview: () -> Unit
) {
    val tint = when (health.severity) {
        HealthSeverity.HEALTHY -> dvAccent(DvRole.Tools)
        // A changed archive blocks a restore exactly as an unreadable one does, so
        // it carries the same weight on screen. Only the sentence differs.
        HealthSeverity.CHANGED,
        HealthSeverity.DAMAGED,
        HealthSeverity.ERROR -> MaterialTheme.colorScheme.error
        HealthSeverity.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(DvShapes.card)
            .clickable(enabled = health.canReview) { onReview() }
            // No horizontal inset: this row has to sit on the same left edge as
            // the headline and the stat tiles above it.
            .padding(vertical = DvSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = when (health.severity) {
                HealthSeverity.HEALTHY -> Icons.Default.VerifiedUser
                HealthSeverity.CHANGED -> Icons.Default.ChangeCircle
                HealthSeverity.DAMAGED -> Icons.Default.ReportProblem
                HealthSeverity.ERROR -> Icons.Default.ErrorOutline
                HealthSeverity.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
            },
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = DvSpacing.md)
        ) {
            Text(
                if (verifying) "Checking backups" else health.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            health.detail?.takeIf { it.isNotBlank() && !verifying }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // No line cap. These two are complete sentences and both
                    // state something the user acts on: the advice names the
                    // action, the footnote names how long the finding has stood
                    // and which kind of run has been over the vault since.
                    //
                    // Capping them was wrong at every size and worst at 2x,
                    // where the advice lost its tail three separate times —
                    // 2 lines cut it to "confirm the archi…", 3 lines still cut it
                    // to "archives are still …". Both cuts landed on the verb and
                    // the noun doing the work. The row is free to grow; the
                    // sentence is not free to be shortened by the layout.
                    // (Invariant 41: a finding says how old it is and what has
                    // been over the vault since. An ellipsis deletes that.)
                )
            }
            // Its own line, quieter than the one above: the names are what the
            // user acts on, so how long the finding has stood and what has run
            // since must not push them past an ellipsis.
            health.footnote?.takeIf { it.isNotBlank() && !verifying }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Uncapped for the reason given above: this sentence IS the
                    // second fact, and a cap deletes it.
                )
            }
        }
        if (health.canVerify) {
            TextButton(onClick = onVerify, enabled = !verifying) {
                Text(if (verifying) "Checking" else "Check")
            }
        }
    }
}

/** Vault sizes are smaller than disks, so MB matters before GB does. */
private fun formatVaultSize(kb: Long): String = when {
    kb >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f GB", kb / 1_048_576.0)
    kb >= 1024 -> "${kb / 1024} MB"
    else -> "$kb KB"
}

/**
 * `magisk -v` welds three fields into one string: `30.7.5:MAGISK:R` is version,
 * manager and channel. Printed raw that tells the user nothing they can act on,
 * so it reads as `Magisk 30.7.5`.
 *
 * A manager this build has no name for is passed through unchanged rather than
 * swallowed — a version string is diagnostic output, and a manager the app
 * cannot parse should still be identifiable on screen.
 */
private fun rootLabel(root: MainViewModel.RootInfo?, hasRoot: Boolean): String {
    if (root == null) return if (hasRoot) "Granted" else "—"
    val raw = root.managerVersion.trim()
    if (raw.isBlank()) return "Granted"
    val version = raw.substringBefore(':').trim()
    if (version.isBlank()) return raw
    val markers = raw.uppercase()
    val name = when {
        "MAGISK" in markers -> "Magisk"
        "KERNELSU" in markers -> "KernelSU"
        "APATCH" in markers -> "APatch"
        "SUPERUSER" in markers -> "SuperSU"
        else -> return "$version ($raw)"
    }
    return "$name $version"
}

/**
 * The home shortcuts.
 *
 * Two per row, laid out with a weight-based split rather than a fixed column
 * count, so a long tile title never pushes the grid into a horizontal scroll —
 * the failure that made the last row of actions unreachable on a narrow phone.
 *
 * Titles are one line and subtitles wrap to at most two, so the whole grid keeps
 * a predictable height and the page does not jump between rows of differing
 * text length.
 */
@Composable
private fun ShortcutGrid(
    shortcuts: List<HomeShortcut>,
    editing: Boolean,
    enabledFor: (HomeShortcut) -> Boolean,
    onOpen: (HomeShortcut) -> Unit,
    onRemove: (HomeShortcut) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    // Columns are DERIVED, not declared.
    //
    // This was `chunked(SHORTCUT_COLUMNS)` with a constant of 2, and a fixed
    // column count is only correct at one font scale: at 2x the tile is the same
    // number of dp wide and twice the type, so every title truncated — "Back up
    // a…", "Manage b…". A fixed grid cannot notice that it has stopped working,
    // because nothing about the layout changed, only the type inside it.
    //
    // So the widest title is MEASURED at the current font scale — the same
    // `rememberTextMeasurer` approach the bottom-nav labels use — and the column
    // count is the largest that still fits it. Two columns when they do, one when
    // they do not. A threshold on `fontScale` would have been the cheap version
    // and it would have been a guess about one phone.
    BoxWithConstraints {
        val gridDensity = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val titleStyle = MaterialTheme.typography.titleSmall
        val widestTitlePx = shortcuts.maxOfOrNull {
            measurer.measure(
                text = it.title,
                style = titleStyle,
                softWrap = false,
                maxLines = 1,
                density = gridDensity
            ).size.width
        } ?: 0
        // A tile is its title plus the padding either side of it. Below one tile's
        // worth of width there is nothing to measure — the title wraps instead,
        // which is what `titleLines` below arranges.
        val tileNeedsPx = widestTitlePx + with(gridDensity) { (DvSpacing.lg * 2).toPx() }
        val gapPx = with(gridDensity) { DvSpacing.md.toPx() }
        val fitPair = with(gridDensity) { maxWidth.toPx() } >=
            tileNeedsPx * MAX_SHORTCUT_COLUMNS + gapPx * (MAX_SHORTCUT_COLUMNS - 1)
        val columns = if (fitPair) MAX_SHORTCUT_COLUMNS else 1

        Column(verticalArrangement = Arrangement.spacedBy(DvSpacing.md)) {
            shortcuts.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(DvSpacing.md)) {
                    row.forEachIndexed { columnInRow, item ->
                        val index = rowIndex * columns + columnInRow
                        ShortcutTile(
                            item = item,
                            modifier = Modifier.weight(1f),
                            // One line only while two tiles share the row. Given
                            // the whole width the title has room to wrap, and a
                            // truncated name is worse than a two-line one.
                            titleLines = if (columns == 2) 1 else 2,
                            enabled = enabledFor(item),
                            editing = editing,
                            onOpen = { onOpen(item) },
                            onRemove = { onRemove(item) },
                            // The nullability IS the enabled state. Reordering is a
                            // flat list, so "can this move" is index-relative and
                            // NOT row-relative: the second tile of the top row is one
                            // position from the start and can certainly move up.
                            // Gating that on rows left a live-looking arrow that did
                            // nothing when tapped.
                            onMoveUp = if (index > 0) ({ onMove(index, index - 1) }) else null,
                            onMoveDown = if (index + 1 < shortcuts.size)
                                ({ onMove(index, index + 1) }) else null
                        )
                    }
                    // Keeps a trailing odd tile the same width as a paired one.
                    repeat(columns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** At most two per row: more than that and a tile is too small to read. */
private const val MAX_SHORTCUT_COLUMNS = 2

@Composable
private fun ShortcutTile(
    item: HomeShortcut,
    modifier: Modifier,
    titleLines: Int,
    enabled: Boolean,
    editing: Boolean,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    Surface(
        shape = DvShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            // Tap opens. In edit mode the tap does nothing and the arrows below
            // take over, so a tap never competes with a reorder.
            .combinedClickable(
                enabled = enabled,
                onClick = { if (!editing) onOpen() },
                onLongClick = { if (editing) onMoveUp?.invoke() }
            )
    ) {
        Column(modifier = Modifier.padding(DvSpacing.lg)) {
            if (editing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onMoveUp?.invoke() },
                        enabled = onMoveUp != null,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp, "Move up",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onMoveDown?.invoke() },
                        enabled = onMoveDown != null,
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown, "Move down",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onRemove) {
                        Icon(
                            Icons.Default.Close, "Remove shortcut",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = dvContainer(item.accentRole()),
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            item.icon(),
                            contentDescription = null,
                            tint = dvAccent(item.accentRole()),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(DvSpacing.md))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = titleLines,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Which accent a shortcut wears, so the grid reads as one set. */
private fun HomeShortcut.accentRole(): DvRole = when (kind) {
    // Restore wore the backup colour because this mapping stopped at `kind`,
    // which cannot tell "create a backup" from "put one back". They are
    // opposite actions and the palette already has a distinct role for each.
    ShortcutTarget.MIGRATOR -> when (mode) {
        MigratorMode.RESTORE_APPS -> DvRole.Restore
        MigratorMode.BACKUP_APPS -> DvRole.Backup
        else -> DvRole.Storage
    }
    ShortcutTarget.AUTOMATION -> DvRole.Auto
    ShortcutTarget.DEVICE_DATA -> DvRole.Device
    ShortcutTarget.TAB -> DvRole.Storage
}

/** Shown before the user has chosen any shortcuts: an invitation, not an error. */
@Composable
private fun EmptyShortcutsCard(onPick: () -> Unit) {
    ElevatedCard(
        onClick = onPick,
        shape = DvShapes.card,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(DvSpacing.xl),
            verticalArrangement = Arrangement.spacedBy(DvSpacing.sm)
        ) {
            Text(
                "Add your first shortcut",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "Pin the actions you use to the front page.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Choose which actions are pinned to the front page.
 *
 * Every shortcut is listed whether or not it is currently pinned, so this is the
 * one place a removed action can be brought back — the grid only ever shows what
 * is selected, which otherwise made removal feel irreversible.
 */
@Composable
private fun ShortcutPickerDialog(
    selected: List<HomeShortcut>,
    onToggle: (HomeShortcut) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Apps, contentDescription = null) },
        title = { Text("Home shortcuts") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DvSpacing.xs)
            ) {
                HomeShortcut.entries.forEach { item ->
                    val on = selected.any { it == item }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .clickable { onToggle(item) }
                            .padding(vertical = DvSpacing.md, horizontal = DvSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            item.icon(),
                            contentDescription = null,
                            tint = dvAccent(item.accentRole()),
                            modifier = Modifier.size(20.dp)
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = DvSpacing.md)
                        ) {
                            Text(item.title, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (on) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Pinned",
                                tint = dvAccent(item.accentRole())
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onReset) { Text("Reset") } },
        shape = DvShapes.card
    )
}
