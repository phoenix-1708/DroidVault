package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppVerification
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.DeviceVerification
import com.phoenix.droidvault.models.MAX_NAMES
import com.phoenix.droidvault.models.VerificationReport
import com.phoenix.droidvault.models.findingsLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verification's log line, which is the artefact a field report carries.
 *
 * `summaryLine` is counts — "1 of 7 device data archives damaged, 6 of 7
 * readable" — and that is a good sentence for a dialog and a bad one for a bug
 * report, because the question a report asks is *which file*. Invariant 41
 * requires a finding to name the artefact it is about; on screen it did, and in
 * the log it did not, so the one place a diagnosis can actually be done was the
 * one place that could not answer it.
 *
 * The second thing worth pinning is the CAP. A vault with 110 damaged apps would
 * otherwise produce a line nobody reads to the end, and an unreadable log is the
 * same as no log — the fix for an uninformative line is not a longer one.
 */
class FindingsLineTest {

    private fun app(pkg: String, ok: Boolean = true) = AppVerification(
        label = pkg,
        packageName = pkg,
        partsChecked = 5,
        corruptParts = if (ok) emptyList() else listOf("Data"),
        bytesReadable = 1_024L
    )

    private fun device(
        item: DeviceDataItem,
        ok: Boolean = true,
        changed: Boolean = false
    ) = DeviceVerification(
        item = item,
        archiveName = item.archiveFile,
        ok = ok,
        hashMismatchedParts = if (changed) listOf(item.archiveFile) else emptyList()
    )

    @Test
    fun `a clean check says so plainly`() {
        // "findings: none" rather than an empty string, so a reader scanning the
        // log can tell "checked and found nothing" from "this line never ran".
        val report = VerificationReport(
            apps = listOf(app("com.a"), app("com.b")),
            deviceItems = listOf(device(DeviceDataItem.MESSAGES))
        )
        assertEquals("findings: none", report.findingsLine())
    }

    @Test
    fun `a damaged archive is named, not counted`() {
        val report = VerificationReport(
            deviceItems = listOf(
                device(DeviceDataItem.MESSAGES, ok = false),
                device(DeviceDataItem.CALLS)
            )
        )
        val line = report.findingsLine()
        assertTrue("names the file: $line", line.contains("Messages.shift"))
        // And does NOT merely say "1 of 2".
        assertTrue("must not be a bare count: $line", !line.contains("1 of 2"))
    }

    @Test
    fun `a damaged app is named by its package`() {
        val report = VerificationReport(
            apps = listOf(app("com.good"), app("com.broken.one", ok = false))
        )
        assertTrue(report.findingsLine().contains("com.broken.one"))
    }

    @Test
    fun `altered is a different finding from unreadable`() {
        // An archive that reads back but no longer matches its digest is not an
        // archive that is gone. Folding the two would make "changed" read as
        // "lost", which is a different and much scarier claim.
        val report = VerificationReport(
            deviceItems = listOf(
                device(DeviceDataItem.MESSAGES, ok = false),
                device(DeviceDataItem.WIFI, changed = true)
            )
        )
        val line = report.findingsLine()
        assertTrue("unreadable section: $line", line.contains("unreadable device archives"))
        assertTrue("altered section: $line", line.contains("altered since written"))
        assertTrue(line.contains("Messages.shift"))
        assertTrue(line.contains("Wi-Fi.shift"))
    }

    @Test
    fun `a long list is capped, and says that it was`() {
        val many = (1..40).map { app("com.app$it", ok = false) }
        val line = VerificationReport(apps = many).findingsLine()
        // The overflow is stated rather than silently dropped, so a reader can
        // tell "these are all of them" from "there are more".
        assertTrue("states the overflow: $line", line.contains("and 28 more"))
        assertTrue("and does not list them all", !line.contains("com.app40"))
    }

    @Test
    fun `the cap is the documented one`() {
        val many = (1..MAX_NAMES).map { app("com.app$it", ok = false) }
        val line = VerificationReport(apps = many).findingsLine()
        // Exactly at the cap: everything fits and nothing is withheld.
        assertTrue(line.contains("com.app$MAX_NAMES"))
        assertTrue("no overflow claimed at the cap: $line", !line.contains("more"))
    }

    @Test
    fun `one finding reads as one, not as a list of one`() {
        val report = VerificationReport(deviceItems = listOf(device(DeviceDataItem.CALLS, ok = false)))
        val line = report.findingsLine()
        assertTrue(line.contains("unreadable device archives: CallLogs.shift"))
        assertTrue("no empty plural: $line", !line.contains("archives: ,"))
    }

    @Test
    fun `the line carries no payload`() {
        // The logger's own rule (§6): no message bodies, no phone numbers, no
        // SSIDs. What goes in is a package name or a file name, both of which
        // are already on the vault index, plus counts.
        val report = VerificationReport(
            apps = listOf(app("com.example.app", ok = false)),
            deviceItems = listOf(device(DeviceDataItem.MESSAGES, ok = false))
        )
        val line = report.findingsLine()
        assertTrue("names a package: $line", line.contains("com.example.app"))
        assertTrue("names an archive: $line", line.contains("Messages.shift"))
        assertTrue("no brace leaked: $line", !line.contains('{') && !line.contains('}'))
    }
}
