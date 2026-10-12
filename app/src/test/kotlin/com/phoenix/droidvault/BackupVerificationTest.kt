package com.phoenix.droidvault

import com.phoenix.droidvault.models.VerificationParser
import com.phoenix.droidvault.models.VerificationReport
import com.phoenix.droidvault.models.summaryLine
import com.phoenix.droidvault.models.verifyReportAllowsRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Archive verification: parsing the engine's report, and the decision about
 * whether a restore may proceed.
 *
 * The gate is the part that matters. A false "block" makes the app unusable, and
 * a false "allow" half-restores an app on top of a corrupt archive — so both
 * directions are pinned here.
 */
class BackupVerificationTest {

    private fun feed(vararg lines: String): VerificationReport {
        var r = VerificationReport()
        lines.forEach { r = VerificationParser.parse(it, r) }
        return r
    }

    private val done = "VERIFY_DONE|TOTAL:3|OK:2|CORRUPT:1|BYTES:4096"
    private val appOk = "VERIFY_APP|INDEX:1|TOTAL:3|LABEL:Drive|PKG:com.a|PARTS:4|CORRUPT:|BYTES:100"
    private val appBad =
        "VERIFY_APP|INDEX:2|TOTAL:3|LABEL:Broken|PKG:com.b|PARTS:3|CORRUPT:Data.shift,App.shift|BYTES:0"
    private val appOk2 =
        "VERIFY_APP|INDEX:3|TOTAL:3|LABEL:Notes|PKG:com.c|PARTS:1|CORRUPT:|BYTES:200"

    // --- parsing ------------------------------------------------------------

    @Test
    fun `parses a per app line`() {
        val r = feed(appOk)
        assertEquals(1, r.apps.size)
        val app = r.apps[0]
        assertEquals("Drive", app.label)
        assertEquals("com.a", app.packageName)
        assertEquals(4, app.partsChecked)
        assertTrue(app.ok)
        assertEquals(100L, app.bytesReadable)
    }

    @Test
    fun `parses a corrupt app and its part list`() {
        val app = feed(appBad).apps[0]
        assertFalse(app.ok)
        assertEquals(listOf("Data.shift", "App.shift"), app.corruptParts)
        // The engine joins parts with a comma and leaves a trailing one when
        // none are corrupt; both must parse to the same shape.
        assertTrue(feed(appOk).apps[0].corruptParts.isEmpty())
    }

    @Test
    fun `parses the summary line`() {
        val r = feed(appOk, appBad, appOk2, done)
        assertEquals(3, r.total)
        assertEquals(2, r.okCount)
        assertEquals(1, r.corruptCount)
        assertTrue(r.hasCorrupt)
        assertFalse(r.cancelled)
        assertTrue(r.conclusive)
    }

    @Test
    fun `a value containing a colon keeps it`() {
        // Meta.txt values can be paths or versions with colons. Splitting on
        // every colon would truncate the label.
        val r = feed("VERIFY_APP|INDEX:1|TOTAL:1|LABEL:A: B|PKG:com.x|PARTS:1|CORRUPT:|BYTES:1")
        assertEquals("A: B", r.apps[0].label)
    }

    @Test
    fun `an engine error is recorded rather than ignored`() {
        val r = feed(appOk, "ACTION:ERROR|MSG:engine not installed")
        assertEquals("engine not installed", r.error)
        assertFalse(r.conclusive)
    }

    @Test
    fun `unrecognised informational lines are ignored`() {
        // The engine emits INFO: steps too. A new informational line must never
        // turn a finished verification into a failed one.
        val r = feed(appOk, "INFO:STEP|MSG:Verifying 1 of 3", appOk, appOk)
        assertEquals(3, r.apps.size)
        assertTrue(r.error.isEmpty())
        assertFalse(r.cancelled)
    }

    @Test
    fun `cancelled is not conclusive`() {
        val r = feed(appOk, "VERIFY_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0|CANCELLED:1")
        assertTrue(r.cancelled)
        assertFalse(r.conclusive)
        assertNull(r.verifyReportAllowsRestore(setOf("com.a")))
    }

    // --- the restore gate ---------------------------------------------------

    @Test
    fun `a clean report allows the restore`() {
        val r = feed(appOk, appOk2, "VERIFY_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:300")
        assertTrue(r.healthy)
        assertEquals(true, r.verifyReportAllowsRestore(setOf("com.a", "com.c")))
    }

    @Test
    fun `a corrupt archive blocks the restore`() {
        // The whole point: refuse BEFORE restoring, not halfway through.
        val r = feed(appOk, appBad, appOk2, done)
        assertEquals(false, r.verifyReportAllowsRestore(setOf("com.a", "com.b")))
    }

    @Test
    fun `an unrelated corrupt app does not block`() {
        val r = feed(appOk, appBad, appOk2, done)
        assertEquals(true, r.verifyReportAllowsRestore(setOf("com.a", "com.c")))
    }

    @Test
    fun `an empty vault does not block`() {
        // Nothing to restore means nothing to verify. Returning false here would
        // make the Restore button permanently dead on a fresh install.
        val r = feed("VERIFY_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0")
        assertFalse(r.conclusive)
        assertNull(r.verifyReportAllowsRestore(setOf("com.a")))
        // An empty selection is also "nothing checked", not "verified".
        assertNull(r.verifyReportAllowsRestore(emptySet()))
    }

    @Test
    fun `selected packages absent from the report do not block`() {
        // The on-screen list can be stale relative to disk. Blocking on that
        // would be a dead end with no way to proceed.
        val r = feed(appOk, appOk2, "VERIFY_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:300")
        assertNull(r.verifyReportAllowsRestore(setOf("com.never.seen")))
    }

    @Test
    fun `an errored verification never reads as healthy`() {
        val r = VerificationReport(error = "engine unavailable")
        assertFalse(r.healthy)
        assertFalse(r.conclusive)
        assertNull(r.verifyReportAllowsRestore(setOf("com.a")))
    }

    // --- summary text -------------------------------------------------------

    @Test
    fun `summary lines are honest about what happened`() {
        assertEquals(
            "All 2 app backups are readable",
            feed(appOk, appOk2, "VERIFY_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:300").summaryLine()
        )
        assertEquals(
            "2 of 3 app backups readable, 1 damaged",
            feed(appOk, appBad, appOk2, done).summaryLine()
        )
        assertEquals(
            "No app backups found",
            feed("VERIFY_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0").summaryLine()
        )
        assertEquals("Verification cancelled", feed("VERIFY_DONE|CANCELLED:1").summaryLine())
        assertEquals(
            "Verification failed: no root",
            VerificationReport(error = "no root").summaryLine()
        )
    }

    @Test
    fun `a device check never talks about app backups`() {
        // Found on device (2026-10-11): checking device data with no archives
        // reported "No app backups found" — a sentence about something the run
        // never examined. The engine says which kind it checked, so the report
        // carries that rather than trusting the caller to pass a flag.
        assertEquals(
            "No device data archives found",
            feed("VERIFY_DEVICE_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0").summaryLine()
        )
        assertEquals(
            "All 2 device data archives are readable",
            feed(
                "VERIFY_DEVICE_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:10"
            ).summaryLine()
        )
        assertEquals(
            "1 of 2 device data archives readable, 1 damaged",
            feed("VERIFY_DEVICE_DONE|TOTAL:2|OK:1|CORRUPT:1|BYTES:10").summaryLine()
        )
        // The app wording is unchanged.
        assertEquals(
            "No app backups found",
            feed("VERIFY_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0").summaryLine()
        )
    }
}