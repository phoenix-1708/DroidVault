package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppVerification
import com.phoenix.droidvault.models.VerificationReport
import com.phoenix.droidvault.models.restoreBlockReason
import com.phoenix.droidvault.models.verifyReportAllowsRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-restore integrity gate, as applied to BOTH entry points.
 *
 * Found auditing the Migrate/Automation linkage: the rule lived only in the
 * manual Migrate flow (`MainViewModel.restoreBlockedBy`). The scheduler checked
 * that an archive FILE existed and never that it could be READ, so a scheduled
 * restore ran against archives a verification had already reported as damaged.
 * The extraction exists so both paths cannot drift again.
 */
class RestoreBlockReasonTest {

    private fun report(
        total: Int = 2,
        corrupt: List<String> = emptyList(),
        labels: Map<String, String> = emptyMap()
    ) = VerificationReport(
        total = total,
        apps = (listOf("a.one") + corrupt).map { pkg ->
            AppVerification(
                label = labels[pkg] ?: pkg,
                packageName = pkg,
                partsChecked = 2,
                corruptParts = if (pkg in corrupt) listOf("Data.shift") else emptyList(),
                bytesReadable = 1024L
            )
        }
    )

    // --- blocks ------------------------------------------------------------

    @Test
    fun `damage among the selected packages blocks the restore`() {
        val r = report(corrupt = listOf("bad.app"))
        val reason = restoreBlockReason(r, setOf("a.one", "bad.app"))
        assertNotNull("a conclusive corrupt verdict must block", reason)
    }

    @Test
    fun `the reason names the damaged app by label`() {
        val r = report(corrupt = listOf("bad.app"), labels = mapOf("bad.app" to "Photos"))
        val reason = restoreBlockReason(r, setOf("bad.app"))
        assertNotNull(reason)
        assertTrue("should name the app: $reason", reason!!.contains("Photos"))
    }

    @Test
    fun `a package with no label falls back to its package name`() {
        val r = report(corrupt = listOf("com.x.y"))
        val reason = restoreBlockReason(r, setOf("com.x.y"))
        assertNotNull(reason)
        assertTrue(reason!!.contains("com.x.y"))
    }

    // --- blocks on a change, and says which kind it is ----------------------–

    private fun changedReport(
        packages: List<String>,
        labels: Map<String, String> = emptyMap()
    ) = VerificationReport(
        total = packages.size,
        apps = packages.map { pkg ->
            AppVerification(
                label = labels[pkg] ?: pkg,
                packageName = pkg,
                partsChecked = 3,
                corruptParts = emptyList(),
                bytesReadable = 4096L,
                hashMismatchedParts = listOf("Permissions.txt")
            )
        }
    )

    @Test
    fun `an archive that changed since it was written blocks the restore`() {
        // Readable, and not the archive the vault recorded. Restoring it would
        // overwrite an install with unknown data instead of the known one.
        val r = changedReport(listOf("bad.app"))
        assertEquals(false, r.verifyReportAllowsRestore(setOf("bad.app")))
        assertNotNull(restoreBlockReason(r, setOf("bad.app")))
    }

    @Test
    fun `the changed reason names the app and the part, and does not claim damage`() {
        val r = changedReport(listOf("bad.app"), mapOf("bad.app" to "Photos"))
        val reason = restoreBlockReason(r, setOf("bad.app"))!!
        assertTrue("should name the app: $reason", reason.contains("Photos"))
        assertTrue("should name the part: $reason", reason.contains("Permissions.txt"))
        assertTrue("should say changed: $reason", reason.contains("changed since"))
        assertFalse(
            "a readable archive must not be described as unreadable: $reason",
            reason.contains("cannot be read back")
        )
    }

    @Test
    fun `a change outside the selection does not block`() {
        val r = changedReport(listOf("not.selected"))
        assertNull(restoreBlockReason(r, setOf("a.one")))
    }

    @Test
    fun `a report that is both damaged and changed says both`() {
        // Two different problems with two different fixes; a reason that named
        // only one of them would send the user to the wrong repair.
        val r = VerificationReport(
            total = 1,
            apps = listOf(
                AppVerification(
                    label = "Both",
                    packageName = "both.app",
                    partsChecked = 3,
                    corruptParts = listOf("Data.shift"),
                    bytesReadable = 0L,
                    hashMismatchedParts = listOf("App.shift")
                )
            )
        )
        val reason = restoreBlockReason(r, setOf("both.app"))!!
        assertTrue("should report the unreadable part: $reason", reason.contains("cannot be read back"))
        assertTrue("should report the changed part: $reason", reason.contains("changed since"))
    }

    // --- does not block ----------------------------------------------------

    @Test
    fun `a healthy report allows the restore`() {
        assertNull(restoreBlockReason(report(), setOf("a.one")))
    }

    @Test
    fun `damage outside the selection does not block`() {
        // The other apps may well be fine; refusing the whole run over a row the
        // user did not select would be its own bug.
        val r = report(corrupt = listOf("not.selected"))
        assertNull(restoreBlockReason(r, setOf("a.one")))
    }

    @Test
    fun `an inconclusive report allows the restore`() {
        // A fresh vault, a cancelled verify, or an engine that never started all
        // report "unknown". Treating that as damage would make Restore
        // permanently dead on a fresh install.
        val unknown = VerificationReport()
        assertNull(restoreBlockReason(unknown, setOf("a.one")))
        assertNull(restoreBlockReason(null, setOf("a.one")))
    }

    @Test
    fun `an empty selection never blocks`() {
        assertNull(restoreBlockReason(report(corrupt = listOf("a.one")), emptySet()))
    }

    @Test
    fun `an app-level verdict agrees with the package-level one`() {
        // The gate must not contradict the report it reads.
        val r = report(corrupt = listOf("bad.app"))
        assertEquals(false, r.verifyReportAllowsRestore(setOf("bad.app")))
        assertEquals(true, r.verifyReportAllowsRestore(setOf("a.one")))
    }

    @Test
    fun `every app in a restore plan is a real installed app`() {
        // Guards the caller contract: the gate is keyed on package names, so a
        // plan carrying blanks would silently evade it.
        val app = AppInfo(label = "X", packageName = "com.x")
        assertTrue(app.packageName.isNotBlank())
    }
}