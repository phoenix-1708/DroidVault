package com.phoenix.droidvault.models

sealed class EngineEvent {
    data class BackupProgress(
        val pkg: String,
        val label: String,
        val current: Int,
        val total: Int,
        val percent: Int,
        val size: String,
        val jobs: Int = 0,
    ) : EngineEvent()

    data class InfoStep(val msg: String, val jobs: Int = 0, val pkg: String = "") : EngineEvent()

    /**
     * Fine-grained per-part lifecycle event (ACTION:PART). STATE:
     * 0=PENDING 1=RUNNING 2=DONE 3=SKIPPED 4=FAILED, matching [PartState].
     */
    data class PartEvent(
        val pkg: String,
        val part: String,
        val state: Int,
        val reason: String = "",
    ) : EngineEvent()

    /** Per-app terminal event (ACTION:BACKUP_DONE / ACTION:RESTORE_DONE). */
    data class AppDoneEvent(val pkg: String) : EngineEvent()

    data class GlobalDone(val totalKb: String, val timeSec: String) : EngineEvent()

    data class FetchDone(val file: String) : EngineEvent()

    data class InstallDone(val pkg: String) : EngineEvent()

    data class InstallError(val pkg: String, val msg: String) : EngineEvent()

    /** Backend acknowledged cooperative cancellation (cancel flag file). */
    data object Cancelled : EngineEvent()

    /**
     * Shell process exited (success = exit code 0). Synthesized by the watcher,
     * not parsed: guarantees completion even if the final stdout line was lost
     * in async dispatch. Collector must dedup against GlobalDone/Error.
     */
    data class BackendExited(val success: Boolean) : EngineEvent()

    data class Error(val msg: String) : EngineEvent()

    data class RawLog(val line: String) : EngineEvent()
}