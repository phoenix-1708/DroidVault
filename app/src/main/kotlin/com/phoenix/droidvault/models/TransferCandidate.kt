package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * One file on disk that might belong to an export.
 *
 * Deliberately just the three facts discovery needs, so the whole scan can be
 * exercised from a test with no shell and no filesystem.
 */
@Immutable
data class TransferFile(
    val path: String,
    val bytes: Long,
    val modifiedMs: Long
) {
    val name: String get() = path.substringAfterLast('/')
    val dir: String get() = path.substringBeforeLast('/', "")
}

/**
 * How much a set of pieces can be trusted, which is not the same question as
 * "are the files there".
 */
enum class TransferState {
    /** A recorded count matches what is on disk, and the bytes add up. */
    COMPLETE,

    /** Recorded as more pieces than exist. */
    INCOMPLETE,

    /** All the pieces are here, but a size or the byte sum disagrees. */
    DAMAGED,

    /**
     * Split, with no manifest beside it.
     *
     * Not a failure — but not a claim either. Sorted suffixes prove order and
     * never completeness: pieces missing off the *end* leave a set that looks
     * whole, and that set reads as fine until the bytes run out mid-import. So
     * this stays importable and says what it cannot promise.
     */
    UNVERIFIED
}

/**
 * One export, assembled from the pieces that belong to it.
 *
 * A 34 GB export is seventeen `.part-aa`…`.part-aq` files and a manifest. Those
 * seventeen are one thing to the person who made them and seventeen rows to
 * anything that lists a directory, which is why this exists: the unit of choice
 * is the export, never the piece.
 *
 * Everything here is pure. [discover] takes a flat file list and returns the
 * choices, so the whole rule — grouping, ordering, and whether a set is whole —
 * is testable without a device.
 */
@Immutable
data class TransferCandidate(
    val dir: String,
    val base: String,
    /** What to hand the importer: the first piece, or the archive itself. */
    val importPath: String,
    /** Full paths in stream order; empty when the export is a single file. */
    val sourceParts: List<String>,
    val sizes: List<Long>,
    val manifest: VaultTransfer.PartsManifest?,
    val modifiedMs: Long
) {
    val isSplit: Boolean get() = sourceParts.isNotEmpty()

    /** Bytes on disk, preferring the manifest's own figure when it has one. */
    val totalBytes: Long get() = manifest?.totalBytes ?: sizes.sum()

    /** Pieces recorded but absent. Empty unless [state] is INCOMPLETE. */
    val missing: List<String>
        get() = manifest?.let {
            VaultTransfer.missingParts(base, it.parts, sourceParts)
        } ?: emptyList()

    val state: TransferState
        get() = when {
            manifest == null && !isSplit -> TransferState.COMPLETE
            manifest == null -> TransferState.UNVERIFIED
            missing.isNotEmpty() -> TransferState.INCOMPLETE
            // check() re-tests the count and every size, and sums the bytes: the
            // byte sum is the part a count alone cannot cover.
            manifest.check(sizes) != null -> TransferState.DAMAGED
            else -> TransferState.COMPLETE
        }

    /**
     * Why this set is not whole, in words the screen can show verbatim.
     *
     * Null when there is nothing wrong *or* nothing provable — UNVERIFIED has a
     * line of its own in the UI, because "no problem found" and "nothing to
     * check" are different sentences.
     */
    val problem: String?
        get() = when (state) {
            TransferState.INCOMPLETE ->
                "${AppParts.count(missing.size, "part")} missing — " +
                    "copy every ${base}${VaultTransfer.PART_SEP} file back"
            TransferState.DAMAGED -> manifest?.check(sizes)
            else -> null
        }

    /**
     * Whether tapping this row may start an import.
     *
     * A short set is refused rather than attempted: the run would discover the
     * gap by running out of bytes partway through, after having already staged
     * whatever it managed to read.
     */
    val canImport: Boolean
        get() = state != TransferState.INCOMPLETE && state != TransferState.DAMAGED

    /** "17 parts" / "Single file". Counts go through [AppParts.count]. */
    val partsLabel: String
        get() = if (isSplit) AppParts.count(sourceParts.size, "part") else "Single file"

    /**
     * Every file this export is made of, and the one path to hand the trash.
     *
     * A split export is a FOLDER of pieces plus its manifest, and the folder is
     * the thing to quarantine: moving seventeen files one at a time is
     * seventeen chances for a partial delete, and the manifest would be left
     * behind describing pieces that no longer exist. The folder is
     * self-describing, so it moves as one unit and comes back as one unit.
     *
     * A loose archive in the Downloads root has no folder of its own —
     * `reapInProgressExports` documents that those exist — so it is named
     * individually. Nothing is left beside it on purpose: an export carries no
     * `.sha256` of its own (checked against the folder the app actually wrote,
     * which holds seventeen pieces and the manifest and nothing else), so there
     * is no digest left describing an archive that is no longer there.
     */
    val trashTargets: List<String>
        get() = if (isSplit) listOf(dir) else listOf(importPath)

    /** "32.46 GB" — the space a delete gets back, in the user's terms. */
    val sizeLabel: String get() = TransferProgress.humanBytes(totalBytes)
}

object TransferCandidates {

    /**
     * Cap on how many exports one listing will offer.
     *
     * Discovery runs over a user's Downloads folder, which is not a folder this
     * app controls. A cap keeps a directory with a thousand stray archives from
     * turning the page into a scroll, and the cap is applied after sorting so
     * what survives is the most recent.
     */
    const val DEFAULT_CAP = 50

    /**
     * True for a file that belongs to some export — the archive itself, or a
     * piece of one.
     *
     * A piece does not end in the extension, so it is stripped back to its base
     * before the test. Manifests, digests and in-progress temporaries all fail
     * it, which is how they are excluded without naming each one.
     */
    fun isTransferFile(name: String): Boolean {
        if (VaultTransfer.isPartName(name)) {
            return VaultTransfer.splitPrefix(name)?.let {
                VaultTransfer.looksLikeTransferFile(it)
            } ?: false
        }
        return VaultTransfer.looksLikeTransferFile(name)
    }

    /**
     * Group a flat file list into the exports a person would recognise.
     *
     * [manifestJson] is the raw text of every `*.parts.json` found; which folder
     * it sat in is NOT used. That is deliberate, and the first version got it
     * wrong: indexing manifests by directory meant two exports sharing a folder
     * both adopted the second one's recorded base and collapsed into a single
     * row — one export silently replacing another, in a screen whose whole job
     * is to not lose them. A manifest names its own archive in its `base` field,
     * so that is what it is matched on, and the directory drops out of the
     * question entirely.
     *
     * Newest first, capped. Returns nothing for an empty input rather than an
     * empty row in the UI.
     */
    fun discover(
        files: List<TransferFile>,
        manifestJson: List<String> = emptyList(),
        cap: Int = DEFAULT_CAP
    ): List<TransferCandidate> {
        // Index by the base each manifest declares. A manifest whose pieces are
        // all gone simply matches nothing, which is correct: there is nothing to
        // restore, and an empty row would be a worse answer than no row.
        val manifests = manifestJson
            .mapNotNull { VaultTransfer.PartsManifest.fromJson(it) }
            .filter { it.base.isNotBlank() }
            .associateBy { it.base }

        // dir -> base -> pieces found
        val grouped = LinkedHashMap<String, LinkedHashMap<String, MutableList<TransferFile>>>()
        for (f in files) {
            if (!isTransferFile(f.name)) continue
            val base = VaultTransfer.splitPrefix(f.name) ?: f.name
            grouped.getOrPut(f.dir) { LinkedHashMap() }
                .getOrPut(base) { mutableListOf() }
                .add(f)
        }

        val out = mutableListOf<TransferCandidate>()
        for ((dir, byBase) in grouped) {
            for ((base, pieces) in byBase) {
                val manifest = manifests[base]
                val ordered = pieces.sortedBy { it.name }
                // Stream order, not alphabetical-by-accident: with a recorded
                // count the expected names define it exactly.
                val streamOrder = manifest?.let {
                    VaultTransfer.expectedPartNames(base, it.parts)
                        ?.filter { want -> ordered.any { it.name == want } }
                        ?: ordered.map { it.name }
                } ?: ordered.map { it.name }
                val inOrder = ordered.sortedBy { f ->
                    streamOrder.indexOf(f.name).let { if (it < 0) Int.MAX_VALUE else it }
                }
                val split = inOrder.size > 1 || VaultTransfer.isPartName(base)
                out += TransferCandidate(
                    dir = dir,
                    base = base,
                    importPath = inOrder.firstOrNull()?.path ?: "",
                    sourceParts = if (split) inOrder.map { it.path } else emptyList(),
                    sizes = inOrder.map { it.bytes },
                    manifest = manifest,
                    modifiedMs = inOrder.maxOfOrNull { it.modifiedMs } ?: 0L
                )
            }
        }

        return out.sortedWith(
            compareByDescending<TransferCandidate> { it.modifiedMs }
                .thenBy { it.base }
        ).take(if (cap > 0) cap else out.size)
    }
}
