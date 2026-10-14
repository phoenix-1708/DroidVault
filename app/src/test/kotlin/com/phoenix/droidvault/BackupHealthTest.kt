package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppVerification
import com.phoenix.droidvault.models.BackupHealth
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.DeviceVerification
import com.phoenix.droidvault.models.HealthSeverity
import com.phoenix.droidvault.models.VerifyScope
import com.phoenix.droidvault.models.VerificationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup-health line on the dashboard.
 *
 * The whole value of this is refusing to say "healthy" when nothing has been
 * checked. A null result, a cancelled run and an engine that never started are
 * all "unknown", and each previously had somewhere to be quietly rendered as a
 * pass. These tests pin that, plus the damaged case actually naming the apps —
 * the same complaint as the trash naming nothing (X-03).
 */
class BackupHealthTest {

    private fun app(pkg: String, ok: Boolean) = AppVerification(
        label = pkg.substringAfterLast('.').uppercase(),
        packageName = pkg,
        partsChecked = 5,
        corruptParts = if (ok) emptyList() else listOf("Data.shift"),
        bytesReadable = 1024L
    )

    private fun report(
        apps: List<AppVerification> = listOf(app("com.a.one", true)),
        cancelled: Boolean = false,
        error: String = ""
    ) = VerificationReport(
        apps = apps,
        total = apps.size,
        okCount = apps.count { it.ok },
        corruptCount = apps.count { !it.ok },
        bytesReadable = 1024L,
        cancelled = cancelled,
        error = error
    )

    // --- unknown must never read as healthy --------------------------------

    @Test
    fun `no report at all is unknown and offers a check`() {
        val h = BackupHealth.from(null)
        assertEquals(HealthSeverity.UNKNOWN, h.severity)
        assertTrue(h.canVerify)
        assertFalse("an unknown health must not offer 'review'", h.canReview)
    }

    @Test
    fun `an empty vault is unknown, not healthy`() {
        // total == 0 is not a passing grade; `conclusive` already encodes that.
        val h = BackupHealth.from(VerificationReport())
        assertEquals(HealthSeverity.UNKNOWN, h.severity)
    }

    @Test
    fun `a cancelled check is unknown, not healthy`() {
        val h = BackupHealth.from(report(cancelled = true))
        assertEquals(HealthSeverity.UNKNOWN, h.severity)
    }

    @Test
    fun `an errored check says so and offers a retry`() {
        val h = BackupHealth.from(report(error = "engine unavailable"))
        assertEquals(HealthSeverity.ERROR, h.severity)
        assertTrue(h.detail!!.contains("engine unavailable"))
        assertTrue(h.canVerify)
    }

    // --- healthy ------------------------------------------------------------

    @Test
    fun `a conclusive clean report is healthy`() {
        val h = BackupHealth.from(report())
        assertEquals(HealthSeverity.HEALTHY, h.severity)
        assertTrue(h.title.contains("1"))
        assertFalse("nothing to review when everything is fine", h.canReview)
    }

    @Test
    fun `healthy states the count it checked`() {
        val h = BackupHealth.from(report(listOf(app("com.a.one", true), app("com.b.two", true))))
        assertEquals(HealthSeverity.HEALTHY, h.severity)
        assertTrue("must state the scope: ${h.title}", h.title.contains("2"))
    }

    @Test
    fun `a healthy vault still offers a check`() {
        // The result lives in memory only, so hiding the control in the good
        // state left no way to ask again without restarting the app — the button
        // simply vanished after one successful check (found on device,
        // 2026-10-12). Checking on purpose is a thing users do: after a ROM
        // flash, after copying a vault in, or just to know again.
        val h = BackupHealth.from(report())
        assertEquals(HealthSeverity.HEALTHY, h.severity)
        assertTrue("a healthy verdict must not remove the way to re-check", h.canVerify)
    }

    // --- a verdict does not outlive a run over the vault --------------------
    //
    // The dashboard is the app's only present-tense claim about the vault, and a
    // healthy-looking line over a vault that has changed since is a tick nobody
    // earned. Damaged is deliberately NOT given the same treatment: that finding
    // is about a file that is still there unless a backup replaced it, and
    // dropping it would unblock the restore it exists to block.

    @Test
    fun `a report from before the last run no longer reads as healthy`() {
        val h = BackupHealth.from(report().copy(takenAtEpoch = 4L), vaultEpoch = 5L)
        assertEquals(HealthSeverity.UNKNOWN, h.severity)
        assertTrue("it must say what happened: ${h.title}", h.title.contains("changed"))
        assertTrue("and offer the check again", h.canVerify)
        assertFalse("nothing concrete to review", h.canReview)
    }

    @Test
    fun `a report taken at the current epoch reads as healthy`() {
        val h = BackupHealth.from(report().copy(takenAtEpoch = 5L), vaultEpoch = 5L)
        assertEquals(HealthSeverity.HEALTHY, h.severity)
    }

    @Test
    fun `a finding survives the vault changing under it`() {
        val h = BackupHealth.from(
            report(listOf(app("com.b.two", false))).copy(takenAtEpoch = 4L),
            vaultEpoch = 9L
        )
        assertEquals(HealthSeverity.DAMAGED, h.severity)
        assertTrue("the user must still be able to look at it", h.canReview)
    }

    @Test
    fun `a verdict from the previous launch is not this launch's good news`() {
        // Carried over from disk: nothing in this process ever looked at the
        // vault, so the row may not state the tick it was stored with. The
        // headline names the real reason — "changed since the last check" would
        // be a different, and false, claim about what happened.
        val h = BackupHealth.from(
            report().copy(takenAtEpoch = 3L, restoredFromDisk = true),
            vaultEpoch = 3L
        )
        assertEquals(HealthSeverity.UNKNOWN, h.severity)
        assertTrue("say what happened: ${h.title}", h.title.contains("before this launch"))
        assertTrue("and what to do: ${h.detail}", h.detail!!.contains("Check again"))
        assertTrue(h.canVerify)
        assertFalse(h.canReview)
    }

    @Test
    fun `a finding survives a restart`() {
        // The reason the verdict is written down at all: a damaged archive is
        // still damaged unless something replaced it, and the restore gate has to
        // keep refusing after the app was killed and reopened.
        val h = BackupHealth.from(
            report(listOf(app("com.b.two", false))).copy(restoredFromDisk = true),
            vaultEpoch = 0L
        )
        assertEquals(HealthSeverity.DAMAGED, h.severity)
        assertTrue(h.canReview)
        assertTrue("must still name it: ${h.detail}", h.detail!!.contains("TWO"))
    }

    // --- readable but altered -----------------------------------------------
    //
    // Found on the device: one byte of a part changed, same length, and the engine
    // reported `HASH_CHANGED:1|HASH_MISMATCH:Permissions.txt`. The dialog and the
    // restore gate both said "changed"; this card said "All 3 backups readable".

    private fun changedApp(pkg: String) = AppVerification(
        label = pkg.substringAfterLast('.').uppercase(),
        packageName = pkg,
        partsChecked = 3,
        corruptParts = emptyList(),
        bytesReadable = 65L,
        hashMismatchedParts = listOf("Permissions.txt")
    )

    @Test
    fun `a readable archive that changed is not a pass`() {
        val r = VerificationReport(
            apps = listOf(app("com.a.one", true), changedApp("com.b.two")),
            total = 2,
            okCount = 2,
            corruptCount = 0,
            bytesReadable = 1024L
        )
        val h = BackupHealth.from(r)
        assertEquals(HealthSeverity.CHANGED, h.severity)
        assertTrue("say what happened: ${h.title}", h.title.contains("changed"))
        assertTrue("must not claim they are readable: ${h.title}", !h.title.contains("readable"))
        assertTrue("and must name it: ${h.detail}", h.detail!!.contains("TWO"))
        assertTrue("the user must be able to look at it", h.canReview)
        assertTrue(h.canVerify)
    }

    @Test
    fun `a changed device archive is reported too`() {
        // A device-only verdict has no app rows, so the count and the sentence are
        // all the card has to work with.
        val r = VerificationReport(
            total = 2,
            okCount = 2,
            corruptCount = 0,
            scope = VerifyScope.DEVICE,
            deviceChanged = 1
        )
        val h = BackupHealth.from(r)
        assertEquals(HealthSeverity.CHANGED, h.severity)
        assertTrue("name the subject: ${h.title}", h.title.contains("device data archive"))
        assertTrue("and the thing: ${h.detail}", h.detail!!.contains("device data"))
    }

    @Test
    fun `a merged verdict counts both halves and says which`() {
        val r = VerificationReport(
            apps = listOf(changedApp("com.b.two")),
            total = 5,
            okCount = 5,
            corruptCount = 0,
            scope = VerifyScope.BOTH,
            deviceChanged = 1
        )
        val h = BackupHealth.from(r)
        assertEquals(HealthSeverity.CHANGED, h.severity)
        assertTrue("2 of 5: ${h.title}", h.title.contains("2 of 5"))
        assertTrue(
            "a merged verdict is not an app-only one: ${h.title}",
            !h.title.contains("app backup")
        )
    }

    @Test
    fun `a finding of change survives a restart`() {
        val r = VerificationReport(
            apps = listOf(changedApp("com.b.two")),
            total = 1,
            okCount = 1,
            corruptCount = 0,
            restoredFromDisk = true
        )
        assertEquals(HealthSeverity.CHANGED, BackupHealth.from(r, vaultEpoch = 0L).severity)
    }

    // --- damaged ------------------------------------------------------------

    @Test
    fun `damage is reported as damaged and can be reviewed`() {
        val h = BackupHealth.from(
            report(listOf(app("com.a.one", true), app("com.b.two", false)))
        )
        assertEquals(HealthSeverity.DAMAGED, h.severity)
        assertTrue(h.canReview)
        assertTrue("must state how many: ${h.title}", h.title.contains("1 of 2"))
    }

    @Test
    fun `damaged names the apps`() {
        val h = BackupHealth.from(report(listOf(app("com.b.two", false))))
        assertTrue("must name it: ${h.detail}", h.detail!!.contains("TWO"))
    }

    @Test
    fun `a long damage list is summarised rather than dumped`() {
        val many = (1..6).map { app("com.p$it.bad", false) }
        val h = BackupHealth.from(report(many))
        assertEquals(HealthSeverity.DAMAGED, h.severity)
        assertTrue("should cap the list: ${h.detail}", h.detail!!.contains("and 3 more"))
    }

    @Test
    fun `an app with no label falls back to its package name`() {
        val nameless = AppVerification(
            label = "",
            packageName = "com.weird.pkg",
            partsChecked = 1,
            corruptParts = listOf("App.shift"),
            bytesReadable = 1L
        )
        val h = BackupHealth.from(report(listOf(nameless)))
        assertTrue("must not show a blank name", h.detail!!.contains("com.weird.pkg"))
    }

    @Test
    fun `a damaged device archive is named on the card`() {
        // The device half of a verdict used to be counts only, so a damaged
        // Messages.shift reached the card as part of "1 of 7 damaged" with nothing
        // to act on — and the restore gate could not name the archive it refused
        // either. One finding, one name, on every surface that states it.
        val r = VerificationReport(
            total = 3,
            okCount = 2,
            corruptCount = 1,
            scope = VerifyScope.BOTH,
            deviceItems = listOf(
                DeviceVerification(
                    item = DeviceDataItem.MESSAGES,
                    archiveName = "Messages.shift",
                    ok = false,
                    corruptParts = listOf("Messages.shift")
                )
            )
        )
        val h = BackupHealth.from(r)
        assertEquals(HealthSeverity.DAMAGED, h.severity)
        assertTrue("name it: ${h.detail}", h.detail!!.contains("Messages.shift"))
        assertTrue("and say the subject: ${h.title}", h.title.contains("backups"))
        assertTrue("the user must be able to look at it", h.canReview)
    }

    @Test
    fun `a device-only damaged archive is a finding with no app rows`() {
        val r = VerificationReport(
            total = 2,
            okCount = 1,
            corruptCount = 1,
            scope = VerifyScope.DEVICE,
            deviceItems = listOf(
                DeviceVerification(
                    item = DeviceDataItem.CALLS,
                    archiveName = "CallLogs.shift",
                    ok = false,
                    corruptParts = listOf("CallLogs.shift")
                )
            )
        )
        val h = BackupHealth.from(r)
        assertEquals(HealthSeverity.DAMAGED, h.severity)
        assertTrue("say which kind: ${h.title}", h.title.contains("device data archive"))
        assertTrue("name it: ${h.detail}", h.detail!!.contains("CallLogs.shift"))
    }

    // --- a finding says how old it is, and what has run since ----------------

    @Test
    fun `a finding says when it was first seen`() {
        val now = 1_700_000_000_000L
        val twoDays = 2L * 24 * 60 * 60 * 1000
        val r = VerificationReport(
            total = 2,
            okCount = 2,
            deviceChanged = 1,
            apps = listOf(
                AppVerification(
                    label = "A",
                    packageName = "com.a",
                    partsChecked = 5,
                    corruptParts = emptyList(),
                    bytesReadable = 10L,
                    hashMismatchedParts = listOf("Data.shift"),
                    firstSeenMs = now - twoDays
                )
            ),
            deviceItems = listOf(
                DeviceVerification(
                    item = DeviceDataItem.WIFI,
                    archiveName = "Wi-Fi.shift",
                    ok = true,
                    bytesReadable = 1L,
                    hashMismatchedParts = listOf("Wi-Fi.shift"),
                    firstSeenMs = now
                )
            )
        )
        val h = BackupHealth.from(r, 0L, now, null)
        assertEquals(HealthSeverity.CHANGED, h.severity)
        assertTrue("names the archives: ${h.detail}", h.detail!!.contains("Wi-Fi.shift"))
        assertTrue(
            "and says how long the OLDEST of them has stood: ${h.footnote}",
            h.footnote!!.contains("2 days ago")
        )
    }

    @Test
    fun `a finding that outlived a run says so, and names the kind of run`() {
        val now = 1_700_000_000_000L
        val r = VerificationReport(
            total = 1,
            okCount = 1,
            scope = VerifyScope.DEVICE,
            deviceChanged = 1,
            takenAtEpoch = 0L,
            deviceItems = listOf(
                DeviceVerification(
                    item = DeviceDataItem.CALLS,
                    archiveName = "CallLogs.shift",
                    ok = true,
                    bytesReadable = 1L,
                    hashMismatchedParts = listOf("CallLogs.shift"),
                    firstSeenMs = now - 3_600_000L
                )
            )
        )
        // A run released the gate after this verdict was taken (epoch 0 -> 1).
        val h = BackupHealth.from(r, 1L, now, "manual:BACKUP_APPS")
        val foot = checkNotNull(h.footnote)
        assertTrue("an hour ago: $foot", foot.contains("an hour ago"))
        assertTrue("which kind of run: $foot", foot.contains("an app backup has run since"))

        // A restart loses the label but not the fact, and must not invent one.
        val after = BackupHealth.from(r, 1L, now, null)
        val afterFoot = checkNotNull(after.footnote)
        assertTrue(
            "still admits a run happened: $afterFoot",
            afterFoot.contains("A run has been over the vault since")
        )
        assertFalse(
            "and does not name a run it cannot know: $afterFoot",
            afterFoot.contains("backup has run")
        )

        // Nothing has run and no age is known: the two lines above already said it.
        assertNull(BackupHealth.from(r, 0L, 0L, null).footnote)
    }

    @Test
    fun `a finding read back from disk says which launch saw it`() {
        val now = 1_700_000_000_000L
        val r = VerificationReport(
            total = 1,
            corruptCount = 1,
            apps = listOf(
                AppVerification(
                    label = "A",
                    packageName = "com.a",
                    partsChecked = 5,
                    corruptParts = listOf("Data.shift"),
                    bytesReadable = 0L,
                    firstSeenMs = now - 86_400_000L
                )
            )
        )
        val restored = BackupHealth.from(r.copy(restoredFromDisk = true), 0L, now, null)
        assertEquals(HealthSeverity.DAMAGED, restored.severity)
        val foot = checkNotNull(restored.footnote)
        assertTrue("$foot", foot.contains("yesterday"))
        assertTrue(
            "and whose observation it is: $foot",
            foot.contains("checked before this launch")
        )
    }

    @Test
    fun `a deletion is not called a backup`() {
        // The gate's labels are internal names; shown raw they read as debug
        // output, and shown as the wrong kind of run the card describes work that
        // did not happen.
        val now = 1_700_000_000_000L
        val r = VerificationReport(
            total = 1,
            okCount = 0,
            corruptCount = 1,
            apps = listOf(
                AppVerification("A", "com.a", 5, listOf("Data.shift"), 0L, firstSeenMs = now)
            )
        )
        val h = BackupHealth.from(r, 1L, now, "delete:device")
        val foot = checkNotNull(h.footnote)
        assertTrue("$foot", foot.contains("a device-data deletion has run since"))
        assertFalse("never a backup: $foot", foot.contains("backup has run"))
    }
}
