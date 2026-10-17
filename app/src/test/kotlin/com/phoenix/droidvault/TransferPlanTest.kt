package com.phoenix.droidvault

import com.phoenix.droidvault.models.TransferCandidate
import com.phoenix.droidvault.models.TransferPlan
import com.phoenix.droidvault.models.VaultTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the two confirmation sheets say, and the one decision they make.
 *
 * Both transfers used to start on one tap. An export is read-only, so that was
 * merely reckless. An import is not: one tap began a multi-minute write that
 * expands the transfer into the vault, and nothing on screen answered the
 * question the row provokes — *what happens to the backups already on this
 * phone*. The answer is reassuring (`Merge.KEEP_DESTINATION` keeps them) and an
 * answer that reassuring belongs in front of someone.
 */
class TransferPlanTest {
    /**
     * 32 GB expressed in KB, which is the unit every `*Kb` helper takes.
     *
     * Named because getting it wrong is silent and twice now: a test that
     * passed `32_768` for a 32 GB vault is passing 32 **MB**, and `partsFor`
     * answered "one file" with total confidence.
     */
    private val vault32GbKb = 32L * 1024 * 1024


    private fun whole(bytes: Long = 34_852_793_344L) = TransferCandidate(
        dir = "/storage/emulated/0/Download/DroidVault/DV-20261016-0736",
        base = "DV-20261016-0736.dvbackup.tar",
        importPath = "/storage/emulated/0/Download/DroidVault/DV-20261016-0736/DV-20261016-0736.dvbackup.tar",
        sourceParts = emptyList(),
        sizes = listOf(bytes),
        manifest = null,
        modifiedMs = 1_798_000_000_000L
    )

    private fun split(
        pieces: Int,
        here: Int,
        totalBytes: Long = 34_852_793_344L
    ): TransferCandidate {
        val dir = "/storage/emulated/0/Download/DroidVault/DV-20261016-0736"
        val base = "DV-20261016-0736.dvbackup.tar"
        return TransferCandidate(
            dir = dir,
            base = base,
            importPath = "$dir/${VaultTransfer.expectedPartNames(base, pieces)?.first()}",
            // Real part names: `missingParts` matches siblings BY NAME, so a
            // fixture with invented names reports a complete set as short and
            // the test would have been asserting a broken fixture.
            sourceParts = VaultTransfer.expectedPartNames(base, here)
                ?.map { "$dir/$it" } ?: emptyList(),
            sizes = List(here) { 2_097_152_000L },
            manifest = VaultTransfer.PartsManifest(
                base = base, parts = pieces, totalBytes = totalBytes, partBytes = 2_097_152_000L
            ),
            modifiedMs = 1_798_000_000_000L
        )
    }

    private fun rowsOf(rows: List<TransferPlan.Row>, label: String): String? =
        rows.firstOrNull { it.label == label }?.value

    // --- the two answers the sheet exists for ------------------------------

    @Test
    fun `the import sheet says what happens to existing backups`() {
        // This is the whole point. An import is a MERGE — `Merge.KEEP_DESTINATION`
        // leaves an archive already at the destination alone — and a user who
        // fears being overwritten should not have to guess about it.
        val notes = TransferPlan.importNotes(TransferPlan.sourceOf(whole()))
        assertTrue(
            "the sheet does not say backups are kept",
            notes.any { it.contains("nothing is overwritten") }
        )
    }

    @Test
    fun `the promises are not table rows`() {
        // A table makes a promise look like a measurement. The reassurance reads
        // as prose under the facts, which is why it is not in importRows at all.
        val rows = TransferPlan.importRows(TransferPlan.sourceOf(whole()), "/vault", null)
        assertNull(rowsOf(rows, "Your existing backups"))
        assertNull(rowsOf(rows, "This export file"))
    }

    @Test
    fun `the import sheet says the export file survives`() {
        // It is true and it is the question everyone asks twice: does importing
        // consume the file? It never has — the list is deliberately not cleared
        // after an import, because clearing it produced "No exports on this phone".
        val notes = TransferPlan.importNotes(TransferPlan.sourceOf(whole()))
        assertTrue(
            "the sheet does not address the export file",
            notes.any { it.contains("never deleted") }
        )
    }

    @Test
    fun `a listed import names where it came from and where it lands`() {
        val c = whole()
        val rows = TransferPlan.importRows(TransferPlan.sourceOf(c), "/storage/emulated/0/DroidVault", null)
        assertEquals(c.dir, rowsOf(rows, "From"))
        assertEquals("/storage/emulated/0/DroidVault", rowsOf(rows, "Into"))
    }

    @Test
    fun `the size shown is the real total`() {
        val rows = TransferPlan.importRows(TransferPlan.sourceOf(whole()), "/vault", null)
        assertEquals("32.46 GB", rowsOf(rows, "Size"))
    }

    // --- pieces ------------------------------------------------------------

    @Test
    fun `a whole file reports no pieces`() {
        // Omitting the row beats saying "1 piece" about something that is one file.
        val rows = TransferPlan.importRows(TransferPlan.sourceOf(whole()), "/vault", null)
        assertNull(rowsOf(rows, "Pieces"))
    }

    @Test
    fun `a complete set says so`() {
        val c = split(3, 3)
        val rows = TransferPlan.importRows(TransferPlan.sourceOf(c), "/vault", null)
        assertEquals("3 pieces, all here", rowsOf(rows, "Pieces"))
    }

    @Test
    fun `a short set says what is missing, not just that it is incomplete`() {
        // The user has to be able to act on this: "4 pieces here of 17" tells
        // them thirteen are elsewhere, which "Not complete" does not.
        val c = split(17, 4)
        val rows = TransferPlan.importRows(TransferPlan.sourceOf(c), "/vault", null)
        val parts = rowsOf(rows, "Pieces")!!
        assertTrue("'$parts' does not count what is here", parts.startsWith("4 pieces of 17"))
        assertTrue("'$parts' does not count what is missing", parts.contains("13 pieces missing"))
    }

    // --- space -------------------------------------------------------------

    @Test
    fun `a space figure that was not measured is omitted, never zero`() {
        // §2.79's shape with the volume turned down: a failed probe rendered as
        // "0 GB free" would be the app asserting the phone is full.
        val rows = TransferPlan.importRows(TransferPlan.sourceOf(whole()), "/vault", null)
        assertNull("an unmeasured space must not render a row", rowsOf(rows, "Space"))
    }

    @Test
    fun `space is reported as free and needed`() {
        val free = 70L * 1024 * 1024          // 70 GB
        val rows = TransferPlan.importRows(TransferPlan.sourceOf(whole()), "/vault", free)
        val space = rowsOf(rows, "Space")!!
        assertTrue("'$space' does not say what is free", space.contains("70.00 GB free"))
        assertTrue("'$space' does not say what is needed", space.contains("needed"))
        // "about", because the gate surveys the real archive when it runs.
        assertTrue(
            "'$space' states an estimate as exact",
            space.contains("about")
        )
    }

    @Test
    fun `the sheet and the gate compute the same need`() {
        // The whole reason the headroom constants moved into VaultTransfer: two
        // copies of this number is two answers waiting to disagree, and a dialog
        // that says 40 GB is fine while the gate refuses 40 GB is worse than
        // either.
        val c = whole()
        assertEquals(
            VaultTransfer.importNeedsKb(
                TransferPlan.unpackKb(TransferPlan.sourceOf(c)),
                VaultTransfer.IMPORT_HEADROOM_KB
            ),
            TransferPlan.importNeedKb(TransferPlan.sourceOf(c))
        )
        assertEquals(
            VaultTransfer.exportNeedsKb(1024L, true, VaultTransfer.EXPORT_HEADROOM_KB),
            TransferPlan.exportNeedKb(1024L, true)
        )
        assertEquals(
            VaultTransfer.exportNeedsKb(1024L, false, VaultTransfer.EXPORT_HEADROOM_KB),
            TransferPlan.exportNeedKb(1024L, false)
        )
    }

    @Test
    fun `splitting needs room for two copies at once`() {
        // The pieces are verified against the single file before it is deleted,
        // so both exist by design — a gate that budgeted for one refused work
        // that would have fitted, which is the direction that actually annoys.
        assertTrue(
            TransferPlan.exportNeedKb(1000L, true) > TransferPlan.exportNeedKb(1000L, false)
        )
    }

    // --- the disabled button -----------------------------------------------

    @Test
    fun `an unmeasured space is allowed to try, because the gate is the authority`() {
        // Null is NOT permission in the permissive direction being assumed here:
        // the run re-measures and refuses honestly if it is short. Blocking on
        // an unknown would refuse work that would have fitted.
        assertTrue(TransferPlan.canStart(null, 100L))
        assertNull(TransferPlan.shortfallNote(null, 100L))
    }

    @Test
    fun `a measured shortfall blocks, and says by how much`() {
        val need = 40L * 1024 * 1024
        val free = 10L * 1024 * 1024
        assertFalse(TransferPlan.canStart(free, need))
        val note = TransferPlan.shortfallNote(free, need)
        assertTrue("no note for a dead button: '$note'", note != null)
        assertTrue(
            "'$note' does not name what would enable it",
            note!!.contains("30.00 GB", ignoreCase = true) &&
                note.contains("Free up", ignoreCase = true)
        )
    }

    @Test
    fun `exactly enough is enough`() {
        val need = 40L * 1024 * 1024
        assertTrue(TransferPlan.canStart(need, need))
        assertNull(TransferPlan.shortfallNote(need, need))
    }

    // --- export ------------------------------------------------------------

    @Test
    fun `the export sheet carries no reassurance section`() {
        // Asked for and deliberately absent. An export does not touch your
        // backups, so a line promising it will not is a line about something the
        // user did not ask about, and it pushed the real facts — the size, the
        // destination, the space — down a screen they now have to scroll.
        val rows = TransferPlan.exportRows("/vault", "/dl/DV-1", 1024L, null, 0L)
        assertNull(rowsOf(rows, "Your backups"))
        assertNull(rowsOf(rows, "Read only"))
        // And the facts it does carry are all still there.
        for (label in listOf("Size", "From", "Goes to", "Inside")) {
            assertTrue("the export sheet lost its '$label' row", rowsOf(rows, label) != null)
        }
    }

    @Test
    fun `the export sheet names its destination folder`() {
        val rows = TransferPlan.exportRows("/vault", "/dl/DV-20261016-0736", 1024L, null, 0L)
        assertEquals("/dl/DV-20261016-0736", rowsOf(rows, "Goes to"))
    }

    @Test
    fun `an unmeasured vault size omits the rows that depend on it`() {
        // Both the size and the space figure are derived from it, and a space
        // figure derived from a size nobody has is a guess wearing a unit.
        val rows = TransferPlan.exportRows("/vault", "/dl/DV-1", null, null, 0L)
        assertNull(rowsOf(rows, "Size"))
        assertNull(rowsOf(rows, "Space"))
        assertEquals("/vault", rowsOf(rows, "From"))
    }

    @Test
    fun `the split choice is restated as a piece count`() {
        // "8 GB parts" against a 32 GB vault is four pieces, and working that
        // out before choosing is the whole point of choosing.
        val vaultBytes = 32L * 1024 * 1024 * 1024
        assertEquals(
            // "Any one imports" rides here rather than in the subtitle, which
            // had grown to two lines and pushed the button off the screen in the
            // worst case — a split chosen on a vault with too little room.
            "About 4 pieces of about 8.00 GB — any one imports",
            TransferPlan.exportTargetLine(8L * 1024 * 1024 * 1024, vaultBytes / 1024)
        )
        assertEquals(
            "One file containing everything",
            TransferPlan.exportTargetLine(0L, vaultBytes / 1024)
        )
        // A split size larger than the vault is not an error, it is one file.
        assertTrue(
            TransferPlan.exportTargetLine(64L * 1024 * 1024 * 1024, 1024L)
                .startsWith("Small enough")
        )
    }

    @Test
    fun `a piece count is at least one`() {
        // The split size is a BYTE count and the vault size a KB count. The
        // first version of this test passed KB for both and expected 4 pieces
        // and got 4096 — which is exactly what "1024 KB split into 256 byte
        // pieces" is. A 1 MB vault split at 256 KB is four pieces.
        assertEquals(1, TransferPlan.partsFor(0L, 1024L))
        assertEquals(4, TransferPlan.partsFor(256L * 1024L, 1024L))
        // A split size bigger than the vault is one piece, never zero or less.
        assertEquals(1, TransferPlan.partsFor(4096L * 1024L * 1024L, 1024L))
    }

    // --- the reason a split needs twice the room ---------------------------

    @Test
    fun `a split says why it needs the extra room`() {
        // 65 GB demanded for a 32 GB vault reads as a bug unless the sheet says
        // why. The why is real: the packer writes the single file, verifies it,
        // splits it, checks every piece and only then deletes the single file,
        // so both exist for a while.
        val notes = TransferPlan.exportNotes(8L * 1024 * 1024 * 1024, vault32GbKb)
        assertEquals(1, notes.size)
        val line = notes.single()
        assertTrue("does not name the pieces: '$line'", line.contains("4 pieces"))
        assertTrue("does not say why: '$line'", line.contains("both exist"))
    }

    @Test
    fun `one file explains nothing, because there is nothing to explain`() {
        // A line about room when the space figure is already 1x plus headroom
        // is noise, and noise on a confirmation is what makes people stop
        // reading them.
        assertTrue(TransferPlan.exportNotes(0L, vault32GbKb).isEmpty())
    }

    @Test
    fun `a split size that still yields one file says that instead`() {
        val notes = TransferPlan.exportNotes(64L * 1024 * 1024 * 1024, vault32GbKb)
        assertEquals(1, notes.size)
        assertTrue(
            "claims both copies exist when there is only one file: '${notes.single()}'",
            notes.single().contains("no extra room")
        )
    }

    @Test
    fun `the split reason shows no brace`() {
        // The same property `UserFacingStringTest` asserts for every other
        // producer, checked here because these are new lines of prose and the
        // brace bug has shipped three times in this app (§2.72).
        for (line in TransferPlan.exportNotes(8L * 1024 * 1024 * 1024, vault32GbKb) +
            TransferPlan.exportNotes(64L * 1024 * 1024 * 1024, vault32GbKb)) {
            assertTrue(
                "'$line' shows a brace to the user",
                !line.contains('{') && !line.contains('}')
            )
        }
    }
}
