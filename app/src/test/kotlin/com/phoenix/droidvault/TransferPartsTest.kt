package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.VaultTransfer
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split transfers name their pieces `<archive>.part-aa`, `<archive>.part-ab`, …
 * and record the set in `<archive>.parts.json`. These pin the naming, the
 * completeness check, and the manifest gate — because a set that looks
 * complete but is short writes a truncated vault that reports success.
 */
class TransferPartsTest {

    private val base = "DroidVault-1.dvbackup.tar.gz"

    @Test
    fun `piece names are recognised and whole archives are not`() {
        assertTrue(VaultTransfer.isPartName("$base.part-aa"))
        assertTrue(VaultTransfer.isPartName("$base.part-zz"))
        assertFalse(VaultTransfer.isPartName(base))
        assertFalse(VaultTransfer.isPartName("$base.part-a"))
        assertFalse(VaultTransfer.isPartName("$base.part-aaa"))
        assertFalse(VaultTransfer.isPartName("$base.part-Aa"))
    }

    @Test
    fun `the prefix strips exactly one suffix`() {
        assertEquals(base, VaultTransfer.splitPrefix("$base.part-ab"))
        assertNull(VaultTransfer.splitPrefix(base))
    }

    @Test
    fun `expected names run aa ab then ba`() {
        val names = VaultTransfer.expectedPartNames(base, 28)!!
        assertEquals("$base.part-aa", names[0])
        assertEquals("$base.part-ab", names[1])
        assertEquals("$base.part-az", names[25])
        assertEquals("$base.part-ba", names[26])
        assertEquals("$base.part-bb", names[27])
    }

    @Test
    fun `more than 676 pieces are refused, not misnamed`() {
        assertNull(VaultTransfer.expectedPartNames(base, 677))
        assertNull(VaultTransfer.expectedPartNames(base, 0))
    }

    @Test
    fun `a gap anywhere in the set is reported`() {
        val full = VaultTransfer.expectedPartNames(base, 3)!!
        assertTrue(VaultTransfer.missingParts(base, 3, full).isEmpty())
        // Order of the listing must not matter.
        assertTrue(VaultTransfer.missingParts(base, 3, full.reversed()).isEmpty())
        assertEquals(
            listOf("$base.part-ab"),
            VaultTransfer.missingParts(base, 3, listOf(full[0], full[2]))
        )
    }

    @Test
    fun `a manifest checks count sizes and sum`() {
        val m = VaultTransfer.PartsManifest(base, 3, 10L, 4L)
        // 4 + 4 + 2 = 10: the last piece holds the remainder.
        assertNull(m.check(listOf(4, 4, 2)))
        // A short set fails on count before sizes are even looked at.
        assertTrue(m.check(listOf(4, 4))!!.contains("2 of 3"))
        // A damaged middle piece names itself.
        assertTrue(m.check(listOf(4, 3, 2))!!.contains("part 2 of 3"))
        // A short last piece names itself (the per-piece check runs first,
        // which is better: it says which piece to copy again).
        assertTrue(m.check(listOf(4, 4, 1))!!.contains("part 3 of 3"))
    }

    @Test
    fun `garbage is not a manifest`() {
        assertNull(VaultTransfer.PartsManifest.fromJson(""))
        assertNull(VaultTransfer.PartsManifest.fromJson("not json"))
        assertNull(VaultTransfer.PartsManifest.fromJson("""{"parts":0,"totalBytes":0}"""))
    }

    @Test
    fun `a manifest round-trips through its own fields`() {
        val json = """{"base":"$base","parts":9,"totalBytes":100,"partBytes":12}"""
        val m = VaultTransfer.PartsManifest.fromJson(json)!!
        assertEquals(9, m.parts)
        assertEquals(100L, m.totalBytes)
        assertEquals(12L, m.partBytes)
        // 8 full pieces of 12 + a remainder of 4.
        assertNull(m.check(List(8) { 12L } + listOf(4L)))
    }
}

/**
 * Space gates refuse up front with numbers, or not at all. The import gate
 * once measured the wrong filesystem — and, when that directory did not
 * exist, measured nothing — so a 34 GB import sailed through with 96 GB free
 * against a ~103 GB need and died as ENOSPC halfway instead.
 */
class TransferSpaceGateTest {

    @Test
    fun `import needs room for the expansion, and nothing else`() {
        // 28 GB transfer, 28 GB unpacked, 512 MB headroom.
        val unpackKb = 28L * 1024 * 1024
        val need = VaultTransfer.importNeedsKb(unpackKb, 512L * 1024)
        assertEquals(unpackKb + 512L * 1024, need)
    }

    @Test
    fun `a 71 GB phone can import a 28 GB transfer`() {
        // The field case that was refused: 29.7 GB of parts, 71 GB free, and a
        // gate demanding 84 GB for three copies when only one is ever written.
        // The pieces are read, never written; the move into the vault is a
        // rename because staging lives inside the vault.
        val freeKb = 71L * 1024 * 1024
        val unpackKb = 28L * 1024 * 1024
        assertTrue(
            "a 71 GB phone must be allowed to import a 28 GB transfer",
            freeKb >= VaultTransfer.importNeedsKb(unpackKb, 512L * 1024)
        )
    }

    @Test
    fun `a phone with less than the expansion still refuses`() {
        // The gate is not toothless: too little room must still be refused up
        // front rather than discovered as ENOSPC with the disk already full.
        val need = VaultTransfer.importNeedsKb(28L * 1024 * 1024, 512L * 1024)
        assertTrue(20L * 1024 * 1024 < need)
    }

    @Test
    fun `export needs one file plus its pieces, on one filesystem`() {
        val vaultKb = 30L * 1024 * 1024
        val headroomKb = 512L * 1024
        // The pack is written straight into the export folder and renamed
        // there, so there is no second filesystem and no copy.
        assertEquals(vaultKb + headroomKb, VaultTransfer.exportNeedsKb(vaultKb, false, headroomKb))
        // Splitting holds the archive AND its pieces at peak: the single file is
        // deleted only after the pieces have been verified against it.
        assertEquals(2 * vaultKb + headroomKb, VaultTransfer.exportNeedsKb(vaultKb, true, headroomKb))
    }
}

/**
 * The bound on a transfer step must track the size of the work.
 *
 * It was a flat 60 minutes, and that is a speed limit dressed as a liveness
 * check. A 29.7 GB export legitimately outran it, the engine was killed
 * mid-split, and the app deleted the parts it had already written.
 */
class TransferWatchdogTest {

    @Test
    fun `a small transfer is barely charged for its size`() {
        // 1 MiB at the 2 MB/s floor is half a second over the base — the
        // common case should feel like the old flat bound.
        val base = 60L * 60 * 1000L
        val bound = VaultTransfer.transferWatchdogMs(1L * 1024 * 1024, base)
        assertTrue("bound was $bound", bound in base..(base + 1_000L))
    }

    @Test
    fun `a 29 GB archive is allowed longer than the floor`() {
        // The measured field case: packing 29.7 GB took 67 minutes, so a bound
        // of 60 minutes guaranteed the split was killed every time.
        val archive = 29_765_935_266L
        val bound = VaultTransfer.transferWatchdogMs(archive, 60L * 60 * 1000L)
        assertTrue("bound was ${bound / 60_000}min", bound > 67L * 60 * 1000L)
        // Generous, but still a real ceiling: hours, not days.
        assertTrue("bound was ${bound / 3_600_000}h", bound < 12L * 60 * 60 * 1000L)
    }

    @Test
    fun `the bound grows with the bytes and never shrinks`() {
        var last = 0L
        for (mb in listOf(1L, 64L, 1024L, 8192L, 29_765L)) {
            val b = VaultTransfer.transferWatchdogMs(mb * 1024 * 1024, 60L * 60 * 1000L)
            assertTrue("$mb MB should not shrink the bound", b >= last)
            last = b
        }
    }

    @Test
    fun `a negative size cannot produce a nonsense bound`() {
        assertEquals(
            60_000L,
            VaultTransfer.transferWatchdogMs(-5L, 60_000L)
        )
    }
}

/**
 * The transfer file name must accept both the current format and every format
 * an older build wrote.
 *
 * Export stopped compressing, because the payload is already zstd and gzip was
 * running at 32 MB/s to save 0.7% of a 30 GB vault — 20 minutes of the user's
 * time. That is a format change, and the one thing it must not do is strand a
 * transfer somebody already copied to their PC.
 */
class TransferFormatTest {

    @Test
    fun `current exports are plain tar`() {
        assertEquals("dvbackup.tar", VaultTransfer.EXTENSION)
        assertTrue(VaultTransfer.looksLikeTransferFile("DV-20261011-1200.dvbackup.tar"))
    }

    @Test
    fun `a transfer written by an older build still imports`() {
        // Pre-3.5.6: gzip. It is a different format on disk and must still be
        // recognised by name, or the user picks it and is told it is not a
        // transfer at all.
        assertTrue(VaultTransfer.looksLikeTransferFile("DV-20261008-0636.dvbackup.tar.gz"))
        assertEquals("dvbackup.tar.gz", VaultTransfer.LEGACY_EXTENSION)
    }

    @Test
    fun `neither format is mistaken for a bare tarball`() {
        assertFalse(VaultTransfer.looksLikeTransferFile("photos.tar.gz"))
        assertFalse(VaultTransfer.looksLikeTransferFile("backup.tar"))
        assertFalse(VaultTransfer.looksLikeTransferFile("DV.dvbackup.tar.gz.exe"))
    }

    @Test
    fun `a newline still cannot survive a file name`() {
        // The name reaches shell commands; that check is not negotiable.
        assertFalse(VaultTransfer.looksLikeTransferFile("a\n.dvbackup.tar"))
        assertFalse(VaultTransfer.looksLikeTransferFile("a\r.dvbackup.tar.gz"))
    }

    @Test
    fun `parts of an old gzip transfer keep their own base`() {
        // `splitPrefix` strips only the piece suffix, so the archive it names
        // still ends in `.tar.gz` and still resolves to a file that exists.
        assertEquals(
            "DV-20261008-0636.dvbackup.tar.gz",
            VaultTransfer.splitPrefix("DV-20261008-0636.dvbackup.tar.gz.part-aa")
        )
        assertEquals(
            "DV-20261011-1200.dvbackup.tar",
            VaultTransfer.splitPrefix("DV-20261011-1200.dvbackup.tar.part-aa")
        )
    }

    @Test
    fun `the export name carries the current format`() {
        val name = VaultTransfer.exportFileName(1791448609947L, java.time.ZoneOffset.UTC)
        assertEquals("DV-20261008-0836.dvbackup.tar", name)
    }
}

/**
 * One folder per export, named for when it started.
 *
 * They used to share a single folder, so a second export landed fourteen
 * pieces among the first export's fourteen. Deleting "the export" meant
 * guessing which files belonged together, and a half-deleted set looks like a
 * complete one until import refuses it.
 */
class ExportBatchTest {

    @Test
    fun `the folder is named for the start time`() {
        // 1791448609947 ms = 2026-10-08 08:36:49 UTC.
        assertEquals(
            "DV-20261008-0836",
            VaultTransfer.exportBatchName(1791448609947L, java.time.ZoneOffset.UTC)
        )
    }

    @Test
    fun `the folder and the files inside agree`() {
        // The stamp appears twice, which is the point: the folder says when
        // without opening anything, and the files agree with it.
        val stamp = 1791448609947L
        val batch = VaultTransfer.exportBatchName(stamp, java.time.ZoneOffset.UTC)
        assertEquals("/storage/emulated/0/Download/DroidVault/DV-20261008-0836",
            VaultTransfer.exportBatchDir(batch))
        assertEquals(
            VaultTransfer.exportFileName(stamp, java.time.ZoneOffset.UTC),
            "$batch.${VaultTransfer.EXTENSION}"
        )
    }

    @Test
    fun `a folder name is safe on a FAT32 card over MTP`() {
        // `:` is not a legal filename character on FAT32/exFAT, which is where
        // these land when the user copies them to a PC.
        val name = VaultTransfer.exportBatchName(1791448609947L, java.time.ZoneOffset.UTC)
        assertTrue("got $name", name.all { it.code < 128 })
        assertFalse(name.contains(':'))
        assertFalse(name.contains(' '))
        assertFalse(name.contains('/'))
        assertTrue(name.length < 40)
    }

    @Test
    fun `parts inside a batch folder keep their own base`() {
        // Sibling resolution is what makes a folder layout importable at all:
        // picking one piece finds the rest beside it.
        val base = VaultTransfer.exportFileName(1791448609947L)
        val name = base + VaultTransfer.PART_SEP + "aa"
        assertTrue(VaultTransfer.isPartName(name))
        assertEquals(base, VaultTransfer.splitPrefix(name))
        assertTrue(VaultTransfer.looksLikeTransferFile(base))
    }

    private fun app(
        label: String,
        appKb: Long = 0,
        dataKb: Long = 0,
        mediaKb: Long = 0,
        diskKb: Long = 0
    ) = AppInfo(
        label = label,
        packageName = "com.$label",
        appSizeKb = appKb,
        dataSizeKb = dataKb,
        mediaSizeKb = mediaKb,
        diskSizeKb = diskKb
    )

    @Test
    fun `a run watchdog scales with the bytes it may write`() {
        // The defect: this call site inherited a flat 15 minutes,
        // which `ShellEngine` enforces with `future.cancel(true)` — an outright
        // kill of a backup that was still legitimately writing.
        val small = VaultTransfer.runWatchdogMs(listOf(app("a", dataKb = 5_000)))
        val large = VaultTransfer.runWatchdogMs(listOf(app("a", dataKb = 5_000_000)))

        assertTrue("scaled bound must exceed the flat one: $large vs $small", large > small)
        assertTrue("a 5 TB ceiling is not a ceiling", large < TimeUnit.DAYS.toMillis(3))
    }

    @Test
    fun `the run watchdog is never tighter than the flat bound it replaced`() {
        // Scaling may only ever make the ceiling more generous. A regression
        // here would re-kill runs that used to finish.
        assertEquals(VaultTransfer.RUN_WATCHDOG_MS, VaultTransfer.runWatchdogMs(emptyList()))
        assertTrue(
            VaultTransfer.runWatchdogMs(listOf(app("a", dataKb = 1))) >=
                VaultTransfer.RUN_WATCHDOG_MS
        )
    }

    @Test
    fun `an unmeasured app does not shrink the bound back to the floor`() {
        // A size of 0 means "not measured", not "empty". If zero apps are
        // charged nothing, a failed `du` silently returns the run to a flat
        // bound — the exact failure this scaling exists to remove.
        val unmeasured = VaultTransfer.runWriteBytesKb(listOf(app("a")))
        assertTrue("got $unmeasured", unmeasured >= VaultTransfer.MIN_APP_KB)
        // It is a FLOOR, not an addition: an app measured at 200 GB is charged
        // 200 GB, not 200 GB plus the floor.
        assertEquals(
            200_000L,
            VaultTransfer.runWriteBytesKb(listOf(app("a", dataKb = 200_000)))
        )
        assertEquals(
            250_000L,
            VaultTransfer.runWriteBytesKb(listOf(app("a", appKb = 50_000, dataKb = 200_000)))
        )
    }

    @Test
    fun `a negative size cannot reduce the bound`() {
        // `-1` is this codebase's "unknown" sentinel. A negative total would
        // flow straight into the division and could return a bound under the
        // floor.
        assertTrue(
            VaultTransfer.runWatchdogMs(listOf(app("a", dataKb = -1, appKb = -99))) >=
                VaultTransfer.RUN_WATCHDOG_MS
        )
    }
}
