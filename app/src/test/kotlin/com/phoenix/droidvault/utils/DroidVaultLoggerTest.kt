package com.phoenix.droidvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar

/**
 * Pure-formatter contracts of [DroidVaultLogger] (no Android context needed —
 * `log()` itself requires the installed appContext and is covered on-device).
 */
class DroidVaultLoggerTest {

    @Test
    fun `formatLine shape - timestamp level tag message`() {
        val line = DroidVaultLogger.formatLine(0, DroidVaultLogger.I, "MyTag", "hello")
        assertTrue(line.endsWith(" I/MyTag: hello"))
        // "MM-dd HH:mm:ss.SSS " prefix
        assertTrue(Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ").containsMatchIn(line))
    }

    @Test
    fun `formatLine flattens newlines in tag and message`() {
        val line = DroidVaultLogger.formatLine(0, 'W', "Ta\ng", "one\ntwo")
        assertFalse(line.contains('\n'))
        assertTrue(line.endsWith(" W/Ta g: one two"))
    }

    @Test
    fun `formatLine caps tag at 24 chars and message at 2000`() {
        val line = DroidVaultLogger.formatLine(0, 'D', "T".repeat(40), "m".repeat(3000))
        val tagPart = line.substringAfter("/").substringBefore(": ")
        assertEquals(24, tagPart.length)
        assertEquals(2000, line.substringAfter(": ").length)
    }

    @Test
    fun `logFileName uses the per-day yyyy-MM-dd scheme`() {
        val date: java.util.Date = GregorianCalendar(2026, Calendar.OCTOBER, 5).time
        assertEquals("droidvault-2026-10-05.log", DroidVaultLogger.logFileName(date))
    }
}

/**
 * [parseDuKb] (internal, ProgressTracker.kt): root `du -sk` output → path→KB.
 * The restore byte-progress poller feeds engine watchDirs through this.
 */
class ParseDuKbTest {

    @Test
    fun `parses space and tab separated lines`() {
        val m = parseDuKb(listOf("12 /data/data/com.a", "7\t/data/data/com.b"))
        assertEquals(12L, m["/data/data/com.a"])
        assertEquals(7L, m["/data/data/com.b"])
    }

    @Test
    fun `skips junk lines and keeps the last duplicate`() {
        val m = parseDuKb(listOf("", "notkb /x", "abc", "4 /dup", "9 /dup"))
        assertEquals(9L, m["/dup"])
        assertFalse(m.containsKey("/x"))
        assertEquals(1, m.size)
    }

    @Test
    fun `empty input yields empty map`() {
        assertTrue(parseDuKb(emptyList()).isEmpty())
    }
}
