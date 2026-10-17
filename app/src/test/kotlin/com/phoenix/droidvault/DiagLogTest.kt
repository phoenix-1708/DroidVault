package com.phoenix.droidvault

import com.phoenix.droidvault.models.DiagLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live log sheet's two decisions, taken out of the Composable.
 *
 * Both exist because the sheet got them wrong. It always scrolled to the newest
 * line, so reading anything while a run was streaming was impossible — the view
 * snapped back on the next line. And it had no way to narrow 2000 lines, which
 * is the one thing a field tool exists to do.
 */
class DiagLogTest {

    private fun line(level: Char, tag: String, msg: String) =
        "10-15 20:35:14.257 $level/$tag: $msg"

    private val sample = listOf(
        line('V', "Seed", "1DM+: comps=[1, 2] avail=[1, 2, 3, 4, 5]"),
        line('D', "Migrate", "fetch AllBackups: 110 app(s) in 1887ms"),
        line('I', "Engine", "Rust engine and launcher verified — up to date"),
        line('I', "Trash", "quarantined 1 item as b179 (1 device backup): Bluetooth"),
        line('W', "Gate", "run refused: gate held by export"),
        line('E', "Transfer", "transfer failed: no space")
    )

    // --- levelOf ----------------------------------------------------------

    @Test
    fun `each level is read back`() {
        assertEquals('V', DiagLog.levelOf(line('V', "Seed", "x")))
        assertEquals('D', DiagLog.levelOf(line('D', "Migrate", "x")))
        assertEquals('I', DiagLog.levelOf(line('I', "Engine", "x")))
        assertEquals('W', DiagLog.levelOf(line('W', "Gate", "x")))
        assertEquals('E', DiagLog.levelOf(line('E', "Transfer", "x")))
    }

    @Test
    fun `a tag containing a space still parses`() {
        // The reason this is a regex and not "split on spaces, take token
        // three": `formatLine` strips newlines from a tag but NOT spaces, so a
        // tag with one shifts every later token.
        assertEquals('W', DiagLog.levelOf("10-15 20:35:14.257 W/My Tag: hello"))
    }

    @Test
    fun `slashes in the message cannot confuse it`() {
        // Anchored at the start, so this is the level and not the first slash
        // anywhere on the line.
        assertEquals(
            'I',
            DiagLog.levelOf("10-15 20:35:14.257 I/Engine: path is /a/b/c/d")
        )
    }

    @Test
    fun `a line this app did not write has no level`() {
        // Null rather than a default. A diagnostic view that invents a severity
        // for a line it does not understand is worse than one that admits it.
        assertNull(DiagLog.levelOf(""))
        assertNull(DiagLog.levelOf("some other program's output"))
        assertNull(DiagLog.levelOf("10-15 20:35:14.257 no level here at all"))
        // A `Z/` is not a level the logger writes.
        assertNull(DiagLog.levelOf("10-15 20:35:14.257 Z/Thing: x"))
    }

    // --- filter -----------------------------------------------------------

    @Test
    fun `the level is a floor not an exact match`() {
        val warn = DiagLog.filter(sample, minLevel = 'W')
        // W and above, never the three below.
        assertEquals(2, warn.size)
        assertTrue(warn.any { it.startsWith("10-15 20:35:14.257 W/") })
        assertTrue(warn.any { it.startsWith("10-15 20:35:14.257 E/") })
    }

    @Test
    fun `everything at the lowest level`() {
        assertEquals(sample.size, DiagLog.filter(sample, minLevel = 'V').size)
    }

    @Test
    fun `errors only`() {
        val errs = DiagLog.filter(sample, minLevel = 'E')
        assertEquals(1, errs.size)
        assertTrue(errs.single().contains("Transfer"))
    }

    @Test
    fun `an empty query filters nothing`() {
        // The floor is spelled out so this test is about the QUERY. A search box
        // that starts by hiding things is a search box people learn to distrust.
        assertEquals(sample.size, DiagLog.filter(sample, minLevel = 'V', query = "").size)
        assertEquals(sample.size, DiagLog.filter(sample, minLevel = 'V', query = "   ").size)
    }

    @Test
    fun `the default floor is info, so debug chatter is hidden until asked for`() {
        // Stated rather than implied, because "I" is a choice: the engine
        // narrates every step, and a field reader wants the decision and the
        // failure, not the narration.
        val byDefault = DiagLog.filter(sample)
        assertEquals(4, byDefault.size)
        assertTrue(byDefault.none { it.contains("/Seed:") })
        assertTrue(byDefault.none { it.contains("/Migrate:") })
        // And one tap brings them back.
        assertEquals(sample.size, DiagLog.filter(sample, minLevel = 'V').size)
    }

    @Test
    fun `a query finds a tag as easily as a word`() {
        // `Trash`, `Gate` and `Transfer` are the tags the destructive paths
        // record under, and those are what a report asks about.
        assertEquals(1, DiagLog.filter(sample, query = "Trash").size)
        assertEquals(1, DiagLog.filter(sample, query = "quarantined").size)
    }

    @Test
    fun `a query ignores case`() {
        assertEquals(
            DiagLog.filter(sample, query = "TRASH").size,
            DiagLog.filter(sample, query = "trash").size
        )
    }

    @Test
    fun `a query with no match is empty, not everything`() {
        assertTrue(DiagLog.filter(sample, query = "zzzznotpresent").isEmpty())
    }

    @Test
    fun `the level floor and the query compose`() {
        val out = DiagLog.filter(sample, minLevel = 'W', query = "Gate")
        assertEquals(1, out.size)
        assertTrue(out.single().contains("held by export"))
    }

    @Test
    fun `a line with no level survives every filter`() {
        // Every line this app writes has a level, so an unparseable one came
        // from elsewhere. Dropping it would be a diagnostic view silently hiding
        // what it does not understand.
        val foreign = listOf("some other program's output", line('E', "Transfer", "boom"))
        assertEquals(2, DiagLog.filter(foreign, minLevel = 'E').size)
        assertEquals(2, DiagLog.filter(foreign, minLevel = 'V').size)
    }

    @Test
    fun `a foreign line is subject to the query like anything else`() {
        // Being KEPT by the level floor is about severity, not about matching
        // everything: a search for "boom" should not return a line that does not
        // say boom, however unreadable it is.
        val foreign = listOf("some other program's output", line('E', "Transfer", "boom"))
        assertEquals(1, DiagLog.filter(foreign, minLevel = 'V', query = "boom").size)
        assertEquals(1, DiagLog.filter(foreign, minLevel = 'V', query = "output").size)
    }

    @Test
    fun `an empty log stays empty`() {
        assertTrue(DiagLog.filter(emptyList(), minLevel = 'V').isEmpty())
    }

    @Test
    fun `severity order is not alphabetical order`() {
        // The exact trap: a Chan's numeric order is the alphabet, so 'E' (69) is
        // LESS than 'V' (86) and 'I' (73) is less than 'W' (87). Anything
        // comparing levels with < or > is comparing the alphabet. The filter is
        // right because it goes through the table; a screen that reasoned about
        // levels directly got it wrong and shipped it.
        assertEquals("VDIWE", DiagLog.LEVELS.joinToString(""))
        // And the behaviour that matters, stated through the filter rather than
        // through a comparison: at the highest floor, only the E line survives.
        assertEquals(1, DiagLog.filter(sample, minLevel = 'E').size)
        // At the lowest floor everything does — so the table's ends are right.
        assertEquals(sample.size, DiagLog.filter(sample, minLevel = 'V').size)
    }

    // --- shouldStickToTail ------------------------------------------------

    @Test
    fun `at the end, the tail follows`() {
        assertTrue(DiagLog.shouldStickToTail(lastVisibleIndex = 9, totalItems = 10))
    }

    @Test
    fun `one line up, the tail still follows`() {
        // A line partially scrolled off the bottom is not "the reader left".
        assertTrue(DiagLog.shouldStickToTail(lastVisibleIndex = 8, totalItems = 10))
    }

    @Test
    fun `five lines up, the reader is reading`() {
        assertTrue(!DiagLog.shouldStickToTail(lastVisibleIndex = 4, totalItems = 10))
    }

    @Test
    fun `an empty list never scrolls`() {
        // Opening the sheet must not scroll into nothing.
        assertTrue(!DiagLog.shouldStickToTail(lastVisibleIndex = 0, totalItems = 0))
        assertTrue(!DiagLog.shouldStickToTail(lastVisibleIndex = -1, totalItems = 0))
    }

    @Test
    fun `a single visible line still follows`() {
        assertTrue(DiagLog.shouldStickToTail(lastVisibleIndex = 0, totalItems = 1))
    }

    @Test
    fun `the threshold is honoured exactly`() {
        // At threshold 0, one line up is already "reading".
        assertTrue(!DiagLog.shouldStickToTail(lastVisibleIndex = 8, totalItems = 10, threshold = 0))
        assertTrue(DiagLog.shouldStickToTail(lastVisibleIndex = 9, totalItems = 10, threshold = 0))
    }
}
