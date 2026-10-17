package com.phoenix.droidvault.models

import com.phoenix.droidvault.utils.Formatters

/**
 * What the confirmation sheets say, decided without a Composable.
 *
 * Both transfers used to start on one tap. An export is read-only, so that was
 * merely reckless — it filled Downloads with 32 GB and gave no chance to pick a
 * split. **An import is not**: one tap began a multi-minute write that expands
 * the transfer into the vault, and nothing on the screen answered the only
 * question a reader of that row actually has, which is *what happens to the
 * backups already on this phone*. The answer is reassuring — `Merge
 * .KEEP_DESTINATION` keeps them — and an answer that reassuring is worth putting
 * in front of someone rather than in a comment.
 *
 * So the confirmation exists, and this is what it says. Every row is produced
 * here rather than in a Composable for the reason invariant 53 gives: text a
 * user reads is a value with an expected output, so it can be asserted.
 *
 * **The numbers are estimates and the sheet says so.** `needsKb` mirrors the
 * gate's own arithmetic using the same [VaultTransfer.IMPORT_HEADROOM_KB] and
 * [VaultTransfer.EXPORT_HEADROOM_KB] the gate uses — the same constants, not a
 * second copy of the numbers — but the gate surveys the real archive when it
 * runs, so the figure here can be out. A sheet that stated it as fact and the
 * gate that contradicted it would be two answers waiting to disagree, which is
 * §2.79's shape with the volume turned down.
 *
 * **A space figure that was not measured is omitted, never zero.** `freeKb` is
 * nullable precisely so a failed probe cannot render "0 GB free", which is the
 * assertion §2.79 is about. An absent row claims nothing and the gate stays the
 * authority.
 */
/**
 * What is about to be imported, described rather than typed.
 *
 * **Deliberately not a [TransferCandidate].** A confirmation sheet describes an
 * import; it does not describe a row. Keeping it to one shape means the sheet
 * can also stand in front of the SAF path — a file picked out of a file manager,
 * which is the same multi-minute write into the same vault and was completely
 * ungated — instead of the gate existing only where a list happened to be.
 */
data class ImportSource(
    /** Where the bytes are now, in words a person can act on. */
    val from: String,
    val totalBytes: Long,
    val isSplit: Boolean,
    val piecesHere: Int,
    val piecesExpected: Int,
    val piecesMissing: Int,
    /**
     * Non-null when the file is NOT on this phone yet, saying what has to happen
     * before the import can start. Null when it already is — the common case for
     * a transfer the app wrote itself.
     */
    val stagedFirst: String? = null
)

object TransferPlan {

    /** Build one from a listed export — the shape [TransferLocator] found. */
    fun sourceOf(candidate: TransferCandidate): ImportSource {
        val expected = candidate.manifest?.parts ?: candidate.sourceParts.size
        return ImportSource(
            from = candidate.dir,
            totalBytes = candidate.totalBytes,
            isSplit = candidate.isSplit,
            piecesHere = candidate.sourceParts.size,
            piecesExpected = expected,
            piecesMissing = candidate.missing.size,
            stagedFirst = null
        )
    }


    /** One "label / value" line. */
    data class Row(val label: String, val value: String)

    /**
     * Whether the operation may start on what is known now.
     *
     * **Null means "we could not measure it"**, and null is NOT permission in
     * the permissive direction: the gate is the authority either way, so an
     * unknown space is allowed to try and refused honestly if it is short. A
     * measured shortfall is the one case where the button is dead, and it says
     * why — a control that cannot succeed has to name what would change that
     * (invariant 50).
     */
    fun canStart(freeKb: Long?, needKb: Long): Boolean =
        freeKb == null || freeKb >= needKb

    /**
     * The free-space line, or null when it was not measured.
     *
     * `about` is not decoration. The gate surveys the real archive, so this
     * number is an estimate and presenting it as exact is how a dialog ends up
     * disagreeing with the run it just approved.
     */
    fun spaceRow(freeKb: Long?, needKb: Long): Row? {
        if (freeKb == null) return null
        return Row(
            "Space",
            if (canStart(freeKb, needKb)) {
                "${Formatters.formatKb(freeKb)} free · about " +
                    Formatters.formatKb(needKb) + " needed"
            } else {
                "Only ${Formatters.formatKb(freeKb)} free · about " +
                    Formatters.formatKb(needKb) + " needed"
            }
        )
    }

    /** What to say under a button that cannot be pressed. */
    fun shortfallNote(freeKb: Long?, needKb: Long): String? {
        if (canStart(freeKb, needKb)) return null
        val short = Formatters.formatKb(needKb - freeKb!!)
        return "Not enough free space. Free up $short more and try again."
    }

    /**
     * The KB an import will expand to.
     *
     * A `.shift` transfer is an uncompressed tar (invariant 29: a plain tar's
     * uncompressed size is its file size to within block padding), so the file
     * size is the expansion. A 0 here means not measured — the gate re-derives
     * it for real.
     */
    fun unpackKb(source: ImportSource): Long = source.totalBytes / 1024L

    /** KB an import needs, using the gate's own arithmetic. */
    fun importNeedKb(source: ImportSource): Long =
        VaultTransfer.importNeedsKb(unpackKb(source), VaultTransfer.IMPORT_HEADROOM_KB)

    /** KB an export needs, using the gate's own arithmetic. */
    fun exportNeedKb(vaultKb: Long, split: Boolean): Long =
        VaultTransfer.exportNeedsKb(vaultKb, split, VaultTransfer.EXPORT_HEADROOM_KB)

    /**
     * The import sheet's rows.
     *
     * Ordered by what changes a decision: what it is, how much, whether there is
     * room, where it lands, and then the two consequences nobody was told.
     */
    fun importRows(
        source: ImportSource,
        vaultPath: String,
        freeKb: Long?
    ): List<Row> {
        // Size first: it is the number the whole sheet is about, and the two
        // paths below it are the ones that wrap onto extra lines.
        val rows = mutableListOf<Row>()
        rows += Row("Size", Formatters.formatBytes(source.totalBytes))
        rows += Row("From", source.from)
        rows += Row("Into", vaultPath)
        spaceRow(freeKb, importNeedKb(source))?.let { rows += it }
        source.stagedFirst?.let { rows += Row("First", it) }
        if (source.isSplit) {
            rows += Row(
                "Pieces",
                if (source.piecesMissing == 0) {
                    AppParts.count(source.piecesExpected, "piece") + ", all here"
                } else {
                    AppParts.count(source.piecesHere, "piece") + " of " +
                        source.piecesExpected + " · " +
                        AppParts.count(source.piecesMissing, "piece missing", "pieces missing")
                }
            )
        }
        return rows
    }

    /**
     * The two answers the list row never gave, and the whole reason this sheet
     * exists — as prose rather than as table rows, because they are promises and
     * not facts, and a table makes a promise look like a measurement.
     *
     * `KEEP_DESTINATION` means an archive already at the destination is left
     * alone: an import is a MERGE, and a user who fears being overwritten is
     * asking the right question. The cancel promise is in the same breath
     * because it is the same promise from the other side — cancelling removes
     * staging and never touches the vault.
     */
    fun importNotes(source: ImportSource): List<String> = buildList {
        add("Your backups are kept — nothing is overwritten")
        add(
            if (source.stagedFirst == null) {
                "Cancel part-way any time; the export file is never deleted"
            } else {
                "Cancel part-way any time; nothing already in your vault is touched"
            }
        )
    }

    /**
     * The export sheet's rows, plus the split choice the sheet also makes.
     *
     * "Read only" is qualified rather than asserted flatly, because it is not
     * quite true and a sheet that overstated it would be worse than one that
     * said nothing: the packer writes `<vault>/.export-meta` to carry
     * `automation-state.json` and the folder registry, then removes it. Saying
     * so costs one clause and is the kind of thing someone moving 32 GB around
     * is entitled to know.
     */
    fun exportRows(
        vaultPath: String,
        batchDir: String,
        vaultKb: Long?,
        freeKb: Long?,
        splitBytes: Long
    ): List<Row> {
        val rows = mutableListOf<Row>()
        rows += Row("From", vaultPath)
        if (vaultKb != null && vaultKb > 0) {
            rows += Row("Size", Formatters.formatKb(vaultKb))
        }
        rows += Row("Goes to", batchDir)
        if (vaultKb != null && vaultKb > 0) {
            spaceRow(freeKb, exportNeedKb(vaultKb, splitBytes > 0))?.let { rows += it }
        }
        rows += Row("Inside", "Apps, device data, schedules and folders")
        return rows
    }

    // --- the chrome --------------------------------------------------------

    internal const val IMPORT_TITLE = "Import this backup?"
    internal const val EXPORT_TITLE = "Export your backups?"

    /**
     * What the import is about to do, in one sentence.
     *
     * The cancelling clause is load-bearing. An import is the only thing in the
     * app that writes tens of gigabytes for minutes on a user's command, and
     * "you can cancel it part-way, and cancelling leaves your backups untouched"
     * is the difference between a person reading this and a person bracing for
     * it. It is true: a cancel removes staging and never touches the vault.
     */
    internal fun importSubtitle(source: ImportSource): String =
        "Goes into ${Formatters.formatBytes(source.totalBytes)} of backups on this phone."

    /**
     * The export's one question, which is the split choice.
     *
     * "Parts import the same way — pick any one of them" is not decoration: the
     * single most confusing thing about a split transfer is that a user has to
     * work out that any one piece is enough, and the import screen lists them as
     * seventeen rows.
     */
    internal const val EXPORT_SUBTITLE =
        "One file, or parts that are easier to move."

    /**
     * What the export will produce, restated after the choice is made.
     *
     * Restated rather than left implied, because the radio buttons say "2 GB
     * parts" and the reader has to work out how many that is for THEIR vault —
     * a 32 GB vault at 8 GB parts is four pieces, and knowing that before
     * choosing is the whole point of the choice.
     */
    internal fun exportTargetLine(splitBytes: Long, vaultKb: Long?): String = when {
        splitBytes <= 0 -> "One file containing everything"
        vaultKb == null || vaultKb <= 0 -> "Split into pieces of about " +
            Formatters.formatBytes(splitBytes)
        else -> {
            val pieces = partsFor(splitBytes, vaultKb)
            if (pieces <= 1) {
                "Small enough to be one file, even split at " +
                    Formatters.formatBytes(splitBytes)
            } else {
                "About ${AppParts.count(pieces, "piece")} of about " +
                    Formatters.formatBytes(splitBytes) + " — any one imports"
            }
        }
    }

    /**
     * Why a split needs twice the room, in one line, and only when it does.
     *
     * A sheet that states a correct figure without its reason is a sheet that
     * makes the figure look like a bug — and 65 GB demanded for a 32 GB vault
     * reads as exactly that. The number is right: the packer writes the single
     * file, verifies it, splits it, checks every piece, and only then deletes
     * the single file (`VaultTransferManager`), so for a while both exist. The
     * alternative is streaming `tar | split` at half the cost, which gives up
     * any way to fall back to a whole archive when a split dies half-done.
     *
     * Empty for One file, because there is nothing to explain.
     */
    internal fun exportNotes(splitBytes: Long, vaultKb: Long?): List<String> {
        if (splitBytes <= 0) return emptyList()
        val pieces = partsFor(splitBytes, vaultKb)
        return listOf(
            if (pieces > 1) {
                "Splitting keeps the whole file until all " +
                    AppParts.count(pieces, "piece") +
                    " are checked, so both exist for a while."
            } else {
                "This split size still makes one file, so it needs no extra room."
            }
        )
    }

    /** Pieces a split of [splitBytes] over a vault of [vaultKb] would produce. */
    internal fun partsFor(splitBytes: Long, vaultKb: Long?): Int {
        if (splitBytes <= 0) return 1
        val kb = vaultKb ?: return 0
        return ((kb * 1024L) / splitBytes).toInt().coerceAtLeast(1)
    }
}