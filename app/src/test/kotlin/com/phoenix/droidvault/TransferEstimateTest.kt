package com.phoenix.droidvault

import com.phoenix.droidvault.models.TransferProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The time estimate on the import progress strip.
 *
 * Invariant 31: *an estimate that moves the wrong way is worse than none.* The
 * old per-screen version showed a figure derived from an unstable counter, so
 * "~51s left" climbed to "~1m 9s" while the run may have been nearly finished.
 *
 * Every test below is a case where the honest answer is a NUMBER, paired with
 * one where the honest answer is SILENCE. The silence cases are the point: an
 * estimate is only allowed to exist once a rate can be measured, and every
 * other input must produce null rather than a plausible-looking guess.
 */
class TransferEstimateTest {

    private val window = TransferProgress.Estimate.RATE_WINDOW_MS

    /** Samples spanning `seconds` at a steady `percentPerSecond`. */
    private fun ramp(
        percentPerSecond: Double,
        seconds: Long,
        startPercent: Int = 0
    ): List<TransferProgress.Estimate.Sample> {
        val out = mutableListOf<TransferProgress.Estimate.Sample>()
        var t = 0L
        var p = startPercent.toDouble()
        while (t <= seconds * 1000L) {
            out += TransferProgress.Estimate.Sample(t, p.toInt().coerceAtMost(99))
            t += 1_000L
            p += percentPerSecond
        }
        return out
    }

    @Test
    fun `a steady rate gives a rate`() {
        // 1% per second: 100 seconds of work, so at 20% there are 80 s left and
        // the label rounds UP to whole minutes.
        val rate = TransferProgress.Estimate.windowedRate(ramp(1.0, 60, startPercent = 5))
        assertEquals(1.0, rate!!, 0.0001)
        assertEquals("about 2 min left", TransferProgress.Estimate.label(20, rate))
    }

    @Test
    fun `a single sample is not a rate`() {
        // The very first report of a run. Extrapolating from one point is how
        // "~51s left" appears on a 34 GB import.
        assertNull(
            TransferProgress.Estimate.windowedRate(
                listOf(TransferProgress.Estimate.Sample(0, 1))
            )
        )
    }

    @Test
    fun `samples inside the window are not yet a rate`() {
        // Three samples 1 s apart span 2 s, far short of the window. A rate over
        // 2 s of tar's own writeback burst is noise.
        val tight = listOf(
            TransferProgress.Estimate.Sample(0, 10),
            TransferProgress.Estimate.Sample(1_000, 11),
            TransferProgress.Estimate.Sample(2_000, 12)
        )
        assertNull(TransferProgress.Estimate.windowedRate(tight))
    }

    @Test
    fun `a stalled counter produces silence, never an infinite estimate`() {
        // The survey phase of a 34 GB import reports -1 percent for minutes.
        // A rate computed across that flat line is zero, and "0 min left" or
        // "∞ left" are both worse than saying nothing.
        val stalled = ramp(0.0, 60, startPercent = 0)
        assertNull(TransferProgress.Estimate.windowedRate(stalled))
    }

    @Test
    fun `going backwards is not a rate`() {
        // percent is monotonic by construction, but a rate must not be built
        // from a drop if one ever reaches here: a negative rate would render a
        // "time remaining" that grows as the run proceeds.
        val backwards = listOf(
            TransferProgress.Estimate.Sample(0, 40),
            TransferProgress.Estimate.Sample(window + 1_000, 12)
        )
        assertNull(TransferProgress.Estimate.windowedRate(backwards))
    }

    @Test
    fun `an indeterminate phase never gets a label`() {
        // -1 is "we cannot count this", and no amount of rate makes it countable.
        assertNull(TransferProgress.Estimate.label(-1, 1.0))
    }

    @Test
    fun `a finished run never gets a label`() {
        assertNull(TransferProgress.Estimate.label(100, 1.0))
    }

    @Test
    fun `no rate means no label`() {
        assertNull(TransferProgress.Estimate.label(50, 0.0))
    }

    @Test
    fun `the label rounds up, so it is never optimistic`() {
        // 61 seconds remaining must not read "1 min left" — it would be said
        // again 30 seconds later, which is the complaint the rule came from.
        // 1% left at 1/61 %/s is 61 seconds: ceil gives 2 min, and the run has
        // a minute and a second to go.
        assertEquals("about 2 min left", TransferProgress.Estimate.label(99, 1.0 / 61.0))
        // 1% left at 1 %/s is one second: still "about a minute", because
        // anything under a minute is the same answer to the reader.
        assertEquals("about a minute left", TransferProgress.Estimate.label(99, 1.0))
    }

    @Test
    fun `a long run is expressed in hours`() {
        // A whole job's worth of percent per hour, measured from nothing done:
        // exactly an hour. Six thousand seconds of "min left" is a number the
        // reader has to divide in their head.
        // 100% at 100/3600 %/s is a one-hour job, and nothing done yet.
        assertEquals(
            "about 1 hour left",
            TransferProgress.Estimate.label(0, 100.0 / 3_600.0)
        )
        // 4% done on a 100-minute job: 96 minutes left. The remainder is kept,
        // because "1 hour" would understate it by 36 minutes.
        assertEquals(
            "about 1 hour 36 min left",
            TransferProgress.Estimate.label(4, 100.0 / 6_000.0)
        )
    }

    @Test
    fun `elapsed is withheld until there is something to say`() {
        // The first few seconds of a run. A clock that appears instantly reads as
        // a promise about how long this will take; it is only shown once the run
        // has demonstrably been going for a while.
        assertNull(TransferProgress.Estimate.elapsedLabel(5_000L))
        assertNull(TransferProgress.Estimate.elapsedLabel(TransferProgress.Estimate.MIN_ELAPSED_MS - 1))
        // Past that, the honest answer below a minute is not "0 min elapsed" —
        // which reads as stopped — but that it has not yet been a minute.
        assertEquals(
            "under a minute elapsed",
            TransferProgress.Estimate.elapsedLabel(TransferProgress.Estimate.MIN_ELAPSED_MS)
        )
        assertEquals("under a minute elapsed", TransferProgress.Estimate.elapsedLabel(59_000L))
        assertEquals("11 min elapsed", TransferProgress.Estimate.elapsedLabel(11 * 60_000L))
    }

    @Test
    fun `elapsed does not grow without bound`() {
        assertEquals(
            "over an hour elapsed",
            TransferProgress.Estimate.elapsedLabel(3 * 3_600_000L)
        )
    }

    @Test
    fun `a speed is only shown against a real total`() {
        // The rate is percent-per-second, so turning it into bytes needs the
        // archive's own recorded size. Without it the honest answer is nothing
        // rather than a figure derived from a default.
        val rate = 0.5
        assertNull(TransferProgress.Estimate.rateLabel(rate, 0L))
        assertEquals(
            "50 MB/s",
            TransferProgress.Estimate.rateLabel(rate, 10_000_000_000L)
        )
    }
}