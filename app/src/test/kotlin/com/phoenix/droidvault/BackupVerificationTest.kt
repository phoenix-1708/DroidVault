package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppVerification
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.DeviceVerification
import com.phoenix.droidvault.models.VerdictStaleness
import com.phoenix.droidvault.models.VerifyScope
import com.phoenix.droidvault.models.VerificationParser
import com.phoenix.droidvault.models.VerificationReport
import com.phoenix.droidvault.models.restoreBlockReason
import com.phoenix.droidvault.models.restoreBlockReasonDevice
import com.phoenix.droidvault.models.staleness
import com.phoenix.droidvault.models.summaryLine
import com.phoenix.droidvault.models.verifyReportAllowsRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    // --- a changed archive is not a good archive ----------------------------
    //
    // Readable is not the same as "the archive we made". The engine reports a
    // digest that no longer matches beside the readability verdict rather than
    // inside it, because the two need different actions — and a restore must
    // refuse on either.

    private val appChanged =
        "VERIFY_APP|INDEX:1|TOTAL:1|LABEL:Drive|PKG:com.a|PARTS:3|CORRUPT:|BYTES:100|" +
                "HASHED:2|HASH_CHANGED:1|HASH_UNRECORDED:0|HASH_MISMATCH:Permissions.txt"

    @Test
    fun `parses a digest mismatch apart from damage`() {
        val app = feed(appChanged).apps[0]
        assertTrue("it still reads back", app.ok)
        assertTrue(app.changed)
        assertFalse("but it is not the archive that was written", app.trustworthy)
        assertEquals(listOf("Permissions.txt"), app.hashMismatchedParts)
    }

    @Test
    fun `an empty mismatch field is not a mismatch`() {
        // The engine writes the field on every line, empty when there is nothing
        // to report. Splitting an empty string must not invent a part.
        val app = feed(
            "VERIFY_APP|INDEX:1|TOTAL:1|LABEL:A|PKG:com.a|PARTS:1|CORRUPT:|BYTES:1|HASH_MISMATCH:"
        ).apps[0]
        assertFalse(app.changed)
        assertTrue(app.trustworthy)
    }

    @Test
    fun `a changed archive blocks the restore`() {
        val r = feed(appChanged, "VERIFY_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:100|HASH_CHANGED:1")
        assertEquals(false, r.verifyReportAllowsRestore(setOf("com.a")))
        assertFalse("nothing was unreadable", r.hasCorrupt)
        assertTrue(r.hasChanged)
        assertFalse("readable is not enough to call it healthy", r.healthy)
        assertNotNull(restoreBlockReason(r, setOf("com.a")))
    }

    @Test
    fun `a change outside the selection does not block it`() {
        val r = feed(
            appOk,
            appChanged.replace("PKG:com.a", "PKG:com.other"),
            "VERIFY_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:200|HASH_CHANGED:1"
        )
        assertEquals(true, r.verifyReportAllowsRestore(setOf("com.a")))
    }

    @Test
    fun `device archives report a change too`() {
        val r = feed("VERIFY_DEVICE_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:10|HASH_CHANGED:1")
        assertTrue(r.hasChanged)
        assertFalse(r.healthy)
        assertEquals(
            "1 device data archive changed since they were written, 2 of 2 readable",
            r.summaryLine()
        )
    }

    @Test
    fun `how much was read and how much was skipped are both reported`() {
        // A verification of an unchanged vault reads almost nothing. Reporting
        // only "all readable" would let the user believe every byte was just
        // proved again.
        val r = feed("VERIFY_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:0|SKIPPED:3|READ:1")
        assertEquals(3, r.skippedParts)
        assertEquals(1, r.readParts)
    }

    // --- a verdict is a measurement of a moment ------------------------------
    //
    // A report describes the vault as it was while its bytes were read. Every
    // writer holds the run gate, so the gate's release counter is the one signal
    // that the vault may have moved on — and the report carries the value it was
    // taken at. Staleness must withdraw the GOOD news and keep the finding.

    @Test
    fun `a report is current only at the epoch it was taken at`() {
        val r = feed(appOk, done).copy(takenAtEpoch = 7L)
        assertTrue(r.stillCurrent(7L))
        assertFalse("a run since then may have written anything", r.stillCurrent(8L))
    }

    @Test
    fun `an unstamped report is current until the first run`() {
        // Default 0 is what a report carries before any run has released the
        // gate in this session. It is the assumption that under-claims, so a
        // caller who forgets to stamp gets a stale verdict, never a green tick.
        val r = feed(appOk, done)
        assertTrue(r.stillCurrent(0L))
        assertFalse(r.stillCurrent(1L))
    }

    @Test
    fun `staleness withdraws the good news and keeps the finding`() {
        val healthy = feed(appOk, done).copy(takenAtEpoch = 1L)
        assertFalse(healthy.stillCurrent(2L))
        // The restore gate is a different question — "is anything you SELECTED
        // known bad?" — and deliberately ignores the epoch: refusing a restore
        // because some other app was re-backed-up afterwards would make Restore
        // unusable for anyone who backs up on a schedule.
        assertEquals(true, healthy.verifyReportAllowsRestore(setOf("com.a")))
        val damaged = feed(appBad, done).copy(takenAtEpoch = 1L)
        assertFalse(damaged.stillCurrent(2L))
        assertEquals(false, damaged.verifyReportAllowsRestore(setOf("com.b")))
    }

    // --- a verdict that covers both halves of the work ----------------------
    //
    // One check runs two engine verbs and shows one verdict, and the arithmetic
    // that folds them together is where half of a result can quietly disappear.

    @Test
    fun `a merged verdict keeps both halves of the work`() {
        // The hand-written merge this replaces kept apps, total, okCount,
        // corruptCount and error, and dropped bytesReadable, deviceChanged,
        // readParts, skippedParts and cancelled — so a device archive whose digest
        // no longer matched left the verdict entirely and the health row read
        // "all readable" over it (§2.47).
        val appsRun = feed(appOk, "VERIFY_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:100|READ:1")
        val deviceRun = feed(
            "VERIFY_DEVICE_DONE|TOTAL:4|OK:3|CORRUPT:0|BYTES:900|HASH_CHANGED:1|READ:3|SKIPPED:1"
        )
        val merged = appsRun.mergedWith(deviceRun)
        assertEquals(5, merged.total)
        assertEquals(4, merged.okCount)
        assertEquals(1000L, merged.bytesReadable)
        assertEquals(1, merged.deviceChanged)
        assertEquals(4, merged.readParts)
        assertEquals(1, merged.skippedParts)
        assertEquals(VerifyScope.BOTH, merged.scope)
        assertTrue("a device archive that changed is a change", merged.hasChanged)
        assertFalse("and it is not healthy news", merged.healthy)
    }

    @Test
    fun `a half that stopped early makes the whole verdict incomplete`() {
        val merged = feed(appOk, done).mergedWith(
            feed("VERIFY_DEVICE_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:1|CANCELLED:1")
        )
        assertTrue("a half that was cut short cannot be summarised as done", merged.cancelled)
        assertFalse(merged.conclusive)
        assertFalse(merged.healthy)
    }

    @Test
    fun `a changed device archive is counted once, in its own unit`() {
        // The two runs put different things in the same field name: an app run's
        // done-line HASH_CHANGED counts mismatched PARTS, a device run's counts
        // archives. Summing them would claim more changed backups than exist, and
        // the app half is already counted by its rows — so the merge keeps the
        // device number and lets the rows speak for the apps.
        val appsRun = feed(
            appChanged,
            "VERIFY_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:100|HASH_CHANGED:1"
        )
        val deviceRun = feed("VERIFY_DEVICE_DONE|TOTAL:2|OK:1|CORRUPT:0|BYTES:9|HASH_CHANGED:1")
        val merged = appsRun.mergedWith(deviceRun)
        assertEquals(
            "2 backups changed since they were written, 2 of 3 readable",
            merged.summaryLine()
        )
    }

    @Test
    fun `a merged verdict names both halves, not just one`() {
        // "All 3 app backups are readable" over a verdict that also covers device
        // archives is a sentence about work the run did not do — §2.44's defect,
        // reachable again through the merge.
        val merged = feed(appOk, "VERIFY_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:100")
            .mergedWith(feed("VERIFY_DEVICE_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:9"))
        assertEquals("All 3 backups are readable", merged.summaryLine())
    }

    @Test
    fun `a changed count is in the unit its sentence needs`() {
        // For an app run the rows are the count of changed BACKUPS, while the
        // engine's done-line number counts mismatched PARTS — one part changed in
        // one app is one backup, not two. For a device run the count is all there
        // is. Getting this wrong makes the card claim more changed backups than
        // exist.
        val appsRun = feed(
            appChanged,
            "VERIFY_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:0|HASH_CHANGED:1"
        )
        assertEquals(1, appsRun.changedCount)
        val deviceRun = feed(
            "VERIFY_DEVICE_DONE|TOTAL:3|OK:3|CORRUPT:0|BYTES:9|HASH_CHANGED:2"
        )
        assertEquals(2, deviceRun.changedCount)
        assertEquals(3, appsRun.mergedWith(deviceRun).changedCount)
    }

    // --- where a verdict came from -----------------------------------------

    @Test
    fun `an epoch match is current and a mismatch is changed-since`() {
        val r = feed(appOk, done).copy(takenAtEpoch = 3L)
        assertEquals(VerdictStaleness.CURRENT, r.staleness(3L))
        assertEquals(VerdictStaleness.CHANGED_SINCE, r.staleness(4L))
    }

    @Test
    fun `a report read back from disk is never current, whatever the epoch says`() {
        // The counter is not persisted, so after a restart a restored record and a
        // fresh one can carry the same value — and equality is exactly what the
        // epoch check reads as "still describes the vault". Provenance is the
        // missing half of the question, and it is why the counter does not need to
        // be durable.
        val restored = feed(appOk, done).copy(takenAtEpoch = 3L, restoredFromDisk = true)
        assertEquals(VerdictStaleness.FROM_LAST_SESSION, restored.staleness(3L))
        assertEquals(VerdictStaleness.FROM_LAST_SESSION, restored.staleness(9L))
    }

    @Test
    fun `provenance does not unblock a restore`() {
        // The gate asks whether anything SELECTED is known bad; a damaged archive
        // is still damaged after a restart. A finding that expires on reboot is
        // the failure this whole path exists to prevent.
        val damaged = feed(appBad, done).copy(takenAtEpoch = 1L, restoredFromDisk = true)
        assertEquals(false, damaged.verifyReportAllowsRestore(setOf("com.b")))
        assertNotNull(restoreBlockReason(damaged, setOf("com.b")))
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
        // Readable but altered: "all readable" would be true and useless.
        assertEquals(
            "1 app backup changed since they were written, 1 of 1 readable",
            feed(appChanged, "VERIFY_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:100|HASH_CHANGED:1")
                .summaryLine()
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

    // --- the device half names what it found -------------------------------
    //
    // A count is not a finding. "1 of 7 device data archives damaged" cannot be
    // acted on, cannot be named on the card, and cannot refuse the ONE archive a
    // restore plan actually restores — so the device rows are kept, not just the
    // summary. Before they were, a damaged messages archive reached the restore
    // gate as an anonymous number and the gate let it through.

    private val deviceOk = "VERIFY_DEVICE_ITEM|NAME:Messages.shift|STATUS:OK|HASH:ok"
    private val deviceBad = "VERIFY_DEVICE_ITEM|NAME:CallLogs.shift|STATUS:CORRUPT"
    private val deviceChangedRow =
        "VERIFY_DEVICE_ITEM|NAME:Contacts.shift|STATUS:OK|HASH:changed"

    @Test
    fun `a device item line becomes a row that names its archive`() {
        val r = feed(deviceOk, deviceBad, deviceChangedRow)
        assertEquals(3, r.deviceItems.size)
        assertEquals("Messages.shift", r.deviceItems[0].archiveName)
        assertEquals(DeviceDataItem.MESSAGES, r.deviceItems[0].item)
        assertTrue("it reads back", r.deviceItems[0].trustworthy)
        assertFalse("this one does not", r.deviceItems[1].ok)
        assertEquals(
            "the item's own name is what the UI shows",
            "Calls",
            r.deviceItems[1].label
        )
        assertTrue("readable, but not the archive that was written", r.deviceItems[2].changed)
        assertEquals(listOf("Contacts.shift"), r.deviceItems[2].hashMismatchedParts)
    }

    @Test
    fun `an unrecorded digest is unknown, not changed`() {
        // `HASH:unrecorded` is "there was no digest to compare". Reporting it as
        // changed would put a finding on an archive nothing has evidence about.
        val r = feed("VERIFY_DEVICE_ITEM|NAME:Wi-Fi.shift|STATUS:OK|HASH:unrecorded")
        assertTrue(r.deviceItems[0].trustworthy)
        assertFalse(r.deviceItems[0].changed)
    }

    @Test
    fun `an app line's hash count is never a device count`() {
        // The two halves put different things in the same field name: an app
        // done-line's HASH_CHANGED counts mismatched PARTS. Reading it onto the
        // device number made an app's `Permissions.txt` show up as "device data"
        // changed on the Home card.
        val r = feed(
            appChanged,
            "VERIFY_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:100|HASH_CHANGED:1"
        )
        assertEquals(0, r.deviceChanged)
        assertEquals(0, r.deviceChangedArchives)
        assertTrue("the app row is still the finding", r.hasChanged)
    }

    @Test
    fun `a device summary alone still names what changed`() {
        // A report that carries only the done-line — an older engine, or a stored
        // verdict — knows which archives changed from HASH_MISMATCH, and those names
        // are the finding.
        val r = feed(
            "VERIFY_DEVICE_DONE|TOTAL:3|OK:3|CORRUPT:0|BYTES:30|HASH_CHANGED:1|" +
                    "HASH_MISMATCH:Messages.shift"
        )
        assertEquals(listOf("Messages.shift"), r.deviceChangedArchivesNamed())
        assertEquals(1, r.deviceChangedArchives)
    }

    @Test
    fun `device rows travel through the merge`() {
        val merged = feed(deviceBad, "VERIFY_DEVICE_DONE|TOTAL:1|OK:0|CORRUPT:1|BYTES:0")
            .mergedWith(feed(deviceOk, "VERIFY_DEVICE_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:5"))
        assertEquals(2, merged.deviceItems.size)
        assertEquals(listOf("CallLogs.shift"), merged.deviceDamagedArchives())
        assertTrue("a device finding is still a finding", merged.hasAnyDamagedArchives())
    }

    // --- the device restore gate -------------------------------------------

    @Test
    fun `a damaged device archive blocks the restore that would write it`() {
        val r = feed(
            deviceBad,
            deviceOk,
            "VERIFY_DEVICE_DONE|TOTAL:2|OK:1|CORRUPT:1|BYTES:5"
        )
        val blocked = restoreBlockReasonDevice(r, setOf(DeviceDataItem.CALLS))
        assertNotNull("the plan restores exactly the damaged archive", blocked)
        assertTrue("it must name the archive: $blocked", blocked!!.contains("CallLogs.shift"))
        assertTrue("and say it is damaged: $blocked", blocked.contains("damaged"))
    }

    @Test
    fun `a device archive outside the plan does not block it`() {
        val r = feed(deviceBad, "VERIFY_DEVICE_DONE|TOTAL:1|OK:0|CORRUPT:1|BYTES:0")
        assertNull(
            "restoring messages is not affected by a damaged call log",
            restoreBlockReasonDevice(r, setOf(DeviceDataItem.MESSAGES))
        )
    }

    @Test
    fun `a changed device archive blocks, and says it changed rather than broke`() {
        val r = feed(
            deviceChangedRow,
            "VERIFY_DEVICE_DONE|TOTAL:1|OK:1|CORRUPT:0|BYTES:9|HASH_CHANGED:1|" +
                    "HASH_MISMATCH:Contacts.shift"
        )
        val blocked = restoreBlockReasonDevice(r, setOf(DeviceDataItem.CONTACTS))!!
        assertTrue("name it: $blocked", blocked.contains("Contacts.shift"))
        assertTrue("it was altered, not unreadable: $blocked", blocked.contains("changed since"))
        assertFalse(blocked.contains("cannot be read back"))
    }

    @Test
    fun `an unconclusive device verdict never blocks`() {
        // An empty vault, a cancelled run and an engine that never started all
        // report "unknown", and refusing a restore on "unknown" would make Restore
        // permanently dead on a fresh install.
        assertNull(restoreBlockReasonDevice(VerificationReport(), setOf(DeviceDataItem.MESSAGES)))
        assertNull(
            restoreBlockReasonDevice(
                feed(deviceBad, "VERIFY_DEVICE_DONE|TOTAL:0|OK:0|CORRUPT:0|BYTES:0|CANCELLED:1"),
                setOf(DeviceDataItem.CALLS)
            )
        )
        assertNull(restoreBlockReasonDevice(null, setOf(DeviceDataItem.CALLS)))
        assertNull(
            "nothing selected is nothing to gate",
            restoreBlockReasonDevice(feed(deviceBad, "VERIFY_DEVICE_DONE|TOTAL:1|OK:0|CORRUPT:1|BYTES:0"), emptySet())
        )
    }

    // --- a finding keeps the age it was first seen at ------------------------

    @Test
    fun `a finding keeps its first sighting across checks`() {
        val now = 1_700_000_000_000L
        val day = 86_400_000L
        val first = VerificationReport(
            total = 2,
            okCount = 1,
            corruptCount = 1,
            apps = listOf(
                AppVerification("A", "com.a", 5, listOf("Data.shift"), 0L),
                AppVerification("B", "com.b", 5, emptyList(), 0L)
            )
        ).withFindingAges(null, now - day)
        assertEquals("seen by the check that found it", now - day, first.apps[0].firstSeenMs)
        assertEquals("a healthy row carries no age", 0L, first.apps[1].firstSeenMs)
        assertEquals(now - day, first.takenAtMs)

        // The same archive still failing later: the age belongs to the FINDING,
        // not to whichever check last happened to look.
        val second = VerificationReport(
            total = 2,
            okCount = 1,
            corruptCount = 1,
            apps = listOf(
                AppVerification("A", "com.a", 5, listOf("Data.shift"), 0L),
                AppVerification("B", "com.b", 5, emptyList(), 0L)
            )
        ).withFindingAges(first, now)
        assertEquals("the finding is older than this check", now - day, second.apps[0].firstSeenMs)
        assertEquals("while the reading is this check's", now, second.takenAtMs)

        // A NEW finding starts its age now, rather than inheriting the age of a
        // problem the user already fixed.
        val moved = VerificationReport(
            total = 2,
            okCount = 1,
            corruptCount = 1,
            apps = listOf(
                AppVerification("A", "com.a", 5, emptyList(), 0L),
                AppVerification("B", "com.b", 5, listOf("Data.shift"), 0L)
            )
        ).withFindingAges(first, now)
        assertEquals(0L, moved.apps[0].firstSeenMs)
        assertEquals(now, moved.apps[1].firstSeenMs)

        // Repaired: nothing is failing, so there is no age to state, and the
        // reader must not fall back to the age of the check.
        val healed = VerificationReport(
            total = 2,
            okCount = 2,
            corruptCount = 0,
            apps = listOf(
                AppVerification("A", "com.a", 5, emptyList(), 0L),
                AppVerification("B", "com.b", 5, emptyList(), 0L)
            )
        ).withFindingAges(second, now)
        assertEquals(0L, healed.apps[0].firstSeenMs)
        assertNull("nothing is failing, so there is no age to state", healed.oldestFindingMs())
    }

    @Test
    fun `a device finding is matched by its archive, not by the row`() {
        val now = 1_700_000_000_000L
        val row = DeviceVerification(
            item = DeviceDataItem.CALLS,
            archiveName = "CallLogs.shift",
            ok = true,
            bytesReadable = 1L,
            hashMismatchedParts = listOf("CallLogs.shift")
        )
        val first = VerificationReport(
            total = 1,
            okCount = 1,
            scope = VerifyScope.DEVICE,
            deviceChanged = 1,
            deviceItems = listOf(row)
        ).withFindingAges(null, now - 5_000L)

        // A later run reports the same archive with no item resolved (a build that
        // does not know the name): the age follows the FILE, which is the identity
        // the gate matches on too.
        val later: VerificationReport = VerificationReport(
            total = 1,
            okCount = 1,
            scope = VerifyScope.DEVICE,
            deviceChanged = 1,
            deviceItems = listOf(row.copy(item = null))
        ).withFindingAges(first, now)
        assertEquals(now - 5_000L, later.deviceItems[0].firstSeenMs)
    }

    @Test
    fun `the merge carries the repaired records`() {
        // Both halves count the same unit — one `.sha256` record rewritten — so
        // this one IS summed, unlike the `HASH_CHANGED` counts that mean different
        // things in the two runs.
        val apps = VerificationReport(total = 1, okCount = 1, reconciledRecords = 2)
        val device = VerificationReport(
            total = 1,
            okCount = 1,
            reconciledRecords = 1,
            scope = VerifyScope.DEVICE
        )
        assertEquals(3, apps.mergedWith(device).reconciledRecords)
    }

    @Test
    fun `the engine's repaired-record count is read off both summaries`() {
        val apps = VerificationParser.parse(
            "VERIFY_DONE|TOTAL:2|OK:2|CORRUPT:0|BYTES:0|HASH_CHANGED:0|HASH_UNRECORDED:0|" +
                "HASH_BACKFILLED:0|HASH_RECONCILED:1|SKIPPED:2|READ:0|MARKED:1|INDEX:updated",
            VerificationReport()
        )
        assertEquals(1, apps.reconciledRecords)
        val both = VerificationParser.parse(
            "VERIFY_DEVICE_DONE|TOTAL:5|OK:5|CORRUPT:0|BYTES:10|HASH_CHANGED:0|HASH_MISMATCH:|" +
                "HASH_UNRECORDED:0|HASH_BACKFILLED:0|HASH_RECONCILED:2|SKIPPED:5|READ:0|INDEX:updated",
            apps
        )
        assertEquals("the two halves add up", 3, both.reconciledRecords)
    }
}
