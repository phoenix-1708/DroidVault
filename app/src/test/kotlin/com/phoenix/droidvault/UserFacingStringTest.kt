package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.TransferCandidate
import com.phoenix.droidvault.models.TransferFile
import com.phoenix.droidvault.models.TransferPlan
import com.phoenix.droidvault.models.TransferProgress
import com.phoenix.droidvault.models.TransferState
import com.phoenix.droidvault.models.Trash
import com.phoenix.droidvault.ui.screens.EMPTY_BODY_NO_BACKUPS
import com.phoenix.droidvault.ui.screens.EMPTY_BODY_UNKNOWN
import com.phoenix.droidvault.ui.screens.EMPTY_TITLE
import com.phoenix.droidvault.ui.screens.IMPORT_HELP
import com.phoenix.droidvault.ui.screens.emptyBody
import com.phoenix.droidvault.utils.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The strings a user actually reads, checked as VALUES.
 *
 * §2.67 shipped `5/5 parts}` on every run the app had ever made. A `}` outside a
 * template placeholder is an ordinary character, so the compiler had no opinion,
 * and the unit suite could not see it: it asserts logic, and that was a literal.
 * Worse, while a run is live the element is simply absent from the tree, so
 * every screenshot of an in-progress run showed a correct screen too.
 *
 * The second instance was found on device the day this file was written, in the
 * trash card on Manage — **"1 deleted backup} recoverable"** — on the one screen
 * whose whole job is telling someone precisely what a destructive action did.
 *
 * The first attempt at a guard for this scanned the SOURCE for unbalanced braces
 * inside string literals. That is unsound and it was deleted: a Kotlin `${…}` can
 * contain arbitrary expressions with their own strings and braces
 * (`"${ShellQuote.q("$path")}"`), so the scanner fell out of step with the code
 * it was reading and reported 1224 offenders on a clean tree. A guard that
 * false-positives gets deleted, and a guard that hangs gets killed — the version
 * before that spun at 100% CPU on a bare `$` in a shell string.
 *
 * So the property is asserted where it is actually decidable: on the VALUES.
 * Every function that builds a user-facing string is called here and its output
 * inspected. A new producer is one line to add, and a stray brace in any of them
 * fails by name.
 */
class UserFacingStringTest {

    /**
     * The check itself, so a producer is added in one place.
     *
     * Both braces, not just `}`: a `{` renders just as visibly, and §2.67's rule
     * is about the rendered character, not about which end it was.
     */
    private fun assertReadable(what: String, value: String) {
        assertTrue(
            "\"$value\" ($what) shows a brace to the user",
            !value.contains('{') && !value.contains('}')
        )
    }

    private fun <T> readable(what: String, vararg values: T) {
        values.forEach { assertReadable(what, it.toString()) }
    }

    // --- the producers ----------------------------------------------------

    @Test
    fun `AppParts count is readable`() {
        // Zero and one are the cases a plural rule is written for and the cases
        // nobody checks by hand, and both nouns the app counts in a headline.
        readable(
            "AppParts.count",
            AppParts.count(0, "export"),
            AppParts.count(1, "export"),
            AppParts.count(2, "export"),
            AppParts.count(1, "deleted backup"),
            AppParts.count(0, "deleted backup"),
            AppParts.count(1, "part"),
            AppParts.count(17, "part"),
            AppParts.count(3, "entry", "entries")
        )
    }

    @Test
    fun `run progress meta is readable`() {
        // The exact string §2.67 shipped, and the boundaries around it.
        for (done in 0..5) {
            for (parts in 0..6) {
                assertReadable("AppParts.progressMeta($done, $parts)", AppParts.progressMeta(done, parts))
            }
        }
    }

    @Test
    fun `byte and count formatting is readable`() {
        readable(
            "Formatters.formatBytes",
            *listOf(
                0L, 1L, 999L, 1_000L, 1_048_576L,
                34_852_793_344L, 16_777_216L, Long.MAX_VALUE
            ).map { Formatters.formatBytes(it) }.toTypedArray()
        )
        readable(
            "Formatters.formatCount",
            Formatters.formatCount(0),
            Formatters.formatCount(1),
            Formatters.formatCount(110)
        )
    }

    @Test
    fun `an export row is readable in every state`() {
        // The row's whole identity is two lines of text, and one of the four
        // states is the damaged one — the case where a user most needs to read
        // it correctly and least expects to.
        val dir = "/storage/emulated/0/Download/DroidVault/DV-20261014-1829"
        val base = "DV-20261014-1829.dvbackup.tar"
        val part = { n: String -> "$dir/$base.part-$n" }
        val whole = TransferCandidate(
            dir = dir,
            base = base,
            importPath = part("aa"),
            sourceParts = listOf(part("aa"), part("ab"), part("ac")),
            sizes = listOf(8_388_608L, 8_388_608L, 8_388_608L),
            manifest = null,
            modifiedMs = 1_798_000_000_000L
        )
        val short = whole.copy(
            sourceParts = listOf(part("aa")),
            sizes = listOf(8_388_608L),
            manifest = com.phoenix.droidvault.models.VaultTransfer.PartsManifest(
                base = base, parts = 3, totalBytes = 25_165_824L, partBytes = 8_388_608L
            )
        )
        val wrongSize = whole.copy(
            sizes = listOf(8_388_608L, 8_388_609L, 8_388_608L),
            manifest = com.phoenix.droidvault.models.VaultTransfer.PartsManifest(
                base = base, parts = 3, totalBytes = 25_165_825L, partBytes = 8_388_609L
            )
        )
        for (c in listOf(whole, short, wrongSize)) {
            readable(
                "TransferCandidate(${c.state}).partsLabel",
                c.partsLabel,
                c.sizeLabel,
                c.problem ?: "",
                Formatters.exportStamp(c.modifiedMs),
                c.missing.joinToString(", ")
            )
        }
        // And the states those three actually are, so the loop above is not
        // quietly testing one case three times.
        assertEquals(TransferState.UNVERIFIED, whole.state)
        assertEquals(TransferState.INCOMPLETE, short.state)
        assertEquals(TransferState.DAMAGED, wrongSize.state)
    }

    @Test
    fun `a trash batch label is readable`() {
        // Every kind the app quarantines under, because a stray brace in this
        // one is the bug that was found on device.
        for (kind in listOf(
            "device backups", "apps", "folders", "exports", "device data", "user data"
        )) {
            for (count in 0..3) {
                assertReadable("Trash.batchLabel($count, $kind)", Trash.batchLabel(count, kind))
            }
        }
    }

    @Test
    fun `a transfer estimate is readable`() {
        // Suppressed or not, whatever it says must be a sentence. The null cases
        // are the interesting ones and are covered in TransferEstimateTest; this
        // is about the characters.
        for (percent in -1..100) {
            for (rate in listOf(0.0, 0.01, 1.0, 100.0 / 61.0, 100.0 / 3_600.0)) {
                val label = com.phoenix.droidvault.models.TransferProgress.Estimate
                    .label(percent, rate)
                if (label != null) assertReadable("Estimate.label($percent, $rate)", label)
            }
        }
        for (ms in listOf(0L, 5_000L, 8_000L, 59_000L, 11 * 60_000L, 3 * 3_600_000L)) {
            val label = com.phoenix.droidvault.models.TransferProgress.Estimate
                .elapsedLabel(ms)
            if (label != null) assertReadable("Estimate.elapsedLabel($ms)", label)
        }
    }

    // --- and the values those producers are read through -------------------

    @Test
    fun `the exported size label the delete promises is the one the row shows`() {
        // The toast says "<size> freed" using this label, so it is a claim about
        // how much space came back, and it has to be the recorded total.
        val file = TransferFile(
            path = "/storage/emulated/0/Download/DroidVault/DV-1/DV-1.dvbackup.tar",
            bytes = 16_777_216L,
            modifiedMs = 1_798_000_000_000L
        )
        val c = com.phoenix.droidvault.models.TransferCandidates.discover(listOf(file)).single()
        assertEquals(TransferProgress.humanBytes(16_777_216L), c.sizeLabel)
        assertReadable("TransferCandidate.sizeLabel", c.sizeLabel)
    }

    // --- the Import page's own prose ---------------------------------------

    /**
     * The empty state is three different sentences, and all three are read by
     * someone who has just lost a phone or flashed one.
     *
     * `IMPORT_HELP` was in here from the start as an untested value — it is the
     * longest piece of prose the app shows and nothing ever checked what it
     * renders as.
     */
    @Test
    fun `the import page's empty state is readable in all three states`() {
        readable(
            "emptyBody",
            emptyBody(null),
            emptyBody(0),
            emptyBody(1),
            emptyBody(2),
            emptyBody(110),
            emptyBody(2000)
        )
        readable("IMPORT_HELP", IMPORT_HELP)
        readable("EMPTY_TITLE", EMPTY_TITLE)
        // The reassuring sentence must actually carry the count. "Your backups
        // are in the vault" is true and gives the reader no way to tell whether
        // it means two archives or two hundred.
        assertTrue(
            "the reassuring body does not report how many backups are in the vault",
            emptyBody(110).contains("already holds 110 app backups")
        )
        // Singular all the way through. "already holds 1 app backup" — the
        // obvious phrasing ("your N backups are") needs a verb that agrees with
        // the count, and AppParts.count only pluralises the noun. Written that
        // way this read "Your 1 app backup are already in the vault".
        assertTrue(
            "singular is singular in the reassuring body",
            emptyBody(1).contains("already holds 1 app backup ") &&
                !emptyBody(1).contains("1 app backups")
        )
    }

    /**
     * A read that has not happened is not a vault that is empty.
     *
     * `backupCount` is null until the startup read lands, and `null` must not
     * borrow the "there is nothing to export" wording — that would tell someone
     * who just flashed their phone that they have no backups, which is the
     * assertion this screen exists to stop making (§2.79).
     */
    @Test
    fun `an unmeasured vault is never reported as an empty one`() {
        assertTrue(
            "the not-measured body must not claim there are no backups",
            !emptyBody(null).contains("no app backups")
        )
        assertTrue(
            "nor may it claim there are some",
            !emptyBody(null).contains("already in the vault")
        )
        assertTrue(
            "the empty body must say what would change it",
            emptyBody(0).contains("Migrate tab")
        )
        // And each state is genuinely distinct, so this cannot pass by all three
        // quietly returning the same sentence.
        assertEquals(3, setOf(emptyBody(null), emptyBody(0), emptyBody(110)).size)
    }

    // --- the transfer confirmation sheets ----------------------------------

    /**
     * Every row both sheets can show, in every state they can be in.
     *
     * The three-row import sheet is the one that matters most and was never
     * checked: it is what stands between a person and a thirty-gigabyte write,
     * and "what happens to my backups" is the sentence they will act on.
     */
    @Test
    fun `a transfer confirmation is readable in every state`() {
        val whole = TransferCandidate(
            dir = "/storage/emulated/0/Download/DroidVault/DV-20261016-0736",
            base = "DV-20261016-0736.dvbackup.tar",
            importPath = "/storage/emulated/0/Download/DroidVault/DV-20261016-0736/DV-20261016-0736.dvbackup.tar",
            sourceParts = emptyList(),
            sizes = listOf(34_852_793_344L),
            manifest = null,
            modifiedMs = 1_798_000_000_000L
        )
        val dir = whole.dir
        val base = whole.base
        val split = TransferCandidate(
            dir = dir,
            base = base,
            importPath = "$dir/$base.part-aa",
            sourceParts = com.phoenix.droidvault.models.VaultTransfer
                .expectedPartNames(base, 17)?.map { "$dir/$it" } ?: emptyList(),
            sizes = List(17) { 2_097_152_000L },
            manifest = com.phoenix.droidvault.models.VaultTransfer.PartsManifest(
                base = base, parts = 17, totalBytes = 34_852_793_344L, partBytes = 2_097_152_000L
            ),
            modifiedMs = 1_798_000_000_000L
        )
        val vault = "/storage/emulated/0/DroidVault"
        val gb = 1024L * 1024L

        readable(
            "TransferPlan.importRows",
            *TransferPlan.importRows(TransferPlan.sourceOf(whole), vault, null).map { it.value }.toTypedArray(),
            *TransferPlan.importRows(TransferPlan.sourceOf(whole), vault, 70L * 1024).map { it.value }.toTypedArray(),
            *TransferPlan.importRows(TransferPlan.sourceOf(whole), vault, 10L * 1024 * 1024).map { it.value }.toTypedArray(),
            *TransferPlan.importRows(TransferPlan.sourceOf(split), vault, 70L * 1024 * 1024).map { it.value }.toTypedArray(),
            TransferPlan.importSubtitle(TransferPlan.sourceOf(whole)),
            *TransferPlan.importNotes(TransferPlan.sourceOf(whole)).toTypedArray(),
            TransferPlan.shortfallNote(null, 1L),
            TransferPlan.shortfallNote(10L * 1024 * 1024, 40L * 1024 * 1024),
            TransferPlan.shortfallNote(40L * 1024 * 1024, 40L * 1024 * 1024)
        )
        readable(
            "TransferPlan.exportRows",
            *TransferPlan.exportRows(vault, "/dl/DV-1", null, null, 0L).map { it.value }.toTypedArray(),
            *TransferPlan.exportRows(vault, "/dl/DV-1", 32_768L, 70L * 1024 * 1024, 0L).map { it.value }.toTypedArray(),
            *TransferPlan.exportRows(vault, "/dl/DV-1", 32_768L, 1L * 1024 * 1024, 8L * gb).map { it.value }.toTypedArray()
        )
        // The split restatement, across the shapes it can take.
        readable(
            "TransferPlan.exportTargetLine",
            TransferPlan.exportTargetLine(0L, 32_768L),
            TransferPlan.exportTargetLine(8L * gb, 32_768L),
            TransferPlan.exportTargetLine(64L * gb, 32_768L),
            TransferPlan.exportTargetLine(8L * gb, null)
        )
        readable("TransferPlan titles", TransferPlan.IMPORT_TITLE, TransferPlan.EXPORT_TITLE, TransferPlan.EXPORT_SUBTITLE)
    }
}
