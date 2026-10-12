package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppSeed
import com.phoenix.droidvault.models.LiveOp
import com.phoenix.droidvault.models.PartState
import com.phoenix.droidvault.models.Parts
import com.phoenix.droidvault.models.ProgressReducer
import com.phoenix.droidvault.models.ProgressState
import com.phoenix.droidvault.models.EngineEvent
import com.phoenix.droidvault.models.TrackerEvent
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * App-wide live progress bus for backup/restore runs.
 *
 * Singleton by design: the Migrate tab, the Automation tab, and the
 * headless [ConfigRunWorker] all run the same backend, and the Processing
 * screen must render whichever is live. The run owner must call [beginRun]
 * and feed events via [onEngineEvent]/[prepare]/[complete]/[fail], and clear
 * ownership in [endRun].
 */
object ProgressTracker {

    private val _state = MutableStateFlow<ProgressState>(ProgressState.Idle)
    val state: StateFlow<ProgressState> = _state.asStateFlow()

    @Volatile
    private var owner: String? = null

    /** Monotonic id incremented at each [beginRun]; screens key animations on it. */
    @Volatile
    var runId: Long = 0
        private set

    private val lock = Any()

    /** Byte-progress poller cadence. */
    private const val POLL_INTERVAL_MS = 500L
    /** Root `du` on extraction dirs runs every Nth poll tick (2s), not per tick. */
    private const val DIR_POLL_EVERY_TICKS = 4

    /** Byte-progress poller for backup/restore runs (fills the running part smoothly). */
    private var pollScope: CoroutineScope? = null
    private var watchFiles: Map<String, Map<String, List<String>>> = emptyMap()
    private var watchDirs: Map<String, Map<String, List<String>>> = emptyMap()
    private var partExpectedKb: Map<String, Map<String, Long>> = emptyMap()

    @Volatile
    private var currentWatchPkg: String? = null

    /**
     * True once any part in this run reported state 4 (failed).
     *
     * Found on device: two apps had a red cross beside them — "Backup Media/OBB
     * bundle failed" — while the headline read "Completed / Finished (exit
     * confirmed)". Both statements were individually true and together they
     * lied: the exit status only says the ENGINE exited cleanly, not that the
     * WORK did. Reconciled at [BackendExited]/[GlobalDone] so a run that wrote
     * most of what it was asked to, and failed the rest, says so instead of
     * claiming unqualified success.
     */
    @Volatile
    private var sawPartFailure = false

    /**
     * Per-app wall-clock durations for the CURRENT run (first AppStarted →
     * AppDone/terminal). Consumed by AutomationRunner to stamp
     * [com.phoenix.droidvault.models.AppHistoryRecord.durationMs]; cleared on
     * every ownership change so stale durations never leak into the next run.
     */
    private val durationByPkg = HashMap<String, Long>()

    /** Package -> epoch ms its first AppStarted arrived (this run only). */
    private val startedAtByPkg = HashMap<String, Long>()

    /** Ownership stamp: changes whenever a run begins or releases the tracker. */
    private var epoch: Long = 0

    /** Only when nobody owns the tracker (idle or terminal) does a new run take it. */
    fun beginRun(ownerId: String, event: TrackerEvent.Start): Boolean = synchronized(lock) {
        val s = _state.value
        val busy = owner != null && s is ProgressState.Running
        if (busy) return false
        owner = ownerId
        runId += 1
        epoch += 1
        // Per-run, or the NEXT run inherits this one's verdict and a clean
        // backup is reported as "Partially completed" forever after.
        sawPartFailure = false
        durationByPkg.clear()
        startedAtByPkg.clear()
        watchFiles = event.apps.associate { it.packageName to it.watchFiles }
        watchDirs = event.apps.associate { it.packageName to it.watchDirs }
        partExpectedKb = event.apps.associate { it.packageName to it.partExpectedKb }
        _state.value = ProgressReducer.reduce(s, event)
        true
    }

    fun onEvent(event: TrackerEvent) {
        DroidVaultLogger.v("Progress", "tracker: $event")
        synchronized(lock) {
            if (owner == null && event !is TrackerEvent.Completed && event !is TrackerEvent.Failed) return
            // Duration bookkeeping runs before the reduce so AppDone measurement
            // uses the same tick that settles the app.
            recordDuration(event)
            _state.value = ProgressReducer.reduce(_state.value, event)
        }
    }

    private fun recordDuration(event: TrackerEvent) {
        when (event) {
            is TrackerEvent.AppStarted ->
                if (event.pkg.isNotEmpty()) startedAtByPkg.putIfAbsent(event.pkg, System.currentTimeMillis())
            is TrackerEvent.AppDone -> settleDuration(event.pkg)
            // A failed part is terminal for the app in practice; keep the record
            // honest instead of dropping the app's duration on failure.
            is TrackerEvent.Part -> if (event.state == PartState.FAILED) settleDuration(event.pkg)
            // Global terminal: everything started but never AppDone'd (crashed
            // backend / cancel) keeps the elapsed it actually had.
            is TrackerEvent.Completed, is TrackerEvent.Failed ->
                startedAtByPkg.keys.toList().forEach { settleDuration(it) }
            else -> Unit
        }
    }

    private fun settleDuration(pkg: String) {
        val startedAt = startedAtByPkg[pkg] ?: return
        startedAtByPkg.remove(pkg)
        val prev = durationByPkg[pkg] ?: 0L
        if (prev == 0L) {
            durationByPkg[pkg] = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
        }
    }

    /** Translate a parsed backend event into tracker events. Safe on any thread. */
    fun onEngineEvent(event: EngineEvent, op: LiveOp) {
        DroidVaultLogger.v("Progress", "engine: $event")
        when (event) {
            is EngineEvent.BackupProgress -> {
                if (event.total > 0) {
                    currentWatchPkg = event.pkg.ifEmpty { currentWatchPkg }
                    onEvent(
                        TrackerEvent.AppStarted(
                            pkg = event.pkg.ifEmpty { event.label },
                            label = event.label,
                            cur = event.current,
                            total = event.total,
                            pct = event.percent,
                            size = event.size,
                        )
                    )
                } else {
                    onEvent(TrackerEvent.Step(event.label.ifEmpty { event.size }))
                }
            }

            is EngineEvent.InfoStep -> {
                val msg = stripJobs(event.msg)
                when {
                    // Restore's pre-install pass: the engine names the app it
                    // is installing. Mark THAT app's Install part RUNNING and
                    // keep the human label as the subtitle — a per-app event
                    // instead of the old unnamed step that left every queued
                    // row looking busy at once (field report: "all rows
                    // spinner, counter stuck at 0/63").
                    msg.startsWith("Installing ") && event.pkg.isNotEmpty() -> {
                        onEvent(TrackerEvent.Part(event.pkg, Parts.INSTALL, PartState.RUNNING))
                        onEvent(TrackerEvent.Step(msg))
                    }
                    msg == "INSTALLING" && event.pkg.isNotEmpty() ->
                        onEvent(TrackerEvent.Part(event.pkg, Parts.INSTALL, PartState.RUNNING))
                    else -> onEvent(TrackerEvent.Step(msg))
                }
            }

            is EngineEvent.InstallDone -> onEvent(TrackerEvent.InstallDone(event.pkg))
            is EngineEvent.InstallError -> onEvent(TrackerEvent.InstallError(event.pkg, event.msg))

            // Per-app terminal events from the backend: THE source of truth for
            // which app actually finished (fixes hero/list app mismatch).
            is EngineEvent.AppDoneEvent -> onEvent(TrackerEvent.AppDone(event.pkg))

            is EngineEvent.PartEvent -> {
                // Defensive: only parts the app was SEEDED with get chips. The
                // engine never emits these for unseeded parts anymore (per-app
                // TARGETS comps carry selection ∩ availability ∩ consent), but
                // a stale engine or future emitter must not fabricate phantom
                // parts into the completed card either.
                val seeded = (_state.value as? ProgressState.Running)?.apps
                    ?.firstOrNull { it.packageName == event.pkg }?.parts
                if (seeded != null && seeded.none { it.key == event.part }) {
                    DroidVaultLogger.v(
                        "Progress", "dropping event for unseeded part ${event.part} (${event.pkg})"
                    )
                } else {
                    onEvent(
                        TrackerEvent.Part(
                            pkg = event.pkg,
                            part = event.part,
                            state = when (event.state) {
                                2 -> PartState.DONE
                                3 -> PartState.SKIPPED
                                4 -> {
                                    sawPartFailure = true
                                    PartState.FAILED
                                }
                                1 -> PartState.RUNNING
                                else -> PartState.PENDING
                            },
                            reason = event.reason,
                        )
                    )
                }
            }

            is EngineEvent.Error -> onEvent(TrackerEvent.Failed("Operation Failed", event.msg))
            is EngineEvent.Cancelled -> onEvent(
                TrackerEvent.Completed(
                    title = "Operation Cancelled",
                    subtitle = "Stopped. Finished apps are kept; the rest were not backed up.",
                    cancelled = true
                )
            )

            is EngineEvent.GlobalDone -> onEvent(
                if (sawPartFailure) {
                    TrackerEvent.Completed(
                        "Partially completed",
                        "Some items failed \u2014 open a run to see which" +
                                if (event.totalKb.toLongOrNull() != 0L) {
                                    " (${formatDone(event.totalKb, event.timeSec)})"
                                } else {
                                    ""
                                }
                    )
                } else {
                    TrackerEvent.Completed("Completed", formatDone(event.totalKb, event.timeSec))
                }
            )

            // A clean engine exit is not a clean run. Say which of the two it was.
            is EngineEvent.BackendExited -> if (event.success) {
                onEvent(
                    if (sawPartFailure) {
                        TrackerEvent.Completed(
                            "Partially completed",
                            "Finished, but some items failed \u2014 expand a red item for the reason"
                        )
                    } else {
                        TrackerEvent.Completed("Completed", "Finished (exit confirmed)")
                    }
                )
            } else {
                onEvent(TrackerEvent.Failed("Operation Failed", "backend exited abnormally"))
            }

            is EngineEvent.FetchDone,
            is EngineEvent.RawLog -> Unit
        }
    }

    private fun stripJobs(msg: String): String = msg.substringBefore("|JOBS:")

    private fun formatDone(totalKb: String, timeSec: String): String {
        val kb = totalKb.toLongOrNull() ?: 0L
        val size = when {
            kb >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f GB", kb / 1_048_576.0)
            kb >= 1024 -> String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
            else -> "$kb KB"
        }
        val sec = timeSec.toIntOrNull() ?: 0
        val time = if (sec >= 60) "${sec / 60}m ${sec % 60}s" else "${sec}s"
        return "Total Size: $size | Time: $time"
    }

    // ----- Run-owner helpers (called from the run coroutines) -----

    fun startRun(
        ownerId: String,
        op: LiveOp,
        seeds: List<AppSeed>,
        title: String,
        interactive: Boolean = true,
    ): Boolean {
        val ok = beginRun(ownerId, TrackerEvent.Start(op, seeds, title, interactive))
        if (ok && op == LiveOp.BACKUP) startPolling()
        return ok
    }

    /**
     * Seed the tracker for the device-data phase.
     *
     * attach=true (mixed run: apps + device data): the app phase already put
     * the tracker in Running/Done with the app list — append one pseudo app
     * per device item AFTER the apps and re-enter Running. The finished-app
     * summary is never wiped.
     * attach=false (device-only run): start a fresh run from Idle unless
     * another run is genuinely still in flight (then refuse: the phase runs
     * untracked and the notification keeps its own progress).
     *
     * One pseudo app per item, single part keyed by the item label — the
     * Processing screen renders it exactly like an app with one ring segment.
     */
    fun startDevicePhase(
        ownerId: String,
        op: LiveOp,
        itemLabels: List<String>,
        title: String,
        interactive: Boolean = true,
        attach: Boolean,
    ): Boolean = synchronized(lock) {
        if (itemLabels.isEmpty()) return true
        val s = _state.value
        val seeds = itemLabels.map { lbl ->
            AppSeed(
                packageName = "device:$lbl", label = lbl,
                isSystem = false, iconPath = null, parts = listOf(lbl)
            )
        }
        if (attach) {
            val prevApps = when (s) {
                is ProgressState.Running -> s.apps
                is ProgressState.Done -> s.apps
                is ProgressState.Failed -> s.apps
                else -> emptyList()
            }
            // Refuse only when a live run still has unfinished apps (a foreign
            // run owns the bus); our own app phase ends settled.
            if (s is ProgressState.Running && owner != null && prevApps.any { !it.done }) return false
            owner = ownerId
            val appStates = (prevApps.map { it.copy(done = true) } +
                    seeds.filter { seed -> prevApps.none { it.packageName == seed.packageName } }
                        .map {
                            com.phoenix.droidvault.models.AppProgress(
                                packageName = it.packageName, label = it.label,
                                parts = it.parts.map { p ->
                                    com.phoenix.droidvault.models.PartProgress(p)
                                }
                            )
                        })
            _state.value = ProgressState.Running(
                op = op,
                title = title,
                phase = com.phoenix.droidvault.models.RunPhase.PROCESSING,
                subtitle = "Preparing…",
                overallPct = 0,
                currentIndex = appStates.indexOfFirst { !it.done }.coerceAtLeast(0),
                apps = appStates,
                interactive = (s as? ProgressState.Running)?.interactive ?: interactive,
                startedAtMs = (s as? ProgressState.Running)?.startedAtMs ?: System.currentTimeMillis(),
            )
            true
        } else {
            val foreignRun = s is ProgressState.Running && owner != null
            if (foreignRun) return false
            owner = ownerId
            runId += 1
            epoch += 1
            durationByPkg.clear()
            startedAtByPkg.clear()
            watchFiles = emptyMap()
            watchDirs = emptyMap()
            partExpectedKb = emptyMap()
            _state.value = ProgressReducer.reduce(
                ProgressState.Idle,
                TrackerEvent.Start(op, seeds, title, interactive)
            )
            true
        }
    }

    fun devicePrepare(message: String, pct: Int) = onEvent(TrackerEvent.Prepare(message, pct))

    fun deviceItemStep(itemLabel: String, progress: Int, step: String, subLabel: String? = null) {
        // DeviceManager's Folders item reports the SPECIFIC folder in flight
        // via [subLabel] ("Android/media/com.whatsapp"). Show that folder in
        // the hero/subtitle while the canonical "device:Folders" pseudo app
        // keeps receiving the progress — never a phantom app per folder.
        val pkg = "device:$itemLabel"
        val display = subLabel?.takeIf { it.isNotBlank() } ?: itemLabel
        // AppStarted first: moves currentIndex (hero) and writes the subtitle.
        // cur/total come from the live device-pseudo-app list.
        val s = _state.value as? ProgressState.Running
        val idx = s?.apps?.indexOfFirst { it.packageName == pkg } ?: -1
        if (s != null && idx >= 0) {
            onEvent(
                TrackerEvent.AppStarted(
                    pkg = pkg, label = display,
                    cur = idx + 1, total = s.apps.size,
                    pct = progress.coerceIn(1, 99), size = ""
                )
            )
        }
        onEvent(TrackerEvent.Part(pkg, itemLabel, PartState.RUNNING, progress = progress.coerceIn(1, 99)))
        onEvent(TrackerEvent.Step(step))
    }

    /**
     * A folder sub-label overwrote the pseudo app's display label while the
     * item ran; before marking the item terminal, put the item name back so
     * the finished summary reads "Folders ✓", not the last folder path.
     */
    private fun resetDeviceLabel(itemLabel: String) {
        val s = _state.value as? ProgressState.Running ?: return
        val pkg = "device:$itemLabel"
        val app = s.apps.firstOrNull { it.packageName == pkg } ?: return
        if (app.label == itemLabel) return
        val pct = app.parts.firstOrNull()?.progress?.coerceIn(1, 99) ?: 1
        onEvent(
            TrackerEvent.AppStarted(
                pkg = pkg, label = itemLabel,
                cur = s.apps.indexOfFirst { it.packageName == pkg } + 1,
                total = s.apps.size, pct = pct, size = ""
            )
        )
    }

    fun deviceItemDone(itemLabel: String) {
        resetDeviceLabel(itemLabel)
        onEvent(TrackerEvent.Part("device:$itemLabel", itemLabel, PartState.DONE))
        onEvent(TrackerEvent.AppDone("device:$itemLabel"))
    }

    fun deviceItemFailed(itemLabel: String, reason: String) {
        resetDeviceLabel(itemLabel)
        onEvent(TrackerEvent.Part("device:$itemLabel", itemLabel, PartState.FAILED, reason = reason))
        onEvent(TrackerEvent.AppDone("device:$itemLabel"))
    }

    fun prepare(message: String, pct: Int) = onEvent(TrackerEvent.Prepare(message, pct))

    fun complete(title: String, subtitle: String) {
        stopPolling()
        onEvent(TrackerEvent.Completed(title, subtitle))
    }

    fun fail(title: String, message: String) {
        stopPolling()
        onEvent(TrackerEvent.Failed(title, message))
    }

    fun cancelRequested() = onEvent(TrackerEvent.CancelRequested)

    /** Release ownership (call in the run's finally). Terminal states are kept for display. */
    fun endRun(ownerId: String) = synchronized(lock) {
        stopPolling()
        // Late stragglers (AppDone never arrived) still get their elapsed time.
        // The epoch intentionally does NOT advance: the run owner snapshots its
        // durations right after this returns, and they stay valid until the
        // next beginRun/dismiss clears them.
        startedAtByPkg.keys.toList().forEach { settleDuration(it) }
        if (owner == ownerId) owner = null
    }

    /**
     * Copy of this run's per-app durations (pkg -> ms; absent = unknown).
     * Safe to call after [endRun] — the map survives until the NEXT [beginRun],
     * so cancelled runs keep the durations of apps that did finish.
     */
    fun durationsSnapshot(): Map<String, Long> = synchronized(lock) { durationByPkg.toMap() }

    /** True while [durationsSnapshot] still belongs to the run that claimed [stamp]. */
    fun sameEpoch(stamp: Long): Boolean = synchronized(lock) { stamp == epoch }

    /** Stamp to pair with [sameEpoch] around a run. */
    fun currentEpoch(): Long = synchronized(lock) { epoch }

    /** Peek whether a run currently owns the tracker (screens use it to auto-navigate). */
    fun isRunning(): Boolean = _state.value is ProgressState.Running

    /** User dismissed the Processing screen (Finish). Only allowed when not Running. */
    fun dismiss() = synchronized(lock) {
        if (_state.value !is ProgressState.Running) {
            owner = null
            epoch += 1
            startedAtByPkg.clear()
            _state.value = ProgressState.Idle
        }
    }

    // ----- Byte-level progress polling -----

    /**
     * While a run is active, poll the CURRENT app's candidates and translate
     * on-disk growth into RUNNING-part fill 1..99.
     *
     * Backup watches the growing .shift outputs; RESTORE watches the
     * extraction target dirs under /data (added for progress parity — a
     * 15 GB extraction used to sit on a motionless spinner while backup at
     * least had engine part events).
     *
     * Measurement: java.io first (some ROMs expose the vault), then ONE
     * batched root `du -sk` for everything java.io cannot see — the app
     * declares no storage permission, so vault paths and /data paths are
     * invisible to File.exists() (the old watcher silently no-op'd on
     * scoped-storage ROMs for exactly that reason).
     */
    private fun startPolling() {
        stopPolling()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pollScope = scope
        scope.launch {
            var tick = 0
            while (true) {
                delay(POLL_INTERVAL_MS)
                tick++
                val pkg = currentWatchPkg ?: continue
                val runningState = _state.value as? ProgressState.Running ?: continue
                val app = runningState.apps.firstOrNull { it.packageName == pkg } ?: continue
                val files = watchFiles[pkg].orEmpty()
                val dirs = watchDirs[pkg].orEmpty()
                if (files.isEmpty() && dirs.isEmpty()) continue
                // Dirs every DIR_POLL_EVERY_TICKS: a du of a multi-GB
                // extraction target walks the whole tree — not ten times a
                // second.
                val dirTick = tick % DIR_POLL_EVERY_TICKS == 0
                // Collect every part's unmeasurable paths FIRST, then issue a
                // SINGLE root `du` for the whole tick. The batching was only
                // ever within one part, so a run with N running parts forked N
                // processes every 500 ms — and because libsu serialises jobs per
                // session, each fork also delayed the backup's own stdout.
                val pending = ArrayList<PartSample>()
                val allUnresolved = ArrayList<String>()
                for (part in app.parts) {
                    if (part.state != PartState.RUNNING) continue
                    val fileCandidates = files[part.key].orEmpty()
                    val dirCandidates = if (dirTick) dirs[part.key].orEmpty() else emptyList()
                    if (fileCandidates.isEmpty() && dirCandidates.isEmpty()) continue
                    var writtenKb = 0L
                    val unresolved = mutableListOf<String>()
                    for (f in fileCandidates) {
                        try {
                            val file = java.io.File(f)
                            if (file.exists()) writtenKb += file.length() / 1024
                            else unresolved.add(f)
                        } catch (_: Exception) {
                            unresolved.add(f)
                        }
                    }
                    for (d in dirCandidates) unresolved.add(d)
                    // The previously-reported percentage rides along so the
                    // compare below still uses the value the reducer last
                    // accepted, not a stale re-read.
                    pending.add(PartSample(part.key, unresolved, writtenKb, part.progress))
                    allUnresolved.addAll(unresolved)
                }
                if (pending.isEmpty()) continue
                val sizes = if (allUnresolved.isEmpty()) {
                    emptyMap()
                } else {
                    measureRootKb(allUnresolved)
                }
                for ((partKey, unresolved, javaKb, prevPct) in pending) {
                    var writtenKb = javaKb
                    for (p in unresolved) writtenKb += sizes[p] ?: 0L
                    val expected = partExpectedKb[pkg]?.get(partKey) ?: 0L
                    val pct = if (expected > 0) {
                        ((writtenKb * 100) / expected).toInt().coerceIn(1, 99)
                    } else {
                        1
                    }
                    if (pct > prevPct) {
                        onEvent(TrackerEvent.Part(pkg, partKey, PartState.RUNNING, progress = pct))
                    }
                }
            }
        }
    }

    /** One part's measurement work for a single poll tick. */
    private data class PartSample(
        val key: String,
        val unresolved: List<String>,
        val javaKb: Long,
        val prevPct: Int
    )

    /** One batched root `du -sk` for everything java.io cannot see. */
    private fun measureRootKb(paths: List<String>): Map<String, Long> {
        return try {
            val cmd = buildString {
                append("du -sk")
                for (p in paths) {
                    if (p.isNotBlank()) append(' ').append(ShellQuote.q(p))
                }
                append(" 2>/dev/null")
            }
            parseDuKb(Shell.cmd(cmd).exec().out)
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun stopPolling() {
        pollScope?.let { scope ->
            try {
                scope.cancel()
            } catch (_: Exception) {
            }
        }
        pollScope = null
        currentWatchPkg = null
    }
}

/**
 * Pure parser for `du -sk` output lines ("<kb><ws><path>") — path -> KB.
 * Toybox du separates with whitespace; tolerate both tab and spaces.
 */
internal fun parseDuKb(lines: List<String>): Map<String, Long> {
    val map = HashMap<String, Long>()
    for (line in lines) {
        val t = line.trim()
        if (t.isEmpty()) continue
        val ws = t.indexOfFirst { it.isWhitespace() }
        if (ws <= 0) continue
        val kb = t.substring(0, ws).toLongOrNull() ?: continue
        val path = t.substring(ws + 1).trim()
        if (path.isNotEmpty()) map[path] = kb
    }
    return map
}
