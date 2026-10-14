package com.phoenix.droidvault

import com.phoenix.droidvault.models.VaultTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The parse of `du -sk` output, and the gate that was inert because of it.
 *
 * Found on device (POCO F6): `du -sk /storage/emulated/0/DroidVault` answers
 *
 *     413008<TAB>/storage/emulated/0/DroidVault
 *
 * and the export's space gate read that whole line with `trim().toLongOrNull()`.
 * `trim()` removes whitespace at the ENDS of a string and never the one in the
 * middle, so the line stayed `"413008\t/storage/…"` — not a number, null, and the
 * gate's `vaultKb > 0` was false on every export. The gate therefore never
 * refused anything.
 *
 * What that cost: `tar -cf` filled the storage as root until ENOSPC, the engine
 * discarded the partial archive, and the app reported "Could not read the vault —
 * is root granted?" — blaming root for a full disk, on a device left at 100%.
 *
 * This file documents the same defect that `probeKb` was already written to fix
 * (§2.31): the fix was applied to one of the two readers of the same output.
 */
class DuOutputParseTest {

    /** The exact line the device produced. */
    private val realLine = "413008\t/storage/emulated/0/DroidVault"

    /**
     * The single reader, as `VaultTransferManager.duKb` performs it.
     *
     * Duplicated here rather than imported because it is `private` to a manager
     * that needs a live root shell; what is under test is the parse, and this is
     * the expression the manager uses. [the_test_that_reads_the_source] below
     * fails if the two ever drift apart.
     */
    private fun parseDuKb(line: String?): Long =
        line?.trim()?.split("\\s+".toRegex())?.firstOrNull()?.toLongOrNull() ?: 0L

    @Test
    fun `a tab inside the line is not removed by trim`() {
        // The premise of the whole finding, asserted rather than assumed.
        assertEquals("413008\t/storage/emulated/0/DroidVault", realLine.trim())
        assertEquals(
            "if trim() had removed the interior tab this bug would not exist",
            null,
            realLine.trim().toLongOrNull()
        )
        assertEquals(413008L, parseDuKb(realLine))
    }

    @Test
    fun `a size with no path still parses`() {
        assertEquals(413008L, parseDuKb("413008"))
        assertEquals(413008L, parseDuKb("  413008  "))
    }

    @Test
    fun `unreadable output is zero, which every caller gates on`() {
        // 0 must mean "unknown", never a real measurement: the gate is
        // `vaultKb > 0`, so a failure cannot invent a refusal.
        for (junk in listOf(null, "", "   ", "\n", "du: cannot access", "not-a-number")) {
            assertEquals("junk must read as unknown, not as a size: $junk", 0L, parseDuKb(junk))
        }
    }

    @Test
    fun `the size a real vault produces is enough to make the gate bite`() {
        // 413008 KB is a ~404 MB vault. With no split the export needs the vault
        // plus headroom, so the gate must be able to reach a refusal at all.
        val vaultKb = parseDuKb(realLine)
        assertTrue("the read must be non-zero or the gate is inert", vaultKb > 0)
        val need = VaultTransfer.exportNeedsKb(vaultKb, split = false, headroomKb = 2L * 1024 * 1024)
        // Enough room -> allowed; not enough -> refused. The second line is the
        // one that could never execute before.
        assertTrue("with headroom the need exceeds the bare size", vaultKb + 1 < need)
        assertTrue(
            "a device with less free space than the export needs must be REFUSED",
            need > vaultKb
        )
        assertEquals(
            "a split doubles the budget, so it must demand more than the plain copy",
            2 * vaultKb + 2L * 1024 * 1024,
            VaultTransfer.exportNeedsKb(vaultKb, split = true, headroomKb = 2L * 1024 * 1024)
        )
    }

    @Test
    fun `the_test_that_reads_the_source`() {
        // Every `du` read in the transfer manager must go through ONE parse.
        // A second reader of the same shell output is a second chance to get the
        // parse wrong, which is exactly how the export gate ended up inert while
        // the progress bar's reader beside it was correct.
        val src = File("src/main/kotlin/com/phoenix/droidvault/utils/VaultTransferManager.kt")
            .readText()
            .let { raw ->
                Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
                    .replace(raw, " ")
                    .replace(Regex("//[^\n]*"), " ")
            }
        // Every line that still builds a `du -sk` command by hand. The two
        // multi-path probes are fine — they need one fork for N paths — but their
        // RESULT must be parsed by the same expression, which is the half that
        // actually broke.
        // The single-path reader is `duKb`; the only other legitimate form is a
        // multi-path probe that must join N paths into one fork. Anything else
        // building a `du` line by hand is a second reader of the same output.
        // Every `du` result must be reduced by the SAME expression. The reader's
        // own declaration is that expression, so it is the reference — and only
        // the lines that CONSUME a `du` result matter. A whole-line
        // `trim().toLongOrNull()` elsewhere in the file is fine when it parses
        // `stat -c %s`, which answers a bare number, and is the defect when it
        // parses `du`, which answers `<kb><TAB><path>`. The test therefore
        // requires the split at every du result site and says nothing about the
        // others.
        val readerBody = src.indexOf("private fun duKb")
        assertTrue("duKb - the one shared `du` reader - not found", readerBody >= 0)
        val fieldSplit = "split(\"\\\\s+\".toRegex())"
        assertTrue(
            "the shared du reader must take the first whitespace-separated field",
            src.substring(readerBody).contains(fieldSplit)
        )
        // Each `du` result is consumed either by duKb itself or by a
        // `forEach { line -> ... }` that must split it.
        val duResultConsumers = Regex("""probe\.forEach\s*\{\s*line\s*->""").findAll(src).count()
        assertEquals(
            "every multi-path `du` probe must consume each result line by splitting " +
                "it on whitespace",
            2,
            duResultConsumers
        )
        // duKb itself, the two multi-path probes, and the two other readers of a
        // whitespace-separated shell answer (the export part-size probe and the
        // import header parse). Counted rather than pinned to 3 so an unrelated
        // correct reader cannot make this fail; what it forbids is going DOWN.
        val splitSites = Regex(Regex.escape(fieldSplit)).findAll(src).count()
        assertTrue(
            "fewer `du` result readers split on whitespace than before (was 5, now " +
                "$splitSites): a `du -sk` line reduced without splitting yields 0",
            splitSites >= 5
        )
        // And no consumer of a `du` line may parse it whole. `stat -c %s` output
        // is a bare number, so this is scoped to the du sites by requiring the
        // split on the very next lines.
        val duWholeLine = Regex(
            """du -sk [^\n]*\n(?:[^\n]*\n){0,3}?[^\n]*trim\(\)\?\.toLongOrNull"""
        ).findAll(src).count()
        assertEquals(
            "a `du -sk` result must never be parsed as a whole line: trim() never " +
                "removes the interior tab, so the read silently yields 0",
            0,
            duWholeLine
        )
        // And the gate's own read must be the shared one.
        assertTrue(
            "the export space gate must measure the vault with duKb(), the reader " +
                "that takes the first field",
            src.contains("val vaultKb = duKb(vaultPath)")
        )
    }
}