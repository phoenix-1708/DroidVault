package com.phoenix.droidvault.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The line a device-data restore leaves on screen for Messages.
 *
 * Field-verified before this existed: a run that restored 94 messages ended
 * showing "Restored 0 Messages.", because the MMS pass ran last and reported its
 * own count under the item's words. The count is the answer to "did my messages
 * come back?", so the four cases are pinned here rather than being left to a
 * `when` inside a pipeline.
 */
class MessageRestoreSummaryTest {

    @Test
    fun `a run that wrote messages says so, whatever the picture pass did`() {
        assertEquals(
            "Restored 94 Messages.",
            MessageRestoreSummary.of(restored = 94, alreadyPresent = 0)
        )
        // The shape that shipped wrong: SMS restored, MMS empty. The MMS pass
        // used to be the last to speak, so this exact run read "Restored 0".
        val mixed = MessageRestoreSummary.of(restored = 94, alreadyPresent = 2)
        assertEquals("Restored 94 Messages (2 already on the phone).", mixed)
        assertFalse(mixed.contains("Restored 0"))
    }

    @Test
    fun `nothing to restore is not the same statement as a failed restore`() {
        // An idempotent re-run: the phone already holds every message. Saying
        // "Restored 0" reads as a failure, and saying "Restored 96" would claim
        // work that never happened.
        val line = MessageRestoreSummary.of(restored = 0, alreadyPresent = 96)
        assertEquals("All 96 Messages are already on the phone.", line)
        assertFalse(line.contains("Restored 0"))
    }

    @Test
    fun `an empty archive says what it found rather than reporting zero writes`() {
        assertEquals(
            "No Messages in the archive.",
            MessageRestoreSummary.of(restored = 0, alreadyPresent = 0)
        )
    }

    @Test
    fun `no line names a brace, which is what shipped on a finished run before`() {
        // §4.53: a `}` outside `${…}` is an ordinary character, so the compiler
        // has no opinion and every in-progress screenshot looks right.
        val cases = listOf(
            MessageRestoreSummary.of(1, 0),
            MessageRestoreSummary.of(1, 2),
            MessageRestoreSummary.of(0, 3),
            MessageRestoreSummary.of(0, 0)
        )
        cases.forEach { assertFalse("brace in: $it", it.contains("}")) }
    }
}
