package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * Live progress model for the Processing screen (backup/restore runs).
 *
 * Pure Kotlin (no Android imports) so the reducer is unit-testable.
 * [ProgressReducer] is the single state machine; [com.phoenix.droidvault.utils.ProgressTracker]
 * feeds it shell events from any run path (Migrate tab or Automation).
 */

enum class LiveOp { BACKUP, RESTORE }

enum class PartState { PENDING, RUNNING, DONE, SKIPPED, FAILED }

enum class RunPhase { PREPARING, PROCESSING, FINALIZING }

/** Canonical part keys shared with the backend (DroidVault.sh PART: field). */
object Parts {
    const val APP = "App"
    const val DATA = "Data"
    const val MEDIA = "Media"
    const val SSAID = "AndID"
    const val PERM = "Perm"
    const val INSTALL = "Install"

    /** All parts in canonical display order. */
    val ALL = listOf(APP, DATA, PERM, SSAID, MEDIA, INSTALL)

    /** Human label for a part, op-aware ("Backup App" / "Restore Data" / "Install APK"). */
    fun displayName(op: LiveOp, part: String): String = when (part) {
        INSTALL -> if (op == LiveOp.RESTORE) "Install APK" else "Install"
        else -> (if (op == LiveOp.BACKUP) "Backup " else "Restore ") + when (part) {
            APP -> "APK"
            DATA -> "Data"
            MEDIA -> "Media/OBB"
            SSAID -> "Android ID"
            PERM -> "Permissions"
            else -> part
        }
    }
}

/**
 * Restore seed policy: the per-app part list is the user's selection
 * INTERSECTED with what the backup actually contains. The engine
 * require_part-skips missing artifacts at runtime, so seeding unavailable
 * parts produced "5/5 parts" with grayed "no X in backup" chips on APK-only
 * restores (user report 2026-10-05). Backups seed the raw selection — the
 * engine creates exactly what it runs.
 */
object SeedPolicy {
    fun restoreComponents(selected: Set<Int>, availableInBackup: Set<Int>): Set<Int> =
        if (availableInBackup.isEmpty()) selected else selected intersect availableInBackup
}

@Immutable
data class PartProgress(
    val key: String,
    val state: PartState = PartState.PENDING,
    val reason: String = "",
    /** 0..100 byte-level fill while RUNNING (polled); 100 implies DONE. */
    val progress: Int = 0,
    /**
     * How many bytes this part is expected to write, in KB. 0 when unknown.
     *
     * Without this, an app's byte progress is the MEAN of its parts' percentages
     * — so an app whose size is 13.6 GB in one part and 2 MB across four others
     * reports itself ~80% done the moment the small parts land, having written
     * almost nothing. That is what "24.6 GB done" was showing while a 13.6 GB
     * media copy had barely started.
     */
    val expectedKb: Long = 0,
)

@Immutable
data class AppProgress(
    val packageName: String,
    val label: String,
    val isSystem: Boolean = false,
    val iconPath: String? = null,
    val parts: List<PartProgress> = emptyList(),
    val done: Boolean = false,
    /** Total bytes this app contributes (APK+data+media), for size display/ETA. */
    val sizeKb: Long = 0,
) {
    /** Terminal parts (done/skipped/failed) — matches the reference "6/6" counting. */
    val doneCount: Int
        get() = parts.count {
            it.state == PartState.DONE || it.state == PartState.SKIPPED || it.state == PartState.FAILED
        }

    /** Ready when every part reached a terminal state. */
    val isSettled: Boolean
        get() = parts.all {
            it.state == PartState.DONE || it.state == PartState.SKIPPED || it.state == PartState.FAILED
        }

    val hasFailure: Boolean get() = parts.any { it.state == PartState.FAILED }

    /**
     * Bytes this app has actually written, as far as it can be known.
     *
     * Terminal parts count in full (FAILED too — the bytes were spent), a running
     * part counts its polled fraction, and a pending part counts nothing. Purely
     * a function of the parts, so it is testable without a device.
     */
    val doneKb: Long
        get() = parts.sumOf {
            when (it.state) {
                PartState.DONE, PartState.SKIPPED, PartState.FAILED -> it.expectedKb
                PartState.RUNNING -> it.expectedKb * it.progress.coerceIn(0, 99) / 100
                PartState.PENDING -> 0L
            }
        }

    /**
     * Bytes this app is expected to write in total.
     *
     * Falls back to [sizeKb] when no part reported a size, so an app measured
     * only as a whole still contributes — and never reports zero for an app the
     * user can plainly see has a size.
     */
    val expectedKb: Long
        get() {
            val sum = parts.sumOf { it.expectedKb }
            return if (sum > 0L) sum else sizeKb
        }

    /** Byte-level fill 0..100 across all parts (RUNNING parts use their polled progress). */
    val byteProgress: Int
        get() = if (parts.isEmpty()) 0 else parts.sumOf {
            when (it.state) {
                PartState.DONE -> 100
                PartState.SKIPPED -> 100
                PartState.FAILED -> 100
                PartState.RUNNING -> it.progress.coerceIn(0, 99)
                PartState.PENDING -> 0
            }
        } / parts.size

    /**
     * Live summary for one row. NOT-STARTED apps must read as PENDING: the
     * field report ("all app rows spinner, counter stuck at 0/63") came from
     * the old `else -> RUNNING` fallback, which painted every untouched queue
     * entry as if it were actively working. A row is only RUNNING when at
     * least one of its parts actually is.
     */
    val summaryState: PartState
        get() = when {
            parts.any { it.state == PartState.FAILED } -> PartState.FAILED
            parts.isEmpty() -> PartState.PENDING
            // All-skipped must win over settled-done, or skipped device items
            // (and gracefully-skipped app parts) render as succeeded.
            parts.all { it.state == PartState.SKIPPED } -> PartState.SKIPPED
            // Every part terminal = the row is finished (AppDone normally
            // flags this; the part states alone are enough to paint it done).
            isSettled -> PartState.DONE
            // Only an actually-running part makes a row spin. A row whose
            // install finished while the data phase has not reached it yet
            // ([Install DONE, Data PENDING]) must read as queued, NOT active —
            // that stale-spinner class of bug is exactly what the field
            // report showed for the whole queue.
            parts.any { it.state == PartState.RUNNING } -> PartState.RUNNING
            else -> PartState.PENDING
        }
}

/** Seed passed at run start: one entry per app the backend will process. */
@Immutable
data class AppSeed(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val iconPath: String?,
    val parts: List<String> = emptyList(),
    val sizeKb: Long = 0,
    /** Backup runs only: part key -> candidate .shift files to poll for byte progress. */
    val watchFiles: Map<String, List<String>> = emptyMap(),
    /**
     * Restore runs only: part key -> extraction TARGET directories whose
     * growing size is the progress signal. These live under /data (root-only),
     * so the tracker measures them with a batched root `du -sk` per tick —
     * java.io cannot even stat them.
     */
    val watchDirs: Map<String, List<String>> = emptyMap(),
    /** Backup runs only: expected KB per part (sizes from the package manager). */
    val partExpectedKb: Map<String, Long> = emptyMap(),
)

/** Neutral events the reducer understands (translated from EngineEvent by the tracker). */
sealed class TrackerEvent {
    @Immutable
    data class Start(
        val op: LiveOp,
        val apps: List<AppSeed>,
        val title: String,
        /** True when the user invoked this run from the UI (drives auto-present). */
        val interactive: Boolean = true,
        val startedAtMs: Long = System.currentTimeMillis(),
    ) : TrackerEvent()

    @Immutable
    data class Prepare(val message: String, val pct: Int) : TrackerEvent()

    @Immutable
    data class AppStarted(
        val pkg: String,
        val label: String,
        val cur: Int,
        val total: Int,
        val pct: Int,
        val size: String,
    ) : TrackerEvent()

    @Immutable
    data class Part(
        val pkg: String,
        val part: String,
        val state: PartState,
        val reason: String = "",
        val progress: Int = 0,
    ) : TrackerEvent()    @Immutable
    data class AppDone(val pkg: String) : TrackerEvent()

    @Immutable
    data class Step(val message: String) : TrackerEvent()

    @Immutable
    data class InstallDone(val pkg: String) : TrackerEvent()

    @Immutable
    data class InstallError(val pkg: String, val msg: String) : TrackerEvent()

    @Immutable
    /**
     * [cancelled] means the run stopped early at the USER'S request, not that it
     * finished. It matters because [settleApp] freezes every queued app's dangling
     * PENDING/RUNNING parts as DONE — correct when the engine really drained the
     * queue, and a lie when it did not.
     *
     * Before this flag existed, cancelling a 63-app backup after 5 rendered 63
     * green ticks reading "6/6 parts" under a headline that said "Operation
     * Cancelled". The list contradicted the headline, and the list is what a user
     * scans to decide whether they still need to run the backup.
     */
    data class Completed(
        val title: String,
        val subtitle: String,
        val cancelled: Boolean = false
    ) : TrackerEvent()

    @Immutable
    data class Failed(val title: String, val message: String) : TrackerEvent()

    data object CancelRequested : TrackerEvent()
}

sealed class ProgressState {
    data object Idle : ProgressState()

    @Immutable
    data class Running(
        val op: LiveOp,
        val title: String,
        val phase: RunPhase,
        val subtitle: String,
        val overallPct: Int,
        val currentIndex: Int,
        val apps: List<AppProgress>,
        val cancelRequested: Boolean = false,
        val interactive: Boolean = true,
        val startedAtMs: Long = 0,
    ) : ProgressState() {
        val processedCount: Int get() = apps.count { it.done }
        val totalCount: Int get() = apps.size
        val currentApp: AppProgress? get() = apps.getOrNull(currentIndex)

        /**
         * Apps whose pre-restore APK install reached a terminal state.
         *
         * Restore runs an INSTALL pass over the whole queue BEFORE any data is
         * touched, so `processedCount` legitimately sits at 0 for minutes —
         * the field report read that as "stuck at 0/63". This counter moves
         * during that pass, giving the hero a number that tracks the work the
         * ring is actually doing.
         */
        val preparedCount: Int get() = apps.count { app ->
            app.parts.any { p ->
                p.key == Parts.INSTALL &&
                    (p.state == PartState.DONE || p.state == PartState.SKIPPED || p.state == PartState.FAILED)
            }
        }
    }

    @Immutable
    data class Done(
        val op: LiveOp,
        val title: String,
        val subtitle: String,
        val apps: List<AppProgress>,
        val interactive: Boolean = true,
    ) : ProgressState() {
        val okCount: Int get() = apps.count { !it.hasFailure && it.summaryState == PartState.DONE }
        val failedCount: Int get() = apps.count { it.hasFailure }
        val skippedCount: Int get() = apps.count { it.summaryState == PartState.SKIPPED }
    }

    @Immutable
    data class Failed(
        val op: LiveOp,
        val title: String,
        val message: String,
        val apps: List<AppProgress>,
        val interactive: Boolean = true,
    ) : ProgressState()
}

/**
 * The single state machine for a live run. Defensive against out-of-order or
 * unknown-package events: unknown apps are created lazily with the package as
 * label; part events for unknown apps during PREPARING create the app entry.
 */
object ProgressReducer {

    fun reduce(state: ProgressState, event: TrackerEvent): ProgressState {
        val running = state as? ProgressState.Running
        return when (event) {
            is TrackerEvent.Start -> ProgressState.Running(
                op = event.op,
                title = event.title,
                phase = RunPhase.PREPARING,
                subtitle = "Preparing…",
                overallPct = 0,
                currentIndex = -1,
                apps = event.apps.map {
                    AppProgress(
                        packageName = it.packageName,
                        label = it.label,
                        isSystem = it.isSystem,
                        iconPath = it.iconPath,
                        parts = it.parts.map { p ->
                            PartProgress(p, expectedKb = it.partExpectedKb[p] ?: 0L)
                        },
                        sizeKb = it.sizeKb,
                    )
                },
                interactive = event.interactive,
                startedAtMs = event.startedAtMs,
            )

            is TrackerEvent.Prepare -> running?.copy(
                phase = RunPhase.PREPARING,
                subtitle = event.message,
                overallPct = event.pct.coerceIn(0, 100),
            ) ?: state

            is TrackerEvent.Step -> {
                if (running == null) return state
                when {
                    // Backend's fixed pre-flight / final strings (kept in sync with DroidVault.sh).
                    event.message.startsWith("Preparing") -> running.copy(phase = RunPhase.PREPARING, subtitle = event.message)
                    event.message.startsWith("Almost Done") -> running.copy(phase = RunPhase.FINALIZING, subtitle = "Finalizing — bundling in background…")
                    else -> running.copy(subtitle = event.message)
                }
            }

            is TrackerEvent.AppStarted -> {
                if (running == null) return state
                val idx = running.apps.indexOfFirst { it.packageName == event.pkg }
                val apps = running.apps.toMutableList()
                val app = if (idx >= 0) {
                    apps[idx]
                } else {
                    // Unknown app (defensive): append in backend order.
                    AppProgress(packageName = event.pkg, label = event.label.ifEmpty { event.pkg }).also {
                        apps.add(it)
                    }
                }
                val updated = app.copy(
                    label = event.label.ifEmpty { app.label },
                    parts = ensureRunnable(app.parts),
                )
                val newIdx = if (idx >= 0) idx else apps.lastIndex
                apps[newIdx] = updated
                running.copy(
                    phase = RunPhase.PROCESSING,
                    currentIndex = newIdx,
                    subtitle = buildSubtitle(event),
                    overallPct = event.pct.coerceIn(0, 100),
                    apps = apps,
                )
            }

            // Part events with progress 1..99 while RUNNING fill the segment.
            is TrackerEvent.Part -> {
                if (running == null) return state
                val next = reducePart(running, event.pkg) { app ->
                    val parts = app.parts.toMutableList()
                    val pIdx = parts.indexOfFirst { it.key == event.part }
                    // Carry the measured size forward. Rebuilding the part from
                    // its event fields alone dropped `expectedKb`, so every part
                    // lost its size the moment it changed state and "GB done"
                    // read 0 for the whole run — caught on device, where a
                    // 109-app backup sat at "3m 49s" with no byte figure at all.
                    val part = PartProgress(
                        event.part, event.state, event.reason, event.progress,
                        expectedKb = parts.getOrNull(pIdx)?.expectedKb ?: 0L
                    )
                    if (pIdx >= 0) parts[pIdx] = part else parts.add(part)
                    app.copy(parts = parts)
                }
                // Pre-restore install pass: an INSTALL part going RUNNING is
                // the ONLY signal the queue has during that phase (no
                // RESTORE_START yet), so move the hero onto the app being
                // installed. Otherwise the screen sat on a generic
                // "Preparing" with no current app while work was happening.
                if (event.part == Parts.INSTALL && event.state == PartState.RUNNING) {
                    val idx = next.apps.indexOfFirst { it.packageName == event.pkg }
                    if (idx >= 0) next.copy(currentIndex = idx) else next
                } else next
            }

            is TrackerEvent.AppDone -> {
                if (running == null) return state
                reduceApp(running, event.pkg) { app ->
                    val parts = app.parts.map {
                        when (it.state) {
                            // Bundles keep draining in the backend's background
                            // after AppDone; report them done like the reference UX.
                            PartState.PENDING, PartState.RUNNING -> it.copy(state = PartState.DONE, progress = 100)
                            else -> it
                        }
                    }
                    val settled = parts.isNotEmpty() && parts.all {
                        it.state == PartState.DONE || it.state == PartState.SKIPPED || it.state == PartState.FAILED
                    }
                    app.copy(parts = parts, done = settled)
                }
            }

            is TrackerEvent.InstallDone -> {
                if (running == null) return state
                applyPart(running, event.pkg, Parts.INSTALL, PartState.DONE)
            }

            is TrackerEvent.InstallError -> {
                if (running == null) return state
                applyPart(running, event.pkg, Parts.INSTALL, PartState.FAILED, event.msg)
            }

            is TrackerEvent.Completed -> {
                val doneState = state as? ProgressState.Done
                val failedState = state as? ProgressState.Failed
                ProgressState.Done(
                    op = running?.op ?: doneState?.op ?: failedState?.op ?: LiveOp.BACKUP,
                    title = event.title,
                    subtitle = event.subtitle,
                    // A cancelled run must NOT invent completion: queued apps keep their
                    // PENDING/RUNNING parts and stay `done = false`, so they render as
                    // not-reached instead of as successes.
                    apps = (running?.apps ?: doneState?.apps ?: failedState?.apps ?: emptyList())
                        .map { if (event.cancelled) it else settleApp(it) },
                    interactive = running?.interactive ?: doneState?.interactive ?: failedState?.interactive ?: true,
                )
            }

            is TrackerEvent.Failed -> {
                val doneState = state as? ProgressState.Done
                val failedState = state as? ProgressState.Failed
                ProgressState.Failed(
                    op = running?.op ?: doneState?.op ?: failedState?.op ?: LiveOp.BACKUP,
                    title = event.title,
                    message = event.message,
                    // Same rule as a cancelled run: apps still queued when the run
                    // errored out did not get backed up, and must not be shown as if
                    // they had. Only apps the engine actually reported are settled.
                    apps = (running?.apps ?: doneState?.apps ?: failedState?.apps ?: emptyList())
                        .map { settleSettledPartsOnly(it) },
                    interactive = running?.interactive ?: doneState?.interactive ?: failedState?.interactive ?: true,
                )
            }

            is TrackerEvent.CancelRequested -> running?.copy(cancelRequested = true) ?: state
        }
    }

    private fun applyPart(
        running: ProgressState.Running,
        pkg: String,
        part: String,
        newState: PartState,
        reason: String = "",
        progress: Int = 0,
    ): ProgressState.Running = reducePart(running, pkg) { app ->
        val parts = app.parts.toMutableList()
        val pIdx = parts.indexOfFirst { it.key == part }
        val next = PartProgress(
            part, newState, reason, progress,
            expectedKb = parts.getOrNull(pIdx)?.expectedKb ?: 0L
        )
        if (pIdx >= 0) parts[pIdx] = next else parts.add(next)
        app.copy(parts = parts)
    }

    private inline fun reducePart(
        running: ProgressState.Running,
        pkg: String,
        transform: (AppProgress) -> AppProgress,
    ): ProgressState.Running {
        val idx = running.apps.indexOfFirst { it.packageName == pkg }
        if (idx < 0) return running
        val apps = running.apps.toMutableList()
        apps[idx] = transform(apps[idx])
        return running.copy(apps = apps)
    }

    private inline fun reduceApp(
        running: ProgressState.Running,
        pkg: String,
        transform: (AppProgress) -> AppProgress,
    ): ProgressState.Running = reducePart(running, pkg, transform)

    /** At app start, parts with no state yet become runnable: first RUNNING, rest PENDING. */
    private fun ensureRunnable(parts: List<PartProgress>): List<PartProgress> {
        if (parts.isEmpty()) return parts
        var firstPendingTouched = false
        return parts.map { p ->
            when (p.state) {
                PartState.PENDING -> {
                    if (!firstPendingTouched) {
                        firstPendingTouched = true
                        p.copy(state = PartState.RUNNING)
                    } else p
                }
                else -> p
            }
        }
    }

    /**
     * Freeze only the parts that already reached a terminal state, and only for an
     * app the engine actually reported on. Unlike [settleApp] this never turns a
     * PENDING or RUNNING part into DONE, so an app that was still queued when the
     * run stopped stays visibly unfinished.
     */
    private fun settleSettledPartsOnly(app: AppProgress): AppProgress {
        val unfinished = app.parts.any {
            it.state == PartState.PENDING || it.state == PartState.RUNNING
        }
        if (unfinished) return app
        return app.copy(done = true)
    }

    /** On completion, freeze any dangling RUNNING/PENDING parts as DONE (bundles drained). */
    private fun settleApp(app: AppProgress): AppProgress = app.copy(
        parts = app.parts.map {
            when (it.state) {
                PartState.PENDING, PartState.RUNNING -> it.copy(state = PartState.DONE)
                else -> it
            }
        },
        done = true,
    )

    private fun buildSubtitle(e: TrackerEvent.AppStarted): String {
        val size = e.size.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
        return if (e.total > 0) "App ${e.cur} of ${e.total}${if (size > 0) " • ${androidFormatKb(size)}" else ""}" else ""
    }

    private fun androidFormatKb(kb: Long): String = when {
        kb >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f GB", kb / 1_048_576.0)
        kb >= 1024 -> String.format(java.util.Locale.US, "%.1f MB", kb / 1024.0)
        else -> "$kb KB"
    }
}
