package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.ResultState
import com.phoenix.droidvault.models.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Manual-run interrupted tombstone: parity with ConfigRunWorker's scheduled
 * tombstones. The store's [AutomationStore.removeInterruptedTombstones] keys
 * on the errorSummary prefix "interrupted mid-run" — the builder MUST keep
 * that contract or dead runs would leave a permanent fake FAILED entry.
 */
class ManualTombstoneTest {

    private fun config(op: ConfigOperation = ConfigOperation.BACKUP) = BackupConfig(
        id = "cfg-1",
        name = "Nightly",
        operation = op
    )

    @Test fun tombstoneShapeIsFailedInterrupted() {
        val t = AutomationRunner.manualTombstone(config(), TriggerType.MANUAL)
        assertEquals("cfg-1", t.configId)
        assertEquals("Nightly", t.configName)
        assertEquals(TriggerType.MANUAL, t.trigger)
        assertEquals(ConfigOperation.BACKUP, t.operation)
        assertEquals(ResultState.FAILED, t.result)
        assertTrue(t.errorSummary.startsWith("interrupted mid-run"))
        // Terminal record: finishedAt must be stamped (History shows it as done).
        assertTrue(t.finishedAt > 0L)
    }

    @Test fun tombstoneCarriesRestoreOperationAndTrigger() {
        val t = AutomationRunner.manualTombstone(config(ConfigOperation.RESTORE), TriggerType.MANUAL)
        assertEquals(ConfigOperation.RESTORE, t.operation)
        assertEquals("interrupted mid-run (process died)", t.errorSummary)
    }

    @Test fun tombstoneMatchesRemovalContract() {
        // Simulate removeInterruptedTombstones' filter: prefix match on the
        // summary + configId equality. A real completion record with the same
        // config must NOT match; the tombstone must.
        val tombstones = listOf(
            AutomationRunner.manualTombstone(config(), TriggerType.MANUAL)
        )
        val real = com.phoenix.droidvault.models.ConfigExecution(
            id = "real-1",
            configId = "cfg-1",
            configName = "Nightly",
            trigger = TriggerType.MANUAL,
            operation = ConfigOperation.BACKUP,
            result = ResultState.SUCCESS
        )
        val removal = { items: List<com.phoenix.droidvault.models.ConfigExecution> ->
            items.filterNot { it.errorSummary.startsWith("interrupted mid-run") && it.configId == "cfg-1" }
        }
        assertEquals(0, removal(tombstones).size)
        assertEquals(1, removal(listOf(real)).size)
    }

    @Test fun tombstonesAreDistinctInstances() {
        val a = AutomationRunner.manualTombstone(config(), TriggerType.MANUAL)
        val b = AutomationRunner.manualTombstone(config(), TriggerType.MANUAL)
        assertNotEquals(a.id, b.id)
        assertTrue(a.startedAt <= b.startedAt + 5_000L)
    }
}
