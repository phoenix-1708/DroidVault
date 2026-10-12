package com.phoenix.droidvault.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.phoenix.droidvault.models.AutomationSchedule
import com.phoenix.droidvault.models.ScheduleMath
import com.phoenix.droidvault.models.ScheduleType
import java.util.concurrent.TimeUnit

/**
 * WorkManager front-end for automation schedules. Timing is inexact by design
 * (Doze/standby defer); unmet conditions defer via constraints. Pure timing in
 * [ScheduleMath]; this object only translates to WorkManager requests.
 */
object AutomationScheduler {

    const val KEY_SCHEDULE_ID = "schedule_id"
    /** Legacy input key; no longer written — the worker resolves from the store. */
    const val KEY_CONFIG_ID = "config_id"

    fun tagFor(id: String): String = "automation-schedule-$id"

    fun toConstraints(s: AutomationSchedule): Constraints =
        Constraints.Builder()
            .setRequiresCharging(s.requiresCharging)
            .setRequiredNetworkType(if (s.requiresUnmetered) NetworkType.UNMETERED else NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(s.requiresBatteryNotLow)
            .build()

    fun reschedule(context: Context, schedule: AutomationSchedule) {
        cancel(context, schedule.id)
        if (!schedule.enabled || !AutomationStore.isMasterEnabled(context)) return
        val wm = try {
            WorkManager.getInstance(context)
        } catch (_: Exception) {
            return
        }
        val constraints = toConstraints(schedule)
        // Only the schedule id rides along: the worker resolves the config
        // list from the store at FIRE time (multi-config schedules — deleted
        // configs must not break the schedule; edits must apply).
        val data = workDataOf(
            KEY_SCHEDULE_ID to schedule.id
        )
        try {
            if (schedule.type == ScheduleType.ONCE) {
                val delay = ScheduleMath.initialDelayMs(schedule)
                val req = OneTimeWorkRequestBuilder<ConfigRunWorker>()
                    .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                    .setConstraints(constraints)
                    .setInputData(data)
                    .addTag(tagFor(schedule.id))
                    .build()
                wm.enqueueUniqueWork(tagFor(schedule.id), ExistingWorkPolicy.REPLACE, req)
            } else {
                // WEEKLY with specific weekdays: daily tick, worker skips non-matching
                // days with a recorded SKIPPED execution (explicit, not silent).
                // Weekdays empty = every day; [ScheduleMath.effectivePeriodMs]
                // encodes that (a bare 7-day period would leave the other six
                // days silently unrung).
                val intervalMs = ScheduleMath.effectivePeriodMs(schedule)
                val req = PeriodicWorkRequestBuilder<ConfigRunWorker>(
                    intervalMs, TimeUnit.MILLISECONDS
                )
                    .setInitialDelay(ScheduleMath.initialDelayMs(schedule), TimeUnit.MILLISECONDS)
                    .setConstraints(constraints)
                    .setInputData(data)
                    .addTag(tagFor(schedule.id))
                    .build()
                wm.enqueueUniquePeriodicWork(
                    tagFor(schedule.id),
                    ExistingPeriodicWorkPolicy.UPDATE,
                    req
                )
            }
        } catch (_: Exception) {
        }
    }

    fun cancel(context: Context, id: String) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(tagFor(id))
        } catch (_: Exception) {
        }
    }

    fun cancelAll(context: Context) {
        try {
            AutomationStore.listSchedules(context).forEach { cancel(context, it.id) }
        } catch (_: Exception) {
        }
    }

    /** Re-enqueue every enabled schedule (used when the master toggle turns on). */
    fun rescheduleAll(context: Context) {
        try {
            AutomationStore.listSchedules(context).filter { it.enabled }.forEach {
                reschedule(context, it)
            }
        } catch (_: Exception) {
        }
    }
}
