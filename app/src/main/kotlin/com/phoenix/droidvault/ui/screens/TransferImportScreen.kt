package com.phoenix.droidvault.ui.screens

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.phoenix.droidvault.MainViewModel
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.TransferCandidate
import com.phoenix.droidvault.models.TransferState
import com.phoenix.droidvault.models.TransferPlan
import com.phoenix.droidvault.models.TransferProgress
import com.phoenix.droidvault.models.VaultTransfer
import com.phoenix.droidvault.ui.safeToast
import com.phoenix.droidvault.ui.components.DvIconBadge
import com.phoenix.droidvault.ui.components.ExportConfirmSheet
import com.phoenix.droidvault.ui.components.ImportConfirmSheet
import com.phoenix.droidvault.ui.components.InfoBlock
import com.phoenix.droidvault.ui.theme.DvRole
import com.phoenix.droidvault.ui.theme.dvAccent
import com.phoenix.droidvault.utils.Formatters
import com.phoenix.droidvault.utils.TransferStatus

/**
 * The exports this phone holds, one row each.
 *
 * This screen exists because the alternative was: tap Import, get the system
 * file picker opened at *storage root* accepting every file type on the device,
 * walk to
 * `Download/DroidVault/<export>/`, and tap one of seventeen identical
 * `.part-aa`…`.part-aq` files — with nothing on screen to say that any of the
 * seventeen was the right one. All of them were.
 *
 * The unit of choice here is the **export**, never the piece, because that is
 * what a person made. Each row carries the two facts that decide whether it can
 * be restored: how big it is, and whether every piece of it is actually here.
 *
 * Reads through the root shell (`TransferLocator`), which needs no permission and
 * which the app already uses to *write* this same folder. Android 11+ refuses
 * `OpenDocumentTree` on `Download`, so an in-app browser over SAF is not
 * available in the one folder that matters — that restriction is why this is a
 * shell read rather than a `DocumentFile` walk.
 */
@Composable
fun TransferImportScreen(
    viewModel: MainViewModel,
    onBrowseFiles: () -> Unit
) {
    val context = LocalContext.current
    // How many app backups the vault holds, or null if that has not been read
    // yet. The empty state says something different for each, and only the
    // Home card was reading it before.
    val overview by viewModel.overview.collectAsState()
    // Hoisted out of EmptyBody so the dialog is not composed inside the
    // weight(1f) Box, and so Migrate's and Import's export entry points are
    // the same dialog rather than two that drift apart.
    var showExportDialog by remember { mutableStateOf(false) }
    // The import confirmation. Held by the ROW that was tapped, so the sheet is
    // composed beside the control that opened it rather than by whatever screen
    // the user happens to be on when a transfer finishes resolving (invariant
    // 21 — nothing in this app may depend on where you navigated).
    var pendingImport by remember { mutableStateOf<TransferCandidate?>(null) }
    // Observed, NOT read with `.value`. Both probes run off-IO when the sheet
    // opens, so a one-shot snapshot is always the pre-probe value: the space row
    // was simply absent from the export sheet, silently, which is a diagnostic
    // that disappears rather than a wrong one. `collectAsState` is the whole
    // difference between a sheet that has the figure and one that quietly does
    // not — and a missing row reads as "nothing to report", which is the one
    // thing a confirmation must not do (§2.79).
    val exportOverview by viewModel.overview.collectAsState()
    val exportFreeKb by viewModel.exportFreeKb.collectAsState()
    val exportVaultKb = exportOverview?.vaultKb

    // Measured as the sheet opens, not at startup: free space in Downloads is
    // the one figure on it that goes stale, and it is on a DIFFERENT filesystem
    // from the vault, so the overview card's number cannot stand in for it.
    LaunchedEffect(showExportDialog) {
        if (showExportDialog) {
            viewModel.refreshExportFreeSpace()
            viewModel.refreshOverview()
        }
    }
    if (showExportDialog) {
        ExportConfirmSheet(
            vaultPath = viewModel.savedPath.value,
            batchDir = viewModel.nextExportDir(),
            vaultKb = exportVaultKb,
            freeKb = exportFreeKb,
            splitOptions = viewModel.splitOptions,
            initialChoice = viewModel.splitChoice,
            onDismiss = { showExportDialog = false },
            onConfirm = { choice ->
                viewModel.splitChoice = choice
                showExportDialog = false
                viewModel.exportVaultForTransfer(
                    context, viewModel.splitOptions[choice].second
                )
            }
        )
    }
    pendingImport?.let { candidate ->
        ImportConfirmSheet(
            source = TransferPlan.sourceOf(candidate),
            vaultPath = viewModel.savedPath.value,
            // The VAULT's filesystem, which is where import staging lands
            // (`$vault/.dvimport`) and therefore what the gate measures. The
            // export destination is a different filesystem and its figure would
            // answer a question nobody asked.
            freeKb = overview?.storage?.freeBytes?.div(1024),
            onDismiss = { pendingImport = null },
            onConfirm = {
                pendingImport = null
                viewModel.importTransfer(context, candidate)
            }
        )
    }
    val candidates by viewModel.transferCandidates.collectAsState()
    val scanning by viewModel.transfersScanning.collectAsState()
    val transfer by viewModel.transferStatus.collectAsState()
    val startedMs by viewModel.transferStartedMs.collectAsState()
    val samples by viewModel.transferSamples.collectAsState()

    // The elapsed clock ticks on its own: progress reports arrive in bursts, so a
    // run that copies 2 GB and then spends forty seconds placing it would show a
    // clock that stopped with the bar. A one-second ticker is what keeps
    // "11 min elapsed" counting while the work is genuinely still going.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }

    // Arriving IS the request to look. Refresh is pull-to-refresh, not a
    // LaunchedEffect keyed on nothing: a listing that re-reads itself would move
    // rows out from under a finger that is on its way to one.
    LaunchedEffect(Unit) { viewModel.refreshTransfers() }
    LaunchedEffect(transfer.running) {
        while (transfer.running) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        ImportSummary(candidates, scanning)

        // The transfer surface. This page has no `MigratorActionScreen` - it
        // lists exports, not apps, so it dispatches around that screen entirely
        // and with it the only progress panel in the app. The result was an
        // import that ran with nowhere to report itself: the page looked
        // untouched from the tap to the end, on the work that takes longest.
        if (transfer.running) {
            Spacer(Modifier.height(12.dp))
            ImportProgressPanel(
                transfer = transfer,
                elapsedMs = if (startedMs > 0L) nowMs - startedMs else 0L,
                samples = samples,
                totalBytes = candidates.sumOf { it.totalBytes },
                onCancel = { viewModel.cancelImport(context) }
            )
        }

        Spacer(Modifier.height(12.dp))

        when {
            // weight(1f), NOT fillMaxSize(). Inside a Column, fillMaxSize claims
            // every remaining pixel, and the "Find it somewhere else…" button below
            // is left with zero height — measured on the device, where the whole
            // list rendered and the only escape hatch was nowhere on screen. The
            // body takes the space it is GIVEN; the button keeps its own.
            scanning && candidates.isEmpty() -> Box(Modifier.weight(1f)) { ScanningBody() }
            candidates.isEmpty() -> Box(Modifier.weight(1f)) {
                EmptyBody(
                    appBackupCount = overview?.backupCount,
                    onExport = { showExportDialog = true }
                )
            }
            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(candidates, key = { it.dir + "/" + it.base }) { c ->
                    TransferRow(
                        candidate = c,
                        onImport = {
                            if (c.canImport) {
                                // Re-measured as the sheet opens. Free space is
                                // the one figure on it that goes stale between
                                // launches, and a gate quoting a number from
                                // startup quotes the wrong number by the time
                                // you need it.
                                viewModel.refreshOverview()
                                pendingImport = c
                            } else context.safeToast(
                                c.problem ?: "This export is not complete.",
                                Toast.LENGTH_LONG
                            )
                        }
                    )
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }

        Spacer(Modifier.height(8.dp))
        BrowseElsewhereButton(onBrowseFiles)
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * The page's header, and the one place it explains itself.
 *
 * This used to be two bare `Text` lines: "1 export / 32.46 GB restoreable".
 * Both are answers to questions nobody asked, and none of the things a user
 * actually needs were on the page at all — what an export IS, where Droid Vault
 * looks for one, why a 34 GB backup is seventeen rows of "17 parts", and what
 * "Can’t confirm it’s complete" is going to cost them.
 *
 * So the live counts stay exactly where they were, and the explanation sits
 * behind the app's existing "i" — the same [InfoBlock] the Automation tab and
 * the Settings rows use, so this is one affordance in the app rather than a
 * second one. It is invisible until asked for, which is the point: a page whose
 * every sentence is a paragraph is a page nobody reads.
 */
@Composable
private fun ImportSummary(candidates: List<TransferCandidate>, scanning: Boolean) {
    val ready = candidates.count { it.canImport }
    val usable = candidates.filter { it.canImport }.sumOf { it.totalBytes }
    InfoBlock(
        title = when {
            scanning && candidates.isEmpty() -> "Looking in Download/DroidVault…"
            candidates.isEmpty() -> "Nothing found yet"
            candidates.size == 1 -> "1 export"
            else -> "${Formatters.formatCount(candidates.size)} exports"
        },
        summary = if (usable > 0) {
            "${Formatters.formatBytes(usable)} restoreable" +
                if (ready < candidates.size) " · $ready of ${candidates.size} ready" else ""
        } else {
            null
        },
        titleStyle = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Medium
        ),
        info = IMPORT_HELP
    )
}

/**
 * The Import page's explanation, in the user's terms.
 *
 * Written to be read once by someone who has never seen this screen. Every
 * sentence answers a question the screen provokes: "where did this come from",
 * "why is one backup so many rows", "what does that chip mean", and "what
 * happens if I tap it".
 */
internal const val EMPTY_TITLE = "No exports on this phone"

/**
 * Neutral: nothing has been measured, so nothing is claimed.
 *
 * Carries the one correction worth making — importing an export puts it back
 * in your vault and does NOT install apps, which the old wording ("ready to
 * restore on this or any other phone") implied it did.
 */
internal const val EMPTY_BODY_UNKNOWN =
    "An export is a copy of your backups that Droid Vault writes to " +
        "Download/DroidVault, so you can move one to another phone or keep a " +
        "copy off this device. Importing puts it back into your vault — it does " +
        "not install apps."

/** Nothing to copy yet, and where to fix that. */
internal const val EMPTY_BODY_NO_BACKUPS =
    "There are no app backups in your vault yet, so an export would be almost " +
        "empty. Back something up first — that lives in the Migrate tab."

/**
 * The reassuring one, and the reason this screen changed.
 *
 * A function rather than a `"{n}"` template on purpose. A placeholder is a
 * string that can reach the UI with the placeholder still in it, which is the
 * same failure mode as a stray brace — correct in source, wrong on screen — and
 * this codebase has three instances of that already. Counting inside the
 * producer cannot be forgotten, because there is nothing left to substitute.
 *
 * The count earns its place: "your backups are in the vault" is true, but the
 * reader cannot tell whether it means two archives or two hundred, and the
 * number is the difference between believing it and going to look.
 *
 * "already holds N app backups" rather than "your N app backups are already
 * there", because the second needs a verb that agrees with the count and
 * `AppParts.count` only ever pluralises the noun. Written the obvious way it
 * rendered "Your 1 app backup are already in the vault", and the unit test
 * caught it before a device did.
 */
internal fun emptyBodyHasBackups(n: Int): String =
    "Your vault on this phone already holds " +
        AppParts.count(n, "app backup") +
        " — an export is not how you restore them here. An export is a " +
        "copy of your backups written to Download/DroidVault, so you can move " +
        "one to another phone. Importing puts it back into your vault; it does " +
        "not install apps."

internal val IMPORT_HELP = buildString {
    append("Droid Vault exports are files it writes to Download/DroidVault so a ")
    append("backup can be moved to another phone. This page lists the ones on ")
    append("this device.\n\n")
    append("A large backup is split into pieces, so all the pieces have to be ")
    append("here. That is what Complete means. A set recorded as more pieces than ")
    append("exist cannot be restored, and is refused rather than half-restored.")
    append("\n\n")
    append("Can’t confirm it’s complete means there is no manifest beside the ")
    append("pieces, so nothing here can prove the set is whole. It is still ")
    append("importable: the import checks before it writes anything.\n\n")
    append("Find it somewhere else… is for a file you copied over from a PC or a ")
    append("cloud drive. An import needs room for the unpacked copy, and it never ")
    append("touches the export itself — the pieces are still on disk afterwards.")
}

@Composable
private fun TransferRow(
    candidate: TransferCandidate,
    onImport: () -> Unit
) {
    val enabled = candidate.canImport
    val container by animateColorAsState(
        targetValue = if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.55f)
        },
        label = "transferRowContainer"
    )
    val tint = dvAccent(DvRole.Migrate)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            // The whole card is still the shortcut. The button below is there so
            // the action is VISIBLE — a card you can tap but which looks like
            // static text is a control nobody finds, and that was this card.
            .clickable(enabled = enabled, onClick = onImport)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DvIconBadge(
                role = DvRole.Migrate,
                icon = Icons.Default.CloudUpload,
                containerSize = 42.dp,
                iconSize = 22.dp,
                // A refused row must not look like a working one. The tile is
                // dimmed rather than replaced, so the row still reads as "an
                // export" — it just stops inviting a tap.
                modifier = if (enabled) Modifier else Modifier.alpha(0.4f)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // The size is the headline. It used to be small grey text under a
                // raw timestamp, which is the number the reader is actually
                // deciding about rendered as a footnote.
                Text(
                    candidate.sizeLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    Formatters.exportStamp(candidate.modifiedMs) + " · " +
                        candidate.partsLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            // Tonal, not filled: with several cards on screen a row of filled
            // buttons competes with itself, and this is one action among a list
            // rather than the page's single purpose.
            Button(
                onClick = onImport,
                enabled = enabled,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = tint.copy(alpha = 0.20f),
                    contentColor = tint
                )
            ) {
                Text("Import", style = MaterialTheme.typography.labelLarge)
            }
        }

        Spacer(Modifier.height(10.dp))
        TransferStateLine(candidate)

        if (candidate.state == TransferState.UNVERIFIED) {
            Spacer(Modifier.height(6.dp))
            Text(
                "No manifest beside these pieces, so nothing here can confirm they " +
                    "are all present. Import will check before it writes anything.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        candidate.problem?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/**
 * The export's health, as a coloured dot and a sentence.
 *
 * **The colour has to mean something.** This was `colorScheme.tertiary` at 16%
 * alpha — a grey pill that is indistinguishable from a disabled control, on the
 * one row whose whole job is to say whether it can be restored. Green, amber and
 * red come from the app's own [DvRole] accents, which `AccentContrastTest`
 * measures against this card's surface in all three themes.
 *
 * A dot rather than a pill: the pill was a box with a word in it and no
 * affordance, and read as a label for the card rather than as a state.
 */
@Composable
private fun TransferStateLine(candidate: TransferCandidate) {
    val (label, role) = when (candidate.state) {
        TransferState.COMPLETE -> "Complete" to DvRole.Success
        // Caution, not failure: there is no manifest, so nothing can prove the
        // set is whole — but it is still importable, and the import checks
        // before it writes anything.
        TransferState.UNVERIFIED -> "Can't confirm it's complete" to DvRole.Warning
        TransferState.INCOMPLETE ->
            "${candidate.missing.size} of ${candidate.manifest?.parts ?: 0} pieces missing" to
                DvRole.Error
        TransferState.DAMAGED -> "Damaged" to DvRole.Error
    }
    val tint = dvAccent(role)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(tint)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = tint
        )
    }
}


@Composable
private fun ScanningBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Reading Download/DroidVault…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Nothing to import, and it says what would change that.
 *
 * Invariant 50: a control that leads nowhere has to name the thing that would
 * make it work. An empty list with a live "Browse files" underneath is the whole
 * message — the export instruction is here because the most likely reason this
 * page is empty is that the user has not exported anything yet.
 */
@Composable
private fun EmptyBody(appBackupCount: Int?, onExport: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        DvIconBadge(
            role = DvRole.Migrate,
            icon = Icons.Default.FolderOpen,
            containerSize = 64.dp,
            iconSize = 30.dp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            EMPTY_TITLE,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(6.dp))
        Text(
            emptyBody(appBackupCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = onExport,
            shape = CircleShape
        ) {
            Text("Export backups")
        }
    }
}

/**
 * The three things this screen can honestly say about an empty list.
 *
 * This paragraph used to be one fixed string that told the user to go and do
 * something on another tab, which is a dead end dressed as an explanation. The
 * commonest reason to be standing here is a phone that was just flashed or
 * reinstalled — and in that case the vault is still exactly where it was, so
 * the most useful sentence this screen can say is "you are already fine", not
 * "go export something".
 *
 * So the body is chosen from what the vault actually holds, and there are three
 * answers because there are three situations:
 *
 *  - **Backups present** — the reassuring one, and the reason this screen was
 *    worth changing at all.
 *  - **Not measured** — `backupCount` is `null` until the startup read lands.
 *    A read that has not happened is not a vault that is empty, so this says
 *    nothing about what you have rather than guessing (invariant 39).
 *  - **Genuinely empty** — nothing to copy yet.
 *
 * Returns a value rather than composing three branches, because invariant 53:
 * text a user reads is a value with an expected output, not a template written
 * inline in a Composable. [UserFacingStringTest] asserts all three.
 */
internal fun emptyBody(appBackupCount: Int?): String = when (appBackupCount) {
    null -> EMPTY_BODY_UNKNOWN
    0 -> EMPTY_BODY_NO_BACKUPS
    else -> emptyBodyHasBackups(appBackupCount)
}

/**
 * The escape hatch, at the foot of the page, as a link rather than a button.
 *
 * It was a full-width outlined button, which made "find a file you copied from
 * a PC" and "make a new export" two equal-weight controls on a screen that has
 * one obvious action. It stays exactly where it was and stays visible whether
 * or not there are exports — importing one from a PC is always a live option —
 * but it no longer competes for the tap.
 */
@Composable
private fun BrowseElsewhereButton(onBrowse: () -> Unit) {
    TextButton(
        onClick = onBrowse,
        modifier = Modifier.fillMaxWidth()
    ) {
        // An icon, because a line of text alone at the foot of a page reads as a
        // caption rather than a control. Still a link and not a second button:
        // this page's one obvious action is importing what is already listed.
        Icon(
            Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text("Find it somewhere else…")
    }
}

/**
 * Live progress for a running import, on the page that started it.
 *
 * Deliberately not the bottom Sheet `MigratorActionScreen` uses. That surface is
 * tethered to the bottom of a screen whose primary action is a footer button; on
 * this page the primary content IS the list, and a sheet would cover the very
 * rows the user is watching. The strip sits under the summary instead, so the
 * list stays visible while the work runs.
 *
 * What it shows, and why each part is there:
 *
 *  - the **phase name** from the run itself ("Restoring backups into your
 *    vault"), which `TransferPhase` has always known and `TransferBus` used to
 *    throw away before it reached any screen;
 *  - a **percentage** and a bar, indeterminate when the run genuinely cannot
 *    count - the survey inflates a 34 GB archive just to count it, and a bar
 *    that pretended otherwise would be the lie invariant 31 forbids;
 *  - **elapsed**, which is measured and always true;
 *  - an **estimate**, only once there is a rate to justify one, and never
 *    during an indeterminate phase.
 */
@Composable
private fun ImportProgressPanel(
    transfer: TransferStatus,
    elapsedMs: Long,
    samples: List<TransferProgress.Estimate.Sample>,
    totalBytes: Long,
    onCancel: () -> Unit
) {
    val rate = TransferProgress.Estimate.windowedRate(samples)
    val remaining = TransferProgress.Estimate.label(transfer.percent, rate ?: 0.0)
    val elapsed = TransferProgress.Estimate.elapsedLabel(elapsedMs)
    val speed = rate?.let { TransferProgress.Estimate.rateLabel(it, totalBytes) }
    val determinate = transfer.percent in 0..100

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    transfer.phaseLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // The engine's own step text, which is what says which app or
                // file is being handled right now.
                if (transfer.detail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        transfer.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (determinate) {
                Spacer(Modifier.width(12.dp))
                Text(
                    "${transfer.percent}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        if (determinate) {
            LinearProgressIndicator(
                progress = { (transfer.percent.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape),
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            )
        }

        // Only figures that are TRUE get rendered. `remaining` is null whenever
        // there is no defensible estimate, and rendering that as "calculating..."
        // would be a promise the run may not keep.
        val meta = listOfNotNull(elapsed, remaining, speed)
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            Text(
                meta.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Cancel is present in EVERY phase, including the indeterminate ones -
        // which are exactly where a long import is indistinguishable from a
        // stuck one, and therefore exactly where a way out matters most.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onCancel) {
                Text(
                    "Cancel",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}


