package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AutomationSchedule
import com.phoenix.droidvault.models.ScheduleMath
import com.phoenix.droidvault.models.ScheduleType
import com.phoenix.droidvault.models.describeAutomationSchedule
import com.phoenix.droidvault.models.displayName
import com.phoenix.droidvault.models.formatScheduleTime
import com.phoenix.droidvault.models.validateAutomationSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AutomationScheduleTest {

    private fun base() = AutomationSchedule(id = "s", configIds = listOf("c"))

    @Test fun validation() {
        assertTrue(validateAutomationSchedule(base(), setOf("c")).isEmpty())
        assertTrue(
            validateAutomationSchedule(base().copy(configIds = listOf("x")), setOf("c"))
                .any { it.contains("unknown config") }
        )
        // Multi-config: all-unknown is an error, but a partially-unknown set is
        // legal by design (fire-time semantics run what still exists).
        assertTrue(validateAutomationSchedule(base().copy(configIds = listOf("c", "d")), setOf("c", "d")).isEmpty())
        assertTrue(validateAutomationSchedule(base().copy(configIds = emptyList()), setOf("c")).isNotEmpty())
        assertTrue(
            validateAutomationSchedule(base().copy(type = ScheduleType.INTERVAL, intervalMinutes = 5), setOf("c"))
                .any { it.contains("15 min") }
        )
        assertTrue(
            validateAutomationSchedule(base().copy(type = ScheduleType.ONCE, triggerAtMillis = 0), setOf("c"))
                .any { it.contains("future time") }
        )
        assertTrue(
            validateAutomationSchedule(base().copy(type = ScheduleType.DAILY, hour = 25), setOf("c"))
                .any { it.contains("invalid time") }
        )
    }

    @Test fun onceScheduleValidatesAndTimesExactly() {
        val now = 1_700_000_000_000L
        val future = base().copy(type = ScheduleType.ONCE, triggerAtMillis = now + 60_000L)
        // "A minute in the future" must validate and carry an exact 60 s delay.
        assertTrue(validateAutomationSchedule(future, setOf("c")).isEmpty())
        assertEquals(60_000L, ScheduleMath.initialDelayMs(future, now))
        // Past/now one-shots fire immediately (clamped, never negative).
        assertEquals(0L, ScheduleMath.initialDelayMs(future.copy(triggerAtMillis = now), now))
        assertEquals(0L, ScheduleMath.initialDelayMs(future.copy(triggerAtMillis = now - 5_000L), now))
        // But a zero epoch is invalid (never scheduled by accident).
        assertTrue(
            validateAutomationSchedule(future.copy(triggerAtMillis = 0), setOf("c"))
                .any { it.contains("future time") }
        )
    }

    @Test fun intervalClampedTo15Min() {
        assertEquals(15 * 60 * 1000L, ScheduleMath.periodicIntervalMs(base().copy(type = ScheduleType.INTERVAL, intervalMinutes = 5)))
        assertEquals(60 * 60 * 1000L, ScheduleMath.periodicIntervalMs(base().copy(type = ScheduleType.INTERVAL, intervalMinutes = 60)))
        assertEquals(24 * 60 * 60 * 1000L, ScheduleMath.periodicIntervalMs(base().copy(type = ScheduleType.DAILY)))
    }

    @Test fun dailyDelayTargetsNextOccurrence() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val now = cal.timeInMillis
        // Later today.
        val d1 = ScheduleMath.initialDelayMs(base().copy(type = ScheduleType.DAILY, hour = 12, minute = 0), now)
        assertEquals(2 * 60 * 60 * 1000L, d1)
        // Earlier today -> tomorrow (~23h).
        val d2 = ScheduleMath.initialDelayMs(base().copy(type = ScheduleType.DAILY, hour = 9, minute = 0), now)
        assertTrue(d2 in (22 * 60 * 60 * 1000L)..(24 * 60 * 60 * 1000L))
    }

    @Test fun weeklyRespectsWeekdays() {
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.DAY_OF_WEEK)
        val tomorrow = today % 7 + 1
        val s = base().copy(type = ScheduleType.WEEKLY, hour = 0, minute = 0, weekdays = setOf(tomorrow))
        val d = ScheduleMath.initialDelayMs(s, cal.timeInMillis)
        assertTrue(d in (1 * 60 * 60 * 1000L)..(49 * 60 * 60 * 1000L))
    }

    @Test fun scheduleCodecRoundTrip() {
        val s = base().copy(
            type = ScheduleType.WEEKLY, hour = 2, minute = 30,
            weekdays = setOf(1, 7), requiresCharging = true, intervalMinutes = 90
        )
        val back = AutomationCodec.scheduleFrom(AutomationCodec.scheduleTo(s))
        assertEquals(s, back)
    }

    @Test fun scheduleCodecToleratesUnknownType() {
        val o = org.json.JSONObject().put("id", "s").put("configIds", org.json.JSONArray().put("c")).put("type", "FROBNICATE")
        assertEquals(ScheduleType.DAILY, AutomationCodec.scheduleFrom(o).type)
        // Legacy single-config rows normalize into the configIds list.
        val legacy = org.json.JSONObject().put("id", "s").put("configId", "c")
        assertEquals(listOf("c"), AutomationCodec.scheduleFrom(legacy).configIds)
    }

    @Test fun describeCoversAllTypes() {
        assertTrue(describeAutomationSchedule(base().copy(type = ScheduleType.ONCE), listOf("C")).contains("once"))
        assertTrue(describeAutomationSchedule(base().copy(type = ScheduleType.DAILY, hour = 3, minute = 5), listOf("C")).contains("03:05 AM"))
        assertTrue(describeAutomationSchedule(base().copy(type = ScheduleType.DAILY, hour = 15, minute = 0), listOf("C")).contains("03:00 PM"))
        assertTrue(describeAutomationSchedule(base().copy(type = ScheduleType.DAILY, hour = 0, minute = 0), listOf("C")).contains("12:00 AM"))
        assertTrue(
            describeAutomationSchedule(
                base().copy(type = ScheduleType.WEEKLY, hour = 2, minute = 0, weekdays = setOf(2)),
                listOf("C")
            ).contains("Mon")
        )
        assertTrue(
            describeAutomationSchedule(
                base().copy(type = ScheduleType.INTERVAL, intervalMinutes = 30, requiresCharging = true), listOf("C")
            ).contains("charging")
        )
    }

    @Test fun twelveHourFormat() {
        assertEquals("12:00 AM", formatScheduleTime(0, 0))
        assertEquals("12:30 PM", formatScheduleTime(12, 30))
        assertEquals("02:05 PM", formatScheduleTime(14, 5))
        assertEquals("11:59 PM", formatScheduleTime(23, 59))
    }

    @Test fun scheduleNameFallsBackToConfig() {
        assertTrue(describeAutomationSchedule(base(), listOf("MyConfig")).startsWith("MyConfig"))
        assertTrue(describeAutomationSchedule(base().copy(name = "Nightly"), listOf("MyConfig")).startsWith("Nightly"))
        assertEquals("Nightly", base().copy(name = "Nightly").displayName(listOf("MyConfig")))
        assertEquals("MyConfig", base().displayName(listOf("MyConfig")))
    }

    @Test fun multiConfigDisplay() {
        // Multiple configurations compact into "First +N".
        assertEquals("A +1", base().copy(configIds = listOf("a", "b")).displayName(listOf("A", "B")))
        assertEquals("A +2", base().copy(configIds = listOf("a", "b", "c")).displayName(listOf("A", "B", "C")))
        // Custom name always wins over the config list.
        assertEquals(
            "Nightly",
            base().copy(configIds = listOf("a", "b"), name = "Nightly").displayName(listOf("A", "B"))
        )
    }
}
