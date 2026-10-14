package com.phoenix.droidvault.utils

import android.util.Log
import com.phoenix.droidvault.models.EngineEvent
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

object ShellEngine {

    private const val TAG = "DroidVaultShell"

    /**
     * The engine's own cooperative cancel flag — the same path
     * `AutomationRunner.CANCEL_FLAG_PATH` uses, named once here because a
     * timeout and a Cancel button must stop the engine the same way.
     */
    private const val ENGINE_CANCEL_FLAG = "/data/local/tmp/droidvault_cancel"

    /** The engine's binary name, matched by `pkill -f` / `pgrep -f`. */
    private const val ENGINE_BINARY = "droidvault-engine"

    /** Compiled once: applied to every backend line, so per-line compilation
     *  showed up as progress-UI latency on large runs. */
    private val SSAID_PATTERN = Regex("SSAID=[^|]+")

    /**
     * Executes a root shell command and emits parsed EngineEvents in real-time.
     *
     * ## Stopping the engine is this object's job, not libsu's
     *
     * The libsu `Future` returned by `enqueue()` is a `ResultFuture`, and its
     * `cancel(boolean)` is, in full:
     *
     * ```java
     * public boolean cancel(boolean mayInterruptIfRunning) {
     *     return latch.getCount() != 0;
     * }
     * ```
     *
     * It ignores the parameter, interrupts nothing, and `isCancelled()` is
     * hardcoded to `false` — verified by decompiling libsu 6.0.0 from this
     * project's own dependency cache, because this file previously claimed the
     * opposite in as many words ("`cancel(true)` interrupts the worker").
     *
     * So a timeout used to do exactly nothing to the engine: it emitted an
     * error, closed the flow so every later line was dropped, released the run
     * gate, and let the engine keep packing archives into the vault with nobody
     * reading them — while the user was already free to start another run at the
     * same vault. Two writers, one gate. [stopEngine] is what actually stops it.
     *
     * [timeoutMs] is therefore a bound on *speed*, and it must be scaled to the
     * work — but a trip must now genuinely end the work.
     *
     * Every call site that moves bytes must pass a bound scaled to the work —
     * `VaultTransfer.runWatchdogMs(apps)` for a backup/restore, or
     * `VaultTransfer.transferWatchdogMs(bytes, …)` for a transfer step. A flat
     * bound here is what killed a full backup of a large vault at 15 minutes
     *; `ConventionGuardTest` fails the build if a call site
     * forgets.
     */
    fun executeEngineCommand(command: String, timeoutMs: Long = 15 * 60 * 1000L): Flow<EngineEvent> = callbackFlow {

        // Whether the command reached its own end on its own. `awaitClose` runs on
        // EVERY exit from this flow — a cancel, a timeout, AND an ordinary
        // completion — and it used to call `stopEngineBlocking()` unconditionally.
        // That raised the engine's cancel flag after a command that had already
        // finished successfully, so the NEXT engine command in the same run
        // started, saw the flag, and reported ACTION:CANCELLED.
        //
        // Measured on device as an import that extracted all 482,592,475 bytes
        // successfully and then reported "Import cancelled" — a verdict about
        // something that had not happened, produced by the tail of the previous
        // command. The engine clears the flag at the start of each run
        // (`engine.rs`), which is why exactly the SECOND command of every burst
        // was the victim, and why single-command paths never showed it.
        var finishedNormally = false

        val outputList = object : CallbackList<String>() {
            override fun onAddElement(line: String) {
                // Redact SSAID (PII). The pattern is a companion val, not a
                // per-line Regex(...) — this runs once per backend stdout line
                // (thousands on a 63-app backup) on libsu's callback thread,
                // where anything slow here delays progress delivery to the UI.
                // Compiled once and reused; the redacted string is computed
                // once and used for both sinks.
                val safe = SSAID_PATTERN.replace(line, "SSAID=***")
                android.util.Log.d(TAG, safe)
                // Diagnostics mirror: every backend protocol line, with the
                // same SSAID redaction, so a field report can be replayed
                // from the exported log alone.
                try {
                    if (line.startsWith("ACTION:ERROR") || line.contains("ERROR")) {
                        DroidVaultLogger.w("Engine", safe.take(400))
                    } else {
                        DroidVaultLogger.d("Engine", safe.take(400))
                    }
                // The diagnostics mirror is best-effort and MUST NOT break the run: a
                // logger that throws here would kill a backup that is otherwise
                // fine. Swallowed deliberately, and the outer try is what keeps
                // the flow alive; `trySend` below is the path that matters.
                } catch (_: Exception) {
                }
                trySend(parseLine(line))
            }
        }

        val future = Shell.cmd(command).to(outputList, outputList).enqueue()

        val watcher = launch(Dispatchers.IO) {
            try {
                val result = future.get(timeoutMs, TimeUnit.MILLISECONDS)
                // Drain window: libsu may deliver the final stdout lines (e.g.
                // ACTION:GLOBAL_DONE) asynchronously after the Future completes.
                // Closing immediately drops them and sticks the UI at "Almost Done".
                kotlinx.coroutines.delay(2000)
                if (!result.isSuccess) {
                    val errorMsg = "Shell command failed with exit code: ${result.code}"
                    android.util.Log.e(TAG, errorMsg)
                    trySend(EngineEvent.Error(errorMsg))
                }
                // Exit guarantee: even if the final line was lost, the collector
                // learns the process ended (it dedups against GlobalDone/Error).
                trySend(EngineEvent.BackendExited(result.isSuccess))
                finishedNormally = result.isSuccess
            } catch (_: TimeoutException) {
                // The engine must STOP, not be abandoned. `future.cancel(true)`
                // is a no-op against libsu's ResultFuture, so this is the only
                // thing standing between a timeout and an orphaned writer still
                // packing into the vault.
                stopEngineBlocking()
                trySend(EngineEvent.Error("Shell command timed out after ${timeoutMs / 1000}s"))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Shell watcher failed: ${e.message}")
                trySend(EngineEvent.Error("Shell execution failed: ${e.message}"))
            } finally {
                close()
            }
        }

        awaitClose {
            // Only a command that did NOT reach its own end needs stopping. An
            // abandoned collector (a cancel, a timeout, a teardown) may still have
            // an engine writing into the vault behind it, and handing the gate back
            // while a writer is live is invariant 26. A command that exited cleanly
            // has no such writer — stopping it anyway is not free, it poisons the
            // next run.
            if (!finishedNormally) {
                stopEngineBlocking()
            }
            watcher.cancel()
        }
    }

    /**
     * Stop the engine, and do not return until it is actually gone.
     *
     * Two steps, in order. First the engine's own cooperative flag, which it
     * polls at its loop points and which is the graceful path: the run finishes
     * the part in hand and stops between them, so no archive is left half
     * written. Then a hard kill by process name, for the case where it is
     * blocked on something that never reaches a loop point.
     *
     * Blocking until the process is gone is the load-bearing part. The caller
     * reports the run failed and releases the run gate immediately afterwards;
     * if this returned while the engine was still alive, the user could start a
     * second run against the same vault while the first was still writing to it.
     * Invariant 26: cleanup must never run against work still in flight, and
     * neither may the gate be released into it.
     *
     * Best effort by design — it is called from `awaitClose`, which must not
     * throw — so a shell failure here degrades to the old behaviour rather than
     * taking down whatever was already unwinding.
     */
    private fun stopEngineBlocking(timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            Shell.cmd("touch ${ShellQuote.q(ENGINE_CANCEL_FLAG)}").exec()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "could not raise the engine cancel flag: ${t.message}")
        }
        try {
            Shell.cmd("pkill -f ${ShellQuote.q(ENGINE_BINARY)}").exec()
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "could not signal the engine: ${t.message}")
        }
        // Confirm it is really down. `pgrep` on the binary, not on the app: the
        // engine is a separate root process, and the app is very much still
        // running here.
        while (System.currentTimeMillis() < deadline) {
            val alive = try {
                Shell.cmd("pidof ${ShellQuote.q(ENGINE_BINARY)} 2>/dev/null || pgrep -f ${ShellQuote.q(ENGINE_BINARY)}").exec().out
                    .any { it.isNotBlank() }
            } catch (t: Throwable) {
                false
            }
            if (!alive) {
                // The stop is over once nothing is running, and the flag only ever
                // meant "this engine". Leaving it behind is not tidiness — it is a
                // live cancel order for the NEXT run, because the engine clears the
                // file at its own start but a caller that reaches `cancelled()`
                // between a touch here and that start sees it and reports
                // ACTION:CANCELLED for work it never began.
                try {
                    Shell.cmd("rm -f ${ShellQuote.q(ENGINE_CANCEL_FLAG)}").exec()
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "could not clear the engine cancel flag: ${t.message}")
                }
                return
            }
            Thread.sleep(100)
        }
        android.util.Log.w(TAG, "engine still alive after ${timeoutMs}ms of SIGTERM")
    }

    fun parseLine(line: String): EngineEvent {
        if (!line.startsWith("ACTION:") && !line.startsWith("INFO:")) return EngineEvent.RawLog(
            line
        )

        val partsMap = mutableMapOf<String, String>()
        var start = 0
        while (start < line.length) {
            val end = line.indexOf('|', start).let { if (it == -1) line.length else it }
            val segment = line.substring(start, end)
            val colonIdx = segment.indexOf(':')
            if (colonIdx != -1) {
                val key = segment.substring(0, colonIdx)
                val value = segment.substring(colonIdx + 1)
                partsMap[key] = value
            }
            start = end + 1
        }

        val action = partsMap["ACTION"]
        val info = partsMap["INFO"]

        return when {
            action == "BACKUP_START" || action == "RESTORE_START" -> EngineEvent.BackupProgress(
                pkg = partsMap["PKG"] ?: "",
                label = partsMap["LABEL"] ?: "",
                current = partsMap["CUR"]?.toIntOrNull() ?: 0,
                total = partsMap["TOT"]?.toIntOrNull() ?: 0,
                percent = partsMap["PCT"]?.toIntOrNull() ?: 0,
                size = partsMap["SIZE"] ?: "",
                jobs = partsMap["JOBS"]?.toIntOrNull() ?: 0,
            )
            info == "STEP" -> EngineEvent.InfoStep(
                msg = partsMap["MSG"] ?: "",
                jobs = partsMap["JOBS"]?.toIntOrNull() ?: 0,
                pkg = partsMap["PKG"] ?: "",
            )
            action == "PART" -> EngineEvent.PartEvent(
                pkg = partsMap["PKG"] ?: "",
                part = partsMap["PART"] ?: "",
                state = partsMap["STATE"]?.toIntOrNull() ?: 0,
                reason = partsMap["REASON"] ?: "",
            )
            action == "GLOBAL_DONE" -> EngineEvent.GlobalDone(
                totalKb = partsMap["TOTAL"] ?: "0",
                timeSec = partsMap["TIME"] ?: "0"
            )

            action == "ERROR" -> EngineEvent.Error(
                msg = partsMap["MSG"] ?: "Unknown Error"
            )
            action == "BACKUP_DONE" || action == "RESTORE_DONE" -> EngineEvent.AppDoneEvent(
                pkg = partsMap["PKG"] ?: ""
            )
            action == "FETCH_DONE" -> EngineEvent.FetchDone(
                file = partsMap["FILE"] ?: ""
            )
            action == "INSTALL_DONE" -> EngineEvent.InstallDone(
                pkg = partsMap["PKG"] ?: ""
            )
            action == "INSTALL_ERROR" -> EngineEvent.InstallError(
                pkg = partsMap["PKG"] ?: "",
                msg = partsMap["MSG"] ?: "Unknown install error"
            )
            action == "CANCELLED" -> EngineEvent.Cancelled
            else -> EngineEvent.RawLog(line)
        }
    }
}