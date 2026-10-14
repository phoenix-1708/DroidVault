package com.phoenix.droidvault.utils

import android.content.Context
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppSeed
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.BackupPlan
import com.phoenix.droidvault.models.ConfigExecution
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.LiveOp
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.models.PartState
import com.phoenix.droidvault.models.PlannableApp
import com.phoenix.droidvault.models.ResultState
import com.phoenix.droidvault.models.TrackerEvent
import com.phoenix.droidvault.models.TriggerType
import com.phoenix.droidvault.models.toEngineIds
import kotlinx.coroutines.launch

/**
 * Maps a [BackupPlan] onto the existing backup engine inputs. Pure mapping —
 * execution stays in AutomationViewModel. No engine changes: per-app
 * `activeComponents` is exactly what `runDynamicOperation` already honors.
 */
object AutomationRunner {

    /** Must match CANCEL_FLAG in assets/DroidVault.sh. World-writable dir: no root needed. */
    const val CANCEL_FLAG_PATH = "/data/local/tmp/droidvault_cancel"

    private const val TAG_CANCEL = "AutomationCancel"

    /**
     * Dedicated root shell for control commands. The shared libsu shell may be
     * blocked behind a long-running backup, so flag/kill commands must never
     * queue on it — otherwise Cancel itself hangs.
     */
    @Volatile
    private var controlShell: com.topjohnwu.superuser.Shell? = null

    /**
     * Fire-and-forget IO scope for cancel fallbacks. A cold su can take ~10 s;
     * the in-app Cancel button used to block the UI thread on it (audit A-19).
     */
    private val cancelScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private fun controlShell(): com.topjohnwu.superuser.Shell? {
        return try {
            var s = controlShell
            if (s == null) {
                s = com.topjohnwu.superuser.Shell.Builder.create()
                    .setFlags(com.topjohnwu.superuser.Shell.FLAG_MOUNT_MASTER)
                    .setTimeout(10)
                    .build()
                controlShell = s
            }
            s
        } catch (e: Exception) {
            android.util.Log.w(TAG_CANCEL, "control shell unavailable: ${e.message}")
            null
        }
    }

    /**
     * Signal a running operation to stop after the current app (cooperative).
     * Returns the channel that worked, for diagnosis. Never throws.
     */
    fun requestCancel(): String {
        try {
            if (java.io.File(CANCEL_FLAG_PATH).createNewFile()) {
                android.util.Log.d(TAG_CANCEL, "flag via direct write")
                return "file"
            }
            if (java.io.File(CANCEL_FLAG_PATH).exists()) {
                android.util.Log.d(TAG_CANCEL, "flag already present")
                return "already"
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG_CANCEL, "direct flag write failed: ${e.message}")
        }
        // Fallback path: the flag exists but is root-owned (the app uid cannot
        // unlink/overwrite it), so it must be (re)touched through root. Run it
        // on IO — never on the caller's thread: the in-app Cancel button used
        // to block for ~10 s on a cold su here (audit A-19). Returns "shell"
        // optimistically; the engine self-heals the flag at run start anyway.
        cancelScope.launch {
            try {
                // NOTE: shell.newJob() — static Shell.cmd() would queue on the
                // (possibly busy) default shell, defeating the purpose.
                val ok = controlShell()?.let {
                    it.newJob().add("touch ${ShellQuote.q(CANCEL_FLAG_PATH)}").exec().isSuccess
                } ?: false
                android.util.Log.d(TAG_CANCEL, "flag via control shell (async): $ok")
            } catch (e: Exception) {
                android.util.Log.w(TAG_CANCEL, "control shell touch failed: ${e.message}")
            }
        }
        return "shell"
    }

    /**
     * Hammer: stop in-flight background bundles via the PID journal the engine
     * maintains. Runs on the dedicated control shell. Deliberately NOT pkill -f:
     * the pattern would match our own control-shell cmdline (suicide) and risks
     * unrelated processes; PIDs are exact.
     */
    fun haltBackend(): Int {
        var killed = 0
        try {
            killed = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(20000L) {
                    var n = 0
                    try {
                        val shell = controlShell() ?: return@withTimeoutOrNull n
                        val out = shell.newJob().add("sh /data/adb/DroidVault/DroidVault.sh --kill-jobs").exec()
                        n = out.out.count { it.startsWith("killed:") }
                        android.util.Log.d(TAG_CANCEL, "haltBackend killed=$n")
                    } catch (e: Exception) {
                        android.util.Log.w(TAG_CANCEL, "haltBackend failed: ${e.message}")
                    }
                    n
                } ?: 0
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG_CANCEL, "haltBackend failed: ${e.message}")
        }
        return killed
    }

    /**
     * Clear the cancel flag before a run. Direct delete first (the common case:
     * the flag was app-created). If a root-owned flag survives (created by the
     * control shell on a previous cancel — the app uid cannot unlink it), fall
     * back to the control shell. The backend also self-heals (rm -f) at each
     * operation start, so a missed clear there is cosmetic; a stale flag HERE
     * is what wrongly cancels the next run.
     */
    fun clearCancel() {
        try {
            if (java.io.File(CANCEL_FLAG_PATH).delete()) return
        } catch (_: Exception) {
        }
        if (!isCancelRequested()) return
        try {
            val ok = controlShell()?.let {
                it.newJob().add("rm -f ${ShellQuote.q(CANCEL_FLAG_PATH)}").exec().isSuccess
            } ?: false
            android.util.Log.d(TAG_CANCEL, "flag clear via control shell: $ok")
        } catch (e: Exception) {
            android.util.Log.w(TAG_CANCEL, "flag clear failed: ${e.message}")
        }
    }

    fun isCancelRequested(): Boolean {
        return try {
            java.io.File(CANCEL_FLAG_PATH).exists()
        } catch (_: Exception) {
            false
        }
    }

    /** Best-effort count of our backend script processes (diagnosis, never kills). */
    fun backendProcCount(): Int {
        try {
            val shell = controlShell()
            if (shell != null) {
                val out = shell.newJob().add("pgrep -c -f 'DroidVault\\.sh' 2>/dev/null || echo 0").exec()
                out.out.joinToString("").trim().toIntOrNull()?.let { return it }
            }
        } catch (_: Exception) {
        }
        // Fallback: default shell with a hard timeout (control session may be dead).
        return try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(10000L) {
                    com.topjohnwu.superuser.Shell.cmd("pgrep -c -f 'DroidVault\\.sh' 2>/dev/null || echo 0")
                        .exec().out.joinToString("").trim().toIntOrNull()
                }
            } ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    /** Processable entries resolved to engine apps (unmatched/skipped dropped). */
    fun toEngineApps(plan: BackupPlan, apps: List<AppInfo>): List<AppInfo> {
        val byPkg = apps.associateBy { it.packageName }
        return plan.processable.mapNotNull { entry ->
            val app = byPkg[entry.packageName] ?: return@mapNotNull null
            app.copy(activeComponents = entry.granted.toEngineIds())
        }
    }

    /**
     * The user's standing restore-consent choices, read from the same persisted
     * prefs the Settings screen writes and [MainViewModel.loadConsentState] reads.
     *
     * Left unset, `AppState` defaults BOTH flags to false, and
     * `runDynamicOperation` folds consent into the per-app seeds:
     *
     * ```
     * val consentComps = state.globalComponents.filter {
     *     when (it) { 3 -> state.allowPermRestore; 5 -> state.allowSsaidRestore; else -> true }
     * }
     * ```
     *
     * The PLANNER already applied consent correctly — `AutomationCapability
     * .resolve(hasRoot, allowPerm, allowSsaid)` above — so this is a SECOND
     * gate, and it was the wrong one: fail-closed, and therefore cancelling out
     * the planner whenever consent WAS granted.
     *
     * Precise impact, because the two gates differ: with consent OFF the
     * planner strips them and this gate is a harmless no-op; with consent ON the
     * planner put them in `granted`, `union` contained 3 and 5, and this gate
     * then stripped them again — so a scheduled restore silently skipped
     * Permissions and Android ID for rules that explicitly listed them
     * ("PERMISSIONS", "ANDROID_ID" in the FULL profile) and still reported
     * success. Found by auditing the Migrate/Automation linkage, not on device.
     *
     * Never a permission hole — fail-closed is the safe direction for a consent
     * gate. It was a silent functional failure reporting success for work that
     * never happened. Both gates now read the same prefs, so they
     * cannot disagree.
     */
    fun buildState(
        plan: BackupPlan,
        operation: ConfigOperation,
        context: Context? = null
    ): AppState {
        val union = plan.processable.flatMap { it.granted.toEngineIds() }.toSet()
            .ifEmpty { setOf(1) }
        return AppState(
            migratorMode = if (operation == ConfigOperation.RESTORE) {
                MigratorMode.RESTORE_APPS
            } else {
                MigratorMode.BACKUP_APPS
            },
            globalComponents = union,
            allowPermRestore = context?.let { InstallerPolicy.isPermRestoreAllowed(it) } ?: false,
            allowSsaidRestore = context?.let { InstallerPolicy.isSsaidAllowed(it) } ?: false
        )
    }

    fun resolveSavedPath(context: Context): String {
        val prefs = context.getSharedPreferences("droidvault_prefs", Context.MODE_PRIVATE)
        return prefs.getString("base_path", SettingsManager.getDefaultPath())
            ?: SettingsManager.getDefaultPath()
    }

    /**
     * Shared execution core used by manual runs and the scheduler worker.
     * Returns the recorded execution; never throws (failures land in it).
     */
    /** Fire-and-forget scope for snapshots: awaiting shell I/O here would let a
     * wedged su session trap run completion (blocking reads ignore coroutine
     * cancellation). History is recorded synchronously above; snapshots follow. */
    private val snapshotScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

    /** Durable per-config completion epoch (see AutomationStore): suppresses
     * scheduled runs that queued behind a just-completed run of the same
     * config (retry + sibling schedule racing, observed in the field). */
    private const val DUPLICATE_SUPPRESS_MS = 60_000L

    suspend fun execute(
        context: Context,
        config: BackupConfig,
        apps: List<PlannableApp>,
        appInfos: List<AppInfo>,
        trigger: TriggerType,
        updateProgress: (action: String, step: String, prog: Int) -> Unit
    ): ConfigExecution =
        // Shared process-wide gate (audit A-05): the second caller waits, then
        // sees the completion marker and no-ops (worker-side dedup). Manual and
        // device-data runs take the SAME gate via RunCoordinator.
        RunCoordinator.withRun("automation:${config.id}") {
            executeLocked(context, config, apps, appInfos, trigger, updateProgress)
        }

    private suspend fun executeLocked(
        context: Context,
        config: BackupConfig,
        apps: List<PlannableApp>,
        appInfos: List<AppInfo>,
        trigger: TriggerType,
        updateProgress: (action: String, step: String, prog: Int) -> Unit
    ): ConfigExecution {
        // A scheduled worker that queued behind a just-completed run of the
        // same config (retry racing a sibling schedule) must not execute the
        // same work again — a double restore duplicates messages/call logs.
        // Durable marker, so it holds even across the process death the role
        // release can cause. Manual runs are user-driven and always run.
        if (trigger == TriggerType.SCHEDULED) {
            val doneAt = AutomationStore.configCompletedAt(context, config.id)
            if (doneAt > 0 && System.currentTimeMillis() - doneAt < DUPLICATE_SUPPRESS_MS) {
                DroidVaultLogger.i(
                    "Automation",
                    "Run '${config.name}' ($trigger) skipped: completed ${System.currentTimeMillis() - doneAt}ms ago"
                )
                val execution = ConfigExecution(
                    id = AutomationStore.newId(),
                    configId = config.id,
                    configName = config.name,
                    trigger = trigger,
                    operation = config.operation,
                    result = ResultState.SKIPPED,
                    errorSummary = "skipped: another run of this config completed moments ago"
                )
                finishExecution(context, execution)
                return execution
            }
        }
        val execution = ConfigExecution(
            id = AutomationStore.newId(),
            configId = config.id,
            configName = config.name,
            trigger = trigger,
            operation = config.operation
        )
        DroidVaultLogger.i(
            "Automation",
            "Run '${config.name}' start: ${config.operation} trigger=$trigger"
        )
        try {
            clearCancel()
            val hasRoot = try {
                com.topjohnwu.superuser.Shell.getShell().isRoot
            } catch (_: Exception) {
                false
            }
            val allowPerm = InstallerPolicy.isPermRestoreAllowed(context)
            val allowSsaid = InstallerPolicy.isSsaidAllowed(context)
            val caps = AutomationCapability.resolve(hasRoot, allowPerm, allowSsaid)
            val reason: (com.phoenix.droidvault.models.BackupComponent) -> String =
                { c -> AutomationCapability.reasonFor(c, hasRoot, allowPerm, allowSsaid) }
            val assignments = AutomationStore.listAssignments(context)
            // RESTORE: feed the planner what the backup folder actually holds
            // (same Meta.txt/shift-file inventory the Migrate tab's restore list
            // shows) so the plan restores the parts that exist — or skips apps
            // with no backup, instead of blindly requesting missing files.
            // CRITICAL: for restore, the backup-folder list is ALSO the planning
            // universe. The caller's list (installed apps) can never contain
            // uninstalled apps, yet restoring those is the whole point (restore =
            // install). Schedulers pass AllInstalled; manual restore passes the
            // installed list too — both would silently drop every uninstalled app.
            // AllBackups carries isInstalled per app, so nothing is lost.
            val backupApps: List<AppInfo>? = if (config.operation == ConfigOperation.RESTORE) {
                try {
                    MigratorManager.fetchAppsList(
                        context, resolveSavedPath(context), "AllBackups"
                    )
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }
            val planApps: List<PlannableApp> = backupApps?.map {
                PlannableApp(it.packageName, it.label, it.isSystem, it.isInstalled)
            } ?: apps
            val plan = BackupPlanner.plan(
                planApps, assignments, config, caps, dryRun = false,
                unavailableReason = reason,
                availableOnDisk = backupApps?.associate { it.packageName to it.availableInBackup }
            )
            val engineApps = toEngineApps(plan, backupApps ?: appInfos)
            if (engineApps.isEmpty() && config.deviceData.isEmpty()) {
                execution.result = ResultState.SKIPPED
                execution.errorSummary = "Nothing to process (all skipped or unavailable)"
                finishExecution(context, execution)
                return execution
            }
            val state = buildState(plan, config.operation, context)
            // P3 pre-run space gate: refuse before touching the backend when the
            // destination is nearly full (df failure never blocks the run — the
            // backend's own ENOSPC handling stays the backstop).
            if (config.operation == ConfigOperation.BACKUP) {
                val dest = resolveSavedPath(context)
                val freeKb = AutomationSafety.freeSpaceKb(dest)
                val space = AutomationSafety.evaluateSpace(freeKb)
                if (!space.ok) {
                    execution.result = ResultState.FAILED
                    execution.errorSummary =
                        "Not enough free space at $dest (${space.availableKb / 1024} MB free, " +
                        "${AutomationSafety.MIN_FREE_KB / 1024} MB required)"
                    finishExecution(context, execution)
                    return execution
                }
            }
            // Pre-restore integrity gate, mirroring the manual Migrate flow.
            //
            // Automation used to check only that the archive FILE existed
            // (`[ -f ... ] && echo y`), never that it could be read, so a
            // scheduled restore happily restored from an archive a previous
            // verification had already reported as damaged — with nobody
            // watching, which is the worst place for that. Found auditing the
            // Migrate/Automation linkage.
            //
            // Verifying first is proportionate rather than wasteful: the restore
            // is about to read every one of those bytes anyway. Only a
            // CONCLUSIVE corrupt verdict blocks — an empty vault or a verify
            // that never started reports "unknown" and must not make Restore
            // permanently dead.
            //
            // The dashboard and Manage-screen checks both pass deviceData = true
            // so one check covers apps AND device data (§2.44's defect fixed on
            // the display side). The restore gate that confirms those same bytes
            // before restoring them must use the same scope: an app-only verdict
            // never read Messages.shift / CallLogs.shift / Folders.shift, so an
            // app-only restore plan that does not touch device data still gates
            // apps correctly, but anything that restores device data (or a mixed
            // run) must see the device verdict too.
            //
            // The gate covers whichever halves the plan actually restores, and a
            // DEVICE-ONLY run is gated too: this used to sit behind
            // `engineApps.isNotEmpty()`, so a config that restored only messages
            // and calls was never verified at all — and the device verdict it did
            // fetch for mixed runs was never consulted, so a damaged
            // `Messages.shift` was restored headlessly by a scheduled run with
            // nobody watching. Same defect as §2.44, one path further out.
            if (config.operation == ConfigOperation.RESTORE) {
                val restoring = engineApps.map { it.packageName }.toSet()
                val restoringDevice = config.deviceData
                if (restoring.isNotEmpty() || restoringDevice.isNotEmpty()) {
                    // Only the halves in the plan: verifying app archives a restore
                    // will not touch is a full pass over the vault to answer a
                    // question nobody asked, and a device archive outside the plan
                    // must not block the apps that are inside it.
                    val appsHalf = if (restoring.isNotEmpty()) {
                        MigratorManager.verifyBackups(
                            context, resolveSavedPath(context), deviceData = false
                        )
                    } else {
                        null
                    }
                    val deviceHalf = if (restoringDevice.isNotEmpty()) {
                        MigratorManager.verifyBackups(
                            context, resolveSavedPath(context), deviceData = true
                        )
                    } else {
                        null
                    }
                    // One verdict from both halves, by the same tested merge the
                    // manual check uses — so the device rows reach the device gate
                    // instead of living in a second report nobody reads.
                    val report = when {
                        appsHalf != null && deviceHalf != null -> appsHalf.mergedWith(deviceHalf)
                        appsHalf != null -> appsHalf
                        else -> deviceHalf
                    }
                    val blocked = report?.let {
                        com.phoenix.droidvault.models.restoreBlockReason(it, restoring)
                            ?: com.phoenix.droidvault.models.restoreBlockReasonDevice(
                                it, restoringDevice
                            )
                    }
                    if (blocked != null) {
                        // The history row carries this for the user; the log carries
                        // it for whoever has to explain a run that did nothing at
                        // 3 a.m. A scheduled restore that stops itself is the one
                        // nobody was watching.
                        DroidVaultLogger.w(
                            "Verify", "Scheduled restore refused: $blocked"
                        )
                        execution.result = ResultState.FAILED
                        execution.errorSummary = blocked
                        finishExecution(context, execution)
                        return execution
                    }
                }
            }
            if (engineApps.isEmpty()) {
                // Device-data-only configs skip the app phase entirely (the
                // empty-plan skip above already returned when there is no
                // device data either).
            } else {
            MigratorManager.runDynamicOperation(
                context = context,
                state = state,
                selectedApps = engineApps,
                currentPath = resolveSavedPath(context),
                updateProgress = updateProgress,
                onComplete = { _, _ -> },
                // ALL automation runs feed the live Processing bus now: manual
                // runs present automatically; scheduled runs stay silent but
                // their notification deep-links to the same live screen.
                trackProgress = true,
                interactiveRun = trigger == com.phoenix.droidvault.models.TriggerType.MANUAL,
                // This runner already holds the shared gate (no re-entrancy).
                gateHeld = true
            )
            }
            // Automation runs change the same on-disk state the Migrate tab
            // reads (backups written / restored). Drop MigratorManager's fetch
            // caches so the Migrate tab refetches instead of showing stale lists.
            MigratorManager.clearCache()
            // Device-data phase (Messages/Calls/Contacts/Wi-Fi/Wallpaper/
            // Bluetooth): runs through the same DeviceManager pipeline the
            // Migrate tab uses — root permission grants, SMS default-app
            // handoff via root (no UI), per-item progress and cancel checks.
            // NOTE: the caller-side context must be the app context (prefs/
            // notifications need it); appContext below is only used for the
            // detached snapshot scope.
            runDeviceDataPhase(
                context, config, execution, updateProgress,
                attachAfterApps = engineApps.isNotEmpty(),
                interactive = trigger == com.phoenix.droidvault.models.TriggerType.MANUAL
            )
            // Durations were measured for THIS run's progress ownership; stamp
            // them before any later run can start (same-epoch guard below).
            val durations = com.phoenix.droidvault.utils.ProgressTracker.durationsSnapshot()
            val durationEpoch = com.phoenix.droidvault.utils.ProgressTracker.currentEpoch()
            // What the ENGINE actually did, per app — as opposed to what the PLAN
            // intended. `e` is a PlannedApp, a pure function of labels + rules +
            // capabilities, and it knows nothing about what ran. The per-app and
            // per-part outcome reaches ProgressTracker and nowhere else, and this
            // function never read it back, so a run where every app but five
            // succeeded recorded SUCCESS for all 63, the notification read
            // "63 ok, 0 failed", and History persisted a false durable record on the
            // UNATTENDED path where nobody watches the progress screen.
            //
            // Snapshot it here, while the tracker still owns this run.
            val engineFailures: Map<String, Boolean> =
                (com.phoenix.droidvault.utils.ProgressTracker.state.value
                        as? com.phoenix.droidvault.models.ProgressState.Done)
                    ?.apps?.associate { it.packageName to it.hasFailure }
                    ?: emptyMap()
            plan.entries.forEach { e ->
                execution.apps.add(
                    com.phoenix.droidvault.models.AppHistoryRecord(
                        packageName = e.packageName,
                        appName = e.label,
                        ruleId = e.ruleId,
                        requested = e.requested,
                        processed = e.granted,
                        result = when {
                            e.skipped -> ResultState.SKIPPED
                            // A verdict must cover the work, not the plan.
                            engineFailures[e.packageName] == true -> ResultState.FAILED
                            else -> ResultState.SUCCESS
                        },
                        reason = e.skipReason.ifEmpty {
                            val missing = e.unavailableReasons.values.distinct().joinToString("; ")
                            if (missing.isNotEmpty()) "unavailable: $missing" else ""
                        },
                        // 0 when this run wasn't tracked or the app never started.
                        durationMs = if (com.phoenix.droidvault.utils.ProgressTracker.sameEpoch(durationEpoch)) {
                            durations[e.packageName] ?: 0L
                        } else 0L,
                    )
                )
            }
            // Device-data failures (already recorded per item) must not be
            // upgraded to SUCCESS by the aggregate logic below.
            val deviceFailed = execution.apps.any { it.deviceItem != null && it.result == ResultState.FAILED }
            // Only SKIPS THAT SHORTCHANGE THE CONFIG make the run partial:
            // backing up an uninstalled labelled app, or restoring an app with
            // nothing on disk. "No matching rule" and "excluded by rule" are
            // the config's scope working as designed — a rule config that
            // covers 1 of 468 apps did exactly what it says and must report
            // SUCCESS (the old rule `skipped.isNotEmpty()` fired on all 467
            // scope skips and made every selective config partial).
            val shortfallSkips = plan.skipped.count { !BackupPlanner.isByDesignSkip(it.skipReason) }
            if (isCancelRequested() || execution.result == ResultState.CANCELLED) {
                // Never overstate: apps finished before the stop are kept on disk,
                // but per-app granularity is unavailable — report CANCELLED.
                execution.apps.forEachIndexed { i, r ->
                    if (r.result != ResultState.SKIPPED) {
                        execution.apps[i] = r.copy(
                            result = ResultState.CANCELLED,
                            reason = "cancelled by user"
                        )
                    }
                }
                execution.result = ResultState.CANCELLED
                execution.errorSummary = "Cancelled by user"
            } else if (execution.result != ResultState.FAILED) {
                execution.result = when {
                    deviceFailed || shortfallSkips > 0 || plan.withUnavailable.isNotEmpty() ->
                        ResultState.PARTIAL_SUCCESS
                    else -> ResultState.SUCCESS
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cooperative coroutine cancel (worker stopped / job cancelled):
            // record honestly, then rethrow so the host handles it.
            execution.result = ResultState.CANCELLED
            execution.errorSummary = "Cancelled"
            finishExecution(context, execution)
            throw e
        } catch (e: Exception) {
            execution.result = ResultState.FAILED
            execution.errorSummary = e.message ?: "failed"
        } finally {
            clearCancel()
        }
        // History FIRST: finishExecution is prefs-only and cannot wedge. The
        // snapshots below hit the shell, which may be dead (blocking exec on a
        // broken session never returns) — they must never trap completion.
        // (The SMS handback release above is the only intentional process-kill
        // point and it runs after this record — see the comment there.)
        DroidVaultLogger.i(
            "Automation",
            "Run '${execution.configName}' done: ${execution.result} " +
                "(ok=${execution.successCount} fail=${execution.failureCount} skip=${execution.skippedCount})" +
                (if (execution.errorSummary.isNotBlank()) " — " + execution.errorSummary.take(200) else "")
        )
        finishExecution(context, execution)
        // No SMS handback any more: nothing takes the default-SMS role (both
        // tabs run Messages through the root path), so there is no kill-prone
        // release after this point and no need to pre-empt the worker's own
        // terminal card.
        //
        // ROM-survival: mirror state + self APK + backend script into the
        // backup folder so a clean flash can restore everything. Kept AFTER
        // history is recorded so a snapshot failure can never lose the run —
        // the same ordering the old SMS handback forced, still the right one.
        // automation-state.json going stale on restore-only days was exactly
        // what this mirror exists to prevent.
        val appContext = context.applicationContext
        try {
            kotlinx.coroutines.withTimeoutOrNull(60000L) {
                val dest = resolveSavedPath(appContext)
                // automation-state.json is the PRIMARY store now: history/
                // markers aside, run bookkeeping does not change configs, so
                // the file is already current — sync defensively anyway.
                AutomationStore.syncPrimaryStateFile(appContext)
                AutomationSnapshot.snapshotSelfApk(appContext, dest)
                AutomationSnapshot.snapshotBackend(appContext, dest)
            } ?: android.util.Log.w("AutomationRunner", "snapshots timed out, skipped")
        } catch (_: Exception) {
        }
        return execution
    }

    /**
     * Device-data phase for one automation run (Messages/Calls/Contacts/
     * Wi-Fi/Wallpaper/Bluetooth). Reuses [DeviceManager.runOperation] —
     * the exact pipeline the Migrate tab drives — with automation-grade
     * orchestration: engine-freshness gate, SMS default-app handoff via
     * root (no user interaction; scheduled runs must never block on UI),
     * cooperative cancel checks between items, and one history record per
     * item. App-phase cancellations skip this phase entirely.
     */
    suspend fun runDeviceDataPhase(
        context: Context,
        config: BackupConfig,
        execution: ConfigExecution,
        updateProgress: (String, String, Int) -> Unit,
        /** True when an app phase precedes this one in the same run (attach to its live state). */
        attachAfterApps: Boolean,
        /** MANUAL runs auto-present the Processing screen; scheduled ones stay notification-linked. */
        interactive: Boolean
    ) {
        val items = config.deviceData.toList()
        if (items.isEmpty()) return
        if (execution.result == ResultState.CANCELLED) return
        if (isCancelRequested()) {
            execution.result = ResultState.CANCELLED
            execution.errorSummary = "Cancelled by user"
            return
        }
        // Engine gate: device-data Wi-Fi/wallpaper/BT shell out to
        // /data/adb/DroidVault/DroidVault.sh, so a stale engine is just as
        // wrong here as for app backups.
        if (!BackendInstaller.isEngineUpToDate(context)) {
            android.util.Log.w("AutomationRunner", "Engine stale — repair before device-data phase")
            if (!BackendInstaller.installEngine(context)) {
                execution.result = ResultState.FAILED
                execution.errorSummary = "Engine update required but could not be applied"
                items.forEach { item ->
                    execution.apps.add(
                        com.phoenix.droidvault.models.AppHistoryRecord(
                            packageName = "",
                            appName = item.label,
                            ruleId = null,
                            requested = emptySet(),
                            processed = emptySet(),
                            result = ResultState.FAILED,
                            reason = "engine out of date",
                            deviceItem = item
                        )
                    )
                }
                return
            }
        }
        val isBackup = config.operation == ConfigOperation.BACKUP
        val liveOp = if (isBackup) LiveOp.BACKUP else LiveOp.RESTORE
        val title = if (isBackup) "Backing up Device Data" else "Restoring Device Data"
        updateProgress(title, "Preparing…", 0)
        // Missing-archive guard FIRST (restore only): a scheduled restore with
        // no Messages.shift / Wallpaper.shift in the backup folder would
        // "restore" nothing and still report success. Checked through the ROOT
        // shell — java.io cannot see the backup dir under scoped storage.
        // Missing items are SKIPPED with an explicit reason, never silently
        // dropped. This runs BEFORE the SMS takeover: an all-missing run fails
        // here and must never perform the role dance at all (the platform
        // kills the process at role release — the old order turned an honest
        // FAILED into a mystery death with no history record).
        // Universal folder source (single source of truth): a config with
        // FOLDERS but no explicit selection follows the shared
        // [BackupFolderStore] list (Settings/Migrate/Automation all edit the
        // same one); an explicit selection is pruned against the store so a
        // folder removed in Settings stops running everywhere immediately.
        val folderPaths = resolveConfigFolders(config)
        var finalSet = config.deviceData
        if (isBackup && com.phoenix.droidvault.models.DeviceDataItem.FOLDERS in finalSet &&
            BackupConfigFolders.sanitize(folderPaths).isEmpty()
        ) {
            // A FOLDERS item with no folders picked would run, pack nothing,
            // and still report SUCCESS. Honest skip instead.
            execution.apps.add(
                com.phoenix.droidvault.models.AppHistoryRecord(
                    packageName = "",
                    appName = com.phoenix.droidvault.models.DeviceDataItem.FOLDERS.label,
                    ruleId = null,
                    requested = emptySet(),
                    processed = emptySet(),
                    result = ResultState.SKIPPED,
                    reason = "no folders selected in the configuration",
                    deviceItem = com.phoenix.droidvault.models.DeviceDataItem.FOLDERS
                )
            )
            finalSet = finalSet - com.phoenix.droidvault.models.DeviceDataItem.FOLDERS
            if (finalSet.isEmpty()) {
                execution.result = ResultState.FAILED
                execution.errorSummary = "Folders selected but no folder paths configured"
                updateProgress("Device Data Failed", execution.errorSummary, 100)
                return
            }
        }
        if (!isBackup) {
            val backupDir = resolveSavedPath(context) + "/Device"
            val missing = finalSet.filter { item ->
                val file = deviceBackupFileFor(item)
                val res = com.topjohnwu.superuser.Shell.cmd(
                    "[ -f ${ShellQuote.q("$backupDir/$file")} ] && echo y"
                ).exec()
                res.out.firstOrNull()?.trim() != "y"
            }
            if (missing.isNotEmpty()) {
                missing.forEach { item ->
                    execution.apps.add(
                        com.phoenix.droidvault.models.AppHistoryRecord(
                            packageName = "",
                            appName = item.label,
                            ruleId = null,
                            requested = emptySet(),
                            processed = emptySet(),
                            result = ResultState.SKIPPED,
                            reason = "no backup archive (${deviceBackupFileFor(item)}) in the backup folder",
                            deviceItem = item
                        )
                    )
                }
                if (missing.size == finalSet.size) {
                    execution.result = ResultState.FAILED
                    execution.errorSummary = "no device-data backups found in the backup folder"
                    updateProgress("Device Data Failed", execution.errorSummary, 100)
                    // execute()'s tail records the FAILED run.
                    return
                }
                finalSet = finalSet - missing.toSet()
            }
        }
        // NO default-SMS role dance here. The Migrate tab stopped taking the
        // role when Messages moved onto the root path (SmsViaRoot: a root-made
        // snapshot for reads, `content insert` as root for writes), and this
        // phase runs the SAME DeviceManager.runOperation — so it needs exactly
        // what Migrate needs: root, which runOperation already grants for it.
        // Keeping the takeover here was pure cost after that change: the phone
        // lost its real SMS app for the run, releasing the role revokes our
        // role-granted permissions and the platform kills this process for it,
        // and it forced the pre-run card, handback cards, durable pending
        // marker and startup heal that the manual path no longer has at all.
        // Root availability is now reported by the Messages item itself
        // (onItemFailure below) rather than by a role takeover.
        if (finalSet.isEmpty()) {
            updateProgress(title, "Nothing left to process", 100)
            return
        }
        // Live per-item progress: one pseudo app per item on the Processing
        // screen (attached after the app phase for mixed runs, fresh run for
        // device-only configs). When another live run owns the bus the phase
        // simply stays on the shade notification — never steals the screen.
        val deviceOwner = "dev-${System.nanoTime()}"
        val tracked = ProgressTracker.startDevicePhase(
            ownerId = deviceOwner,
            op = liveOp,
            itemLabels = finalSet.map { it.label },
            title = title,
            interactive = interactive,
            attach = attachAfterApps,
        )
        var lastTrackedItem: String? = null
        // Per-item failures reported BY the pipeline (Messages refused to read
        // its store, a backup produced no archive, …). Recorded per item below
        // so a scheduled run can never log a Messages item it did not finish as
        // a success — the same signal the Migrate tab now shows in its
        // completion message.
        val itemFailures = LinkedHashMap<String, String>()
        try {
            DeviceManager.runOperation(
                context = context,
                isBackup = isBackup,
                doSms = com.phoenix.droidvault.models.DeviceDataItem.MESSAGES in finalSet,
                doCall = com.phoenix.droidvault.models.DeviceDataItem.CALLS in finalSet,
                doContacts = com.phoenix.droidvault.models.DeviceDataItem.CONTACTS in finalSet,
                doWifi = com.phoenix.droidvault.models.DeviceDataItem.WIFI in finalSet,
                doWallpaper = com.phoenix.droidvault.models.DeviceDataItem.WALLPAPER in finalSet,
                doBluetooth = com.phoenix.droidvault.models.DeviceDataItem.BLUETOOTH in finalSet,
                savedPath = resolveSavedPath(context),
                doFolders = com.phoenix.droidvault.models.DeviceDataItem.FOLDERS in finalSet,
                folderPaths = folderPaths,
                onItemFailure = { label, reason -> itemFailures[label] = reason },
                onItem = if (tracked) { label, subLabel, prog, step ->
                    // Transition-based completion (mirrors the manual path):
                    // a different item starting means the previous one is
                    // finished — mark it done then, not in the end-of-run
                    // burst, so finished rings settle while the run continues.
                    if (lastTrackedItem != null && lastTrackedItem != label) {
                        ProgressTracker.deviceItemDone(lastTrackedItem!!)
                    }
                    lastTrackedItem = label
                    ProgressTracker.deviceItemStep(label, prog, step, subLabel)
                } else null,
                shouldCancel = { isCancelRequested() },
                // This runner already holds the shared gate (no re-entrancy).
                gateHeld = true
            ) { step, prog ->
                updateProgress(title, step, prog)
            }
        } catch (e: DeviceManager.DeviceDataCancelled) {
            execution.result = ResultState.CANCELLED
            execution.errorSummary = "Cancelled by user"
            val unprocessed = markUnprocessedDeviceItems(execution, finalSet, "cancelled by user")
            if (tracked) {
                unprocessed.forEach { ProgressTracker.deviceItemFailed(it.label, "cancelled by user") }
                ProgressTracker.complete("Operation Cancelled", "Stopped. Finished items are kept.")
                ProgressTracker.endRun(deviceOwner)
            }
            // No role was ever taken, so there is nothing to hand back.
            return
        } catch (e: Exception) {
            execution.result = ResultState.FAILED
            execution.errorSummary = e.message ?: "device data failed"
            val unprocessed = markUnprocessedDeviceItems(execution, finalSet, e.message ?: "failed")
            if (tracked) {
                unprocessed.forEach { ProgressTracker.deviceItemFailed(it.label, e.message ?: "failed") }
                ProgressTracker.fail("Device Data Failed", e.message ?: "device data failed")
                ProgressTracker.endRun(deviceOwner)
            }
            return
        }
        // Success: record each processed item. The final in-flight item is
        // marked here; earlier ones were completed transitionally in onItem.
        if (tracked) {
            lastTrackedItem?.let { ProgressTracker.deviceItemDone(it) }
            itemFailures.forEach { (label, reason) -> ProgressTracker.deviceItemFailed(label, reason) }
            ProgressTracker.complete(
                if (itemFailures.isEmpty()) "Completed" else "Completed with errors",
                if (itemFailures.isEmpty()) {
                    "Device data: ${finalSet.size} item${if (finalSet.size == 1) "" else "s"} ok"
                } else {
                    "Device data: ${finalSet.size - itemFailures.size} ok, " +
                        "${itemFailures.size} failed"
                }
            )
            ProgressTracker.endRun(deviceOwner)
        }
        finalSet.forEach { item ->
            execution.apps.add(deviceItemRecord(item, itemFailures[item.label]))
        }
        if (execution.result == ResultState.RUNNING) {
            execution.result = ResultState.SUCCESS
        }
        // execute() records history and runs the aggregates — a FAILED device
        // item above already turns the run into PARTIAL_SUCCESS there.
    }

    /**
     * Messaging candidates verified installed at least once this process —
     * keeps [defaultSmsTarget] off the root shell on repeat handoffs.
     */
    private val smsCandidateCache = mutableSetOf<String>()

    /**
     * One history record for a device-data item.
     *
     * [failureReason] is DeviceManager's onItemFailure — the SAME signal the
     * Migrate tab folds into its completion message — so a scheduled run and a
     * manual run describe an item identically. A reason means FAILED, never
     * SUCCESS: "restored Messages" while the store never changed is the exact
     * failure this whole path has had, so the rule is unit-tested.
     */
    internal fun deviceItemRecord(
        item: com.phoenix.droidvault.models.DeviceDataItem,
        failureReason: String?
    ): com.phoenix.droidvault.models.AppHistoryRecord =
        com.phoenix.droidvault.models.AppHistoryRecord(
            packageName = "",
            appName = item.label,
            ruleId = null,
            requested = emptySet(),
            processed = emptySet(),
            result = if (failureReason == null) ResultState.SUCCESS else ResultState.FAILED,
            reason = failureReason ?: "",
            deviceItem = item
        )

    /**
     * Messaging app a *pending* handback card resolves to.
     *
     * Nothing takes the default-SMS role any more — Migrate and this runner
     * both read and write Messages through the root path — so this is reached
     * only by a card left behind by an older build: the startup
     * healPendingSmsHandoff() marker and AutomationNotifications' Confirm
     * action. The first *installed* messaging app wins (skips GB of Google
     * sync setup on de-Googled ROMs); AOSP Messaging is the always-present
     * tail because the old Google fallback is a package de-Googled ROMs
     * legitimately lack (the stale setting on this device names it without it
     * being installed).
     */
    fun defaultSmsTarget(): String =
        pickSmsFallback(
            listOf(
                "com.google.android.apps.messaging",
                "com.android.messaging",
                "com.android.mms"
            )
        ) { it in smsCandidateCache || installedOnDevice(it) }

    /**
     * Pure decision core (unit-tested): a captured previous default is only
     * usable when it is not us, not blank, and actually INSTALLED — the role
     * setting can name a ghost package on de-Googled ROMs (verified in the
     * field), and handing the role to one would strand SMS with nobody able
     * to receive it.
     */
    internal fun sanitizeCapturedDefault(
        captured: String?,
        selfPkg: String,
        isInstalled: (String) -> Boolean
    ): String? = captured?.takeIf { it != selfPkg && it.isNotEmpty() && isInstalled(it) }

    /**
     * Pure decision core (unit-tested): first installed messaging candidate
     * wins; AOSP Messaging is the always-present tail because the old Google
     * fallback is a package de-Googled ROMs legitimately lack.
     */
    internal fun pickSmsFallback(
        candidates: List<String>,
        isInstalled: (String) -> Boolean
    ): String = candidates.firstOrNull(isInstalled) ?: "com.android.messaging"

    /** Root call per candidate, cached per process (role changes are rare). */
    private fun installedOnDevice(pkg: String): Boolean {
        val res = com.topjohnwu.superuser.Shell.cmd("pm path ${ShellQuote.q(pkg)}").exec()
        val found = res.out.isNotEmpty()
        if (found) smsCandidateCache.add(pkg)
        return found
    }

    /**
     * Universal folder source of truth for a config's FOLDERS item.
     *
     * - Explicit selection present → used as-is, but pruned to what still
     *   exists in the shared [BackupFolderStore] (a folder removed in
     *   Settings disappears from every config's next run — no stale cache).
     * - No explicit selection → the whole shared store list (so a folder
     *   added in Settings is picked up by every FOLDERS-enabled config
     *   immediately, without editing each config).
     */
    fun resolveConfigFolders(config: com.phoenix.droidvault.models.BackupConfig): List<String> {
        val store = BackupFolderStore.folders.value
        return BackupConfigFolders.resolveAgainstStore(config.folderPaths, store)
    }

    /** Archive file the Migrate-tab pipeline writes for one device item. */
    fun deviceBackupFileFor(item: com.phoenix.droidvault.models.DeviceDataItem): String = when (item) {
        com.phoenix.droidvault.models.DeviceDataItem.MESSAGES -> "Messages.shift"
        com.phoenix.droidvault.models.DeviceDataItem.CALLS -> "CallLogs.shift"
        com.phoenix.droidvault.models.DeviceDataItem.CONTACTS -> "Contacts.shift"
        com.phoenix.droidvault.models.DeviceDataItem.WIFI -> "Wi-Fi.shift"
        com.phoenix.droidvault.models.DeviceDataItem.WALLPAPER -> "Wallpaper.shift"
        com.phoenix.droidvault.models.DeviceDataItem.BLUETOOTH -> "Bluetooth.shift"
        com.phoenix.droidvault.models.DeviceDataItem.FOLDERS -> BackupConfigFolders.ARCHIVE_FILE
    }

    /**
     * Cache entry a device item's RESTORE must produce — the only honest
     * signal that the archive actually extracted. tar/zapdos fail quietly (and
     * zapdos exits 0 on empty input, verified on-device), so "the shell said
     * ok" proves nothing; the entry the item needs does. Directories for the
     * shell-driven items, files for the tar/ContentResolver ones.
     */
    fun deviceRestoreOutputFor(item: com.phoenix.droidvault.models.DeviceDataItem): String = when (item) {
        com.phoenix.droidvault.models.DeviceDataItem.MESSAGES -> "messages.json"
        com.phoenix.droidvault.models.DeviceDataItem.CALLS -> "calls.json"
        com.phoenix.droidvault.models.DeviceDataItem.CONTACTS -> "Contacts.vcf"
        com.phoenix.droidvault.models.DeviceDataItem.WIFI -> "Wi-Fi"
        com.phoenix.droidvault.models.DeviceDataItem.WALLPAPER -> "Wallpaper"
        com.phoenix.droidvault.models.DeviceDataItem.BLUETOOTH -> "Bluetooth"
        com.phoenix.droidvault.models.DeviceDataItem.FOLDERS -> "Folders"
    }

    /**
     * Why a restore of [item] came back empty: names BOTH the archive and the
     * entry that should have come out of it. Printed verbatim by the Migrate
     * tab and recorded verbatim in automation history, so one string keeps the
     * two tabs describing the same empty restore identically.
     */
    fun missingRestoreReason(item: com.phoenix.droidvault.models.DeviceDataItem): String =
        "no ${deviceRestoreOutputFor(item)} in ${deviceBackupFileFor(item)} (archive missing or unreadable)"

    fun markUnprocessedDeviceItems(
        execution: ConfigExecution,
        items: Set<com.phoenix.droidvault.models.DeviceDataItem>,
        reason: String
    ): List<com.phoenix.droidvault.models.DeviceDataItem> {
        val done = execution.apps.filter { it.deviceItem != null }.mapNotNull { it.deviceItem }.toSet()
        val unprocessed = items.filter { it !in done }
        unprocessed.forEach { item ->
            execution.apps.add(
                com.phoenix.droidvault.models.AppHistoryRecord(
                    packageName = "",
                    appName = item.label,
                    ruleId = null,
                    requested = emptySet(),
                    processed = emptySet(),
                    result = ResultState.FAILED,
                    reason = reason,
                    deviceItem = item
                )
            )
        }
        return unprocessed
    }

    /**
     * Start-of-run tombstone for MANUAL runs. Scheduled runs already write
     * one in ConfigRunWorker before the risky app-list fetch; a hard process
     * death mid-manual-run (toast a "Backup started" shade card and pull the
     * power cable) left NO history record at all — the run silently never
     * happened as far as History was concerned. This parity record is
     * superseded by the real execution on every completion path
     * ([finishExecution] removes it), so only a genuinely dead process keeps
     * it as an honest FAILED "interrupted" entry.
     */
    fun manualTombstone(config: BackupConfig, trigger: TriggerType): ConfigExecution {
        val t = ConfigExecution(
            id = AutomationStore.newId(),
            configId = config.id,
            configName = config.name,
            trigger = trigger,
            operation = config.operation,
            result = ResultState.FAILED,
            errorSummary = "interrupted mid-run (process died)"
        )
        t.finishedAt = System.currentTimeMillis()
        return t
    }

    private fun finishExecution(context: Context, execution: ConfigExecution) {
        execution.finishedAt = System.currentTimeMillis()
        try {
            AutomationStore.recordExecution(context, execution)
            // Every terminal record supersedes this config's start-of-run
            // tombstone and durably marks the config completed (both must be
            // on disk BEFORE the kill-prone SMS release below). Cleanup lives
            // here — not in the worker's tail — for exactly that reason.
            AutomationStore.removeInterruptedTombstones(context, execution.configId)
            AutomationStore.markConfigCompleted(context, execution.configId, execution.finishedAt)
        } catch (_: Exception) {
        }
    }
}
