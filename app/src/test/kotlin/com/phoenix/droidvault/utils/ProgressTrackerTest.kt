package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppSeed
import com.phoenix.droidvault.models.LiveOp
import com.phoenix.droidvault.models.Parts
import com.phoenix.droidvault.models.PartState
import com.phoenix.droidvault.models.ProgressState
import com.phoenix.droidvault.models.ProgressReducer
import com.phoenix.droidvault.models.EngineEvent
import com.phoenix.droidvault.models.TrackerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTrackerTest {

    private fun seeds() = listOf(
        AppSeed("com.a", "Alpha", isSystem = false, iconPath = null, parts = listOf(Parts.APP, Parts.DATA, Parts.PERM)),
        AppSeed("com.b", "Beta", isSystem = true, iconPath = null, parts = listOf(Parts.APP, Parts.MEDIA)),
    )

    private fun startState() = ProgressReducer.reduce(
        ProgressState.Idle,
        TrackerEvent.Start(LiveOp.BACKUP, seeds(), "Backing up")
    )

    @Test
    fun `start creates running state with seeded apps`() {
        val s = startState()
        assertTrue(s is ProgressState.Running)
        s as ProgressState.Running
        assertEquals(LiveOp.BACKUP, s.op)
        assertEquals(2, s.apps.size)
        assertEquals("Alpha", s.apps[0].label)
        assertEquals(3, s.apps[0].parts.size)
        assertEquals(-1, s.currentIndex)
        assertEquals(0, s.processedCount)
    }

    @Test
    fun `app start advances index and subtitle and marks first part running`() {
        var s = startState()
        s = ProgressReducer.reduce(
            s,
            TrackerEvent.AppStarted("com.a", "Alpha", cur = 1, total = 2, pct = 10, size = "102400")
        ) as ProgressState.Running
        assertEquals(0, s.currentIndex)
        assertEquals(PartState.RUNNING, s.apps[0].parts.first { it.key == Parts.APP }.state)
        assertTrue(s.subtitle.contains("1 of 2"))
        assertTrue(s.subtitle.contains("100.0 MB"))
    }

    @Test
    fun `part events update states and install failure marks app failed`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 10, "")
        )
        s = ProgressReducer.reduce(s, TrackerEvent.Part("com.a", Parts.APP, PartState.DONE))
        s = ProgressReducer.reduce(s, TrackerEvent.Part("com.a", Parts.DATA, PartState.RUNNING))
        s = ProgressReducer.reduce(s, TrackerEvent.Part("com.a", Parts.PERM, PartState.SKIPPED, "consent off"))
        val r = s as ProgressState.Running
        val parts = r.apps[0].parts.associateBy { it.key }
        assertEquals(PartState.DONE, parts[Parts.APP]!!.state)
        assertEquals(PartState.RUNNING, parts[Parts.DATA]!!.state)
        assertEquals(PartState.SKIPPED, parts[Parts.PERM]!!.state)
        assertEquals("consent off", parts[Parts.PERM]!!.reason)

        s = ProgressReducer.reduce(s, TrackerEvent.InstallError("com.a", "INSTALL_FAILED"))
        assertEquals(
            PartState.FAILED,
            (s as ProgressState.Running).apps[0].parts.first { it.key == Parts.INSTALL }.state
        )
        assertTrue(s.apps[0].hasFailure)

        s = ProgressReducer.reduce(s, TrackerEvent.InstallDone("com.a"))
        assertEquals(
            PartState.DONE,
            (s as ProgressState.Running).apps[0].parts.first { it.key == Parts.INSTALL }.state
        )
        assertFalse(s.apps[0].hasFailure)
    }

    @Test
    fun `app done settles dangling parts and counts processed`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 30, "")
        )
        s = ProgressReducer.reduce(s, TrackerEvent.Part("com.a", Parts.APP, PartState.DONE))
        s = ProgressReducer.reduce(s, TrackerEvent.AppDone("com.a"))
        s as ProgressState.Running
        assertTrue(s.apps[0].done)
        assertTrue(s.apps[0].parts.all { it.state == PartState.DONE })
        assertEquals(1, s.processedCount)
    }

    @Test
    fun `completion freezes all apps and reports counts`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 50, "")
        )
        s = ProgressReducer.reduce(s, TrackerEvent.AppDone("com.a"))
        s = ProgressReducer.reduce(s, TrackerEvent.Completed("Backup Complete!", "Total Size: 1.0 GB | Time: 5m"))
        s as ProgressState.Done
        assertEquals(2, s.apps.size)
        assertEquals(2, s.okCount)
        assertEquals(0, s.failedCount)
        assertTrue(s.apps.all { it.done })
    }

    @Test
    fun `failure preserves in-flight apps for the recap list without inventing success`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 20, "")
        )
        s = ProgressReducer.reduce(s, TrackerEvent.Failed("Operation Failed", "disk full"))
        s as ProgressState.Failed
        assertEquals("disk full", s.message)
        // Both apps are still listed — that part is unchanged, and it is why the
        // recap can name what did not finish.
        assertEquals(2, s.apps.size)
        // CHANGED CONTRACT: this used to assert `all { it.done }`. A run that
        // errored out did not back up the apps still sitting in the queue, and
        // the reducer no longer freezes their PENDING parts into DONE. Marking
        // them done showed a green tick per un-backed-up app on the one screen a
        // user consults to decide whether they still need to run the backup.
        assertTrue("a failed run must not report queued apps as done", s.apps.none { it.done })
    }

    @Test
    fun `cancelled completion does not mark the remaining queue as done`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 20, "")
        )
        s = ProgressReducer.reduce(
            s,
            TrackerEvent.Completed("Operation Cancelled", "Stopped.", cancelled = true)
        )
        s as ProgressState.Done
        // The queue was never drained, so nothing may be reported as finished.
        assertEquals(2, s.apps.size)
        assertTrue(s.apps.none { it.done })
        assertEquals(0, s.okCount)
    }

    @Test
    fun `unknown package events are handled defensively`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.AppStarted("com.unknown", "Mystery", 1, 3, 5, "")
        )
        s as ProgressState.Running
        assertEquals(3, s.apps.size)
        assertEquals("Mystery", s.apps.last().label)
        assertEquals(2, s.currentIndex)

        // Part for a package never seen: ignored, no crash.
        val after = ProgressReducer.reduce(s, TrackerEvent.Part("com.nope", Parts.APP, PartState.DONE))
        assertEquals(3, (after as ProgressState.Running).apps.size)
    }

    @Test
    fun `cancel requested flags running state`() {
        val s = ProgressReducer.reduce(startState(), TrackerEvent.CancelRequested)
        assertTrue((s as ProgressState.Running).cancelRequested)
    }

    @Test
    fun `app done without part events settles the app - mismatch regression`() {
        // The backend emits ACTION:BACKUP_DONE per app; before the fix the app
        // never settled (spinner + 0/10 stuck) because parts stayed RUNNING.
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 10, "")
        )
        s = ProgressReducer.reduce(s, TrackerEvent.AppDone("com.a"))
        s as ProgressState.Running
        assertTrue("app must settle on AppDone alone", s.apps[0].done)
        assertEquals(1, s.processedCount)
        assertEquals(PartState.DONE, s.apps[0].summaryState)
    }

    @Test
    fun `start seeds sort order is preserved for display and sizes carried`() {
        val ordered = listOf(
            AppSeed("com.big", "Big", false, null, listOf(Parts.APP, Parts.DATA), sizeKb = 900_000),
            AppSeed("com.tiny", "Tiny", false, null, listOf(Parts.APP), sizeKb = 1_000),
        )
        val s = ProgressReducer.reduce(ProgressState.Idle, TrackerEvent.Start(LiveOp.BACKUP, ordered, "Backing up"))
        s as ProgressState.Running
        assertEquals("com.big", s.apps.first().packageName)
        assertEquals(900_000L, s.apps.first().sizeKb)
    }

    @Test
    fun `running part byte progress feeds byteProgress and never exceeds 99 before done`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 10, "")
        )
        s = ProgressReducer.reduce(s, TrackerEvent.Part("com.a", Parts.APP, PartState.RUNNING, progress = 55))
        s = ProgressReducer.reduce(s, TrackerEvent.Part("com.a", Parts.DATA, PartState.PENDING))
        s = ProgressReducer.reduce(s, TrackerEvent.Part("com.a", Parts.PERM, PartState.SKIPPED))
        s as ProgressState.Running
        // APP running@55, DATA pending@0, PERM skipped@100 -> (55+0+100)/3 = 51
        assertEquals(51, s.apps[0].byteProgress)
        val running = s.apps[0].parts.first { it.key == Parts.APP }
        assertTrue(running.progress < 100)
    }

    @Test
    fun `interactive flag survives to terminal states`() {
        val seedsInteractive = seeds()
        val s = ProgressReducer.reduce(
            ProgressState.Idle,
            TrackerEvent.Start(LiveOp.BACKUP, seedsInteractive, "Backing up", interactive = false)
        )
        val done = ProgressReducer.reduce(s, TrackerEvent.Completed("Done", "ok"))
        assertFalse((done as ProgressState.Done).interactive)
    }

    // ----- EngineEvent translation -----

    @Test
    fun `backup progress with totals becomes AppStarted`() {
        ProgressTracker.startRun("t1", LiveOp.BACKUP, seeds(), "Backing up")
        try {
            ProgressTracker.onEngineEvent(
                EngineEvent.BackupProgress(
                    pkg = "com.a", label = "Alpha", current = 1, total = 2,
                    percent = 40, size = "204800", jobs = 0
                ),
                LiveOp.BACKUP
            )
            val s = ProgressTracker.state.value as ProgressState.Running
            assertEquals("com.a", s.currentApp?.packageName)
            assertEquals(40, s.overallPct)
        } finally {
            ProgressTracker.endRun("t1")
        }
    }

    @Test
    fun `part event state codes map correctly`() {
        ProgressTracker.startRun("t2", LiveOp.RESTORE, seeds(), "Restoring")
        try {
            ProgressTracker.onEngineEvent(
                EngineEvent.PartEvent("com.b", Parts.MEDIA, 2), LiveOp.RESTORE
            )
            val s = ProgressTracker.state.value as ProgressState.Running
            assertEquals(PartState.DONE, s.apps.first { it.packageName == "com.b" }.parts.first { it.key == Parts.MEDIA }.state)
        } finally {
            ProgressTracker.endRun("t2")
        }
    }

    @Test
    fun `backend AppDone event translates to tracker AppDone`() {
        ProgressTracker.startRun("t4", LiveOp.BACKUP, seeds(), "Backing up")
        try {
            ProgressTracker.onEngineEvent(
                EngineEvent.BackupProgress("com.a", "Alpha", 1, 2, 10, "", 0),
                LiveOp.BACKUP
            )
            ProgressTracker.onEngineEvent(EngineEvent.AppDoneEvent("com.a"), LiveOp.BACKUP)
            val s = ProgressTracker.state.value as ProgressState.Running
            assertTrue(s.apps.first { it.packageName == "com.a" }.done)
        } finally {
            ProgressTracker.endRun("t4")
        }
    }

    @Test
    fun `installing step becomes running install part`() {
        ProgressTracker.startRun("t3", LiveOp.RESTORE, seeds(), "Restoring")
        try {
            ProgressTracker.onEngineEvent(
                EngineEvent.InfoStep(msg = "INSTALLING", pkg = "com.a"), LiveOp.RESTORE
            )
            val s = ProgressTracker.state.value as ProgressState.Running
            assertEquals(
                PartState.RUNNING,
                s.apps.first { it.packageName == "com.a" }.parts.first { it.key == Parts.INSTALL }.state
            )
        } finally {
            ProgressTracker.endRun("t3")
        }
    }

    @Test
    fun `beginRun rejects second owner while running and allows after endRun`() {
        assertTrue(ProgressTracker.startRun("ownerA", LiveOp.BACKUP, seeds(), "A"))
        assertFalse(ProgressTracker.startRun("ownerB", LiveOp.BACKUP, seeds(), "B"))
        // A leave mid-run only releases ownership; the screen state stays until
        // completion. Complete, then a fresh run can take over and dismiss works.
        ProgressTracker.complete("Done", "all good")
        ProgressTracker.endRun("ownerA")
        assertTrue(ProgressTracker.startRun("ownerB", LiveOp.BACKUP, seeds(), "B"))
        assertFalse(ProgressTracker.startRun("ownerC", LiveOp.BACKUP, seeds(), "C"))
        ProgressTracker.complete("Done again", "ok")
        ProgressTracker.endRun("ownerB")
        ProgressTracker.dismiss()
        assertTrue(ProgressTracker.state.value is ProgressState.Idle)
    }

    // ----- Per-app durations (run history) -----

    @Test
    fun `durations recorded per app from AppStarted to AppDone`() {
        val epoch = ProgressTracker.currentEpoch()
        ProgressTracker.startRun("dur1", LiveOp.BACKUP, seeds(), "Backing up")
        try {
            val runEpoch = ProgressTracker.currentEpoch()
            assertTrue("beginRun must advance the epoch", runEpoch != epoch)
            ProgressTracker.onEvent(TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 10, ""))
            ProgressTracker.onEvent(TrackerEvent.AppDone("com.a"))
            ProgressTracker.onEvent(TrackerEvent.AppStarted("com.b", "Beta", 2, 2, 50, ""))
            // b not done yet: only a is settled.
            val mid = ProgressTracker.durationsSnapshot()
            assertTrue(mid.containsKey("com.a"))
            assertTrue("unsettled app must not appear yet", !mid.containsKey("com.b"))
            ProgressTracker.onEvent(TrackerEvent.AppDone("com.b"))
            val all = ProgressTracker.durationsSnapshot()
            assertTrue(all.containsKey("com.a"))
            assertTrue(all.containsKey("com.b"))
            assertTrue(all["com.a"]!! >= 0)
            assertTrue(ProgressTracker.sameEpoch(runEpoch))
        } finally {
            ProgressTracker.endRun("dur1")
        }
    }

    @Test
    fun `endRun settles dangling started apps into durations`() {
        ProgressTracker.startRun("dur2", LiveOp.BACKUP, seeds(), "Backing up")
        try {
            ProgressTracker.onEvent(TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 10, ""))
            // No AppDone — cancel/crash path. endRun must still keep its elapsed.
        } finally {
            ProgressTracker.endRun("dur2")
        }
        val snap = ProgressTracker.durationsSnapshot()
        assertTrue("cancelled app keeps its elapsed time", snap.containsKey("com.a"))
    }

    @Test
    fun `new run clears previous durations and epoch guard rejects stale stamp`() {
        ProgressTracker.startRun("dur3", LiveOp.BACKUP, seeds(), "Backing up")
        val oldEpoch = ProgressTracker.currentEpoch()
        ProgressTracker.onEvent(TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 10, ""))
        ProgressTracker.onEvent(TrackerEvent.AppDone("com.a"))
        ProgressTracker.endRun("dur3")
        assertTrue(ProgressTracker.sameEpoch(oldEpoch))

        ProgressTracker.startRun("dur4", LiveOp.BACKUP, seeds(), "Backing up")
        try {
            assertFalse("epoch must advance per run", ProgressTracker.sameEpoch(oldEpoch))
            val snap = ProgressTracker.durationsSnapshot()
            assertTrue("durations must not leak across runs", !snap.containsKey("com.a"))
        } finally {
            ProgressTracker.endRun("dur4")
        }
    }

    // ----- device-data phase -----

    @Test
    fun `device-only phase starts fresh run and tracks per-item progress`() {
        val ok = ProgressTracker.startDevicePhase(
            "devT1", LiveOp.BACKUP, listOf("Messages", "Calls"), "Backing up Device Data",
            interactive = true, attach = false
        )
        assertTrue(ok)
        try {
            val s = ProgressTracker.state.value as ProgressState.Running
            assertEquals(2, s.apps.size)
            assertEquals("Messages", s.apps[0].label)
            assertEquals(listOf("Messages"), s.apps[0].parts.map { it.key })

            ProgressTracker.deviceItemStep("Messages", 40, "Compressing Messages...")
            val s2 = ProgressTracker.state.value as ProgressState.Running
            assertEquals(PartState.RUNNING, s2.apps[0].parts[0].state)
            assertEquals(40, s2.apps[0].parts[0].progress)

            ProgressTracker.deviceItemDone("Messages")
            ProgressTracker.deviceItemStep("Calls", 70, "Compressing Calls...")
            val s3 = ProgressTracker.state.value as ProgressState.Running
            assertEquals(PartState.DONE, s3.apps[0].parts[0].state)
            assertTrue(s3.apps[0].done)
            assertEquals("Calls", s3.apps[s3.currentIndex].label)

            ProgressTracker.deviceItemDone("Calls")
            ProgressTracker.complete("Completed", "Device data: 2 items ok")
            val done = ProgressTracker.state.value as ProgressState.Done
            assertEquals(2, done.apps.size)
            assertTrue(done.apps.all { it.done })
        } finally {
            ProgressTracker.endRun("devT1")
            ProgressTracker.dismiss()
        }
    }

    @Test
    fun `device phase attaches to finished app run without wiping it`() {
        ProgressTracker.startRun("devT2", LiveOp.BACKUP, seeds(), "Backing up")
        try {
            ProgressTracker.onEvent(TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 10, ""))
            ProgressTracker.onEvent(TrackerEvent.AppDone("com.a"))
            ProgressTracker.onEvent(TrackerEvent.Completed("Completed", "ok"))

            val ok = ProgressTracker.startDevicePhase(
                "devT2b", LiveOp.BACKUP, listOf("Wallpaper"), "Backing up Device Data",
                interactive = true, attach = true
            )
            assertTrue(ok)
            val s = ProgressTracker.state.value as ProgressState.Running
            // App phase kept, settled; device item appended.
            assertEquals(3, s.apps.size)
            assertTrue(s.apps[0].done)
            assertTrue(s.apps[1].done)
            assertEquals("com.a", s.apps[0].packageName)
            assertEquals("Wallpaper", s.apps[2].label)
            assertEquals("device:Wallpaper", s.apps[2].packageName)
        } finally {
            ProgressTracker.endRun("devT2b")
            ProgressTracker.dismiss()
        }
    }    @Test
    fun `folders sub-label shows the specific folder on the Folders pseudo app`() {
        val ok = ProgressTracker.startDevicePhase(
            "devT5", LiveOp.BACKUP, listOf("Folders"), "Backing up Device Data",
            interactive = true, attach = false
        )
        assertTrue(ok)
        try {
            // DeviceManager reports the SPECIFIC folder in flight via
            // subLabel; the canonical "device:Folders" pseudo app keeps the
            // progress while the hero shows the real folder path.
            ProgressTracker.deviceItemStep(
                "Folders", 37, "Packing …/media/com.whatsapp…",
                subLabel = "Android/media/com.whatsapp"
            )
            val s = ProgressTracker.state.value as ProgressState.Running
            assertEquals(1, s.apps.size)
            assertEquals("device:Folders", s.apps[0].packageName)
            // Hero label carries the folder in flight...
            assertEquals("Android/media/com.whatsapp", s.apps[0].label)
            // ...while the ring part stays keyed by the item name.
            assertEquals("Folders", s.apps[0].parts[0].key)
            assertEquals(PartState.RUNNING, s.apps[0].parts[0].state)
            assertEquals(37, s.apps[0].parts[0].progress)

            // A different folder keeps updating the SAME pseudo app.
            ProgressTracker.deviceItemStep(
                "Folders", 64, "Packing …/data/org.mozilla.firefox…",
                subLabel = "Android/data/org.mozilla.firefox"
            )
            val s2 = ProgressTracker.state.value as ProgressState.Running
            assertEquals(1, s2.apps.size)
            assertEquals("Android/data/org.mozilla.firefox", s2.apps[0].label)
            assertEquals(64, s2.apps[0].parts[0].progress)

            // Terminal event puts the item name back (finished list reads "Folders").
            ProgressTracker.deviceItemDone("Folders")
            val s3 = ProgressTracker.state.value as ProgressState.Running
            assertEquals("Folders", s3.apps[0].label)

            ProgressTracker.complete("Completed", "ok")
            val done = ProgressTracker.state.value as ProgressState.Done
            assertEquals(1, done.apps.size)
            assertEquals(PartState.DONE, done.apps[0].summaryState)
        } finally {
            ProgressTracker.endRun("devT5")
            ProgressTracker.dismiss()
        }
    }

    @Test
    fun `device item done reaches terminal state`() {
        val ok = ProgressTracker.startDevicePhase(
            "devT3", LiveOp.RESTORE, listOf("Messages", "Wi-Fi"), "Restoring Device Data",
            interactive = false, attach = false
        )
        assertTrue(ok)
        try {
            ProgressTracker.deviceItemDone("Messages")
            ProgressTracker.deviceItemDone("Wi-Fi")
            ProgressTracker.complete("Completed", "ok")
            val done = ProgressTracker.state.value as ProgressState.Done
            assertEquals(PartState.DONE, done.apps[0].summaryState)
            assertEquals(PartState.DONE, done.apps[1].summaryState)
        } finally {
            ProgressTracker.endRun("devT3")
            ProgressTracker.dismiss()
        }
    }

    @Test
    fun `device phase refuses while a foreign run is in flight`() {
        ProgressTracker.startRun("devT4a", LiveOp.BACKUP, seeds(), "Backing up")
        try {
            ProgressTracker.onEvent(TrackerEvent.AppStarted("com.a", "Alpha", 1, 2, 10, ""))
            val refused = ProgressTracker.startDevicePhase(
                "devT4b", LiveOp.BACKUP, listOf("Calls"), "Backing up Device Data",
                interactive = true, attach = false
            )
            assertFalse("must not steal the bus from a live run", refused)
        } finally {
            ProgressTracker.endRun("devT4a")
            ProgressTracker.dismiss()
        }
    }

    // ------------------------------------------------------------------
    // Field bug: "all app rows spinner, counter stuck at 0/63" during the
    // restore pre-install pass. Untouched queue rows must read PENDING and
    // the counter must expose install progress via preparedCount.
    // ------------------------------------------------------------------

    @Test
    fun `untouched queue rows read PENDING not RUNNING`() {
        val s = startState() as ProgressState.Running
        assertEquals(PartState.PENDING, s.apps[0].summaryState)
        assertEquals(PartState.PENDING, s.apps[1].summaryState)
        // And the old fallback must not sneak back through the empty case.
        assertEquals(PartState.PENDING, s.apps[0].copy(parts = emptyList()).summaryState)
    }

    @Test
    fun `only the installing app spins during the pre-install pass`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.Part("com.b", Parts.INSTALL, PartState.RUNNING)
        ) as ProgressState.Running
        // Hero follows the app being installed even before RESTORE_START.
        assertEquals(1, s.currentIndex)
        assertEquals(PartState.RUNNING, s.apps[1].summaryState)
        assertEquals(PartState.PENDING, s.apps[0].summaryState)

        // Install finished, data phase has not reached it yet: queued, not active.
        s = ProgressReducer.reduce(s, TrackerEvent.InstallDone("com.b")) as ProgressState.Running
        assertEquals(PartState.PENDING, s.apps[1].summaryState)

        // Data starts: spinner comes back for exactly that row.
        s = ProgressReducer.reduce(
            s,
            TrackerEvent.Part("com.b", Parts.MEDIA, PartState.RUNNING)
        ) as ProgressState.Running
        assertEquals(PartState.RUNNING, s.apps[1].summaryState)
    }

    @Test
    fun `preparedCount tracks install terminals while processedCount stays 0`() {
        var s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.Part("com.a", Parts.INSTALL, PartState.RUNNING)
        ) as ProgressState.Running
        assertEquals(0, s.preparedCount)
        assertEquals(0, s.processedCount)

        s = ProgressReducer.reduce(s, TrackerEvent.InstallDone("com.a")) as ProgressState.Running
        assertEquals(1, s.preparedCount)
        assertEquals("data restore has not run yet", 0, s.processedCount)

        s = ProgressReducer.reduce(
            s,
            TrackerEvent.InstallError("com.b", "boom")
        ) as ProgressState.Running
        // A failed install is terminal for the pass too: it must not pin the
        // counter at N-1 forever.
        assertEquals(2, s.preparedCount)
    }

    @Test
    fun `skipped install counts as prepared and settles the row`() {
        val s = ProgressReducer.reduce(
            startState(),
            TrackerEvent.Part("com.a", Parts.INSTALL, PartState.SKIPPED, "already installed")
        ) as ProgressState.Running
        assertEquals(1, s.preparedCount)
        // Install SKIPPED, other parts pending -> queued, not active.
        assertEquals(PartState.PENDING, s.apps[0].summaryState)
    }
}
