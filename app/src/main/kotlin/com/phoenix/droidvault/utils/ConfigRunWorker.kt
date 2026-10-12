package com.phoenix.droidvault.utils

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phoenix.droidvault.models.ConfigExecution
import com.phoenix.droidvault.models.ResultState
import com.phoenix.droidvault.models.ScheduleType
import com.phoenix.droidvault.models.TriggerType
import java.util.Calendar

/**
 * Headless schedule execution. Loads the schedule and resolves its
 * configuration list AGAINST THE LIVE STORE at fire time: deleted or disabled
 * configurations are skipped (explicitly logged), survivors run IN ORDER
 * through [AutomationRunner.execute] — the same core as manual runs.
 *
 * One WorkManager work unit per SCHEDULE (not per config): a one-shot disables
 * + marks itself spent once, then the loop skips any config a sibling
 * schedule/attempt already completed at/after the intended fire time (durable
 * per-config completion epochs), so a retry finishes the REMAINING configs
 * instead of duplicating the finished ones.
 */
class ConfigRunWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // First line of the process: any death before the historical first log
        // (which used to sit after the app-list fetch) was indistinguishable
        // from "never ran". Attempt tags make retry forensics possible.
        android.util.Log.w(
            "ConfigRunWorker",
            "doWork enter attempt=$runAttemptCount id=${inputData.getString(AutomationScheduler.KEY_SCHEDULE_ID)}"
        )
        val scheduleId = inputData.getString(AutomationScheduler.KEY_SCHEDULE_ID)
            ?: return Result.failure()
        val schedule = AutomationStore.listSchedules(applicationContext).find { it.id == scheduleId }
        // A spent one-shot (disabled by THIS worker when it fired) may still
        // run here: a mid-run process death makes WorkManager retry, and the
        // retry must complete the run instead of silently dropping it.
        val spentOnce =
            schedule != null && schedule.type == ScheduleType.ONCE &&
            !schedule.enabled && AutomationStore.isSpent(applicationContext, schedule.id)
        if (schedule == null || (!schedule.enabled && !spentOnce)) {
            android.util.Log.w(
                "ConfigRunWorker",
                "run skipped: schedule=${schedule != null} scheduleEnabled=${schedule?.enabled} spentOnce=$spentOnce"
            )
            if (schedule != null) AutomationStore.clearRunEpoch(applicationContext, schedule.id)
            return Result.success()
        }
        // Durable work-unit epoch (audit A-04): attempt 0 captures "now"; its
        // WorkManager retries reuse it, so a retry only finishes configs that
        // did not complete in the earlier attempt — even when the retry lands
        // outside the 60 s duplicate-suppression window.
        val runEpoch = AutomationStore.runEpoch(
            applicationContext,
            schedule.id,
            newEpoch = runAttemptCount == 0
        )
        // Fire-time resolution ("available configurations present at that
        // time"): deleted configs vanish from the list, disabled ones are
        // skipped — the schedule keeps working no matter how configs change.
        val store = AutomationStore.listConfigs(applicationContext)
        val runTargets = schedule.configIds.mapNotNull { id -> store.find { it.id == id } }
            .filter { it.enabled }
        val missing = schedule.configIds.count { id -> store.find { it.id == id }?.enabled != true }
        if (missing > 0) {
            android.util.Log.w(
                "ConfigRunWorker",
                "schedule ${schedule.id.take(8)}: $missing of ${schedule.configIds.size} " +
                    "configurations missing/disabled — running the survivors"
            )
        }
        if (schedule.type == ScheduleType.WEEKLY &&
            schedule.weekdays.isNotEmpty() &&
            Calendar.getInstance().get(Calendar.DAY_OF_WEEK) !in schedule.weekdays
        ) {
            // Explicit, not silent: one SKIPPED record per resolved config so
            // the daily tick never looks like a lost run.
            runTargets.forEach { config ->
                val skipped = ConfigExecution(
                    id = AutomationStore.newId(),
                    configId = config.id,
                    configName = config.name,
                    trigger = TriggerType.SCHEDULED,
                    operation = config.operation,
                    result = ResultState.SKIPPED,
                    errorSummary = "not a scheduled weekday"
                )
                skipped.finishedAt = System.currentTimeMillis()
                AutomationStore.recordExecution(applicationContext, skipped)
            }
            return Result.success()
        }
        val savedPath = AutomationRunner.resolveSavedPath(applicationContext)
        // One-shot schedules auto-disable after firing. Must persist WITHOUT a
        // reschedule (see [AutomationStore.disableSchedule]): a normal toggle
        // would cancel this very worker mid-run. Done BEFORE the loop so a
        // killed app / crash mid-run can never re-arm the spent schedule; the
        // per-config completion epochs below let the WorkManager retry finish
        // the remaining configs without duplicating the finished ones.
        if (schedule.type == ScheduleType.ONCE) {
            AutomationStore.disableSchedule(applicationContext, schedule.id)
            // Remember "this worker fired me" so a mid-run death + WorkManager
            // retry still completes the run (the enabled=false gate would
            // otherwise silently drop it — a run lost with no history).
            AutomationStore.markSpent(applicationContext, schedule.id)
        }
        if (runTargets.isEmpty()) {
            android.util.Log.w(
                "ConfigRunWorker",
                "schedule ${schedule.id.take(8)} has no runnable configurations — nothing to do"
            )
            if (schedule.type == ScheduleType.ONCE) {
                AutomationStore.clearSpent(applicationContext, schedule.id)
            }
            AutomationStore.clearRunEpoch(applicationContext, schedule.id)
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                AutomationNotifications.notifyDone(
                    applicationContext,
                    schedule.name.ifBlank { "Schedule" },
                    "No runnable configurations — skipped"
                )
            }
            return Result.success()
        }
        // Start-of-run tombstone for the FIRST config, written BEFORE anything
        // risky (the shared app-list fetch): if the process dies mid-run it
        // survives as an honest FAILED "interrupted" record — the run is never
        // silently missing from History. Later configs get their own tombstone
        // right before their turn; the runner removes each on completion, and
        // a graceful cancel leaves no not-yet-started tombstone behind.
        fun tombstoneFor(config: com.phoenix.droidvault.models.BackupConfig) {
            AutomationStore.recordExecution(
                applicationContext,
                ConfigExecution(
                    id = AutomationStore.newId(),
                    configId = config.id,
                    configName = config.name,
                    trigger = TriggerType.SCHEDULED,
                    operation = config.operation,
                    result = ResultState.FAILED,
                    errorSummary = "interrupted mid-run (process died; WorkManager retried)"
                )
            )
        }
        tombstoneFor(runTargets.first())
        // One app-list fetch shared by every config in the run (it is the
        // same device inventory; per-config fetches were pure cost).
        val appInfos = try {
            MigratorManager.fetchAppsList(
                applicationContext, savedPath, "AllInstalled"
            )
        } catch (e: Exception) {
            // Headless runs must fail loudly and visibly, not crash the worker:
            // an uncaught throw here reads as a silently missing run in History.
            android.util.Log.w("ConfigRunWorker", "app list fetch failed", e)
            AutomationStore.removeInterruptedTombstones(applicationContext, runTargets.first().id)
            val failed = ConfigExecution(
                id = AutomationStore.newId(),
                configId = runTargets.first().id,
                configName = runTargets.first().name,
                trigger = TriggerType.SCHEDULED,
                operation = runTargets.first().operation,
                result = ResultState.FAILED,
                errorSummary = "could not read app list: ${e.message ?: "unknown error"}"
            )
            failed.finishedAt = System.currentTimeMillis()
            AutomationStore.recordExecution(applicationContext, failed)
            AutomationNotifications.notifyDone(
                applicationContext,
                runTargets.first().name,
                "Run failed: could not read app list"
            )
            return Result.retry()
        }
        val assignCount = AutomationStore.listAssignments(applicationContext).size
        android.util.Log.d(
            "ConfigRunWorker",
            "run schedule=$scheduleId configs=${runTargets.size} apps=${appInfos.size} assignments=$assignCount"
        )
        DroidVaultLogger.i(
            "Automation",
            "Schedule tick: ${runTargets.size} config(s), ${appInfos.size} app(s), $assignCount assignment(s)"
        )
        val apps = appInfos.map {
            com.phoenix.droidvault.models.PlannableApp(
                it.packageName, it.label, it.isSystem, it.isInstalled
            )
        }
        var anyFailed = false
        var ran = 0
        for ((index, config) in runTargets.withIndex()) {
            // Shade Cancel / schedule deletion stops the worker: finish the
            // current config (the runner records it), skip the rest.
            if (isStopped) break
            // Tombstone for every config after the first (the first was
            // written before the shared app-list fetch above).
            if (index > 0) tombstoneFor(config)
            // Completion dedup (durable, epoch-based — audit A-04/A-14): if
            // any run of this config completed at/after this schedule's
            // intended fire time (ONCE) or at/after this work unit started
            // (recurring), the work is done — a sibling schedule or an earlier
            // attempt got it. Never execute the same work twice (a double
            // restore duplicates messages/call logs).
            val completedAt = AutomationStore.configCompletedAt(applicationContext, config.id)
            val alreadyDone = if (schedule.type == ScheduleType.ONCE) {
                completedAt >= schedule.triggerAtMillis
            } else {
                completedAt >= runEpoch
            }
            if (alreadyDone) {
                android.util.Log.w(
                    "ConfigRunWorker",
                    "config ${config.id.take(8)} already completed at/after fire time — skipped"
                )
                DroidVaultLogger.w(
                    "Automation",
                    "Run '${config.name}' skipped — already completed since fire time (dedup)"
                )
                // The tombstone written for this config just above must not
                // survive a skipped run: it would sit in History forever as
                // FAILED "interrupted mid-run" (audit A-14).
                AutomationStore.removeInterruptedTombstones(applicationContext, config.id)
                continue
            }
            ran++
            DroidVaultLogger.v(
                "Automation", "config '${config.name}' starting (${config.operation})"
            )
            val execution = try {
                AutomationRunner.execute(
                    context = applicationContext,
                    config = config,
                    apps = apps,
                    appInfos = appInfos,
                    trigger = TriggerType.SCHEDULED,
                    updateProgress = { _, step, _ ->
                        AutomationNotifications.notifyProgress(
                            applicationContext, config.name, step, config.operation
                        )
                    }
                )
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Stopped (schedule disabled/deleted mid-run or shade Cancel dropping
                // the worker): already recorded as CANCELLED by the shared core.
                // NonCancellable: the worker coroutine is unwinding — a plain notify
                // here can be silently skipped, leaving the progress card stuck.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    AutomationNotifications.notifyDone(
                        applicationContext, config.name, "Cancelled — finished apps are kept"
                    )
                }
                break
            }
            android.util.Log.d(
                "ConfigRunWorker",
                "config=${config.id} result=${execution.result} ok=${execution.successCount} " +
                    "failed=${execution.failureCount} skipped=${execution.skippedCount} " +
                    (execution.errorSummary.take(120))
            )
            if (execution.result == ResultState.FAILED) anyFailed = true
            AutomationNotifications.notifyDone(
                applicationContext,
                config.name,
                "${execution.successCount} ok, ${execution.failureCount} failed, " +
                    "${execution.skippedCount} skipped • ${execution.result.name.lowercase()}"
            )
        }
        // The run(s) completed; the runner durably marked each config done
        // and cleaned its tombstone. Retire this schedule's spent marker only
        // after the whole list ran (a retry must still complete the rest).
        if (schedule.type == ScheduleType.ONCE && !isStopped) {
            AutomationStore.clearSpent(applicationContext, schedule.id)
        }
        val retry = anyFailed && !isStopped
        if (!retry) {
            // The unit is truly done; the next fire captures a fresh epoch.
            // A retry must KEEP its epoch, otherwise its dedup falls back to
            // "now" and re-runs the configs the earlier attempt completed.
            AutomationStore.clearRunEpoch(applicationContext, schedule.id)
        }
        android.util.Log.d("ConfigRunWorker", "schedule $scheduleId done ran=$ran/${runTargets.size}")
        return if (retry) Result.retry() else Result.success()
    }
}
