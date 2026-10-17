package com.phoenix.droidvault.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.phoenix.droidvault.MainViewModel
import com.phoenix.droidvault.models.ImportSource
import com.phoenix.droidvault.models.TransferPlan

/**
 * The confirmation in front of an import: the facts, the promise, one button.
 *
 * **This is a safety gate, not a speed bump.** An import expands the transfer
 * into the vault — tens of gigabytes, for minutes — and it used to start on one
 * tap of a list row, with nothing on the screen answering the question that row
 * provokes. [TransferPlan.importNotes] carries the answer
 * (`KEEP_DESTINATION` keeps what is already there) and a sheet is where an
 * answer like that belongs rather than a code comment.
 *
 * **The sheet has to FIT.** The first version stacked label over value with ten
 * dp between each, and on a 34.9 GB export the Import button was below the fold
 * — a confirmation whose button you have to scroll to find is a confirmation
 * that trains people not to read it. So the facts are a two-column table, the
 * promise is two lines, and the whole thing measures under the fold at the
 * default font. The scroll that remains is for a 2x font, where every screen in
 * the app scrolls and pretending otherwise would be the lie.
 *
 * Every string comes from [TransferPlan], so `UserFacingStringTest` asserts what
 * it actually says.
 */
@Composable
fun ImportConfirmSheet(
    source: ImportSource,
    vaultPath: String,
    freeKb: Long?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val rows = remember(source, vaultPath, freeKb) {
        TransferPlan.importRows(source, vaultPath, freeKb)
    }
    val notes = remember(source) { TransferPlan.importNotes(source) }
    val needKb = remember(source) { TransferPlan.importNeedKb(source) }

    ConfirmSheet(
        title = TransferPlan.IMPORT_TITLE,
        subtitle = TransferPlan.importSubtitle(source),
        rows = rows,
        notes = notes,
        leading = null,
        note = TransferPlan.shortfallNote(freeKb, needKb),
        confirmLabel = "Import",
        confirmEnabled = TransferPlan.canStart(freeKb, needKb),
        onDismiss = onDismiss,
        onConfirm = onConfirm
    )
}

/**
 * The confirmation for a file picked through the system picker.
 *
 * **Held by the ViewModel, rendered by whichever tab launched the picker.** Two
 * tabs can launch it — Migrate's own page and Tools' — and they are different
 * composables, so the sheet is a shared holder rather than a copy in each. The
 * state lives on the ViewModel so it survives the Activity being recreated while
 * the sheet is up, and the sheet renders only where the picker was launched:
 * a confirmation is opened by the control that started the work, never by
 * whichever screen the user happens to be on (invariant 21).
 *
 * Before this existed, "Find it somewhere else…" was the one way into a
 * multi-minute, tens-of-gigabytes write with nothing said before it started —
 * the gate existed only where a list happened to be.
 */
@Composable
fun PickedImportSheet(viewModel: MainViewModel) {
    val source by viewModel.pendingPickSource.collectAsState()
    val overview by viewModel.overview.collectAsState()
    val context = LocalContext.current
    val sourceOrNull = source ?: return
    ImportConfirmSheet(
        source = sourceOrNull,
        vaultPath = viewModel.savedPath.value,
        // The VAULT's filesystem, which is where import staging lands and
        // therefore what the gate measures.
        freeKb = overview?.storage?.freeBytes?.div(1024),
        onDismiss = { viewModel.dismissPendingPick() },
        onConfirm = { viewModel.runPickedImport(context) }
    )
}

/**
 * The confirmation in front of an export, and the place the split choice is made.
 *
 * This replaced two dialogs — a confirm, then a split-size dialog — which meant
 * two decisions for one operation, the second of them taken after the user had
 * already committed to the first. **The split choice belongs here**: "one big
 * file or parts" does not detail the export, it decides what the artefact is
 * and whether it can physically be moved to the PC it is presumably being moved
 * to.
 *
 * The choice is chips in a wrapping row rather than four stacked radios: four
 * radios is four rows of vertical space for one decision, which is what pushed
 * the button off the screen the first time.
 *
 * Same chrome as the import confirmation because both answer the same question —
 * *what is about to happen to my storage* — and someone who has learned one
 * should not have to learn the layout of the other.
 */
@Composable
fun ExportConfirmSheet(
    vaultPath: String,
    batchDir: String,
    vaultKb: Long?,
    freeKb: Long?,
    splitOptions: List<Pair<String, Long>>,
    initialChoice: Int,
    onDismiss: () -> Unit,
    onConfirm: (choice: Int) -> Unit
) {
    var choice by remember { mutableIntStateOf(initialChoice) }
    val splitBytes = splitOptions[choice].second
    val rows = remember(vaultPath, batchDir, vaultKb, freeKb, splitBytes) {
        TransferPlan.exportRows(vaultPath, batchDir, vaultKb, freeKb, splitBytes)
    }
    val needKb = remember(vaultKb, splitBytes) {
        if (vaultKb != null) TransferPlan.exportNeedKb(vaultKb, splitBytes > 0) else 0L
    }

    ConfirmSheet(
        title = TransferPlan.EXPORT_TITLE,
        subtitle = TransferPlan.EXPORT_SUBTITLE,
        rows = rows,
        notes = TransferPlan.exportNotes(splitBytes, vaultKb),
        note = TransferPlan.shortfallNote(freeKb, needKb),
        confirmLabel = "Export",
        confirmEnabled = TransferPlan.canStart(freeKb, needKb),
        onDismiss = onDismiss,
        // Splitting changes the space needed — both copies exist at once while
        // the pieces are verified against the single file — so the figure below
        // is recomputed as the choice moves, which is why `rows` is keyed on
        // `splitBytes` rather than on the vault alone.
        leading = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                splitOptions.forEachIndexed { i, (label, _) ->
                    ChoiceChip(
                        label = label,
                        selected = choice == i,
                        onClick = { choice = i }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                TransferPlan.exportTargetLine(splitBytes, vaultKb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
        },
        onConfirm = { onConfirm(choice) }
    )
}

/**
 * The chrome both confirmations share.
 *
 * `leading` is null on the import side, which is the only structural difference
 * between them — everything else, including the disabled-with-a-reason button,
 * is one implementation. That is invariant 18 applied to two dialogs that would
 * otherwise have been two.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmSheet(
    title: String,
    subtitle: String,
    rows: List<TransferPlan.Row>,
    notes: List<String>,
    leading: (@Composable () -> Unit)?,
    note: String?,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Matches the theme and log sheets. Left at the default this one sits on
        // a different surface from every other sheet in the app, which nobody
        // notices until two are open.
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                // A safety net for a 2x font, not the normal path: at the default
                // size everything above fits with the buttons on screen, which is
                // the whole point of this sheet.
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (leading != null) {
                Spacer(Modifier.height(10.dp))
                leading()
            }

            Spacer(Modifier.height(8.dp))
            FactTable(rows)

            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    notes.forEach { NoteLine(it) }
                }
            }

            Spacer(Modifier.height(14.dp))
            ConfirmRow(
                confirmLabel = confirmLabel,
                confirmEnabled = confirmEnabled,
                note = note,
                onDismiss = onDismiss,
                onConfirm = onConfirm
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * The facts, as a two-column table.
 *
 * **Side by side, not stacked**, and that is the whole reason this sheet fits.
 * A label above its value costs two lines and a 10 dp gap per fact; five facts
 * is ten lines and 50 dp of nothing, which is what put the button under the
 * fold. The label column is fixed so the values line up and can be scanned
 * down the right-hand side.
 *
 * Paths are monospaced and `bodySmall` because they are the one value a person
 * copies into a bug report, and monospace makes a wrapped path readable where
 * proportional type turns it into a smear.
 */
@Composable
private fun FactTable(rows: List<TransferPlan.Row>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // `widthIn(min = …)`, NOT `width(…)`. A fixed column is
                    // what makes the values line up at the default font, and it
                    // is also what made "Space" run into "36.95 GB" at 2x,
                    // because a 74dp column cannot hold a label whose type has
                    // doubled. The minimum still aligns the values; past it the
                    // label grows instead of colliding.
                    modifier = Modifier.widthIn(min = 74.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    row.value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/** One promise, ticked. Promises are prose, so they are not in the table. */
@Composable
private fun NoteLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The buttons, and what an unpressable one says.
 *
 * A disabled control that does not say why is §2.61: "Not yet" and "never" look
 * identical from outside. So the note belongs to this composable rather than to
 * the caller — if the button is dead, the reason is on screen with it, and it
 * cannot be forgotten.
 */
@Composable
private fun ConfirmRow(
    confirmLabel: String,
    confirmEnabled: Boolean,
    note: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (note != null) {
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onDismiss, shape = CircleShape) { Text("Cancel") }
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            shape = CircleShape,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
        ) {
            Text(confirmLabel)
        }
    }
}

/**
 * One split option.
 *
 * Deliberately not [AnimatedFilterChip]: that takes a mandatory leading icon,
 * and a meaningless glyph beside "4 GB parts" is worse than no glyph. This is a
 * segmented single-choice control with its own job, not a filter.
 */
@Composable
private fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.height(32.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}