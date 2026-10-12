package com.phoenix.droidvault

import com.phoenix.droidvault.utils.AutomationStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the automation cache may be backfilled from the vault state file.
 *
 * Found on device as a "Automation data restored" popup the user could not
 * account for. The gate used to be "the cache has no rows", which is true both
 * when the cache was never written (a reinstall — the case it exists for) AND
 * when the user deliberately deleted everything (a present-but-empty section).
 * The second case restored data the user had removed and then announced it as
 * a recovery, which is invariant 3 broken from the other side.
 */
class AutomationRecoveryGateTest {

    @Test
    fun `a fresh install with a populated file recovers`() {
        // The legitimate case: app data was cleared, the vault file survived.
        assertTrue(AutomationStore.shouldRecoverFromFile(prefInitialized = false, fileData = true))
    }

    @Test
    fun `deliberately emptied automation data is NOT recovered`() {
        // The bug. Present-but-empty sections are user intent; restoring here
        // resurrects what the user deleted and calls it a recovery.
        assertFalse(AutomationStore.shouldRecoverFromFile(prefInitialized = true, fileData = true))
    }

    @Test
    fun `nothing recovers when there is no file to recover from`() {
        assertFalse(AutomationStore.shouldRecoverFromFile(prefInitialized = false, fileData = false))
    }

    @Test
    fun `nothing recovers when the cache is already initialized`() {
        assertFalse(AutomationStore.shouldRecoverFromFile(prefInitialized = true, fileData = false))
    }

    @Test
    fun `empty cache and empty file is a no-op in both directions`() {
        assertFalse(AutomationStore.shouldRecoverFromFile(prefInitialized = false, fileData = false))
    }
}