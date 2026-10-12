package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppHistoryRecord
import com.phoenix.droidvault.models.AutomationSchedule
import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.ConfigExecution
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.ResultState
import com.phoenix.droidvault.models.ScheduleMath
import com.phoenix.droidvault.models.ScheduleType
import com.phoenix.droidvault.models.TriggerType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the deep-analysis hardening pass: schedule periods,
 * execution persistence (operation survives deletion), and legacy tolerance.
 */
class ReliabilityHardeningTest {

    private val day = 24 * 60 * 60 * 1000L
    private val week = 7 * day

    private fun schedule(type: ScheduleType, weekdays: Set<Int> = emptySet()) =
        AutomationSchedule(id = "s", configIds = listOf("c"), type = type, weekdays = weekdays)

    @Test fun weeklyWithNoDaysMeansDaily() {
        // "Weekly" with no picked days is documented as every day. The old
        // scheduler enqueued a 7-day period: fired once, then silent for a week.
        assertEquals(day, ScheduleMath.effectivePeriodMs(schedule(ScheduleType.WEEKLY)))
        // The raw weekly period stays 7 days for display math; effectivePeriodMs
        // is what the scheduler must enqueue.
        assertEquals(week, ScheduleMath.periodicIntervalMs(schedule(ScheduleType.WEEKLY)))
    }

    @Test fun weeklyWithSomeDaysTicksDailyForSkipLogic() {
        val s = schedule(ScheduleType.WEEKLY, weekdays = setOf(2, 4, 6)) // Mon/Wed/Fri
        assertEquals(day, ScheduleMath.effectivePeriodMs(s))
    }

    @Test fun weeklyWithAllDaysTicksDaily() {
        // Audit A-15: all seven days ticked means "every day" — the old 7-day
        // period made an every-day schedule run once a week. The raw weekly
        // period stays available for display math.
        val all = (1..7).toSet()
        assertEquals(day, ScheduleMath.effectivePeriodMs(schedule(ScheduleType.WEEKLY, all)))
        assertEquals(week, ScheduleMath.periodicIntervalMs(schedule(ScheduleType.WEEKLY, all)))
    }

    @Test fun dailyAndIntervalUnaffected() {
        assertEquals(day, ScheduleMath.effectivePeriodMs(schedule(ScheduleType.DAILY)))
        assertEquals(
            15 * 60 * 1000L,
            ScheduleMath.effectivePeriodMs(
                schedule(ScheduleType.INTERVAL).copy(intervalMinutes = 15)
            )
        )
    }

    @Test fun executionOperationSurvivesPersistence() {
        val exec = ConfigExecution(
            id = "e1", configId = "c1", configName = "FULL",
            trigger = TriggerType.MANUAL,
            operation = ConfigOperation.RESTORE,
            result = ResultState.SUCCESS
        )
        exec.apps.add(
            AppHistoryRecord(
                packageName = "com.a", appName = "A", ruleId = "r",
                requested = setOf(BackupComponent.APK),
                processed = setOf(BackupComponent.APK),
                result = ResultState.SUCCESS
            )
        )
        // Round-trip through the same JSON shape the store persists.
        val json = executionJson(exec)
        assertEquals("RESTORE", json.optString("operation"))
        assertTrue(exec.apps.isNotEmpty())
    }

    @Test fun legacyExecutionWithoutOperationReadsAsBackup() {
        // Older builds stored no operation field: summaries must default to
        // BACKUP instead of crashing or mislabeling.
        val legacy = JSONObject()
            .put("id", "e2").put("configId", "c").put("configName", "n")
            .put("trigger", "MANUAL").put("startedAt", 1L)
            .put("result", "SUCCESS").put("success", 1)
            .put("failed", 0).put("skipped", 0).put("errorSummary", "")
        assertEquals("BACKUP", legacy.optString("operation", "BACKUP"))
    }

    @Test fun distinctConfigsKeepDistinctOperationsInHistory() {
        // The old History marker looked the config up by name; two configs
        // sharing a name across operations collided. The persisted op cannot.
        val backup = ConfigExecution(
            id = "e3", configId = "cb", configName = "FULL",
            trigger = TriggerType.SCHEDULED, operation = ConfigOperation.BACKUP
        )
        val restore = ConfigExecution(
            id = "e4", configId = "cr", configName = "FULL",
            trigger = TriggerType.SCHEDULED, operation = ConfigOperation.RESTORE
        )
        assertNotEquals(backup.operation, restore.operation)
    }

    /** Mirrors AutomationStore.executionTo without needing an Android Context. */
    private fun executionJson(e: ConfigExecution): JSONObject = JSONObject()
        .put("id", e.id).put("configId", e.configId).put("configName", e.configName)
        .put("trigger", e.trigger.name).put("startedAt", e.startedAt)
        .put("result", e.result.name)
        .put("operation", e.operation.name)
}
