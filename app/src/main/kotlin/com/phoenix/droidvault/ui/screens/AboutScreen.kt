package com.phoenix.droidvault.ui.screens

import com.phoenix.droidvault.ui.safeToast
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SecurityUpdateGood
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phoenix.droidvault.BuildConfig
import com.phoenix.droidvault.R
import com.phoenix.droidvault.ui.components.DvIconBadge
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.ui.theme.dvContainer
import com.phoenix.droidvault.utils.AboutInfo
import com.phoenix.droidvault.utils.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The About destination, hosted as a sibling of Settings by [AppScaffold] (the
 * shared app bar supplies the title and the back arrow, so this file owns the
 * BODY only — no second top bar).
 *
 * CONTENT RULE: every line below describes behaviour that exists in this
 * codebase today. Anything unimplemented, experimental, planned or dead is
 * deliberately absent — including encryption, cloud/NAS, auto-restore on boot
 * and backup verification of data archives at backup time. See
 * `AboutInfo` for the dynamic facts and `HANDOVER.md` for the audit trail.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutScreen(
    context: Context,
    hasRoot: Boolean
) {
    val reduceMotion = rememberReducedMotion()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    // Engine parity is a root shell call — never on the composition thread.
    var engineCurrent by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        engineCurrent = withContext(Dispatchers.IO) {
            runCatching { PermissionChecker.check(context).engineMatch }.getOrNull()
        }
    }

    val (minSdk, targetSdk) = remember(context) { AboutInfo.sdkBounds(context) }

    val versionChip = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}"

    val statusChips = buildList {
        add(
            StatusChip(
                icon = Icons.Default.SecurityUpdateGood,
                label = if (hasRoot) "Root granted" else "No root",
                good = hasRoot,
                tone = if (hasRoot) DvRole.Success else DvRole.Error
            )
        )
        add(
            StatusChip(
                icon = Icons.Default.Memory,
                label = when (engineCurrent) {
                    true -> "Engine current"
                    false -> "Engine outdated"
                    null -> "Engine checking…"
                },
                good = engineCurrent == true,
                tone = when (engineCurrent) {
                    true -> DvRole.Success
                    false -> DvRole.Error
                    null -> DvRole.Info
                }
            )
        )
        add(
            StatusChip(
                icon = Icons.Default.Android,
                label = AboutInfo.platformLabel(),
                good = true,
                tone = DvRole.Info
            )
        )
        val abi = AboutInfo.primaryAbi()
        if (abi.isNotEmpty()) {
            add(StatusChip(icon = Icons.Default.Build, label = abi, good = true, tone = DvRole.Info))
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item("hero") {
            Reveal(visible = reduceMotion, delayMillis = 0) {
                AboutHero(
                    versionChip = versionChip,
                    statusChips = statusChips,
                    hasRoot = hasRoot
                )
            }
        }

        item("purpose") {
            Reveal(visible = reduceMotion, delayMillis = 60) {
                SectionLabel("What Droid Vault is")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Droid Vault copies apps, app data and device data into a folder on this " +
                        "phone, and puts them back on a freshly flashed ROM. It runs entirely " +
                        "on-device through a root-powered operation engine — there is no account, " +
                        "no server and no network access.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item("capabilities") {
            Reveal(visible = reduceMotion, delayMillis = 110) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CAPABILITY_GROUPS.forEach { group ->
                        CapabilityGroupCard(group)
                    }
                }
            }
        }

        item("details") {
            Reveal(visible = reduceMotion, delayMillis = 160) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Details")
                    Spacer(Modifier.height(2.dp))
                    AboutDisclosure(
                        icon = Icons.Default.Inventory2,
                        title = "What each app part holds",
                        subtitle = "Five independent parts, backed up and restored separately",
                        reduceMotion = reduceMotion,
                        facts = APP_PART_FACTS
                    )
                    AboutDisclosure(
                        icon = Icons.Default.Folder,
                        title = "Storage, engine and archives",
                        subtitle = "Where things live on this device",
                        reduceMotion = reduceMotion,
                        facts = STORAGE_FACTS
                    )
                    AboutDisclosure(
                        icon = Icons.Default.Policy,
                        title = "Your data and privacy",
                        subtitle = "Exactly what this app does with your files",
                        reduceMotion = reduceMotion,
                        facts = PRIVACY_FACTS
                    )
                    AboutDisclosure(
                        icon = Icons.Default.Description,
                        title = "Open-source licence",
                        subtitle = "This build is distributed under the GPL v3",
                        reduceMotion = reduceMotion,
                        facts = LICENCE_FACTS
                    )
                }
            }
        }

        item("actions") {
            Reveal(visible = reduceMotion, delayMillis = 210) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("Share")
                    Spacer(Modifier.height(2.dp))
                    AboutActionCard(
                        icon = Icons.Default.Share,
                        title = "Share Droid Vault",
                        subtitle = "Send a short description through any app",
                        reduceMotion = reduceMotion
                    ) {
                        runCatching {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "Droid Vault ${BuildConfig.VERSION_NAME} — root backup & restore " +
                                        "for Android. Backs up apps, app data and device data to a " +
                                        "folder on your phone and restores them on a clean ROM."
                                )
                            }
                            context.startActivity(Intent.createChooser(send, "Share Droid Vault via"))
                        }.onFailure {
                            context.safeToast("No app available to share")
                        }
                    }
                    AboutActionCard(
                        icon = Icons.Default.ContentCopy,
                        title = "Copy build info",
                        subtitle = "Version, package, SDK levels, root and engine status",
                        reduceMotion = reduceMotion
                    ) {
                        val report = AboutInfo.buildReport(
                            minSdk = minSdk,
                            targetSdk = targetSdk,
                            rootGranted = hasRoot,
                            engineCurrent = engineCurrent
                        )
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Droid Vault build info", report))) }
                        context.safeToast("Build info copied")
                    }
                }
            }
        }

        item("author") {
            Reveal(visible = reduceMotion, delayMillis = 250) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    HorizontalRule()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "phoenix-1708",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Droid Vault ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- hero

@Composable
private fun AboutHero(
    versionChip: String,
    statusChips: List<StatusChip>,
    hasRoot: Boolean
) {
    val shape = MaterialTheme.shapes.extraLarge
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            // Slightly stronger than the old 0.16f: in light
                            // theme the hero card and the page behind it are
                            // both near-white, so a 16% tint of an accent was
                            // almost invisible. The VaultRings arcs and this
                            // tint are decoration only - every readable element
                            // below uses solid colours, so removing the gradient
                            // loses no information.
                            accent.copy(alpha = 0.24f),
                            MaterialTheme.colorScheme.surfaceContainer,
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                )
        ) {
            // "Vault" language without literal imagery: concentric rings read
            // as sealed, layered and contained. Three strokes only, alpha falls
            // off, anchored off the top-end corner so they never fight the text.
            VaultRings(modifier = Modifier.matchParentSize())

            Column(modifier = Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The logo is a raster PNG with light artwork, so it must not sit
                    // untinted on a surface: on the light theme's
                    // surfaceContainerHighest it disappeared, while on the dark
                    // theme the same asset read clearly. It is now treated like
                    // every other icon in the app - a tinted container with the
                    // artwork tinted to the matching accent, which
                    // AccentContrastTest pins at 5.6:1 (light) / 5.4:1 (dark).
                    Surface(
                        modifier = Modifier.size(58.dp),
                        shape = CircleShape,
                        color = dvContainer(DvRole.Backup)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            androidx.compose.foundation.Image(
                                painter = painterResource(id = R.drawable.ic_home),
                                contentDescription = "Droid Vault logo",
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                                    dvAccent(DvRole.Backup)
                                ),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Droid Vault",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(5.dp))
                        Surface(
                            shape = CircleShape,
                            color = dvContainer(DvRole.Migrate)
                        ) {
                            Text(
                                versionChip,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = dvAccent(DvRole.Migrate),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    if (hasRoot) "Root backup & restore for Android"
                    else "Backup & restore for rooted Android",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "Move apps, app data and device data across ROM flashes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(16.dp))
                HorizontalRule()
                Spacer(Modifier.height(14.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    statusChips.forEach { chip -> LiveStatusChip(chip) }
                }
            }
        }
    }
}

/** Concentric arc motif drawn behind the hero content. */
@Composable
private fun VaultRings(modifier: Modifier = Modifier) {
    val stroke = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier = modifier) {
        val cx = size.width * 0.88f
        val cy = size.height * 0.12f
        listOf(0.30f, 0.55f, 0.85f, 1.25f).forEachIndexed { i, factor ->
            val r = size.minDimension * factor
            drawCircle(
                color = stroke.copy(alpha = 0.10f - i * 0.022f),
                radius = r,
                center = androidx.compose.ui.geometry.Offset(cx, cy),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx())
            )
        }
    }
}

// ---------------------------------------------------------------- capabilities

private data class Capability(val icon: ImageVector, val label: String)
private data class CapabilityGroupData(
    val icon: ImageVector,
    val title: String,
    val caption: String,
    val role: DvRole,
    val items: List<Capability>
)

/**
 * Category A only — every entry is a feature implemented in this codebase and
 * reachable from the shipped UI. Wording is deliberately literal: "App data"
 * is `/data/data` + `/data/user_de/0` + external files, nothing more.
 */
private val CAPABILITY_GROUPS = listOf(
    CapabilityGroupData(
        icon = Icons.Default.Android,
        title = "Apps",
        caption = "Five independent parts per app",
        role = DvRole.Backup,
        items = listOf(
            Capability(Icons.Default.Android, "App package"),
            Capability(Icons.Default.DataObject, "App data"),
            Capability(Icons.Default.VpnKey, "App permissions"),
            Capability(Icons.Default.Image, "Media & OBB"),
            Capability(Icons.Default.Key, "Android ID")
        )
    ),
    CapabilityGroupData(
        icon = Icons.Default.Contacts,
        title = "Device data",
        caption = "Backup and restore, in both tabs",
        role = DvRole.Device,
        items = listOf(
            Capability(Icons.Default.Sms, "Messages"),
            Capability(Icons.Default.Call, "Call logs"),
            Capability(Icons.Default.Contacts, "Contacts"),
            Capability(Icons.Default.Wifi, "Wi-Fi networks"),
            Capability(Icons.Default.Wallpaper, "Wallpaper"),
            Capability(Icons.Default.Bluetooth, "Bluetooth devices"),
            Capability(Icons.Default.Folder, "Custom folders")
        )
    ),
    CapabilityGroupData(
        icon = Icons.Default.Tune,
        title = "Automation",
        caption = "Repeatable, rule-driven runs",
        role = DvRole.Auto,
        items = listOf(
            Capability(Icons.AutoMirrored.Filled.Label, "App labels"),
            Capability(Icons.Default.Inventory2, "Backup configurations"),
            Capability(Icons.Default.Restore, "Restore configurations"),
            Capability(Icons.Default.Schedule, "Schedules"),
            Capability(Icons.Default.History, "Run history")
        )
    ),
    CapabilityGroupData(
        icon = Icons.Default.Build,
        title = "Device tools",
        caption = "Partition and package operations",
        role = DvRole.Tools,
        items = listOf(
            Capability(Icons.Default.SystemUpdateAlt, "Partition backup & flash"),
            Capability(Icons.Default.DeleteSweep, "Debloat & restore apps"),
            Capability(Icons.Default.SecurityUpdateGood, "Systemize user apps"),
            Capability(Icons.Default.Handshake, "Batch APK install")
        )
    )
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityGroupCard(group: CapabilityGroupData) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DvIconBadge(
                    role = group.role,
                    icon = group.icon,
                    containerSize = 34.dp,
                    iconSize = 19.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        group.caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.items.forEach { item ->
                    // Static tag, NOT a button: there is nothing to tap here, so
                    // it must not look tappable (contentDescription states the
                    // feature name for screen readers).
                    //
                    // Tinted with the group's own accent rather than left on
                    // surfaceContainerHighest: in the shipped schemes
                    // surfaceContainerHighest is the SAME value as the card
                    // behind it, so these tags had no background separation at
                    // all, and a plain `primary` icon sat on a near-identical
                    // surface in light theme. See AccentContrastTest.
                    Surface(
                        shape = CircleShape,
                        color = dvContainer(group.role),
                        modifier = Modifier.semanticsLabel(item.label)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 9.dp, end = 13.dp, top = 7.dp, bottom = 7.dp)
                        ) {
                            Icon(
                                item.icon,
                                contentDescription = null,
                                tint = dvAccent(group.role),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(
                                item.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- disclosure

private data class Fact(val term: String, val detail: String)

@Composable
private fun AboutDisclosure(
    icon: ImageVector,
    title: String,
    subtitle: String,
    facts: List<Fact>,
    reduceMotion: Boolean
) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "disclosurePress"
    )
    val chevronRotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 220),
        label = "disclosureChevron"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        role = Role.Button,
                        onClick = { open = !open }
                    )
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp)
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.Restore,
                    contentDescription = if (open) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }

            AnimatedVisibility(
                visible = open,
                enter = expandVertically(tween(if (reduceMotion) 0 else 220)) + fadeIn(tween(if (reduceMotion) 0 else 180)),
                exit = shrinkVertically(tween(if (reduceMotion) 0 else 180)) + fadeOut(tween(if (reduceMotion) 0 else 120))
            ) {
                Column(modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp)) {
                    HorizontalRule()
                    Spacer(Modifier.height(12.dp))
                    facts.forEachIndexed { index, fact ->
                        FactRow(fact)
                        if (index != facts.lastIndex) Spacer(Modifier.height(11.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun FactRow(fact: Fact) {
    Column {
        Text(
            fact.term,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(2.dp))
        Text(
            fact.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ---------------------------------------------------------------- actions

@Composable
private fun AboutActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    reduceMotion: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "actionPress"
    )
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---------------------------------------------------------------- chips / atoms

private data class StatusChip(
    val icon: ImageVector,
    val label: String,
    val good: Boolean,
    val tone: DvRole
)

@Composable
private fun LiveStatusChip(chip: StatusChip) {
    Surface(
        shape = CircleShape,
        color = dvContainer(chip.tone),
        modifier = Modifier.semanticsLabel("${chip.label}. ${if (chip.good) "OK" else "attention"}")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (chip.good) dvAccent(DvRole.Success)
                        else dvAccent(DvRole.Error)
                    )
            )
            Spacer(Modifier.width(8.dp))
            Text(
                chip.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.1.sp,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun HorizontalRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    )
}

/** Marks a static tag/row as a single readable node for screen readers. */
private fun Modifier.semanticsLabel(text: String): Modifier =
    semantics { contentDescription = text }

/**
 * Entrance reveal. Skipped entirely when the system animation scale is 0
 * ("Remove animations" in Accessibility settings), so reduced-motion users get
 * the content immediately rather than a half-faded page.
 *
 * The content is wrapped in a Column because [AnimatedVisibility] lays out a
 * SINGLE child: emitting a label + spacer + paragraph directly overlapped them
 * on screen (the "What Droid Vault is" header printed on top of its body).
 */
@Composable
private fun Reveal(visible: Boolean, delayMillis: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis.toLong())
        shown = true
    }
    AnimatedVisibility(
        visible = if (visible) shown else true,
        enter = fadeIn(tween(if (visible) 320 else 0))
    ) {
        Column { content() }
    }
}

@Composable
private fun rememberReducedMotion(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return remember(context) {
        runCatching {
            AndroidSettings.Global.getFloat(
                context.contentResolver,
                AndroidSettings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

// ---------------------------------------------------------------- copy

private val APP_PART_FACTS = listOf(
    Fact(
        "App package",
        "The installed APK and its split APKs, stored as App.shift."
    ),
    Fact(
        "App data",
        "The app's private data, its secondary user data and its external files " +
            "(/data/data, /data/user_de/0 and /data/media/0/Android/data). Caches, " +
            "code caches, native libraries, OTA folders and no_backup are excluded."
    ),
    Fact(
        "App permissions",
        "The app's runtime permission and app-op state, read from appops and " +
            "dumpsys. Restoring this part is ON by default — turn it off in " +
            "Settings if you would rather restore an app with the permissions it " +
            "has now."
    ),
    Fact(
        "Media & OBB",
        "The app's media folder and its OBB expansion files."
    ),
    Fact(
        "Android ID",
        "The app-scoped SSAID stored in Meta.txt. Restoring it is ON by default — " +
            "turn it off in Settings if you do not want a restored app to keep the " +
            "identity it had before."
    )
)

private val STORAGE_FACTS = listOf(
    Fact(
        "Backup folder",
        "Everything is written under a single Droid Vault folder that you choose " +
            "in Settings — on internal storage or a mounted SD card."
    ),
    Fact(
        "Archive format",
        "Each part is streamed through tar and compressed with Zstandard (level 1) " +
            "by the bundled zapdos binary."
    ),
    Fact(
        "Partial-write safety",
        "Archives are written to a .tmp name and renamed only after the " +
            "compression succeeds, so an interrupted run never leaves a truncated " +
            "file that looks complete."
    ),
    Fact(
        "Integrity checks",
        "The app-package archive is listed back through the decompressor right " +
            "after it is written, and a corrupt one is deleted. Data and media " +
            "archives are checked as they are read during restore instead, because " +
            "re-reading every archive would double the time of a large backup."
    ),
    Fact(
        "Re-backups",
        "A part whose size and app version have not changed is skipped, so " +
            "re-running a backup only rewrites what actually differs."
    ),
    Fact(
        "Backup engine",
        "A shell script and the zapdos binary are deployed to /data/adb/DroidVault " +
            "and hash-checked against this build on every launch."
    )
)

private val PRIVACY_FACTS = listOf(
    Fact(
        "No network access",
        "The app does not request the INTERNET permission, so it cannot open a " +
            "network connection. There is no account, no telemetry and no sync."
    ),
    Fact(
        "Backups are not encrypted",
        "Archives are stored as-is so any root tool or recovery can read them. " +
            "Anyone who can read your backup folder can read its contents — " +
            "protect the folder with your device lock and delete copies you no " +
            "longer need."
    ),
    Fact(
        "Root is used for what you ask for",
        "Root is required for app and partition access. Droid Vault runs no " +
            "background service, registers no boot receiver and starts nothing " +
            "on its own."
    ),
    Fact(
        "Permissions are temporary",
        "Sensitive runtime permissions are granted for an operation and revoked " +
            "again afterwards where the platform allows it."
    ),
    Fact(
        "Sharing is manual",
        "Nothing is uploaded. If you export or share an archive or a log, that " +
            "leaves the device only through the app you pick."
    )
)

private val LICENCE_FACTS = listOf(
    Fact(
        "Droid Vault",
        "Licensed under the GNU General Public License, version 3. The full " +
            "licence text ships in the project's LICENSE file."
    ),
    Fact(
        "zapdos",
        "The bundled Zstandard command-line binary is by Yann Collet and " +
            "contributors and is distributed under the BSD-style Zstandard " +
            "licence."
    )
)
