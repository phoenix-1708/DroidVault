package com.phoenix.droidvault.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.DvShapes
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.DeviceDataItem

/**
 * Shared configuration-UI building blocks (Backup + Restore Configurations,
 * their editors and the Automation menu). One implementation per widget so
 * the two sides of the system can never drift apart again.
 */

/** Compact section header: bold title + optional one-line hint. */

/**
 * Wrapping chip group (FlowRow): chips flow to the next line on narrow
 * screens — never clipped, never off-screen (spec §11/§22).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> FlowChipGroup(
    items: List<T>,
    selected: (T) -> Boolean,
    label: (T) -> String,
    onToggle: (T) -> Unit,
    enabled: (T) -> Boolean = { true },
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items.forEach { item ->
            FilterChip(
                selected = selected(item),
                enabled = enabled(item),
                onClick = { onToggle(item) },
                label = { Text(label(item), style = MaterialTheme.typography.labelMedium) }
            )
        }
    }
}

/** Short component name for rule summaries/chips. */
fun compShortLabel(c: BackupComponent): String = when (c) {
    BackupComponent.APK -> "APK"
    BackupComponent.PRIVATE_DATA -> "DATA"
    BackupComponent.PERMISSIONS -> "PERM"
    BackupComponent.MEDIA -> "MEDIA"
    BackupComponent.ANDROID_ID -> "ID"
}

/** One-line component summary for a rule ("APK + DATA + PERM"). */
fun ruleSummary(rule: ConfigRule): String =
    if (rule.exclude) "matching apps are skipped"
    else rule.components.sortedBy { it.engineId }
        .joinToString(" + ") { compShortLabel(it) }
        .ifEmpty { "no content selected" }

/**
 * Compact rule row (spec §13/§15): "#1  APK — APK + DATA" with a drag-handle
 * glyph, priority up/down buttons (the accessible reorder path), delete, and
 * a chevron. Tapping the row opens the shared rule editor sheet. Collapsed by
 * design — the full content-chip editor lives in [RuleEditorSheet].
 */
@Composable
fun CompactRuleCard(
    index: Int,
    rule: ConfigRule,
    labelName: String,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onEdit)
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.DragHandle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .alpha(0.55f)
                    .padding(horizontal = 2.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "#$index  $labelName" + if (rule.exclude) "  •  SKIP" else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    ruleSummary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onMoveUp, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = "Move rule up",
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onMoveDown, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "Move rule down",
                    modifier = Modifier.size(20.dp)
                )
            }
            DeleteIconButton(onDelete = onRemove, contentDescription = "Remove rule")
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 2.dp)
            )
        }
    }
}

/**
 * Focused add/edit-rule sheet (spec §14): label + content chips in wrapping
 * rows, a SKIP toggle, and Cancel/Add (or Save). Add/Save stays disabled
 * until the choice is valid. Used for both creating and editing rules.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorSheet(
    visible: Boolean,
    title: String,
    labels: List<Pair<String, String>>, // id -> name
    initialLabelId: String?,
    initialComponents: Set<BackupComponent>,
    initialExclude: Boolean,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (labelId: String, components: Set<BackupComponent>, exclude: Boolean) -> Unit
) {
    if (!visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var labelId by remember(visible) { mutableStateOf(initialLabelId) }
    var comps by remember(visible) { mutableStateOf(initialComponents) }
    var exclude by remember(visible) { mutableStateOf(initialExclude) }
    val valid = labelId != null && (exclude || comps.isNotEmpty())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Column {
                Text(
                    "Label",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                // A rule is meaningless without a label, and `valid` requires one —
                // so with no labels defined this row used to render as the word
                // "Label" above nothing, with "Add rule" dead below it and no
                // reason anywhere (seen on device: App Labels = 0). A button that
                // is disabled must say what would enable it.
                if (labels.isEmpty()) {
                    Text(
                        "No labels yet. Create one under Automation → App Labels first.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowChipGroup(
                        items = labels,
                        selected = { it.first == labelId },
                        label = { it.second },
                        onToggle = { labelId = it.first }
                    )
                }
            }
            Column {
                Text(
                    "Content",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                FlowChipGroup(
                    items = BackupComponent.entries,
                    selected = { it in comps },
                    label = { compShortLabel(it) },
                    onToggle = { c -> comps = if (c in comps) comps - c else comps + c },
                    enabled = { !exclude }
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = exclude,
                        onClick = { exclude = !exclude },
                        label = { Text("SKIP — exclude matching apps") }
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f)
                ) { Text("Cancel") }
                Button(
                    onClick = {
                        labelId?.let { id -> onConfirm(id, comps, exclude) }
                    },
                    enabled = valid,
                    shape = CircleShape,
                    modifier = Modifier.weight(1f)
                ) { Text(confirmLabel) }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

/**
 * Collapsed-by-default Advanced section (spec §16): one summary row with the
 * current value; expanding reveals the controls.
 */
@Composable
fun AdvancedSection(
    summary: String,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, label = "advancedChevron")
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clip(RoundedCornerShape(16.dp))
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Advanced",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(rotation)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    content = content
                )
            }
        }
    }
}

/**
 * Sticky save bar (spec §18): pinned under the scrolling editor content —
 * never covers it, respects navigation-bar insets. Label reflects state;
 * disabled when there is nothing to save or validation fails.
 */
@Composable
fun StickySaveBar(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Button(
                onClick = onClick,
                enabled = enabled,
                shape = CircleShape,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) { Text(label, style = MaterialTheme.typography.titleMedium) }
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }
}

/**
 * Compact navigation row for the Automation hub: deliberately not a big button.
 *
 * The trailing slot carries a **count**, not a sentence. It used to be a subtitle
 * that welded a figure to a description with an em dash — "2 labels - group apps
 * for configurations" — which buried the only scannable number on the page in the
 * middle of prose, and repeated what the row title and its group label already
 * said. A right-aligned figure is read without being read; a clause has to be.
 *
 * A count of `null` means there is genuinely nothing to count (History has no
 * count), so the slot collapses rather than showing a misleading `0`.
 */
@Composable
fun CompactNavRow(
    title: String,
    icon: ImageVector,
    role: DvRole? = null,
    count: Int? = null,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DvIconBadge(
                role = role,
                icon = icon,
                containerSize = 40.dp,
                iconSize = 22.dp
            )
            Spacer(modifier = Modifier.width(14.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (count != null) {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Read as a quantity, not a stray digit.
                    modifier = Modifier.semantics { contentDescription = "$count in $title" }
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Discard-changes confirmation (spec §19). Only shown when edits exist. */
@Composable
fun DiscardChangesDialog(
    visible: Boolean,
    onKeep: () -> Unit,
    onDiscard: () -> Unit
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("Discard changes?") },
        text = { Text("Your changes haven't been saved.") },
        confirmButton = {
            TextButton(
                onClick = onDiscard,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) { Text("Discard") }
        },
        dismissButton = { TextButton(onClick = onKeep) { Text("Keep editing") } },
        shape = DvShapes.card
    )
}

/** Destructive icon button shared by rule rows (visibly destructive). */
@Composable
fun DeleteIconButton(onDelete: () -> Unit, contentDescription: String) {
    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
        Icon(
            Icons.Default.Delete,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp)
        )
    }
}

/**
 * Device-data chip selector body (no embedded title — the section header
 * belongs to the caller, so the header can never be duplicated again).
 * Wrapping FlowRow: every chip is always reachable (no clipped LazyRow).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceDataSectionBody(
    selected: Set<DeviceDataItem>,
    onToggle: (DeviceDataItem) -> Unit
) {
    FlowChipGroup(
        items = DeviceDataItem.entries,
        selected = { it in selected },
        label = { it.label },
        onToggle = onToggle
    )
}

/**
 * Folder selection block shown inside the Device data section when Folders
 * is enabled: checkbox rows with full paths plus an Add-folder launcher.
 */
@Composable
fun FolderSelectionSection(
    availableFolders: List<String>,
    configFolders: List<String>,
    onToggleFolder: (String) -> Unit,
    onAddFolder: ((android.net.Uri) -> Unit)?
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Packed root-side, so Android/data and Android/obb are included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (availableFolders.isEmpty() && configFolders.isEmpty()) {
            Text(
                "No folders registered yet — add one below or in Settings → Backup Folders.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // Display context for same-named disambiguation: computed ONCE per
        // composition before the rows render, not mutated inside each row
        // (a composition-time global write is order-dependent).
        val displayPaths = remember(availableFolders, configFolders) {
            (availableFolders + configFolders).distinct()
        }
        com.phoenix.droidvault.utils.BackupConfigFolders.setDisplayContext(displayPaths)
        displayPaths.forEach { folder ->
            val checked = folder in configFolders
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { onToggleFolder(folder) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = checked, onCheckedChange = { onToggleFolder(folder) })
                // Final folder name only ("IntegrityFIX", not the full path);
                // full path stays as content description for accessibility.
                Text(
                    com.phoenix.droidvault.utils.BackupConfigFolders.shortDisplayName(folder),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { contentDescription = folder }
                )
            }
        }
        if (onAddFolder != null) {
            val addFolderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri -> if (uri != null) onAddFolder(uri) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable { addFolderPicker.launch(null) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Add folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
