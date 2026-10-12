package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppProgress
import com.phoenix.droidvault.models.PartProgress
import com.phoenix.droidvault.models.PartState
import com.phoenix.droidvault.models.TransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "GB done" used to be `appSize * mean(parts' percentages)` — a PART COUNT dressed
 * up as a byte count. An app that is 13.6 GB in one part and a few MB in four
 * others reported itself ~80% complete the moment the small parts landed, which
 * is how the screen came to claim 24.6 GB done while a 13.6 GB media copy had
 * written almost nothing.
 *
 * These pin the byte weighting, and the case the field screenshots actually hit.
 */
class ProgressWeightingTest {

    /** WhatsApp from the field report: one huge media part, four tiny ones. */
    private fun skewedApp() = AppProgress(
        packageName = "com.whatsapp",
        label = "WhatsApp",
        sizeKb = 13_600_000,
        parts = listOf(
            PartProgress("app", PartState.DONE, progress = 100, expectedKb = 20_000),
            PartProgress("data", PartState.DONE, progress = 100, expectedKb = 15_000),
            PartProgress("perm", PartState.DONE, progress = 100, expectedKb = 1),
            PartProgress("ssaid", PartState.PENDING, expectedKb = 0),
            PartProgress("media", PartState.RUNNING, progress = 0, expectedKb = 13_500_000)
        )
    )

    @Test
    fun `a finished small part does not count a running huge part as done`() {
        val app = skewedApp()
        // 35 KB of small parts finished. The media part has written nothing.
        assertEquals(35_001L, app.doneKb)
    }

    @Test
    fun `the old part-count average claimed thirteen gigabytes when 35 KB was written`() {
        val app = skewedApp()
        val honest = app.doneKb.toDouble() / app.expectedKb.toDouble()
        // What the old arithmetic produced: mean of part percentages x app size.
        val meanOfPercentages = app.byteProgress / 100.0
        val dishonest = app.sizeKb * meanOfPercentages
        assertTrue(
            "the part-count figure must be wildly larger than the measured one; " +
                "measured=${app.doneKb} KB, claimed=$dishonest KB",
            dishonest > app.doneKb * 100
        )
        assertTrue("a real fraction of the way through", honest > 0.0 && honest < 0.01)
    }

    @Test
    fun `a running part contributes only its polled fraction`() {
        val app = AppProgress(
            packageName = "x", label = "x", sizeKb = 1000,
            parts = listOf(
                PartProgress("a", PartState.DONE, progress = 100, expectedKb = 400),
                PartProgress("b", PartState.RUNNING, progress = 25, expectedKb = 600)
            )
        )
        assertEquals(400L + 150L, app.doneKb)
        assertEquals(1000L, app.expectedKb)
    }

    @Test
    fun `a failed part still counts its bytes as spent`() {
        // The bytes were written and then lost. Reporting zero would make a
        // failed run look like it saved time.
        val app = AppProgress(
            packageName = "x", label = "x",
            parts = listOf(PartProgress("a", PartState.FAILED, progress = 40, expectedKb = 500))
        )
        assertEquals(500L, app.doneKb)
    }

    @Test
    fun `an app with no part sizes falls back to its whole-app size`() {
        // Never report zero bytes for an app the user can plainly see has a size.
        val app = AppProgress(
            packageName = "x", label = "x", sizeKb = 777,
            parts = listOf(PartProgress("a", PartState.DONE, progress = 100))
        )
        assertEquals(777L, app.expectedKb)
    }

    @Test
    fun `a pending part contributes nothing`() {
        val app = AppProgress(
            packageName = "x", label = "x",
            parts = listOf(
                PartProgress("a", PartState.DONE, progress = 100, expectedKb = 100),
                PartProgress("b", PartState.PENDING, expectedKb = 900)
            )
        )
        assertEquals(100L, app.doneKb)
        assertEquals(1000L, app.expectedKb)
    }

    // --- the import bar and its label must read the same counter -------------

    @Test
    fun `the import label reports the bytes the bar has actually counted`() {
        // The bar climbed to 39% beside "0 B of 3.6 GB": the percentage came from
        // the weighted counter and the label was hard-coded to zero.
        val sizes = mapOf("big" to 3_000_000_000L, "small" to 600_000_000L)
        val w = TransferProgress.Weighted(sizes)
        assertEquals(0L, w.doneBytes)
        w.complete("small")
        assertEquals(600_000_000L, w.doneBytes)
        assertEquals(16, w.lastPercent)
        w.complete("big")
        assertEquals(sizes.values.sum(), w.doneBytes)
        // 99, not 100: 100 is reserved for finish(), so a bar can never claim to
        // be complete while the command that owns it is still running.
        assertEquals(99, w.lastPercent)
        assertEquals(100, w.finish())
    }

    @Test
    fun `completing an unknown key never moves the counters`() {
        val w = TransferProgress.Weighted(mapOf("a" to 100L))
        val pct = w.complete("not-in-the-plan")
        assertEquals(0L, w.doneBytes)
        assertEquals(0, pct)
    }

    @Test
    fun `the same key counted twice does not inflate progress`() {
        val w = TransferProgress.Weighted(mapOf("a" to 100L))
        w.complete("a")
        val pct = w.complete("a")
        // The second call must add nothing: before the fix it added the size
        // again, which could reach 100% with only half the bytes copied.
        assertEquals(100L, w.doneBytes)
        assertEquals(99, pct)
    }
}

/**
 * The weighting is only as good as the sizes that reach it. `expectedKb` is
 * attached at run start, and every subsequent part event rebuilds the part — so
 * a reducer that does not carry the size forward silently reverts every app to
 * "0 bytes", which is exactly what a device run showed: a 109-app backup
 * reading "3m 49s" with no byte figure at all.
 */
class ProgressWeightingSurvivesEventsTest {

    private fun running() = com.phoenix.droidvault.models.ProgressState.Running(
        op = com.phoenix.droidvault.models.LiveOp.BACKUP, title = "Backing up", phase = com.phoenix.droidvault.models.RunPhase.PROCESSING,
        subtitle = "", overallPct = 10, currentIndex = 0,
        apps = listOf(
            AppProgress(
                packageName = "com.whatsapp", label = "WhatsApp", sizeKb = 13_600,
                parts = listOf(
                    PartProgress("app", expectedKb = 20),
                    PartProgress("media", expectedKb = 13_000)
                )
            )
        )
    )

    @Test
    fun `a part going RUNNING keeps its expected size`() {
        val before = (running().apps.first().parts.first { it.key == "media" }).expectedKb
        val after = com.phoenix.droidvault.models.ProgressReducer.reduce(
            running(),
            com.phoenix.droidvault.models.TrackerEvent.Part(
                "com.whatsapp", "media", PartState.RUNNING, progress = 40
            )
        )
        val part = (after as com.phoenix.droidvault.models.ProgressState.Running).apps
            .first().parts.first { it.key == "media" }
        assertEquals(before, part.expectedKb)
        assertEquals(40, part.progress)
    }

    @Test
    fun `a part settling DONE still counts its bytes`() {
        val after = com.phoenix.droidvault.models.ProgressReducer.reduce(
            running(),
            com.phoenix.droidvault.models.TrackerEvent.Part(
                "com.whatsapp", "media", PartState.DONE, progress = 100
            )
        )
        val app = (after as com.phoenix.droidvault.models.ProgressState.Running).apps.first()
        assertEquals("a finished app must contribute its real size", 13_000L, app.doneKb)
    }

    @Test
    fun `the whole run reports bytes from the apps it has finished`() {
        val after = com.phoenix.droidvault.models.ProgressReducer.reduce(
            running(),
            com.phoenix.droidvault.models.TrackerEvent.Part(
                "com.whatsapp", "media", PartState.DONE, progress = 100
            )
        )
        val apps = (after as com.phoenix.droidvault.models.ProgressState.Running).apps
        assertTrue(
            "the run must be able to report a non-zero done figure, or the UI has " +
                "nothing honest to show",
            apps.sumOf { it.doneKb } > 0
        )
    }
}
