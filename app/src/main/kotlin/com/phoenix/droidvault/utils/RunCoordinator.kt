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

    /** Observable process-wide operation owner for app-shell status and availability UI. */
    val ownerState: StateFlow<String?> = _ownerState.asStateFlow()

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
        try {
            mutex.unlock()
        } catch (_: Exception) {
        }
        if (was != null) DroidVaultLogger.v("Gate", "release() by $was")
    }
}
