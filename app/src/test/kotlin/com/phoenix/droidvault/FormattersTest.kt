package com.phoenix.droidvault

import com.phoenix.droidvault.utils.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The age of a finding, as the health row states it.
 *
 * The row's whole job is separating "something just happened" from "this has been
 * wrong for a week", because a finding is not withdrawn by a later run (§2.46) and
 * therefore outlives the check that found it. Two ways to get that wrong are
 * pinned here: an unknown timestamp must not read as brand new (the one answer
 * that makes a user stop looking), and a clock that moved backwards — an NTP
 * correction, a hand-set date — must not produce an age in the future.
 */
class FormattersTest {

    private val now = 1_700_000_000_000L
    private val sec = 1_000L
    private val minute = 60 * sec
    private val hour = 60 * minute
    private val day = 24 * hour

    private fun ago(ms: Long) = now - ms

    @Test
    fun `an unknown end is not an age`() {
        assertNull("no timestamp was ever written", Formatters.relativeAge(now, 0L))
        assertNull("and a negative one is not a date", Formatters.relativeAge(now, -5L))
        assertNull(
            "a clock with no 'now' cannot floor at 'just now' for a finding from last month",
            Formatters.relativeAge(0L, ago(day))
        )
    }

    @Test
    fun `a finding just observed reads as just now`() {
        assertEquals("just now", Formatters.relativeAge(now, now))
        assertEquals("just now", Formatters.relativeAge(now, ago(30 * sec)))
    }

    @Test
    fun `minutes hours and days are spelled the way a person says them`() {
        assertEquals("a minute ago", Formatters.relativeAge(now, ago(minute)))
        assertEquals("5 minutes ago", Formatters.relativeAge(now, ago(5 * minute)))
        assertEquals("an hour ago", Formatters.relativeAge(now, ago(hour)))
        assertEquals("3 hours ago", Formatters.relativeAge(now, ago(3 * hour)))
        assertEquals("yesterday", Formatters.relativeAge(now, ago(day)))
        assertEquals("4 days ago", Formatters.relativeAge(now, ago(4 * day)))
        assertEquals("over a month ago", Formatters.relativeAge(now, ago(60 * day)))
    }

    @Test
    fun `a clock that went backwards does not report the future`() {
        assertEquals("just now", Formatters.relativeAge(now, now + 5 * minute))
    }
}
