package com.phoenix.droidvault.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Automation backup found" offer gate. Field bug this guards against:
 * a fresh-install EMPTY state file (every section null/empty) triggered the
 * dialog on every app open — there is nothing to restore, so the offer must
 * require actual rows; and a dismissed offer must stay dismissed for the
 * same backup file.
 */
class RestoreOfferGateTest {

    @Test
    fun `real backup with empty store is offered`() {
        assertTrue(AutomationSnapshot.shouldOfferRestore(fileRows = 7, localHasData = false, alreadyDeclined = false))
    }

    @Test
    fun `empty shell file is never offered - fresh install nag bug`() {
        assertFalse(AutomationSnapshot.shouldOfferRestore(fileRows = 0, localHasData = false, alreadyDeclined = false))
    }

    @Test
    fun `live local data suppresses the offer`() {
        assertFalse(AutomationSnapshot.shouldOfferRestore(fileRows = 7, localHasData = true, alreadyDeclined = false))
    }

    @Test
    fun `a skipped backup stays skipped`() {
        assertFalse(AutomationSnapshot.shouldOfferRestore(fileRows = 7, localHasData = false, alreadyDeclined = true))
    }

    @Test
    fun `skip wins even against a fresh empty store`() {
        // Dismissed earlier, then user cleared app data again: still skipped
        // for the same file (path+mtime identity unchanged).
        assertFalse(AutomationSnapshot.shouldOfferRestore(fileRows = 3, localHasData = false, alreadyDeclined = true))
    }
}
