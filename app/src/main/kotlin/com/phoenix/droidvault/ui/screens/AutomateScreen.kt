package com.phoenix.droidvault.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LabelImportant
import androidx.activity.compose.rememberLauncherForActivityResult
import com.phoenix.droidvault.ui.components.AdvancedSection
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.DvShapes
import com.phoenix.droidvault.ui.theme.DvSpacing
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.ui.theme.dvContainer
import com.phoenix.droidvault.ui.components.InfoBlock
import com.phoenix.droidvault.ui.components.InfoButton
import com.phoenix.droidvault.ui.components.InfoRow
import com.phoenix.droidvault.ui.components.CompactNavRow
import com.phoenix.droidvault.ui.components.CompactRuleCard
import com.phoenix.droidvault.ui.components.DeviceDataSectionBody
import com.phoenix.droidvault.ui.components.DiscardChangesDialog
import com.phoenix.droidvault.ui.components.FolderSelectionSection
import com.phoenix.droidvault.ui.components.RuleEditorSheet
import com.phoenix.droidvault.ui.components.StickySaveBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phoenix.droidvault.utils.BackupConfigFolders
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.phoenix.droidvault.AutomationViewModel
import com.phoenix.droidvault.AutoMode
import com.phoenix.droidvault.MainViewModel
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.FallbackPolicy
import com.phoenix.droidvault.models.ScheduleType
import com.phoenix.droidvault.models.displayName
import com.phoenix.droidvault.ui.components.AppListItem
import com.phoenix.droidvault.ui.components.ShimmerAppListItem

private fun compShort(c: BackupComponent): String = when (c) {
    BackupComponent.APK -> "APK"
    BackupComponent.PRIVATE_DATA -> "DATA"
    BackupComponent.PERMISSIONS -> "PERM"
    BackupComponent.MEDIA -> "MEDIA"
    BackupComponent.ANDROID_ID -> "ID"
}

/**
 * Screen-section label. Explanations moved behind the "i" pattern
 * ([InfoRow] / TitleRow info) so screens stay clean — this is only a heading.
 */
@Composable
private fun SectionTitle(text: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 4.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Inline section label for use INSIDE cards/editors. */
@Composable
private fun SectionTitleCompact(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

/**
 * Editor section header: bold title + a collapsible "i" explanation
 * (visible only while the i is selected/held) + the grouped card below.
 */
@Composable
private fun EditorSection(
    title: String,
    info: String? = null,
    content: @Composable () -> Unit
) {
    Column {
        if (info != null) {
            InfoRow(title = title, info = info)
        } else {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(8.dp))
        ElevatedCard(shape = RoundedCornerShape(20.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

/**
 * Labeled row with an inline switch: title (+ optional one-line value) on
 * the left, control right. Hints live behind the "i" pattern, not here.
 */
@Composable
private fun LabeledSwitchRow(
    title: String,
    value: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Segmented single-selection control (Material 3 segmented-button look):
 * one clear choice per option, used for Operation / No-match fallback.
 */
@Composable
private fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { option ->
            val isSel = option == selected
            Surface(
                shape = DvShapes.nested,
                color = if (isSel) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(1f)
                    .clip(DvShapes.nested)
                    .clickable { onSelect(option) }
            ) {
                Text(
                    label(option),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSel) MaterialTheme.colorScheme.onSecondaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .padding(vertical = 10.dp)
                        .fillMaxWidth()
                )
            }
        }
    }
}

/** Unsaved-changes banner shown at the top of an editor with pending edits. */
@Composable
private fun UnsavedChangesBanner(visible: Boolean) {
    if (!visible) return
    Surface(
        shape = DvShapes.nested,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Edit,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Unsaved changes — remember to save",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * Shared configuration card for the Backup and Restore lists. One layout for
 * both so they can never drift apart again. Hierarchy, top to bottom:
 *  1. Op icon + name + operation badge (+ disabled hint) + enable switch.
 *  2. Summary line (matching apps / device data) + last-run line.
 *  3. Action row: Preview + Run share the width 50/50 so no label wraps;
 *     Duplicate/Delete live in the overflow menu. The whole card opens the
 *     editor.
 */
@Composable
private fun ConfigCard(
    title: String,
    summary: String,
    isRestore: Boolean,
    enabled: Boolean,
    runEnabled: Boolean,
    /** True only while THIS config's run is live — sibling cards keep their Run button. */
    isThisRunning: Boolean,
    onOpen: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onPreview: () -> Unit,
    onRun: () -> Unit,
    onCancel: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    lastRunText: String? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRestore) MaterialTheme.colorScheme.tertiaryContainer
                            else MaterialTheme.colorScheme.primaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isRestore) Icons.Default.Restore else Icons.Default.Backup,
                        contentDescription = if (isRestore) "Restore configuration" else "Backup configuration",
                        tint = if (isRestore) MaterialTheme.colorScheme.onTertiaryContainer
                        else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (!enabled) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Disabled",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                // Reorder within the list (persisted per operation group).
                Column {
                    IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Move up",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Move down",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            lastRunText?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onPreview,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Preview", maxLines = 1, softWrap = false)
                }
                Button(
                    onClick = if (isThisRunning) onCancel else onRun,
                    enabled = isThisRunning || runEnabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (isThisRunning) "Cancel" else if (isRestore) "Run restore" else "Run backup",
                        maxLines = 1,
                        softWrap = false
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                            onClick = { menuOpen = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Delete,
                                    null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AutomateTab(
    appState: AppState,
    appList: List<AppInfo>,
    mainViewModel: MainViewModel,
    autoViewModel: AutomationViewModel = viewModel(),
    modeOverride: AutoMode? = null
) {
    // Selective subscription: the tab scaffold only needs mode + error, so
    // run-progress ticks stop recomposing the whole tab tree (derivedStateOf
    // dedupes when nothing visible changed). Screens below re-subscribe to
    // exactly what they render.
    val autoFull by autoViewModel.uiState.collectAsStateWithLifecycle()
    // The tab scaffold only needs mode + error: model them as an equality
    // class so run-progress ticks (runStep/runProgress fire every ~100 ms
    // during a run) stop recomposing the whole tab tree. The previous
    // copy()-based wrapper allocated a new, always-unequal state object on
    // EVERY tick — the dedupe it was written for never actually happened.
    val autoState by remember { derivedStateOf { autoFull.mode to autoFull.error } }
    var loadAttempts by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        autoViewModel.refresh()
        mainViewModel.ensureInstalledApps()
    }
    // Self-heal the "sometimes empty" case: if a concurrent fetch held the guard,
    // retry a few times once it settles instead of sticking on "Loading…".
    LaunchedEffect(appList.size, appState.isFetchingApps, loadAttempts) {
        if (appList.isEmpty() && !appState.isFetchingApps && loadAttempts < 3) {
            loadAttempts++
            mainViewModel.ensureInstalledApps()
        }
    }

    autoState.second?.let { err ->
        AlertDialog(
            onDismissRequest = { autoViewModel.clearError() },
            confirmButton = { Button(onClick = { autoViewModel.clearError() }) { Text("OK") } },
            title = { Text("Automation") },
            text = { Text(err) },
            shape = DvShapes.card
        )
    }

    when (modeOverride ?: autoState.first) {
        AutoMode.MENU -> AutoMenu(autoViewModel, appList)
        AutoMode.LABELS -> LabelsScreen(autoViewModel, appList)
        AutoMode.LABEL_DETAIL -> LabelDetailScreen(autoViewModel, appList, appState, mainViewModel)
        AutoMode.CONFIGS -> ConfigsScreen(autoViewModel, appList, appState, mainViewModel)
        AutoMode.EDITOR -> ConfigEditorScreen(autoViewModel)
        AutoMode.PREVIEW -> PreviewScreen(autoViewModel, appList)
        AutoMode.RESTORE_CONFIGS -> RestoreConfigsScreen(autoViewModel, appState)
        AutoMode.RESTORE_EDITOR -> RestoreEditorScreen(autoViewModel)
        AutoMode.RESTORE_PREVIEW -> RestorePreviewScreen(autoViewModel)
        AutoMode.HISTORY -> HistoryScreen(autoViewModel)
        AutoMode.HISTORY_DETAIL -> HistoryDetailScreen(autoViewModel)
        AutoMode.SCHEDULES -> SchedulesScreen(autoViewModel, appList)
        AutoMode.APP_DETAIL -> AppDetailScreen(autoViewModel, appList)
    }
}

@Composable
private fun AutoMenu(viewModel: AutomationViewModel, appList: List<AppInfo>) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.checkStateBackup() }

    state.foundStateBackup?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissStateBackup() },
            confirmButton = {
                Button(onClick = { viewModel.restoreStateBackup() }) { Text("Restore") }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.dismissStateBackup() }) { Text("Skip") }
            },
            title = { Text("Automation backup found") },
            text = {
                Text(
                    "A saved setup (labels, configurations, assignments) was found in " +
                        "your backup folder. Restore it now?"
                )
            },
            shape = DvShapes.card
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Neutral, like every other tab's landing heading. This was the only
        // blue headline in the app, and four more blue group labels sat under it:
        // a lot of accent spent on a list of five destinations, when the colour
        // that carries meaning here is already on the icon badges.
        item {
            Text(
                "Automation",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        item {
            InfoBlock(
                title = "How it fits together",
                info = "Labels select apps. Configurations say WHAT happens to the " +
                    "selected apps: a backup config saves them to your Droid Vault " +
                    "folder, a restore config brings them back. Schedules fire any " +
                    "of them automatically. Flow: create labels → assign apps → " +
                    "build a configuration → preview → run or schedule."
            )
        }

        // Labels are the input to every configuration, not a backup step in their
        // own right, so they keep their own group.
        //
        // These group labels name a STAGE and nothing else. Each one used to carry
        // a second line describing that stage ("Group apps once, reuse them in
        // every configuration"), which said the same thing as the explainer
        // directly above it — so the page repeated itself twice before the user
        // had opened a single destination. The explainer is the right home for
        // that prose; the list below is for navigating.
        item { MenuGroupLabel("Labels") }
        item {
            CompactNavRow(
                title = "App Labels",
                icon = Icons.AutoMirrored.Filled.LabelImportant,
                role = DvRole.Auto,
                count = state.labels.size
            ) { viewModel.setMode(AutoMode.LABELS) }
        }

        item { MenuGroupLabel("Backup") }
        item {
            CompactNavRow(
                title = "Backup Configurations",
                icon = Icons.Default.Backup,
                role = DvRole.Backup,
                count = state.configs.count { it.operation != ConfigOperation.RESTORE }
            ) { viewModel.setMode(AutoMode.CONFIGS) }
        }

        item { MenuGroupLabel("Recover") }
        item {
            CompactNavRow(
                title = "Restore Configurations",
                icon = Icons.Default.Restore,
                role = DvRole.Restore,
                count = state.configs.count { it.operation == ConfigOperation.RESTORE }
            ) { viewModel.setMode(AutoMode.RESTORE_CONFIGS) }
        }

        item { MenuGroupLabel("Operate") }
        item {
            CompactNavRow(
                title = "Schedules",
                icon = Icons.Default.Schedule,
                role = DvRole.Auto,
                // Total schedules, NOT the enabled ones. Every other figure on
                // this page counts all of its kind, because each matches its row
                // title. An enabled-only count under a row titled "Schedules" is
                // a figure that contradicts its own label: three schedules with
                // none switched on would read "0". Which of them are active is a
                // property of the Schedules screen, not of this hub.
                count = state.schedules.size
            ) { viewModel.setMode(AutoMode.SCHEDULES) }
        }
        item {
            // No count: History is a log and Transfer is a pair of actions.
            // Showing "0" would imply it is empty rather than uncounted.
            CompactNavRow(
                title = "History & Transfer",
                icon = Icons.Default.History,
                role = DvRole.Info
            ) { viewModel.openHistory() }
        }
    }
}

/**
 * Group label for the Automation hub: a stage name, one word or two.
 *
 * Not `SectionTitle` — that is the editor's heading and carries a subtitle by
 * design. This one is deliberately mute, and sits closer to the rows it heads
 * than to the group above it, so it reads as labelling what follows.
 */
@Composable
private fun MenuGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp)
    )
}

/**
 * Screen heading: title + optional "i" info affordance. Back navigation is
 * OWNED BY THE APP BAR (single arrow, like every other tab) — screens in the
 * Auto tab must not render their own back arrow, which produced the double-
 * arrow chrome. Keep this a heading, not a navigation row.
 */
@Composable
private fun TitleRow(title: String, info: String? = null) {
    var shown by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (info != null) {
            InfoButton(shown = shown, onToggle = { shown = !shown }, desc = title)
        }
    }
    if (info != null && shown) {
        Text(
            info,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp)
        )
    }
}

// ---------- labels ----------

@Composable
private fun LabelsScreen(viewModel: AutomationViewModel, appList: List<AppInfo>) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TitleRow(
            "App Labels",
            info = "Labels only group apps — they never back up anything by " +
                "themselves. Configurations reference labels to pick which apps " +
                "they process."
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search labels...") }
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { newName = ""; showCreate = true },
            shape = CircleShape
        ) {
            Icon(Icons.Default.Add, null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("New label")
        }
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(
                state.labels.filter { it.name.contains(query, ignoreCase = true) },
                key = { it.id },
                contentType = { "label" }
            ) { label ->
                val count = state.assignments.count { (_, ids) -> label.id in ids }
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { viewModel.openLabel(label.id) },
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(label.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(
                                // "1 apps" on a config holding a single app.
                                com.phoenix.droidvault.models.AppParts.count(count, "app"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { renameId = label.id; renameText = label.name }) {
                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                        }
                        IconButton(onClick = { deleteId = label.id }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            confirmButton = {
                Button(onClick = { viewModel.createLabel(newName); showCreate = false }) { Text("Create") }
            },
            dismissButton = { OutlinedButton(onClick = { showCreate = false }) { Text("Cancel") } },
            title = { Text("New label") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    placeholder = { Text("e.g. FULL") }
                )
            },
            shape = DvShapes.card
        )
    }
    if (renameId != null) {
        AlertDialog(
            onDismissRequest = { renameId = null },
            confirmButton = {
                Button(onClick = { viewModel.renameLabel(renameId!!, renameText); renameId = null }) { Text("Rename") }
            },
            dismissButton = { OutlinedButton(onClick = { renameId = null }) { Text("Cancel") } },
            title = { Text("Rename label") },
            text = {
                OutlinedTextField(value = renameText, onValueChange = { renameText = it }, singleLine = true)
            },
            shape = DvShapes.card
        )
    }
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            confirmButton = {
                Button(onClick = { viewModel.deleteLabel(deleteId!!); deleteId = null }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteId = null }) { Text("Cancel") } },
            title = { Text("Delete label?") },
            text = { Text("Assignments and rules using it are removed too.") },
            shape = DvShapes.card
        )
    }
}

@Composable
private fun LabelDetailScreen(
    viewModel: AutomationViewModel,
    appList: List<AppInfo>,
    appState: AppState,
    mainViewModel: MainViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val label = state.labels.find { it.id == state.selectedLabelId }
    var query by remember { mutableStateOf("") }
    var assignMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // Mirror Backup Apps filtering: user apps by default, opt-in system tiers.
    var showSys by remember { mutableStateOf(false) }
    var showAllSys by remember { mutableStateOf(false) }

    if (label == null) {
        LaunchedEffect(Unit) { viewModel.setMode(AutoMode.LABELS) }
        return
    }
    val assigned = remember(state.assignments, label.id, appList) {
        viewModel.appsForLabel(label.id, appList)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TitleRow(
            label.name,
            info = "Apps with this label follow its configuration rules. Tap an " +
                "app for details; the X removes it from the label."
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (!assignMode) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { selected = emptySet(); assignMode = true },
                    shape = CircleShape
                ) { Text("Assign apps") }
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (assigned.isEmpty()) {
                Text(
                    "No apps assigned yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(assigned, key = { it.packageName }, contentType = { "app" }) { app ->
                    ElevatedCard(
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { viewModel.openDetail(app.packageName, appList, AutoMode.LABEL_DETAIL) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Same 50dp circle + letter fallback as AppListItem:
                            // assigned cards were text-only, which made picking
                            // out an app among many assignments slow (user
                            // request: show icons for identification).
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                val iconPath = app.iconPath
                                if (iconPath != null) {
                                    coil.compose.AsyncImage(
                                        model = iconPath,
                                        contentDescription = "App Icon",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                } else {
                                    Text(
                                        text = app.label.firstOrNull()?.uppercase() ?: "?",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 22.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.toggleAssign(app.packageName, label.id) }) {
                                Icon(Icons.Default.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps...") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    // Clear the selection only once the single bulk write has landed, so a
                    // failed persist cannot leave the user looking at an empty
                    // selection with nothing assigned.
                    onClick = {
                        viewModel.bulkAssign(selected.toList(), label.id) { _ ->
                            selected = emptySet(); assignMode = false
                        }
                    },
                    enabled = selected.isNotEmpty(),
                    shape = CircleShape
                ) { Text("Assign (${selected.size})") }
                OutlinedButton(onClick = { assignMode = false; selected = emptySet() }) { Text("Done") }
            }
            SectionTitleCompact("Tick apps, then Assign. Use search and filters to narrow the list.")
            Spacer(modifier = Modifier.height(8.dp))
            // Same tiers as Backup Apps: System (launchable) / All System.
            // Counts are live: if they read 0 while system apps exist, the
            // fetch flags (not the buttons) are the culprit.
            val sysCount = remember(appList) { appList.count { it.isSystem && it.isLaunchable } }
            val allSysCount = remember(appList) { appList.count { it.isSystem } }
            val userCount = remember(appList) { appList.count { !it.isSystem } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = showSys,
                    onClick = { showSys = !showSys; if (showSys) showAllSys = false },
                    label = { Text("System ($sysCount)") },
                    leadingIcon = { Icon(Icons.Default.Layers, null, modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = showAllSys,
                    onClick = { showAllSys = !showAllSys; if (showAllSys) showSys = false },
                    label = { Text("All ($allSysCount)") },
                    leadingIcon = { Icon(Icons.Default.Settings, null, modifier = Modifier.size(18.dp)) }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            val visible = remember(appList, query, showSys, showAllSys) {
                appList.filter { app ->
                    val matchesSearch = app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                    val matchesType = if (app.isSystem) {
                        showAllSys || (showSys && app.isLaunchable)
                    } else true
                    matchesSearch && matchesType
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "${visible.size} shown • " + when {
                        showAllSys -> "all system"
                        showSys -> "user + system"
                        else -> "user only ($userCount)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        val ids = visible.map { it.packageName }.toSet()
                        selected = if (ids.isNotEmpty() && selected.containsAll(ids)) selected - ids else selected + ids
                    },
                    enabled = visible.isNotEmpty()
                ) {
                    Text(
                        if (visible.isNotEmpty() && selected.containsAll(visible.map { it.packageName })) "Deselect all" else "Select all"
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            if (appState.isFetchingApps) {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(8) { ShimmerAppListItem() }
                }
            } else if (appList.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "No apps loaded yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { mainViewModel.ensureInstalledApps() },
                        shape = CircleShape
                    ) { Text("Reload apps") }
                }
            } else if (visible.isEmpty()) {
                Text(
                    "No apps match. Adjust search or filters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(visible, key = { it.packageName }, contentType = { "app" }) { app ->
                    val has = state.assignments[app.packageName]?.contains(label.id) == true
                    val isChecked = app.packageName in selected || has
                    Column {
                        AppListItem(
                            app = app.copy(isSelected = isChecked),
                            showBackupTime = false,
                            onToggleSelect = { pkg ->
                                selected = if (pkg in selected) selected - pkg else selected + pkg
                            }
                        )
                        val otherLabels = state.assignments[app.packageName]
                            ?.mapNotNull { id -> state.labels.find { it.id == id }?.name }
                            ?.filter { it != label.name }
                        if (!otherLabels.isNullOrEmpty()) {
                            Text(
                                "Labels: ${otherLabels.joinToString(", ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 78.dp, end = 12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- configs ----------

/** Human summary of a config's device-data selection for cards/editors. */
private fun deviceDataSummary(cfg: com.phoenix.droidvault.models.BackupConfig): String? {
    if (cfg.deviceData.isEmpty()) return null
    val items = cfg.deviceData.map { it.label }
    return if (items.size <= 3) items.joinToString(" • ")
    else "${items.take(2).joinToString(" • ")} +${items.size - 2}"
}

/** Rich empty state for the Backup/Restore configuration lists. */
@Composable
private fun ConfigsEmptyState(isRestore: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .background(
                    dvContainer(if (isRestore) DvRole.Restore else DvRole.Backup),
                    CircleShape
                )
        ) {
            Icon(
                if (isRestore) Icons.Default.Restore else Icons.Default.Backup,
                contentDescription = null,
                tint = if (isRestore) dvAccent(DvRole.Restore)
                else dvAccent(DvRole.Backup),
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            if (isRestore) "No restore configurations yet"
            else "No backup configurations yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            if (isRestore) {
                "Create one to automate restores — pick labels, device data and " +
                    "folders once, then run or schedule it after every ROM flash."
            } else {
                "Create one to automate backups — e.g. back up everything in " +
                    "your FULL label every night."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onCreate, shape = CircleShape) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(if (isRestore) "New restore configuration" else "New backup configuration")
        }
    }
}

@Composable
private fun ConfigsScreen(
    viewModel: AutomationViewModel,
    appList: List<AppInfo>,
    appState: AppState,
    mainViewModel: MainViewModel
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteId by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        TitleRow(
            "Backup Configurations",
            info = "A configuration maps labels to backup content: which parts " +
                "(APK, data, permissions, media, Android ID) to save, plus " +
                "device-wide data and folders that need no labels. Preview shows " +
                "the result before anything runs."
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.newConfig() }, shape = CircleShape) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("New backup configuration")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Backup menu only: restore configs belong to the Restore
        // Configurations screen, never in this list.
        val backupConfigs = state.configs.filter { it.operation != ConfigOperation.RESTORE }
        if (backupConfigs.isEmpty()) {
            ConfigsEmptyState(isRestore = false) { viewModel.newConfig() }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(backupConfigs, key = { it.id }, contentType = { "config" }) { cfg ->
                // matchingCount scans the whole app list per call — compute once.
                val matchCount = remember(cfg.id, appList.size) { viewModel.matchingCount(cfg, appList) }
                ConfigCard(
                    title = cfg.name.ifEmpty { "(unnamed)" },
                    summary = buildString {
                        append(matchCount)
                        append(if (matchCount == 1) " app" else " apps")
                        deviceDataSummary(cfg)?.let {
                            append(" • ")
                            append(it)
                        }
                    },
                    isRestore = false,
                    enabled = cfg.enabled,
                    runEnabled = cfg.enabled && !state.isRunning,
                    isThisRunning = state.runningConfigId == cfg.id,
                    onOpen = { viewModel.editConfig(cfg.id) },
                    onEnabledChange = { viewModel.setConfigEnabled(cfg.id, it) },
                    onPreview = {
                        viewModel.preview(
                            cfg.id, appList,
                            appState.hasRoot,
                            appState.allowPermRestore,
                            appState.allowSsaidRestore
                        )
                    },
                    onRun = { viewModel.runConfig(cfg.id, appList) },
                    onCancel = { viewModel.cancelRun() },
                    onDuplicate = { viewModel.duplicateConfig(cfg.id, "${cfg.name} copy") },
                    onDelete = { deleteId = cfg.id },
                    onMoveUp = { viewModel.moveConfig(cfg.id, up = true) },
                    onMoveDown = { viewModel.moveConfig(cfg.id, up = false) },
                    lastRunText = viewModel.lastRunLabel(cfg.id, cfg.name, isRestore = false)
                )
            }
        }
    }
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            confirmButton = {
                Button(onClick = { viewModel.deleteConfig(deleteId!!); deleteId = null }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteId = null }) { Text("Cancel") } },
            title = { Text("Delete configuration?") },
            text = { Text("Its rules are deleted. Backups on disk are untouched.") },
            shape = DvShapes.card
        )
    }
}

// ---------- restore configs ----------

@Composable
private fun RestoreConfigsScreen(
    viewModel: AutomationViewModel,
    appState: AppState
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteId by remember { mutableStateOf<String?>(null) }
    val restoreConfigs = state.configs.filter { it.operation == ConfigOperation.RESTORE }

    LaunchedEffect(Unit) { viewModel.loadRestoreApps() }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TitleRow(
            "Restore Configurations",
            info = "Restore configs drive the same --restore engine as the " +
                "Migrate tab: install APKs, then data, media, permissions and " +
                "Android ID where the backup holds them. PERM and ID need their " +
                "Safety Consents in Settings."
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.newRestoreConfig() }, shape = CircleShape) {
                Icon(Icons.Default.Add, null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("New restore configuration")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        InfoRow(
            title = "Backup folder",
            value = "${state.restoreApps.size} " +
                (if (state.restoreApps.size == 1) "app" else "apps") +
                " with backups on device" +
                (if (state.isLoadingRestoreApps) " • refreshing…" else ""),
            isPrimary = true,
            info = "Counts the app backups found in your Droid Vault folder. " +
                "A restore only covers apps present here — everything else is " +
                "skipped at run time."
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (state.isLoadingRestoreApps && state.restoreApps.isEmpty()) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(6) { ShimmerAppListItem() }
            }
        } else if (restoreConfigs.isEmpty()) {
            ConfigsEmptyState(isRestore = true) { viewModel.newRestoreConfig() }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(restoreConfigs, key = { it.id }) { cfg ->
                    ConfigCard(
                        title = cfg.name.ifEmpty { "(unnamed)" },
                        summary = buildString {
                            append(state.restoreApps.count { it.availableInBackup.isNotEmpty() })
                            append(" app backups on device")
                            deviceDataSummary(cfg)?.let {
                                append(" • ")
                                append(it)
                            }
                        },
                        isRestore = true,
                        enabled = cfg.enabled,
                        runEnabled = cfg.enabled && !state.isRunning,
                        isThisRunning = state.runningConfigId == cfg.id,
                        onOpen = { viewModel.editRestoreConfig(cfg.id) },
                        onEnabledChange = { viewModel.setConfigEnabled(cfg.id, it) },
                        onPreview = {
                            viewModel.restorePreview(
                                cfg.id,
                                appState.hasRoot,
                                appState.allowPermRestore,
                                appState.allowSsaidRestore
                            )
                        },
                        onRun = { viewModel.runRestoreConfig(cfg.id, state.restoreApps) },
                        onCancel = { viewModel.cancelRun() },
                        onDuplicate = { viewModel.duplicateRestoreConfig(cfg.id, "${cfg.name} copy") },
                        onDelete = { deleteId = cfg.id },
                        onMoveUp = { viewModel.moveConfig(cfg.id, up = true) },
                        onMoveDown = { viewModel.moveConfig(cfg.id, up = false) },
                        lastRunText = viewModel.lastRunLabel(cfg.id, cfg.name, isRestore = true)
                    )
                }
            }
        }
    }

    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            confirmButton = {
                Button(onClick = { viewModel.deleteRestoreConfig(deleteId!!); deleteId = null }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteId = null }) { Text("Cancel") } },
            title = { Text("Delete restore configuration?") },
            text = { Text("Its rules are deleted. Backups on disk are untouched.") },
            shape = DvShapes.card
        )
    }
}@Composable
private fun RestoreEditorScreen(viewModel: AutomationViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val wrap = state.editingRestoreConfig

    val cfg = wrap?.config
    if (cfg == null) {
        LaunchedEffect(Unit) { viewModel.setMode(AutoMode.RESTORE_CONFIGS) }
        return
    }
    // Focused rule sheet: null rule = add mode, non-null = editing that rule.
    var sheetRule by remember { mutableStateOf<com.phoenix.droidvault.models.ConfigRule?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    val labelName = { id: String -> state.labels.find { it.id == id }?.name ?: "(deleted)" }
    val errors = viewModel.restoreEditingErrors()
    val isNew = state.configs.none { it.id == cfg.id }
    val dirty = viewModel.restoreEditingDirty()
    val folders by viewModel.backupFolders.collectAsStateWithLifecycle()


    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        item {
            TitleRow(if (isNew) "New restore configuration" else "Edit restore configuration")
        }
        item { UnsavedChangesBanner(dirty && errors.isEmpty()) }

        // ---- Basics ----
        item {
            EditorSection("Basics", "Name this configuration so schedules stay recognizable.") {
                OutlinedTextField(
                    value = cfg.name,
                    onValueChange = { viewModel.updateRestoreEditing(name = it) },
                    singleLine = true,
                    shape = DvShapes.nested,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Name, e.g. Full Restore") }
                )
                OutlinedTextField(
                    value = cfg.description,
                    onValueChange = { viewModel.updateRestoreEditing(description = it) },
                    shape = DvShapes.nested,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Description (optional)") }
                )
                LabeledSwitchRow(
                    title = "Enabled",
                    value = if (cfg.enabled) "Runs manually or on a schedule" else "Ignored until re-enabled",
                    checked = cfg.enabled,
                    onChange = { viewModel.updateRestoreEditing(enabled = it) }
                )
                // Type is fixed by where the config was created — compact
                // metadata, not a large permanently-disabled control.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Type", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Restore installs apps and their data from the backup folder.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            "Restore",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // ---- Restore behaviour ----
        item {
            EditorSection(
                "Restore behaviour",
                "Graceful parts: restore only the parts a backup actually holds, " +
                    "like the Migrate tab. With it off, an app whose backup is " +
                    "missing any requested part is restored with nothing."
            ) {
                LabeledSwitchRow(
                    title = "Graceful parts",
                    value = if (cfg.restoreGraceful) "Restore what the backup holds" else "All-or-nothing per app",
                    checked = cfg.restoreGraceful,
                    onChange = { viewModel.updateRestoreEditing(restoreGraceful = it) }
                )
            }
        }

        // ---- Device data: ONE section; folders nested inside (spec §11/§12) ----
        item {
            EditorSection(
                "Device data",
                "Device-wide items run on every execution — no labels needed. " +
                    "Wallpaper, Messages, Wi-Fi and more. Folders are packed " +
                    "root-side, so Android/data and Android/obb are included."
            ) {
                DeviceDataSectionBody(
                    selected = cfg.deviceData,
                    onToggle = { item ->
                        viewModel.updateRestoreEditing(deviceData =
                            if (item in cfg.deviceData) cfg.deviceData - item else cfg.deviceData + item)
                    }
                )
                if (DeviceDataItem.FOLDERS in cfg.deviceData) {
                    FolderSelectionSection(
                        availableFolders = folders,
                        configFolders = cfg.folderPaths,
                        onToggleFolder = { folder ->
                            viewModel.updateRestoreEditing(folderPaths =
                                if (folder in cfg.folderPaths) cfg.folderPaths - folder else cfg.folderPaths + folder)
                        },
                        onAddFolder = { uri ->
                            com.phoenix.droidvault.utils.BackupFolderStore.pathFromTreeUri(uri)?.let { path ->
                                // Register in the shared store FIRST (Settings
                                // is the master list); only then select it for
                                // this config. A rejected path must not leak
                                // into the selection.
                                viewModel.addBackupFolder(path) { ok ->
                                        if (ok && path !in cfg.folderPaths) {
                                            viewModel.updateRestoreEditing(folderPaths = cfg.folderPaths + path)
                                        }
                                    }
                            }
                        }
                    )
                }
            }
        }

        // ---- App rules: compact rows + focused sheet editor (spec §13–§15) ----
        item {
            InfoRow(
                title = "App rules",
                value = if (cfg.rules.isEmpty()) "none yet — add one below" else "${cfg.rules.size} — top rule wins",
                isPrimary = cfg.rules.isNotEmpty(),
                info = "Each rule maps a label to content (APK, data, permissions, " +
                    "media, Android ID). When an app carries several labels, the " +
                    "topmost matching rule wins; use the arrows to reorder. " +
                    "SKIP rules exclude matching apps entirely."
            )
        }
        itemsIndexed(cfg.rules.sortedBy { it.priority }, key = { _, r -> r.id }) { idx, rule ->
            CompactRuleCard(
                index = idx + 1,
                rule = rule,
                labelName = labelName(rule.labelId),
                onEdit = { sheetRule = rule; sheetOpen = true },
                onMoveUp = { viewModel.moveRestoreRule(rule.id, true) },
                onMoveDown = { viewModel.moveRestoreRule(rule.id, false) },
                onRemove = { viewModel.removeRestoreRule(rule.id) }
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DvShapes.nested)
                    .clickable { sheetRule = null; sheetOpen = true }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Add rule",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ---- Advanced: collapsed by default (spec §16) ----
        item {
            AdvancedSection(
                summary = "No-match fallback: " +
                    if (cfg.fallback == FallbackPolicy.SKIP) "Skip" else "Use default content"
            ) {
                InfoRow(
                    title = "No-match fallback",
                    info = "Choose what happens to apps no rule selects: skip them " +
                        "entirely, or restore them with the default content (the " +
                        "parts below, as the engine defines them)."
                )
                SegmentedControl(
                    options = listOf(FallbackPolicy.SKIP, FallbackPolicy.USE_DEFAULT),
                    selected = cfg.fallback,
                    label = { if (it == FallbackPolicy.SKIP) "Skip" else "Use default" },
                    onSelect = { viewModel.updateRestoreEditing(fallback = it) }
                )
            }
        }

        if (errors.isNotEmpty()) {
            item {
                Surface(
                    shape = DvShapes.nested,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        errors.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

    }

        // Sticky save bar: only present when there is something to do.
        AnimatedVisibility(
            visible = dirty || isNew,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            StickySaveBar(
                label = if (isNew) "Create restore configuration" else "Save changes",
                enabled = errors.isEmpty(),
                onClick = { viewModel.saveRestoreEditing() }
            )
        }
    }

    RuleEditorSheet(
        visible = sheetOpen,
        title = if (sheetRule == null) "Add rule" else "Edit rule",
        labels = state.labels.map { it.id to it.name },
        initialLabelId = sheetRule?.labelId ?: state.labels.firstOrNull()?.id,
        initialComponents = sheetRule?.components ?: setOf(BackupComponent.APK),
        initialExclude = sheetRule?.exclude ?: false,
        confirmLabel = if (sheetRule == null) "Add rule" else "Save",
        onDismiss = { sheetOpen = false },
        onConfirm = { labelId, comps, exclude ->
            val existing = sheetRule
            if (existing == null) viewModel.addRestoreRule(labelId, comps, exclude)
            else viewModel.updateRestoreRule(existing.id, components = comps, exclude = exclude)
            sheetOpen = false
            sheetRule = null
        }
    )

    // Shared back guard: the app-bar arrow / predictive back set pendingBack
    // when this editor holds unsaved changes. Every entry point now honors the
    // confirm (previously only the in-content arrow did; the app-bar arrow
    // silently discarded).
    DiscardChangesDialog(
        visible = state.pendingBack,
        onKeep = { viewModel.keepEditing() },
        onDiscard = { viewModel.discardEditing() }
    )
}

@Composable
private fun RestorePreviewScreen(viewModel: AutomationViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val plan = state.restorePreviewPlan
    if (plan == null) {
        LaunchedEffect(Unit) { viewModel.setMode(AutoMode.RESTORE_CONFIGS) }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TitleRow("Dry run: ${plan.configName}")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "${plan.processable.size} restorable • ${plan.skipped.size} skipped • ${plan.withUnavailable.size} with unavailable components",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Nothing was modified. This is a preview only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                state.restorePreviewConfigId?.let { viewModel.runRestoreConfig(it, state.restoreApps) }
            },
            enabled = !state.isRunning && plan.processable.isNotEmpty(),
            shape = CircleShape
        ) {
            Text("Run restore now")
        }
        if (state.isRunning) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = { viewModel.cancelRun() },
                shape = CircleShape
            ) {
                Text("Cancel run")
            }
            TextButton(onClick = { viewModel.forceResetRun() }) {
                Text(
                    "Force reset stuck UI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${state.runAction} — ${state.runStep}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        val byPkg = remember(state.restoreApps) { state.restoreApps.associateBy { it.packageName } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val ready = plan.processable
            val skipped = plan.skipped
            if (ready.isNotEmpty()) {
                item {
                    Text(
                        "Ready for restore (${ready.size}) — tap for details",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(ready, key = { it.packageName }) { e ->
                    Box(
                        modifier = Modifier.clickable {
                            viewModel.openDetail(e.packageName, state.restoreApps, AutoMode.RESTORE_PREVIEW)
                        }
                    ) {
                        PreviewAppRow(
                            entryLabel = e.label.ifEmpty { e.packageName },
                            packageName = e.packageName,
                            app = byPkg[e.packageName],
                            status = e.granted.sortedBy { it.engineId }.joinToString(" + ") { compShort(it) },
                            ok = true
                        )
                    }
                }
            }
            if (skipped.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Skipped (${skipped.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SectionTitleCompact("Assign these apps a matching label, or change the fallback, to include them.")
                }
                items(skipped, key = { it.packageName }) { e ->
                    PreviewAppRow(
                        entryLabel = e.label.ifEmpty { e.packageName },
                        packageName = e.packageName,
                        app = byPkg[e.packageName],
                        status = e.skipReason.ifEmpty { "skipped" },
                        ok = false
                    )
                }
            }
            if (plan.withUnavailable.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Partial components (${plan.withUnavailable.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (plan.withUnavailable.any { e ->
                            e.unavailableReasons.values.any { it.startsWith("needs consent") }
                        }
                    ) {
                        Text(
                            "Tip: enable them in Settings → Safety Consents, then preview again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                items(plan.withUnavailable, key = { "ru-" + it.packageName }) { e ->
                    val reasonText = e.unavailable
                        .sortedBy { it.engineId }
                        .joinToString(", ") { c ->
                            "${compShort(c)} (${e.unavailableReasons[c] ?: "unavailable"})"
                        }
                    PreviewAppRow(
                        entryLabel = e.label.ifEmpty { e.packageName },
                        packageName = e.packageName,
                        app = byPkg[e.packageName],
                        status = "unavailable: $reasonText",
                        ok = false
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfigEditorScreen(viewModel: AutomationViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cfg = state.editingConfig
    if (cfg == null) {
        LaunchedEffect(Unit) { viewModel.setMode(AutoMode.CONFIGS) }
        return
    }
    // Focused rule sheet: null rule = add mode, non-null = editing that rule.
    var sheetRule by remember { mutableStateOf<com.phoenix.droidvault.models.ConfigRule?>(null) }
    var sheetOpen by remember { mutableStateOf(false) }
    val labelName = { id: String -> state.labels.find { it.id == id }?.name ?: "(deleted)" }
    val errors = viewModel.editingErrors()
    val isNew = state.configs.none { it.id == cfg.id }
    val dirty = viewModel.editingDirty()
    val folders by viewModel.backupFolders.collectAsStateWithLifecycle()


    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
    LazyColumn(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 4.dp)
    ) {
        item {
            TitleRow(if (isNew) "New configuration" else "Edit configuration")
        }
        item { UnsavedChangesBanner(dirty && errors.isEmpty()) }

        // ---- Basics ----
        item {
            EditorSection("Basics", "Name this configuration so schedules stay recognizable.") {
                OutlinedTextField(
                    value = cfg.name,
                    onValueChange = { viewModel.updateEditing(name = it) },
                    singleLine = true,
                    shape = DvShapes.nested,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Name, e.g. Daily Backup") }
                )
                OutlinedTextField(
                    value = cfg.description,
                    onValueChange = { viewModel.updateEditing(description = it) },
                    shape = DvShapes.nested,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Description (optional)") }
                )
                LabeledSwitchRow(
                    title = "Enabled",
                    value = if (cfg.enabled) "Runs manually or on a schedule" else "Ignored until re-enabled",
                    checked = cfg.enabled,
                    onChange = { viewModel.updateEditing(enabled = it) }
                )
                // Type is fixed by where the config was created — show it as
                // compact metadata, not a large inactive control.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Type", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Restores are configured in the Restore Configurations menu.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            "Backup",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // ---- Device data: ONE section; folders nested inside (spec §11/§12) ----
        item {
            EditorSection(
                "Device data",
                "Device-wide items run on every execution — no labels needed. " +
                    "Wallpaper, Messages, Wi-Fi and more. Folders are packed " +
                    "root-side, so Android/data and Android/obb are included."
            ) {
                DeviceDataSectionBody(
                    selected = cfg.deviceData,
                    onToggle = { item ->
                        viewModel.updateEditing(deviceData =
                            if (item in cfg.deviceData) cfg.deviceData - item else cfg.deviceData + item)
                    }
                )
                if (DeviceDataItem.FOLDERS in cfg.deviceData) {
                    FolderSelectionSection(
                        availableFolders = folders,
                        configFolders = cfg.folderPaths,
                        onToggleFolder = { folder ->
                            viewModel.updateEditing(folderPaths =
                                if (folder in cfg.folderPaths) cfg.folderPaths - folder else cfg.folderPaths + folder)
                        },
                        onAddFolder = { uri ->
                            com.phoenix.droidvault.utils.BackupFolderStore.pathFromTreeUri(uri)?.let { path ->
                                // Register in the shared store FIRST (Settings
                                // is the master list); only then select it for
                                // this config. A rejected path must not leak
                                // into the selection.
                                viewModel.addBackupFolder(path) { ok ->
                                    if (ok && path !in cfg.folderPaths) {
                                        viewModel.updateEditing(folderPaths = cfg.folderPaths + path)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        // ---- App rules: compact rows + focused sheet editor (spec §13–§15) ----
        item {
            InfoRow(
                title = "App rules",
                value = if (cfg.rules.isEmpty()) "none yet — add one below" else "${cfg.rules.size} — top rule wins",
                isPrimary = cfg.rules.isNotEmpty(),
                info = "Each rule maps a label to content (APK, data, permissions, " +
                    "media, Android ID). When an app carries several labels, the " +
                    "topmost matching rule wins; use the arrows to reorder. " +
                    "SKIP rules exclude matching apps entirely."
            )
        }
        itemsIndexed(cfg.rules.sortedBy { it.priority }, key = { _, r -> r.id }) { idx, rule ->
            CompactRuleCard(
                index = idx + 1,
                rule = rule,
                labelName = labelName(rule.labelId),
                onEdit = { sheetRule = rule; sheetOpen = true },
                onMoveUp = { viewModel.moveRule(rule.id, true) },
                onMoveDown = { viewModel.moveRule(rule.id, false) },
                onRemove = { viewModel.removeRule(rule.id) }
            )
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(DvShapes.nested)
                    .clickable { sheetRule = null; sheetOpen = true }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Add rule",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // ---- Advanced: collapsed by default (spec §16) ----
        item {
            AdvancedSection(
                summary = "No-match fallback: " +
                    if (cfg.fallback == FallbackPolicy.SKIP) "Skip" else "Use default content"
            ) {
                InfoRow(
                    title = "No-match fallback",
                    info = "Choose what happens to apps no rule selects: skip them " +
                        "entirely, or restore them with the default content (the " +
                        "parts below, as the engine defines them)."
                )
                SegmentedControl(
                    options = listOf(FallbackPolicy.SKIP, FallbackPolicy.USE_DEFAULT),
                    selected = cfg.fallback,
                    label = { if (it == FallbackPolicy.SKIP) "Skip" else "Use default" },
                    onSelect = { viewModel.updateEditing(fallback = it) }
                )
            }
        }

        if (errors.isNotEmpty()) {
            item {
                Surface(
                    shape = DvShapes.nested,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        errors.forEach {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

    }

        // Sticky save bar: only present when there is something to do —
        // never a permanently-disabled "Saved" button.
        AnimatedVisibility(
            visible = dirty || isNew,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            StickySaveBar(
                label = if (isNew) "Create configuration" else "Save changes",
                enabled = errors.isEmpty(),
                onClick = { viewModel.saveEditing() }
            )
        }
    }

    RuleEditorSheet(
        visible = sheetOpen,
        title = if (sheetRule == null) "Add rule" else "Edit rule",
        labels = state.labels.map { it.id to it.name },
        initialLabelId = sheetRule?.labelId ?: state.labels.firstOrNull()?.id,
        initialComponents = sheetRule?.components ?: setOf(BackupComponent.APK),
        initialExclude = sheetRule?.exclude ?: false,
        confirmLabel = if (sheetRule == null) "Add rule" else "Save",
        onDismiss = { sheetOpen = false },
        onConfirm = { labelId, comps, exclude ->
            val existing = sheetRule
            if (existing == null) viewModel.addRule(labelId, comps, exclude)
            else viewModel.updateRule(existing.id, components = comps, exclude = exclude)
            sheetOpen = false
            sheetRule = null
        }
    )

    // Shared back guard: the app-bar arrow / predictive back set pendingBack
    // when this editor holds unsaved changes. Every entry point now honors the
    // confirm (previously only the in-content arrow did; the app-bar arrow
    // silently discarded).
    DiscardChangesDialog(
        visible = state.pendingBack,
        onKeep = { viewModel.keepEditing() },
        onDiscard = { viewModel.discardEditing() }
    )
}

@Composable
private fun PreviewScreen(viewModel: AutomationViewModel, appList: List<AppInfo>) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val plan = state.previewPlan
    if (plan == null) {
        LaunchedEffect(Unit) { viewModel.setMode(AutoMode.CONFIGS) }
        return
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TitleRow("Dry run: ${plan.configName}")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "${plan.processable.size} processable • ${plan.skipped.size} skipped • ${plan.withUnavailable.size} with unavailable components",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Nothing was modified. This is a preview only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                state.previewConfigId?.let { viewModel.runConfig(it, appList) }
            },
            enabled = !state.isRunning && plan.processable.isNotEmpty(),
            shape = CircleShape
        ) {
            Text("Run backup now")
        }
        if (state.isRunning) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = { viewModel.cancelRun() },
                shape = CircleShape
            ) {
                Text("Cancel run")
            }
            TextButton(onClick = { viewModel.forceResetRun() }) {
                Text(
                    "Force reset stuck UI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${state.runAction} — ${state.runStep}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        val byPkg = remember(appList) { appList.associateBy { it.packageName } }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val ready = plan.processable
            val skipped = plan.skipped
            if (ready.isNotEmpty()) {
                item {
                    Text(
                        "Ready for backup (${ready.size}) — tap for details",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                items(ready, key = { it.packageName }) { e ->
                    Box(
                        modifier = Modifier.clickable {
                            viewModel.openDetail(e.packageName, appList, AutoMode.PREVIEW)
                        }
                    ) {
                        PreviewAppRow(
                            entryLabel = e.label.ifEmpty { e.packageName },
                            packageName = e.packageName,
                            app = byPkg[e.packageName],
                            status = e.granted.sortedBy { it.engineId }.joinToString(" + ") { compShort(it) },
                            ok = true
                        )
                    }
                }
            }
            if (skipped.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Skipped (${skipped.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SectionTitleCompact("Assign these apps a matching label, or change the fallback, to include them.")
                }
                items(skipped, key = { it.packageName }) { e ->
                    PreviewAppRow(
                        entryLabel = e.label.ifEmpty { e.packageName },
                        packageName = e.packageName,
                        app = byPkg[e.packageName],
                        status = e.skipReason.ifEmpty { "skipped" },
                        ok = false
                    )
                }
            }
            if (plan.withUnavailable.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Partial components (${plan.withUnavailable.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                    if (plan.withUnavailable.any { e ->
                            e.unavailableReasons.values.any { it.startsWith("needs consent") }
                        }
                    ) {
                        Text(
                            "Tip: enable them in Settings → Safety Consents, then preview again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
                items(plan.withUnavailable, key = { "u-" + it.packageName }) { e ->
                    val reasonText = e.unavailable
                        .sortedBy { it.engineId }
                        .joinToString(", ") { c ->
                            "${compShort(c)} (${e.unavailableReasons[c] ?: "unavailable"})"
                        }
                    PreviewAppRow(
                        entryLabel = e.label.ifEmpty { e.packageName },
                        packageName = e.packageName,
                        app = byPkg[e.packageName],
                        status = "unavailable: $reasonText",
                        ok = false
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewAppRow(
    entryLabel: String,
    packageName: String,
    app: AppInfo?,
    status: String,
    ok: Boolean
) {
    ElevatedCard(shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                if (app?.iconPath != null) {
                    coil.compose.AsyncImage(
                        model = app.iconPath,
                        contentDescription = "App Icon",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Text(
                        text = entryLabel.firstOrNull()?.uppercase() ?: "?",
                        color = androidx.compose.ui.graphics.Color.White,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entryLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun HistoryScreen(viewModel: AutomationViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) viewModel.importFromUri(uri)
    }

    // Fire the share chooser once per export (scoped-storage safe path).
    LaunchedEffect(state.shareUri) {
        state.shareUri?.let { uriString ->
            try {
                val uri = android.net.Uri.parse(uriString)
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(
                    android.content.Intent.createChooser(send, "Save automation backup")
                )
            } catch (_: Exception) {
            }
            viewModel.consumeShareUri()
        }
    }

    var showClearConfirm by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val historyUndoSnapshot = state.historyUndoSnapshot
    // "History cleared — UNDO": shown once per clear for 10 seconds, then it
    // is gone. The snapshot state MUST be consumed when the snackbar leaves —
    // including the leave-path where this coroutine is cancelled (navigating
    // away mid-display), which skips both result branches. Without the
    // finally, the stale snapshot resurrected the snackbar on every revisit
    // of the History screen until Undo was tapped.
    LaunchedEffect(historyUndoSnapshot) {
        if (historyUndoSnapshot != null) {
            try {
                val result = snackbarHostState.showSnackbar(
                    message = "Run history cleared",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Long // 10 s, then auto-dismiss
                )
                if (result == SnackbarResult.ActionPerformed) viewModel.undoClearHistory()
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Screen closed mid-display — drop the undo opportunity.
            } finally {
                viewModel.consumeHistoryUndo()
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TitleRow(
            "History & Transfer",
            info = "Every manual and scheduled run lands here with its per-item " +
                "result. Export/import moves your labels, rules and configuration " +
                "setup between devices — never the backups or secrets themselves."
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.exportToFile() }, shape = CircleShape) {
                Text("Export")
            }
            OutlinedButton(onClick = { picker.launch("application/json") }) {
                Text("Import")
            }
            // Cleaning: wipe all run history behind a confirm — summaries AND
            // their per-app drill-down logs.
            OutlinedButton(
                onClick = { showClearConfirm = true },
                shape = CircleShape,
                enabled = state.history.isNotEmpty()
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear")
            }
        }
        state.lastTransfer?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Filter: All / Backups / Restores (persisted operation on each run).
        var historyFilter by remember { mutableStateOf(0) }
        if (showClearConfirm) {
            AlertDialog(
                onDismissRequest = { showClearConfirm = false },
                confirmButton = {
                    Button(onClick = {
                        viewModel.clearHistory()
                        showClearConfirm = false
                    }) { Text("Clear") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
                },
                title = { Text("Clear all run history?") },
                text = {
                    Text(
                        "Every run summary and per-app log is deleted. This cannot be undone. " +
                            "Schedules, configurations and backups on disk are not touched."
                    )
                },
                shape = DvShapes.card
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = historyFilter == 0,
                onClick = { historyFilter = 0 },
                label = { Text("All") }
            )
            FilterChip(
                selected = historyFilter == 1,
                onClick = { historyFilter = 1 },
                leadingIcon = { Icon(Icons.Default.Backup, null, modifier = Modifier.size(16.dp)) },
                label = { Text("Backups") }
            )
            FilterChip(
                selected = historyFilter == 2,
                onClick = { historyFilter = 2 },
                leadingIcon = { Icon(Icons.Default.Restore, null, modifier = Modifier.size(16.dp)) },
                label = { Text("Restores") }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        val visibleHistory = remember(state.history, historyFilter) {
            when (historyFilter) {
                1 -> state.history.filter { it.operation != "RESTORE" }
                2 -> state.history.filter { it.operation == "RESTORE" }
                else -> state.history
            }
        }
        if (visibleHistory.isEmpty()) {
            HistoryEmptyState(allRunsEmpty = state.history.isEmpty())
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleHistory, key = { it.id }) { h ->
                // Persisted on the run itself (ConfigExecution.operation): the
                // old config-lookup wrongly marked a BACKUP run as restore when
                // its config shared a name with a restore config, and lost the
                // marker entirely once the config was deleted.
                val wasRestore = h.operation == "RESTORE"
                ElevatedCard(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.clickable { viewModel.openHistoryDetail(h.id) }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (wasRestore) {
                                Icon(
                                    Icons.Default.Restore,
                                    contentDescription = "Restore run",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .padding(end = 4.dp)
                                )
                            }
                            Text(
                                h.configName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                h.result,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (h.result == "SUCCESS") MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "${h.success} ok • ${h.failed} failed • ${h.skipped} skipped • ${h.trigger.lowercase()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (h.errorSummary.isNotEmpty()) {
                            Text(
                                h.errorSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
}

/**
 * Empty state for the History list: a quiet illustration + guidance. Two
 * shapes — nothing ever recorded vs. the active filter hiding every run.
 */
@Composable
private fun HistoryEmptyState(allRunsEmpty: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(96.dp)
                .background(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    CircleShape
                )
        ) {
            Icon(
                Icons.Default.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(44.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            if (allRunsEmpty) "No runs recorded yet"
            else "No runs match this filter",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            if (allRunsEmpty) {
                "Run a configuration — or wait for a schedule — and every " +
                    "backup and restore lands here with its per-item result."
            } else {
                "Try All, Backups or Restores above to see the runs you have."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

/** "2m 14s" / "1h 05m" / "45s" — per-app wall time in the run history. */
private fun formatAppDuration(ms: Long): String {
    val sec = ms / 1000
    return when {
        sec >= 3600 -> "%dh %02dm".format(java.util.Locale.US, sec / 3600, (sec % 3600) / 60)
        sec >= 60 -> "${sec / 60}m ${sec % 60}s"
        else -> "${sec}s"
    }
}

@Composable
private fun HistoryDetailScreen(viewModel: AutomationViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.clearHistoryDetailError() }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TitleRow(
            "Run Details",
            info = "Per-app / per-item result for this run (capped at 200 " +
                "entries). Runs made before this build, or cancelled runs, may " +
                "not have a log."
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (state.historyDetailApps.isEmpty()) {
            Text(
                "No per-app records kept for this run. Runs made before this build, " +
                    "or cancelled runs, may not have one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // key + contentType: this list was index-matched with no key, so any
            // change re-ran every visible row of up to 200 cards.
            items(
                items = state.historyDetailApps,
                key = { "${it.packageName}|${it.deviceItem}|${it.result.name}" },
                contentType = { "hist" }
            ) { r ->
                ElevatedCard(shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                r.appName.ifEmpty { r.packageName },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                r.result.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (r.result) {
                                    com.phoenix.droidvault.models.ResultState.SUCCESS ->
                                        MaterialTheme.colorScheme.primary
                                    com.phoenix.droidvault.models.ResultState.SKIPPED ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else -> MaterialTheme.colorScheme.error
                                }
                            )
                        }
                        if (r.deviceItem != null) {
                            Text(
                                "Device data item",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                r.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (r.durationMs > 0) {
                            Text(
                                "Took " + formatAppDuration(r.durationMs),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (r.processed.isNotEmpty()) {
                            Text(
                                "Processed: " + r.processed.joinToString(", ") { compShort(it) },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (r.reason.isNotEmpty()) {
                            Text(
                                r.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun scheduleSummary(
    s: com.phoenix.droidvault.models.AutomationSchedule,
    configNames: List<String>
): String = com.phoenix.droidvault.models.describeAutomationSchedule(s, configNames)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SchedulesScreen(viewModel: AutomationViewModel, appList: List<AppInfo>) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteId by remember { mutableStateOf<String?>(null) }
    val editing = state.editingSchedule
    val errors = if (editing != null) viewModel.scheduleErrors() else emptyList()
    val configName = { id: String -> state.configs.find { it.id == id }?.name ?: "(deleted)" }

    LaunchedEffect(Unit) {
        // refresh(): a background worker may have auto-disabled a fired ONCE
        // schedule (or recorded runs) since this screen was last open.
        viewModel.refresh()
        viewModel.loadHistory()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            TitleRow("Schedules")
        }
        item {
            ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Scheduled runs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Automatic recurring backups and restores. Turning off cancels all pending runs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        }
                        Switch(
                            checked = state.masterEnabled,
                            onCheckedChange = { viewModel.setMasterEnabled(it) }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.runAllScheduled(appList) },
                        enabled = state.masterEnabled && !state.isRunning &&
                            state.schedules.any { it.enabled },
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run all schedules")
                    }
                    if (state.isRunning) {
                        OutlinedButton(
                            onClick = { viewModel.cancelRun() },
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel run")
                        }
                    }
                    if (state.isRunning) {
                        Text(
                            "${state.runAction} — ${state.runStep}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Schedules (${state.schedules.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (editing != null) {
            item {
                ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Schedule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = editing.name,
                            onValueChange = { viewModel.updateEditingSchedule(editing.copy(name = it.take(64))) },
                            singleLine = true,
                            shape = CircleShape,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Name (optional, e.g. Nightly full)") }
                        )
                        Text(
                            "A friendly name for this entry. Blank shows the configuration name instead.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Configurations:", style = MaterialTheme.typography.bodySmall)
                        // Wraps rather than scrolls: the set is unbounded, and
                        // a horizontal scroller hid configurations past the edge
                        // with nothing to suggest they were there.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            state.configs.forEach { c ->
                                FilterChip(
                                    selected = c.id in editing.configIds,
                                    onClick = {
                                        // Multi-select: every picked config runs
                                        // in list order at fire time.
                                        val next =
                                            if (c.id in editing.configIds) editing.configIds - c.id
                                            else editing.configIds + c.id
                                        viewModel.updateEditingSchedule(editing.copy(configIds = next))
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (c.operation == ConfigOperation.RESTORE) Icons.Default.Restore else Icons.Default.Backup,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            c.name,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                        Text(
                            if (editing.configIds.size <= 1) "Pick one or more configurations — they run in the order shown."
                            else "${editing.configIds.size} configuration${if (editing.configIds.size == 1) "" else "s"} run in the order picked. Missing or disabled ones are skipped at run time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "The arrow icon marks restore configurations — their scheduled runs restore apps instead of backing them up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Type:", style = MaterialTheme.typography.bodySmall)
                        // Wraps: seven day chips stop fitting one line at a
                        // large font scale, and a scroller would push the last
                        // days off the edge of the sheet.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.phoenix.droidvault.models.ScheduleType.entries.forEach { t ->
                                FilterChip(
                                    selected = editing.type == t,
                                    onClick = { viewModel.updateEditingSchedule(editing.copy(type = t)) },
                                    label = { Text(t.name.lowercase(), style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        Text(
                            when (editing.type) {
                                com.phoenix.droidvault.models.ScheduleType.ONCE -> "Runs a single time, then disables itself. Good for testing."
                                com.phoenix.droidvault.models.ScheduleType.DAILY -> "Runs at the same local time every day."
                                com.phoenix.droidvault.models.ScheduleType.WEEKLY -> "Runs on the chosen weekdays. No days picked behaves like daily."
                                com.phoenix.droidvault.models.ScheduleType.INTERVAL -> "Repeats around the clock. Minimum 15 minutes (system limit)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (editing.type == com.phoenix.droidvault.models.ScheduleType.DAILY ||
                            editing.type == com.phoenix.droidvault.models.ScheduleType.WEEKLY
                        ) {
                            var showTimePicker by remember { mutableStateOf(false) }
                            OutlinedButton(
                                onClick = { showTimePicker = true },
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Schedule, null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(com.phoenix.droidvault.models.formatScheduleTime(editing.hour, editing.minute))
                            }
                            Text(
                                "Local time the run begins. Doze or battery savers may delay it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (showTimePicker) {
                                val timeState = androidx.compose.material3.rememberTimePickerState(
                                    initialHour = editing.hour,
                                    initialMinute = editing.minute,
                                    is24Hour = false
                                )
                                AlertDialog(
                                    onDismissRequest = { showTimePicker = false },
                                    confirmButton = {
                                        Button(onClick = {
                                            viewModel.updateEditingSchedule(
                                                editing.copy(hour = timeState.hour, minute = timeState.minute)
                                            )
                                            showTimePicker = false
                                        }) { Text("Set time") }
                                    },
                                    dismissButton = {
                                        OutlinedButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                                    },
                                    title = { Text("Start time") },
                                    text = {
                                        androidx.compose.material3.TimePicker(state = timeState)
                                    },
                                    shape = DvShapes.card
                                )
                            }
                        }
                        if (editing.type == com.phoenix.droidvault.models.ScheduleType.WEEKLY) {
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf("S", "M", "T", "W", "T", "F", "S").mapIndexed { i, d -> (i + 1) to d }
                                    .forEach { (day, d) ->
                                    FilterChip(
                                        selected = day in editing.weekdays,
                                        onClick = {
                                            val next = if (day in editing.weekdays) editing.weekdays - day else editing.weekdays + day
                                            viewModel.updateEditingSchedule(editing.copy(weekdays = next))
                                        },
                                        label = { Text(d, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }
                        if (editing.type == com.phoenix.droidvault.models.ScheduleType.INTERVAL) {
                            OutlinedTextField(
                                value = editing.intervalMinutes.toString(),
                                onValueChange = { v ->
                                    v.toLongOrNull()?.let { viewModel.updateEditingSchedule(editing.copy(intervalMinutes = it)) }
                                },
                                singleLine = true,
                                shape = CircleShape,
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text("Minutes (min 15)") }
                            )
                        }
                        if (editing.type == com.phoenix.droidvault.models.ScheduleType.ONCE) {
                            var showDatePicker by remember { mutableStateOf(false) }
                            var showOnceTimePicker by remember { mutableStateOf(false) }
                            val onceDateText = remember(editing.triggerAtMillis) {
                                if (editing.triggerAtMillis > 0) {
                                    try {
                                        java.text.SimpleDateFormat("EEE, dd MMM", java.util.Locale.getDefault())
                                            .format(java.util.Date(editing.triggerAtMillis))
                                    } catch (_: Exception) {
                                        "Pick a date"
                                    }
                                } else "Pick a date"
                            }
                            val onceTimeText = remember(editing.triggerAtMillis) {
                                if (editing.triggerAtMillis > 0) {
                                    val c = java.util.Calendar.getInstance()
                                        .apply { timeInMillis = editing.triggerAtMillis }
                                    com.phoenix.droidvault.models.formatScheduleTime(
                                        c.get(java.util.Calendar.HOUR_OF_DAY),
                                        c.get(java.util.Calendar.MINUTE)
                                    )
                                } else "Pick a time"
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { showDatePicker = true },
                                    shape = CircleShape,
                                    modifier = Modifier.weight(1f)
                                ) { Text(onceDateText) }
                                OutlinedButton(
                                    onClick = { showOnceTimePicker = true },
                                    shape = CircleShape,
                                    modifier = Modifier.weight(1f)
                                ) { Text(onceTimeText) }
                            }
                            Text(
                                "Runs once at the chosen date and time, then disables itself.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (showDatePicker) {
                                val dateState =
                                    androidx.compose.material3.rememberDatePickerState(
                                        initialSelectedDateMillis = editing.triggerAtMillis
                                            .takeIf { it > 0 } ?: System.currentTimeMillis()
                                    )
                                androidx.compose.material3.DatePickerDialog(
                                    onDismissRequest = { showDatePicker = false },
                                    confirmButton = {
                                        Button(onClick = {
                                            dateState.selectedDateMillis?.let { picked ->
                                                val c = java.util.Calendar.getInstance()
                                                    .apply { timeInMillis = editing.triggerAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis() }
                                                val target = java.util.Calendar.getInstance()
                                                    .apply { timeInMillis = picked }
                                                c.set(java.util.Calendar.YEAR, target.get(java.util.Calendar.YEAR))
                                                c.set(java.util.Calendar.DAY_OF_YEAR, target.get(java.util.Calendar.DAY_OF_YEAR))
                                                if (c.timeInMillis < System.currentTimeMillis()) {
                                                    c.timeInMillis = System.currentTimeMillis() + 60000
                                                }
                                                viewModel.updateEditingSchedule(editing.copy(triggerAtMillis = c.timeInMillis))
                                            }
                                            showDatePicker = false
                                        }) { Text("Set date") }
                                    },
                                    dismissButton = {
                                        OutlinedButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                                    }
                                ) {
                                    androidx.compose.material3.DatePicker(state = dateState)
                                }
                            }
                            if (showOnceTimePicker) {
                                val onceCal = java.util.Calendar.getInstance().apply {
                                    timeInMillis = editing.triggerAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                                }
                                val onceTimeState =
                                    androidx.compose.material3.rememberTimePickerState(
                                        initialHour = onceCal.get(java.util.Calendar.HOUR_OF_DAY),
                                        initialMinute = onceCal.get(java.util.Calendar.MINUTE),
                                        is24Hour = false
                                    )
                                AlertDialog(
                                    onDismissRequest = { showOnceTimePicker = false },
                                    confirmButton = {
                                        Button(onClick = {
                                            val c = java.util.Calendar.getInstance().apply {
                                                timeInMillis = editing.triggerAtMillis.takeIf { it > 0 } ?: System.currentTimeMillis()
                                                set(java.util.Calendar.HOUR_OF_DAY, onceTimeState.hour)
                                                set(java.util.Calendar.MINUTE, onceTimeState.minute)
                                                set(java.util.Calendar.SECOND, 0)
                                                set(java.util.Calendar.MILLISECOND, 0)
                                                if (timeInMillis < System.currentTimeMillis()) {
                                                    add(java.util.Calendar.DAY_OF_YEAR, 1)
                                                }
                                            }
                                            viewModel.updateEditingSchedule(editing.copy(triggerAtMillis = c.timeInMillis))
                                            showOnceTimePicker = false
                                        }) { Text("Set time") }
                                    },
                                    dismissButton = {
                                        OutlinedButton(onClick = { showOnceTimePicker = false }) { Text("Cancel") }
                                    },
                                    title = { Text("Run time") },
                                    text = {
                                        androidx.compose.material3.TimePicker(state = onceTimeState)
                                    },
                                    shape = DvShapes.card
                                )
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Charging only", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = editing.requiresCharging,
                                onCheckedChange = { viewModel.updateEditingSchedule(editing.copy(requiresCharging = it)) }
                            )
                        }
                        Text(
                            "When on, the run waits for the charger instead of running on battery. Off means run anytime.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Unmetered net only", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = editing.requiresUnmetered,
                                onCheckedChange = { viewModel.updateEditingSchedule(editing.copy(requiresUnmetered = it)) }
                            )
                        }
                        Text(
                            "Backups stay on device, so this rarely matters. On for strict metered-data safety.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Battery not low", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Switch(
                                checked = editing.requiresBatteryNotLow,
                                onCheckedChange = { viewModel.updateEditingSchedule(editing.copy(requiresBatteryNotLow = it)) }
                            )
                        }
                        Text(
                            "Skips the run while battery saver is active. Recommended for large backups.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        errors.forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.saveEditingSchedule() },
                                enabled = errors.isEmpty(),
                                shape = CircleShape
                            ) { Text("Save") }
                            OutlinedButton(onClick = { viewModel.cancelEditingSchedule() }) { Text("Cancel") }
                        }
                    }
                }
            }
        } else {
            item {
                // A schedule runs a configuration, so with none there is nothing to
                // schedule. The button used to be greyed with no explanation and no
                // route forward — a beginner had no way to know a configuration had
                // to exist first, or where to make one.
                if (state.configs.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(DvSpacing.sm)
                    ) {
                        Text(
                            "Create a configuration first",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "A schedule runs a configuration automatically, so there is " +
                                "nothing to schedule until one exists.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = { viewModel.setMode(AutoMode.CONFIGS) },
                            shape = CircleShape
                        ) {
                            Icon(Icons.Default.Add, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Go to Backup Configurations")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            viewModel.newSchedule(
                                listOfNotNull(state.configs.firstOrNull()?.id)
                            )
                        },
                        shape = CircleShape
                    ) {
                        Icon(Icons.Default.Add, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("New schedule")
                    }
                }
            }
        }
        // Nothing under the heading used to look like a broken screen rather than
        // an empty one.
        if (state.schedules.isEmpty() && editing == null && state.configs.isNotEmpty()) {
            item {
                Text(
                    "No schedules yet. Add one and Droid Vault runs your configurations " +
                        "on their own, even when the app is closed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = DvSpacing.sm)
                )
            }
        }
        items(state.schedules, key = { it.id }) { s ->
            val schedCfg = state.configs.find { it.id == s.configIds.firstOrNull() }
            val schedNames = s.configIds.map { configName(it) }
            ScheduleCard(
                index = state.schedules.indexOf(s) + 1,
                configName = s.displayName(schedNames),
                isRestore = schedCfg?.operation == ConfigOperation.RESTORE,
                summary = scheduleSummary(s, schedNames),
                schedule = s,
                lastRun = viewModel.latestFor(
                    s.configIds.firstOrNull() ?: "",
                    schedCfg?.name ?: ""
                ),
                onEdit = { viewModel.editSchedule(s.id) },
                onToggle = { viewModel.setScheduleEnabled(s.id, it) },
                onRunNow = { viewModel.runScheduleNow(s.id) },
                onDelete = { deleteId = s.id },
                runNowEnabled = !state.isRunning
            )
        }
    }
    if (deleteId != null) {
        AlertDialog(
            onDismissRequest = { deleteId = null },
            confirmButton = {
                Button(onClick = { viewModel.deleteSchedule(deleteId!!); deleteId = null }) { Text("Delete") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteId = null }) { Text("Cancel") } },
            title = { Text("Delete schedule?") },
            text = { Text("Pending runs are cancelled.") },
            shape = DvShapes.card
        )
    }
}

private fun formatRunTime(millis: Long): String {
    if (millis <= 0) return "Never"
    return try {
        java.text.SimpleDateFormat("dd MMM, hh:mm a", java.util.Locale.getDefault())
            .format(java.util.Date(millis))
    } catch (_: Exception) {
        "Unknown"
    }
}

@Composable
private fun ScheduleCard(
    index: Int,
    configName: String,
    isRestore: Boolean,
    summary: String,
    schedule: com.phoenix.droidvault.models.AutomationSchedule,
    lastRun: com.phoenix.droidvault.utils.AutomationStore.ExecutionSummary?,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
    runNowEnabled: Boolean = true
) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable { onEdit() },
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (schedule.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isRestore) Icons.Default.Restore else Icons.Default.Schedule,
                        contentDescription = null,
                        tint = if (schedule.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRestore) {
                            Icon(
                                Icons.Default.Restore,
                                contentDescription = "Restore schedule",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(end = 4.dp)
                            )
                        }
                        Text(configName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = schedule.enabled, onCheckedChange = onToggle)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Options")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Run now") },
                            onClick = { menuOpen = false; onRunNow() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (schedule.enabled) "Turn off" else "Turn on") },
                            onClick = { menuOpen = false; onToggle(!schedule.enabled) }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            ScheduleOptionRow(
                icon = Icons.Default.Schedule,
                title = "START TIME",
                value = startTimeText(schedule),
                hint = "Local time the run begins. Doze or battery savers may delay it.",
                role = DvRole.Auto
            )
            ScheduleOptionRow(
                icon = Icons.Default.Refresh,
                title = "REPEAT",
                value = repeatText(schedule),
                hint = "How often it recurs. Intervals under 15 min are raised to 15.",
                role = DvRole.Auto
            )
            ScheduleOptionRow(
                icon = Icons.Default.Storage,
                // Was "BACKUP LOCATION / Device". Read as "where backups go" rather
                // than "which folder", and actively misleading for anyone whose
                // vault sits on an SD card — the app has three names for this folder
                // (Backup location / Vault Location / Droid Vault Directory) and
                // this row was the one that stated a fact instead of naming it.
                title = "BACKUP FOLDER",
                value = "Your Droid Vault folder",
                hint = "Always the folder you chose for Droid Vault. Cloud and NAS are not supported.",
                role = DvRole.Storage
            )
            val conds = buildList {
                if (schedule.requiresCharging) add("charging")
                if (schedule.requiresUnmetered) add("unmetered net")
                if (schedule.requiresBatteryNotLow) add("battery ok")
            }
            if (conds.isNotEmpty()) {
                ScheduleOptionRow(
                    icon = Icons.Default.Check,
                    title = "CONDITIONS",
                    value = conds.joinToString(", "),
                    hint = "The run waits until every condition holds.",
                    role = DvRole.Success
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                (if (schedule.enabled) "Active — " else "Paused — ") +
                    (lastRun?.let { "last run " + formatRunTime(it.startedAt) + " • " + it.result.lowercase() } ?: "never ran"),
                style = MaterialTheme.typography.bodySmall,
                color = if (schedule.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 52.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Visible one-tap "run this schedule immediately" — previously only
            // reachable through the card's overflow menu, where it went unnoticed.
            OutlinedButton(
                onClick = onRunNow,
                enabled = runNowEnabled,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Run now")
            }
        }
    }
}

private fun startTimeText(s: com.phoenix.droidvault.models.AutomationSchedule): String =
    com.phoenix.droidvault.models.scheduleWhenText(s)

private fun repeatText(s: com.phoenix.droidvault.models.AutomationSchedule): String =
    com.phoenix.droidvault.models.scheduleRepeatText(s)

@Composable
private fun ScheduleOptionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    value: String,
    hint: String,
    role: DvRole = DvRole.Auto
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(dvContainer(role)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = dvAccent(role), modifier = Modifier.size(22.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AppDetailScreen(viewModel: AutomationViewModel, appList: List<AppInfo>) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val detail = state.detail

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            TitleRow(detail?.app?.label ?: "App details")
        }
        if (state.detailLoading || detail == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(5) { ShimmerAppListItem() }
                }
            }
            return@LazyColumn
        }
        val app = detail.app
        item {
            ElevatedCard(shape = RoundedCornerShape(20.dp)) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        if (app.iconPath != null) {
                            coil.compose.AsyncImage(
                                model = app.iconPath,
                                contentDescription = "App Icon",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            Text(
                                text = app.label.firstOrNull()?.uppercase() ?: "?",
                                color = androidx.compose.ui.graphics.Color.White,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            app.label,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${app.version} (${app.versionCode})" +
                                if (detail.frozen) " • frozen" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (detail.frozen) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (app.isLaunchable) {
                    Button(
                        onClick = { viewModel.launchApp(context, app.packageName) },
                        shape = CircleShape
                    ) { Text("Launch") }
                }
                Button(
                    onClick = { viewModel.toggleFreeze(app.packageName, appList) },
                    shape = CircleShape
                ) { Text(if (detail.frozen) "Unfreeze" else "Freeze") }
            }
            Text(
                "Freeze disables the app reversibly (safer than uninstall).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Text("Labels", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            // Wraps: the label set is unbounded and a scroller hid labels
            // past the edge with no affordance.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.labels.forEach { l ->
                    val has = state.assignments[app.packageName]?.contains(l.id) == true
                    FilterChip(
                        selected = has,
                        onClick = { viewModel.toggleAssign(app.packageName, l.id) },
                        label = { Text(l.name, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            if (state.labels.isEmpty()) {
                Text(
                    "No labels yet — create one on the Labels screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            InfoRow(
                title = "Backup parts",
                value = if (detail.parts.isEmpty()) "none" else "${detail.parts.count { it.present }} of ${detail.parts.size} on device",
                isPrimary = detail.parts.any { it.present },
                info = "Sizes from the backup stored on this device. Missing parts " +
                    "were never backed up — run a backup configuration with the " +
                    "corresponding content selected to create them."
            )
        }
        if (!detail.backupDirExists) {
            item {
                Text(
                    "No backup on device for this app.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        items(detail.parts, key = { it.fileName }) { part ->
            ElevatedCard(
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.CardDefaults.elevatedCardColors(
                    containerColor = if (part.present) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (part.present) Icons.Default.Check else Icons.Default.Close,
                        null,
                        // Presence is a state, not a brand: green means "this
                        // part is on the device", neutral means it is not. Using
                        // scheme primary here made a factual check read as a
                        // decorative accent.
                        tint = if (part.present) dvAccent(DvRole.Success)
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        part.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        com.phoenix.droidvault.utils.AppDetailLoader.formatBytes(part.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item {
            Text("Info", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                DetailInfoRow("Type", if (app.isSystem) "System" else "User")
                DetailInfoRow("Backup", app.backupTime.ifEmpty { "No backup on device" })
                DetailInfoRow("Android ID", if (detail.meta.containsKey("SSAID")) "saved" else "—")
                detail.meta["SysPath"]?.let { DetailInfoRow("System path", it) }
            }
        }
        item {
            Text(
                "Granted (${detail.granted.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                if (detail.granted.isEmpty()) "None recorded — back up with Permissions to fill this."
                else detail.granted.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Text("Denied (${detail.denied.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                if (detail.denied.isEmpty()) "None." else detail.denied.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailInfoRow(title: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}
