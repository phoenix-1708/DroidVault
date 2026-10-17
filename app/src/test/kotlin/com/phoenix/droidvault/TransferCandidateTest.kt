package com.phoenix.droidvault

import com.phoenix.droidvault.models.TransferCandidate
import com.phoenix.droidvault.models.TransferCandidates
import com.phoenix.droidvault.models.TransferFile
import com.phoenix.droidvault.models.TransferState
import com.phoenix.droidvault.models.VaultTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules behind the Import page.
 *
 * These exist because the page makes claims it cannot otherwise check: that a
 * set of pieces is whole, and that a row may be tapped. Everything here is
 * pure — a list of files in, a list of rows out — because the shell read cannot
 * run in a unit test and the decisions must not depend on it.
 */
class TransferCandidateTest {

    private val exportDir = "/storage/emulated/0/Download/DroidVault/DV-20261014-1829"

    /** Seventeen pieces, the real shape of a 34 GB transfer on the device. */
    private fun splitSet(
        dir: String = exportDir,
        base: String = "DV-20261014-1829.dvbackup.tar",
        parts: Int = 17,
        bytesEach: Long = 2_147_483_648L,
        totalBytes: Long = 34_852_793_344L,
        present: Int = parts,
        mtime: Long = 1_798_000_000_000L
    ): Pair<List<TransferFile>, List<String>> {
        val names = VaultTransfer.expectedPartNames(base, parts)!!
        val files = names.take(present).mapIndexed { i, name ->
            TransferFile(
                path = "$dir/$name",
                // The last piece is whatever is left over, exactly as split wrote it.
                bytes = if (i == present - 1 && present == parts) {
                    totalBytes - bytesEach * (parts - 1)
                } else bytesEach,
                modifiedMs = mtime
            )
        }
        val manifest = """{"parts":$parts,"totalBytes":$totalBytes,""" +
            """"partBytes":$bytesEach,"base":"$base"}"""
        return files to listOf(manifest)
    }

    @Test
    fun `seventeen pieces are ONE row, not seventeen`() {
        val (files, manifests) = splitSet()
        val found = TransferCandidates.discover(files, manifests)

        assertEquals("an export is one thing, however many files it is made of", 1, found.size)
        val c = found.first()
        assertEquals(17, c.sourceParts.size)
        assertEquals("17 parts", c.partsLabel)
        assertTrue(c.isSplit)
    }

    @Test
    fun `pieces are offered in STREAM order, not filesystem order`() {
        val (files, manifests) = splitSet(parts = 17)
        // A directory listing arrives in whatever order the filesystem feels like.
        val shuffled = files.shuffled()
        val c = TransferCandidates.discover(shuffled, manifests).first()

        val expected = VaultTransfer.expectedPartNames("DV-20261014-1829.dvbackup.tar", 17)!!
        c.sourceParts.forEachIndexed { i, path ->
            assertEquals(
                "piece ${i + 1} must be the ${i + 1}th byte-run of the stream",
                "$exportDir/${expected[i]}", path
            )
        }
    }

    @Test
    fun `a complete set is COMPLETE and importable`() {
        val (files, manifests) = splitSet()
        val c = TransferCandidates.discover(files, manifests).first()

        assertEquals(TransferState.COMPLETE, c.state)
        assertTrue(c.canImport)
        assertEquals(emptyList<String>(), c.missing)
        assertEquals("no problem to report", null, c.problem)
        assertEquals(34_852_793_344L, c.totalBytes)
    }

    /**
     * The case §2.29 is about, and the reason a count exists at all.
     *
     * Sorted suffixes prove order and never completeness: pieces missing off the
     * END leave a set that looks complete, and it reads as fine until the bytes
     * run out mid-import. Counting what is there instead of comparing it against
     * what was recorded would have passed this.
     */
    @Test
    fun `a set missing pieces off the END is refused`() {
        val (files, manifests) = splitSet(present = 14)
        val c = TransferCandidates.discover(files, manifests).first()

        assertEquals(TransferState.INCOMPLETE, c.state)
        assertFalse("a short set must not be tappable", c.canImport)
        assertEquals(3, c.missing.size)
        assertTrue("the refusal must name what is wrong", c.problem!!.contains("3 parts missing"))
    }

    @Test
    fun `a set missing a MIDDLE piece is refused too`() {
        val (files, manifests) = splitSet(parts = 5)
        val holed = files.filterNot { it.name.endsWith("part-ac") }
        val c = TransferCandidates.discover(holed, manifests).first()

        assertEquals(TransferState.INCOMPLETE, c.state)
        assertEquals(listOf("DV-20261014-1829.dvbackup.tar.part-ac"), c.missing)
    }

    @Test
    fun `every piece present but a size wrong is DAMAGED, not COMPLETE`() {
        val (files, manifests) = splitSet(parts = 3, present = 3)
        // Same count, one piece truncated by a failed copy.
        val tampered = files.mapIndexed { i, f ->
            if (i == 1) f.copy(bytes = f.bytes - 1) else f
        }
        val c = TransferCandidates.discover(tampered, manifests).first()

        assertEquals(TransferState.DAMAGED, c.state)
        assertFalse(c.canImport)
        assertTrue(c.problem!!.contains("damaged") || c.problem!!.contains("add up"))
    }

    @Test
    fun `a single file export is COMPLETE with no manifest`() {
        val file = TransferFile(
            path = "/storage/emulated/0/Download/DV-20260928-1603.dvbackup.tar",
            bytes = 1_100_000_000L,
            modifiedMs = 1_797_000_000_000L
        )
        val c = TransferCandidates.discover(listOf(file)).first()

        assertEquals(TransferState.COMPLETE, c.state)
        assertTrue(c.canImport)
        assertFalse(c.isSplit)
        assertEquals("Single file", c.partsLabel)
        assertEquals(emptyList<String>(), c.sourceParts)
        assertEquals("/storage/emulated/0/Download/DV-20260928-1603.dvbackup.tar", c.importPath)
    }

    /**
     * A split set with nothing recorded beside it.
     *
     * Not COMPLETE, because completeness is exactly what cannot be established —
     * but not INCOMPLETE either, because nothing is known to be absent. The user
     * is told the truth and allowed to try; the importer checks before it writes.
     */
    @Test
    fun `a split set with no manifest is UNVERIFIED, not silently COMPLETE`() {
        val names = VaultTransfer.expectedPartNames("DV-x.dvbackup.tar", 4)!!
        val files = names.map { TransferFile("$exportDir/$it", 100L, 1_798_000_000_000L) }
        val c = TransferCandidates.discover(files).first()

        assertEquals(TransferState.UNVERIFIED, c.state)
        assertTrue("nothing is known to be missing, so it stays importable", c.canImport)
        assertEquals("4 parts", c.partsLabel)
    }

    @Test
    fun `a corrupt manifest reads as absent, never as a crash`() {
        val (files, _) = splitSet()
        val c = TransferCandidates.discover(
            files,
            listOf("{not json at all")
        ).first()

        assertEquals(TransferState.UNVERIFIED, c.state)
        assertTrue(c.canImport)
    }

    @Test
    fun `newest first, because the export you want is the one you just made`() {
        val (old, oldM) = splitSet(dir = "/d/old", base = "DV-old.dvbackup.tar", mtime = 1_000L)
        val (new, newM) = splitSet(dir = "/d/new", base = "DV-new.dvbackup.tar", mtime = 9_000L)
        val found = TransferCandidates.discover(old + new, oldM + newM)

        assertEquals(listOf("/d/new", "/d/old"), found.map { it.dir })
    }

    @Test
    fun `the listing is capped, and keeps the most recent`() {
        val all = (1..120).map { i ->
            TransferFile(
                path = "/d/DV-$i.dvbackup.tar",
                bytes = 10L,
                modifiedMs = i * 1000L
            )
        }
        val found = TransferCandidates.discover(all, cap = 5)

        assertEquals(5, found.size)
        assertEquals("/d/DV-120.dvbackup.tar", found.first().importPath)
    }

    @Test
    fun `only transfer files are listed — no digests, no manifests, no temporaries`() {
        val noise = listOf(
            TransferFile("$exportDir/x.dvbackup.tar.sha256", 60L, 1L),
            TransferFile("$exportDir/x.dvbackup.tar.parts.json", 99L, 1L),
            TransferFile("$exportDir/.dv-export-abc.tmp", 10L, 1L),
            TransferFile("$exportDir/notes.txt", 10L, 1L),
            TransferFile("/somewhere/random.zip", 10L, 1L)
        )
        assertEquals(emptyList<TransferCandidate>(), TransferCandidates.discover(noise))
    }

    @Test
    fun `a piece is recognised by its base, not by ending in the extension`() {
        // The piece does NOT end in .dvbackup.tar — that is the whole reason a
        // naive looksLikeTransferFile test would drop all seventeen of them.
        assertTrue(TransferCandidates.isTransferFile("DV-x.dvbackup.tar"))
        assertTrue(TransferCandidates.isTransferFile("DV-x.dvbackup.tar.part-aa"))
        assertTrue(TransferCandidates.isTransferFile("DV-x.dvbackup.tar.gz.part-aa"))
        assertFalse(TransferCandidates.isTransferFile("DV-x.dvbackup.tar.sha256"))
        assertFalse(TransferCandidates.isTransferFile("DV-x.dvbackup.tar.parts.json"))
        assertFalse(TransferCandidates.isTransferFile("photo.jpg"))
    }

    @Test
    fun `two exports in one folder stay two rows`() {
        val (a, am) = splitSet(dir = "/d", base = "DV-a.dvbackup.tar", parts = 2, totalBytes = 200L, bytesEach = 100L)
        val (b, bm) = splitSet(dir = "/d", base = "DV-b.dvbackup.tar", parts = 2, totalBytes = 300L, bytesEach = 150L)
        val found = TransferCandidates.discover(a + b, am + bm)

        assertEquals(2, found.size)
        assertEquals(setOf("DV-a.dvbackup.tar", "DV-b.dvbackup.tar"), found.map { it.base }.toSet())
    }

    @Test
    fun `nothing on disk is an empty list, not an error`() {
        assertEquals(emptyList<TransferCandidate>(), TransferCandidates.discover(emptyList()))
    }

    @Test
    fun `a split export is quarantined as its folder, not piece by piece`() {
        // Seventeen files moved one at a time is seventeen chances for a partial
        // delete — and a manifest left behind describing pieces that are gone.
        // The folder is the thing that moves, and it comes back as one unit.
        val (files, manifests) = splitSet()
        val c = TransferCandidates.discover(files, manifests).single()

        assertEquals(listOf(exportDir), c.trashTargets)
    }

    @Test
    fun `a loose archive is quarantined by its own path`() {
        // A single-file export in the Downloads root has no folder of its own,
        // so naming the folder would take the user's other downloads with it.
        val loose = "/storage/emulated/0/Download/DV-20261014-1829.dvbackup.tar"
        val files = listOf(TransferFile(loose, 1_000L, 1_798_000_000_000L))
        val c = TransferCandidates.discover(files).single()

        assertFalse(c.isSplit)
        assertEquals(listOf(loose), c.trashTargets)
    }

    @Test
    fun `the delete names a size in the same terms the row does`() {
        // The space a delete gives back is the claim the toast makes, so it has
        // to come from the same recorded total the row shows.
        val (files, manifests) = splitSet()
        val c = TransferCandidates.discover(files, manifests).single()

        assertEquals("34.9 GB", c.sizeLabel)
    }
}
