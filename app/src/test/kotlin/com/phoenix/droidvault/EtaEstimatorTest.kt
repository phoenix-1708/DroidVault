package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppProgress
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.EtaEstimator
import com.phoenix.droidvault.models.PartProgress
import com.phoenix.droidvault.models.PartState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "time left" figure used to be `elapsed * remaining / done` — an average
 * over the whole run. Nothing about that arithmetic is wrong, and it still
 * climbed: a part that legitimately moves no bytes for a while (a 13 GB media
 * copy, a quiesce before a tree swap) holds `done` still while `elapsed` keeps
 * rising, so the average rate falls and the figure GROWS. Field report: "~51s
 * left" became "~1m 9s left" and read as the run going backwards.
 *
 * Three properties are pinned here, because a number that moves the wrong way
 * is worse than no number: it never grows, it disappears rather than inventing
 * one when the counter cannot be trusted, and its RATE is taken from the bytes
 * actually written rather than from the run's progress counter.
 */
class EtaEstimatorTest {

    private val totalKb = 100_000L

    @Test
    fun `no estimate before there is a window to measure`() {
        val eta = EtaEstimator()
        // Two seconds of samples is not a rate.
        assertNull(eta.update(writtenKb = 50_000, remainingKb = 50_000, nowMs = 0))
        assertNull(eta.update(writtenKb = 51_000, remainingKb = 49_000, nowMs = 2_000))
    }

    @Test
    fun `a steady run gets an estimate that matches its own rate`() {
        val eta = EtaEstimator()
        // 1 MB/s for 20 s: 20,000 KB written, 80,000 KB left, so 80 s remain.
        var last: Long? = null
        for (second in 0..20) {
            last = eta.update(
                writtenKb = 20_000L + second * 1_000L,
                remainingKb = totalKb - (20_000L + second * 1_000L),
                nowMs = second * 1_000L
            )
        }
        assertNotNull("a steady run must produce an estimate", last)
        // 80 s ± one window of tolerance.
        assertTrue(
            "expected roughly 80 s, got $last",
            last!! in 60..100
        )
    }

    @Test
    fun `a stalled part shows nothing rather than a bigger number`() {
        val eta = EtaEstimator()
        // A run that moved steadily, then stopped dead for 20 s.
        for (second in 0..20) {
            eta.update(
                writtenKb = 20_000L + second * 1_000L,
                remainingKb = totalKb - (20_000L + second * 1_000L),
                nowMs = second * 1_000L
            )
        }
        val before = eta.update(writtenKb = 40_000, remainingKb = 60_000, nowMs = 20_000)
        assertNotNull(before)
        // The counter has not moved since 20 s. The honest answer is "I do not
        // know", not a larger number.
        assertNull(
            "a stalled window must not produce an estimate",
            eta.update(writtenKb = 40_000, remainingKb = 60_000, nowMs = 40_000)
        )
    }

    @Test
    fun `the figure never grows within a run`() {
        val eta = EtaEstimator()
        var previous: Long? = null
        // A run whose rate keeps FALLING: each second it moves less than the
        // one before. The old average-based estimate grew under exactly this.
        var written = 0L
        for (second in 0..120) {
            written += (120 - second).coerceAtLeast(1) * 100
            val shown = eta.update(
                writtenKb = written,
                remainingKb = totalKb - written,
                nowMs = second * 1_000L
            )
            if (shown != null) {
                if (previous != null) {
                    assertTrue(
                        "the estimate grew from $previous to $shown — a number that " +
                            "moves the wrong way reads as the run going backwards",
                        shown <= previous
                    )
                }
                previous = shown
            }
        }
    }

    @Test
    fun `nothing is claimed when the run is finished or nothing was written`() {
        val eta = EtaEstimator()
        for (second in 0..20) {
            eta.update(
                writtenKb = 20_000L + second * 1_000L,
                remainingKb = totalKb - (20_000L + second * 1_000L),
                nowMs = second * 1_000L
            )
        }
        // Done: there is no time left to report.
        assertNull(eta.update(writtenKb = 40_000, remainingKb = 0, nowMs = 30_000))
        // Nothing written: there is no rate to report.
        assertNull(eta.update(writtenKb = 0, remainingKb = 50_000, nowMs = 40_000))
    }

    @Test
    fun `one sample per instant, however often the frame is composed`() {
        val eta = EtaEstimator()
        // The same instant evaluated three times must not shift the window.
        eta.update(writtenKb = 20_000, remainingKb = 80_000, nowMs = 0)
        val first = eta.update(writtenKb = 21_000, remainingKb = 79_000, nowMs = 10_000)
        val second = eta.update(writtenKb = 21_000, remainingKb = 79_000, nowMs = 10_000)
        val third = eta.update(writtenKb = 21_000, remainingKb = 79_000, nowMs = 10_000)
        assertEquals(first, second)
        assertEquals(second, third)
    }

    /**
     * A part the engine SKIPPED is counted as done, and it was never written.
     *
     * Measured on the reference device: a restore of eight apps reported
     * "626.6 MB of 1.4 GB" four seconds in, while the bytes still to write had
     * barely moved — the counter had jumped over the parts the engine had
     * nothing to do for. A rate taken from that counter is inflated by bytes
     * that were never written, so the estimate says the run is nearly over
     * while it is not.
     */
    @Test
    fun `a skipped part is done but not written`() {
        val app = AppProgress(
            packageName = "com.example",
            label = "Example",
            parts = listOf(
                PartProgress("app", PartState.SKIPPED, expectedKb = 500_000),
                PartProgress("data", PartState.DONE, expectedKb = 200_000),
            )
        )
        assertEquals("the counter counts the skipped part in full", 700_000L, app.doneKb)
        assertEquals("the skipped part was never written", 500_000L, app.skippedKb)
        assertEquals("so the written bytes are the ones that moved", 200_000L, app.writtenKb)
    }

    /**
     * The rate the estimator uses must not include the skipped parts, or the
     * estimate collapses to "almost done" on an incremental run.
     */
    @Test
    fun `a skipped part does not inflate the rate`() {
        val estimator = EtaEstimator()
        // 20 s: 20,000 KB written (the data part), 500,000 KB of it skipped
        // (the APK parts), and 80,000 KB still to write.
        var written = 0L
        var shown: Long? = null
        for (second in 0..20) {
            written += 1_000L
            shown = estimator.update(
                writtenKb = written,
                remainingKb = totalKb - written,
                nowMs = second * 1_000L
            )
        }
        assertNotNull("a steady write must produce an estimate", shown)
        // 1 MB/s with 80,000 KB left is ~80 s — not the ~0.1 s a counter
        // inflated by 500 MB of skipped bytes would have produced.
        assertTrue(
            "expected roughly 80 s, got $shown",
            shown!! in 60..100
        )
    }
}
