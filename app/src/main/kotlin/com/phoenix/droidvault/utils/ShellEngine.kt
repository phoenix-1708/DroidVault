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

    /** Compiled once: applied to every backend line, so per-line compilation
     *  showed up as progress-UI latency on large runs. */
    private val SSAID_PATTERN = Regex("SSAID=[^|]+")

    /**
     * Executes a root shell command and emits parsed EngineEvents in real-time.
     * Cancellation is real: the libsu job is enqueued as a [java.util.concurrent.Future]
     * and [java.util.concurrent.Future.cancel] interrupts the worker on timeout
     * or flow close. (`Shell.Job` itself exposes no cancel API.)
     *
     * [timeoutMs] is enforced by `future.get(timeout)` and then `cancel(true)`,
     * which **interrupts the engine mid-write**. It is therefore a bound on
     * *speed*, not on liveness, and this default only suits a command that does
     * not move a meaningful number of bytes.
     *
     * Every call site that moves bytes must pass a bound scaled to the work —
     * `VaultTransfer.runWatchdogMs(apps)` for a backup/restore, or
     * `VaultTransfer.transferWatchdogMs(bytes, …)` for a transfer step. A flat
     * bound here is what killed a full backup of a large vault at 15 minutes
     *; `ConventionGuardTest` fails the build if a call site
     * forgets.
     */
    fun executeEngineCommand(command: String, timeoutMs: Long = 15 * 60 * 1000L): Flow<EngineEvent> = callbackFlow {

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
            } catch (_: TimeoutException) {
                future.cancel(true)
                trySend(EngineEvent.Error("Shell command timed out after ${timeoutMs / 1000}s"))
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Shell watcher failed: ${e.message}")
                trySend(EngineEvent.Error("Shell execution failed: ${e.message}"))
            } finally {
                close()
            }
        }

        awaitClose {
            future.cancel(true)
            watcher.cancel()
        }
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