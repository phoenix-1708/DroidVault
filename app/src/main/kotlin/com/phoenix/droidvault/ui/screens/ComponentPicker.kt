package com.phoenix.droidvault.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.ui.theme.DvMotion

private fun partIcon(id: Int): ImageVector = when (id) {
    AppParts.APP -> Icons.Default.Android
    AppParts.DATA -> Icons.Default.Storage
    AppParts.MEDIA -> Icons.Default.PermMedia
    AppParts.PERMISSIONS -> Icons.Default.Security
    AppParts.SSAID -> Icons.Default.Smartphone
    else -> Icons.Default.Storage
}

/**
 * Every component option in one block, with no horizontal scrolling.
 *
 * These were a LazyRow of labelled chips, which put five pills plus an action
 * past the width of a phone: the last three were only reachable by swiping a
 * row with no affordance saying there was more. A [FlowRow] wraps instead, so
 * the whole set is visible at once and every pill keeps its name.
 *
 * The pills here are the DEFAULT for apps selected from now on. Per-app choices
 * live on the app's own page, and "Apply all" is the one explicit way to push
 * the default onto apps that are already selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ComponentPickerCard(
    isRestore: Boolean,
    defaultComponents: Set<Int>,
    matchesAllSelected: Boolean,
    hasSelection: Boolean,
    onToggleComponent: (Int) -> Unit,
    onApplyAll: () -> Unit,
    onShowHelp: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (isRestore) "What to restore" else "What to back up",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Default for newly selected apps",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                InfoButton(onClick = onShowHelp)
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = onApplyAll, enabled = hasSelection) {
                    Text("Apply all")
                }
            }

            Spacer(Modifier.padding(top = 4.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AppParts.ORDER.forEach { id ->
                    PartPill(
                        id = id,
                        selected = defaultComponents.contains(id),
                        onClick = { onToggleComponent(id) }
                    )
                }
            }

            Spacer(Modifier.padding(top = 8.dp))
            Text(
                when {
                    hasSelection && !matchesAllSelected ->
                        "Some apps use different parts. Tap an app to change its own."
                    hasSelection ->
                        "Every selected app uses these parts."
                    else ->
                        "Tap an app in the list to choose different parts for it."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoButton(onClick: () -> Unit) {
    BoxedIcon(
        icon = Icons.Default.Info,
        contentDescription = "How components work",
        onClick = onClick
    )
}

@Composable
private fun BoxedIcon(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * One component toggle.
 *
 * A check mark rides in beside the label so the selected state does not rely on
 * colour alone — the pills sit on a tinted surface and colour-blind users need
 * a second channel.
 */
@Composable
private fun PartPill(id: Int, selected: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHigh,
        animationSpec = DvMotion.settle(),
        label = "partBg"
    )
    val content by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = DvMotion.settle(),
        label = "partFg"
    )
    // A small pop when a part is switched on: the row is dense enough that a
    // colour change alone is easy to miss.
    val pop by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = DvMotion.Springs.overshoot(),
        label = "partPop"
    )

    Surface(
        shape = CircleShape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .scale(pop)
            .clip(CircleShape)
            .clickable(role = Role.Checkbox, onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
            }
            Icon(partIcon(id), contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                AppParts.shortLabel(id),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

/**
 * Explains the whole model in one place.
 *
 * The rules are non-obvious — a default that only affects future selections, a
 * separate apply-to-all, per-app overrides, and two consent gates — and they
 * were previously discoverable only by reading the code or by being surprised.
 */
@Composable
fun ComponentHelpDialog(isRestore: Boolean, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Info, contentDescription = null) },
        title = { Text(if (isRestore) "How restoring works" else "How backing up works") },
        text = {
            Column(
                // Scrollable: at a 2x font scale this content is taller than the
                // dialog, and a non-scrollable column would clip the last rows
                // with no way to reach them.
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "An app can be saved in five separate parts. You choose which " +
                            "ones to use, and the parts are restored independently.",
                    style = MaterialTheme.typography.bodyMedium
                )
                AppParts.ORDER.forEach { id ->
                    Row {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            modifier = Modifier.size(30.dp)
                        ) {
                            androidx.compose.foundation.layout.Box(
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    partIcon(id),
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                AppParts.label(id),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                AppParts.description(id),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                HorizontalNote()
                Bullet("These pills set the default for apps you select from now on.")
                Bullet(
                    "They never change an app you already picked. Use Apply all " +
                            "if you want them on everything you have selected."
                )
                Bullet(
                    "To give one app different parts, tap that app in the list and " +
                            "choose there. Each app then keeps its own choice."
                )
                Bullet(
                    "Permissions and Android ID are consent-gated. If they are " +
                            "switched off in Settings they cannot be restored, and " +
                            "they will say so here."
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•  ", style = MaterialTheme.typography.bodySmall)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HorizontalNote() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            "Sizes shown are what that part takes on this device. Permissions and " +
                    "Android ID are tiny text files.",
            modifier = Modifier.padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
