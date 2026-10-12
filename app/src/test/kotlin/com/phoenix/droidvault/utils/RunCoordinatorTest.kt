package com.phoenix.droidvault.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit A-05: manual, device-data and automation runs must share ONE process
 * gate, so a double restore / two SMS-role handoffs can never overlap.
 */
class RunCoordinatorTest {

    @Test fun tryAcquireFailsWhileHeldAndSucceedsAfterRelease() {
        assertFalse(RunCoordinator.isBusy)
        assertTrue(RunCoordinator.tryAcquire("manual"))
        assertTrue(RunCoordinator.isBusy)
        assertEquals("manual", RunCoordinator.currentOwner)
        // Second (non-waiting) acquirer refuses.
        assertFalse(RunCoordinator.tryAcquire("automation"))
        RunCoordinator.release()
        assertFalse(RunCoordinator.isBusy)
        assertNull(RunCoordinator.currentOwner)
        assertTrue(RunCoordinator.tryAcquire("automation"))
        RunCoordinator.release()
    }

    @Test fun defensivelyReleasingAnUnheldGateNeverThrows() {
        assertFalse(RunCoordinator.isBusy)
        RunCoordinator.release()
        RunCoordinator.release()
        assertFalse(RunCoordinator.isBusy)
    }

    @Test fun withRunHoldsTheGateForTheWholeBlock() = runBlocking {
        val observed = RunCoordinator.withRun("automation") {
            assertTrue(RunCoordinator.isBusy)
            assertEquals("automation", RunCoordinator.currentOwner)
            "done"
        }
        assertEquals("done", observed)
        assertFalse(RunCoordinator.isBusy)
        assertNull(RunCoordinator.currentOwner)
    }
}
