package com.phoenix.droidvault.utils

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide serialization of engine runs (audit A-05).
 *
 * Before this existed, only [AutomationRunner.execute] took a lock. A manual
 * Migrate run could therefore overlap a scheduled one: both would drive the
 * same engine over the same vault folders and the telephony provider — a
 * double restore duplicates messages/call logs, and two simultaneous SMS-role
 * handoffs fight over the default-SMS app.
 *
 * Two access shapes:
 *  - [withRun] (suspend, waits): the automation worker queues, because a
 *    retry racing a sibling schedule must wait and then dedup, not skip work
 *    that its sibling has not finished yet.
 *  - [tryAcquire]/[release] (non-waiting): manual UI paths refuse with a clear
 *    "another operation is running" message instead of freezing the UI behind
 *    a potentially 30-minute run.
 *
 * Nested engine calls made BY a run that already holds the gate pass
 * `gateHeld = true` down (AutomationRunner → runDynamicOperation /
 * DeviceManager.runOperation) — the mutex is deliberately not reentrant.
 */
object RunCoordinator {

    private val mutex = Mutex()
    private val _ownerState = MutableStateFlow<String?>(null)
    private val _vaultEpoch = MutableStateFlow(0L)

    /** Observable process-wide operation owner for app-shell status and availability UI. */
    val ownerState: StateFlow<String?> = _ownerState.asStateFlow()

    /**
     * Incremented when the gate is released: a run that may have written to the
     * vault has just finished.
     *
     * Everything that changes vault state holds this gate — that is the point of
     * it — so this single signal covers app backups, device-data runs, deletes,
     * folders, transfers and imports without every writer having to remember to
     * announce itself. A verdict about the vault (the dashboard's health line,
     * and the pre-restore gate that reads the same report) describes the vault as
     * it was when it was taken and must not outlive a run that changed it: a
     * stale "all readable" is the same class of lie as a green tick nothing
     * earned, and a stale damaged verdict refuses a restore that is now fine.
     *
     * Conservative on purpose — a run that failed or wrote nothing also bumps it.
     * The alternative is guessing whether a run wrote, and guessing wrong is the
     * failure mode this exists to prevent.
     */
    val vaultEpoch: StateFlow<Long> = _vaultEpoch.asStateFlow()

    private val _lastWriter = MutableStateFlow<String?>(null)

    /**
     * The label of the last run to release the gate; null when none has in this
     * process.
     *
     * The epoch says THAT something was written; this says WHAT kind of thing —
     * "an app backup" rather than "a run" — which is the difference between a
     * finding that may already be repaired and a wall of silence. Label only: it
     * is the same string the gate was taken with (`manual:BACKUP_APPS`,
     * `device-data`, `transfer`, `automation:<id>`), never a package or a path, so
     * nothing about the user's data travels with it. Null after a restart, because
     * a process that was not running cannot know what ran while it was not.
     */
    val lastWriter: StateFlow<String?> = _lastWriter.asStateFlow()

    /** Current holder label, for messages/diagnostics. Null when idle. */
    @Volatile
    var currentOwner: String? = null
        private set

    val isBusy: Boolean get() = currentOwner != null

    /** Waits for the gate, runs [block], releases. */
    suspend fun <T> withRun(owner: String, block: suspend () -> T): T = mutex.withLock {
        currentOwner = owner
        _ownerState.value = owner
        DroidVaultLogger.v("Gate", "acquired (wait) by $owner")
        try {
            block()
        } finally {
            currentOwner = null
            _ownerState.value = null
            _vaultEpoch.value += 1
            _lastWriter.value = owner
            DroidVaultLogger.v("Gate", "released by $owner")
        }
    }

    /** Takes the gate without waiting; false when another run holds it. */
    fun tryAcquire(owner: String): Boolean {
        if (!mutex.tryLock()) {
            DroidVaultLogger.v("Gate", "refused $owner — held by ${currentOwner ?: "?"}")
            return false
        }
        currentOwner = owner
        _ownerState.value = owner
        DroidVaultLogger.v("Gate", "acquired by $owner")
        return true
    }

    /**
     * Releases the gate. **Only ever call this from a path that acquired it.**
     *
     * There is no owner tracking underneath, so a `release()` from a path that
     * did not acquire frees whichever run actually holds the gate — two runs
     * then overlap over the same archives and the same telephony provider,
     * which is the entire failure this object exists to prevent. Nothing here
     * can tell the two apart, which is why the pairing is a rule and not a
     * runtime check.
     *
     * The shape every call site uses: `tryAcquire`, return early when it
     * refuses, and put the `release()` in a `finally` that only that path can
     * reach. `withRun` releases its own.
     */
    fun release() {
        val was = currentOwner
        currentOwner = null
        _ownerState.value = null
        _vaultEpoch.value += 1
        if (was != null) _lastWriter.value = was
        try {
            mutex.unlock()
        } catch (_: Exception) {
        }
        if (was != null) DroidVaultLogger.v("Gate", "release() by $was")
    }
}
