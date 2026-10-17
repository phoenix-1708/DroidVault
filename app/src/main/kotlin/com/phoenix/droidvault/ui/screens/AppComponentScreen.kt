package com.phoenix.droidvault.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.ui.theme.DvMotion

private fun compIcon(id: Int): ImageVector = when (id) {
    AppParts.APP -> Icons.Default.Android
    AppParts.DATA -> Icons.Default.Storage
    AppParts.MEDIA -> Icons.Default.PermMedia
    AppParts.PERMISSIONS -> Icons.Default.Security
    AppParts.SSAID -> Icons.Default.Smartphone
    else -> Icons.Default.Storage
}

/**
 * Per-app component page.
 *
 * The old design asked for two decisions in two places — tick the app in the
 * list, then find the right icon chip in a row of five global toggles — with no
 * way to see what one app would actually get. This page collapses both into one
 * place: every part the app can be backed up or restored with, with its own
 * size, plus the total, and it tells the user why a part is unavailable rather
 * than hiding it.
 */
@Composable
fun AppComponentScreen(
    app: AppInfo,
    mode: MigratorMode,
    selected: Set<Int>,
    selectable: Set<Int>,
    freeKb: Long,
    allowPermissions: Boolean,
    allowSsaid: Boolean,
    onToggle: (Int) -> Unit,
    onSetAll: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val isRestore = mode == MigratorMode.RESTORE_APPS
    val estimateKb = AppParts.estimateKb(selected, app)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header -------------------------------------------------------------
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 8.dp, end = 20.dp, top = 12.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onBack),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        if (app.iconPath != null) {
                            AsyncImage(
                                model = app.iconPath,
                                contentDescription = null,
                                modifier = Modifier.size(52.dp),
                                colorFilter = if (isRestore && !app.isInstalled) {
                                    ColorFilter.colorMatrix(ColorMatrix().apply {
                                        setToSaturation(0f)
                                    })
                                } else null
                            )
                        } else {
                            Text(
                                app.label.firstOrNull()?.uppercase() ?: "?",
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            app.label,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // Body ---------------------------------------------------------------
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    if (isRestore) "Choose what to restore" else "Choose what to back up",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                SelectAllRow(
                    allSelected = selectable.isNotEmpty() && selectable.all { it in selected },
                    anySelected = selected.isNotEmpty(),
                    enabled = selectable.isNotEmpty(),
                    onToggle = {
                        val allSelected = selectable.isNotEmpty() && selectable.all { it in selected }
                        onSetAll(!allSelected)
                    }
                )
            }

            // An app whose archive holds nothing this mode can restore would
            // otherwise render five permanently dead rows with no explanation.
            // Say what happened and what would fix it.
            if (selectable.isEmpty()) {
                item { NothingRestorableCard(isRestore, allowPermissions, allowSsaid) }
            } else {
                items(AppParts.ORDER, key = { it }) { id ->
                    val enabled = id in selectable
                    ComponentRow(
                        id = id,
                        enabled = enabled,
                        checked = id in selected,
                        sizeKb = AppParts.sizeKb(id, app),
                        // Say WHY a part is unavailable. A permanently disabled
                        // row with no explanation reads as a bug; on restore the
                        // two reasons (nothing archived / consent withheld) need
                        // different fixes from the user.
                        blockedReason = blockedReason(
                            id = id,
                            enabled = enabled,
                            isRestore = isRestore,
                            availableInBackup = app.availableInBackup,
                            allowPermissions = allowPermissions,
                            allowSsaid = allowSsaid
                        ),
                        onToggle = { onToggle(id) }
                    )
                }
            }

            item {
                EstimateCard(
                    selected = selected,
                    estimateKb = estimateKb,
                    freeKb = freeKb,
                    isRestore = isRestore
                )
            }
        }

        // Footer -------------------------------------------------------------
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (selected.isEmpty()) "Nothing selected"
                            // One `}` too many rendered a literal brace into this
                            // line: "2 of 3 parts}". The count is the string;
                            // the wrapper around it is text too, and is written
                            // as text so it has no template to get wrong.
                            else selected.size.toString() + " of " +
                                com.phoenix.droidvault.models.AppParts.count(selectable.size, "part"),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (isRestore) "Restored from the vault archive"
                            else "Written to the vault",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.Button(
                        onClick = onBack,
                        shape = CircleShape,
                        modifier = Modifier.height(48.dp)
                    ) { Text("Done") }
                }
            }
        }
    }
}

/**
 * Why a part cannot be chosen right now, or null when it can.
 *
 * Consent and availability are separate failures with separate remedies, so
 * they get separate messages rather than one "unavailable".
 */
private fun blockedReason(
    id: Int,
    enabled: Boolean,
    isRestore: Boolean,
    availableInBackup: Set<Int>,
    allowPermissions: Boolean,
    allowSsaid: Boolean
): String? {
    if (enabled) return null
    if (isRestore && availableInBackup.isNotEmpty() && id !in availableInBackup) {
        return "Not in this backup"
    }
    val consentMissing = when (id) {
        AppParts.PERMISSIONS -> !allowPermissions
        AppParts.SSAID -> !allowSsaid
        else -> false
    }
    if (consentMissing) {
        return if (isRestore) "Turn on in Settings → Restore options"
        else "Restore consent is off"
    }
    // An empty selectable set with everything "unavailable" would be an
    // unexplained dead end; say so rather than showing five dead rows.
    return "Not available"
}

@Composable
private fun SelectAllRow(
    allSelected: Boolean,
    anySelected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (allSelected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onToggle)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = allSelected, onCheckedChange = null, enabled = enabled)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (allSelected) "Clear all" else "Select all",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (anySelected && !allSelected) {
                    Text(
                        "Some parts are selected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Shown when no part of this app can be selected at all.
 *
 * Reaching it requires a real situation — an archive holding only
 * consent-gated parts while both consents are withheld — so the card names the
 * cause and the fix rather than rendering five dead checkboxes.
 */
@Composable
private fun NothingRestorableCard(
    isRestore: Boolean,
    allowPermissions: Boolean,
    allowSsaid: Boolean
) {
    val consentBlocked = isRestore && !allowPermissions && !allowSsaid
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (consentBlocked) "Restore consent is off"
                else "Nothing to restore",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (consentBlocked) {
                    "This backup only holds permissions and Android ID, and both are " +
                        "switched off in Settings → Restore options."
                } else {
                    "This backup contains no part that can be restored."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun ComponentRow(
    id: Int,
    enabled: Boolean,
    checked: Boolean,
    sizeKb: Long,
    blockedReason: String?,
    onToggle: () -> Unit
) {
    // 0.5 rather than 0: fully invisible rows read as a rendering failure, and
    // the user still needs to see WHAT exists so they can act on the reason.
    val alpha by animateFloatAsState(
        targetValue = if (enabled) 1f else 0.5f,
        animationSpec = DvMotion.settle(),
        label = "compAlpha"
    )
    val borderColor by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = DvMotion.settle(),
        label = "compBorder"
    )

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .then(
                if (checked) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(16.dp)
                ) else Modifier
            )
            .clickable(enabled = enabled, onClick = onToggle)
            .animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .alpha(alpha),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                compIcon(id),
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        AppParts.label(id),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (blockedReason != null) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Unavailable",
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    blockedReason ?: AppParts.description(id),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    formatComponentSize(sizeKb),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
            }
        }
    }
}

@Composable
private fun EstimateCard(
    selected: Set<Int>,
    estimateKb: Long,
    freeKb: Long,
    isRestore: Boolean
) {
    // The run button disables itself on this same comparison in BOTH directions
    // (the restore target is /data, not the vault), so the page must warn in
    // both directions too. Warning only on backup would promise a restore the
    // button is about to refuse.
    val insufficient = freeKb > 0 && estimateKb > freeKb
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (isRestore) "Space needed on device" else "Space needed in vault",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatComponentSize(estimateKb),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (selected.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Pick at least one part to include this app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (insufficient) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Only ${formatComponentSize(freeKb)} free — pick fewer parts or free up space first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** Human size for a single part. Sub-KB metadata parts read as "<1 KB". */
private fun formatComponentSize(kb: Long): String = when {
    kb >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f GB", kb / 1_048_576.0)
    kb >= 1024 -> String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
    kb > 0 -> "$kb KB"
    else -> "—"
}