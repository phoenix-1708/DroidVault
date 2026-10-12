package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppHistoryRecord
import com.phoenix.droidvault.models.ConfigExecution
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.ResultState
import com.phoenix.droidvault.models.TriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * History invariants for device-data automation runs (the empty-backup-folder
 * case): every requested item must land in the per-run log as its own record
 * with an explicit reason — and a folder full of missing archives must never
 * read as a clean success.
 */
class DeviceDataHistoryTest {

    private fun skippedItem(item: DeviceDataItem, file: String) = AppHistoryRecord(
        packageName = "",
        appName = item.label,
        ruleId = null,
        requested = emptySet(),
        processed = emptySet(),
        result = ResultState.SKIPPED,
        reason = "no backup archive ($file) in the backup folder",
        deviceItem = item
    )

    @Test fun missingArchivesRecordSkippedPerItemNotSuccess() {
        val execution = ConfigExecution(
            id = "e1", configId = "c1", configName = "Device Restore",
            trigger = TriggerType.SCHEDULED, operation = ConfigOperation.RESTORE,
            result = ResultState.FAILED,
            errorSummary = "no device-data backups found in the backup folder"
        )
        val files = mapOf(
            DeviceDataItem.MESSAGES to "Messages.shift",
            DeviceDataItem.CALLS to "CallLogs.shift",
            DeviceDataItem.CONTACTS to "Contacts.shift"
        )
        files.forEach { (item, file) -> execution.apps.add(skippedItem(item, file)) }
        execution.finishedAt = System.currentTimeMillis()

        // Per-item truth: one SKIPPED row with the exact archive name each.
        assertEquals(3, execution.skippedCount)
        assertEquals(0, execution.successCount)
        assertEquals(0, execution.failureCount)
        assertTrue(execution.apps.all { it.deviceItem != null && it.result == ResultState.SKIPPED })
        assertTrue(
            execution.apps.all {
                it.reason.startsWith("no backup archive (") && it.reason.endsWith(") in the backup folder")
            }
        )
        // The archive names shown must be the ones the pipeline actually looks for.
        assertEquals(
            files.values.toList(),
            execution.apps.map { it.reason.substringAfter("(").substringBefore(")") }
        )
    }

    @Test fun partialMissingSkipsOnlyMissingItems() {
        val execution = ConfigExecution(
            id = "e2", configId = "c1", configName = "Device Restore",
            trigger = TriggerType.SCHEDULED, operation = ConfigOperation.RESTORE
        )
        // Messages exists and succeeds; Wi-Fi archive is missing.
        execution.apps.add(
            AppHistoryRecord(
                packageName = "", appName = DeviceDataItem.MESSAGES.label,
                ruleId = null, requested = emptySet(), processed = emptySet(),
                result = ResultState.SUCCESS, deviceItem = DeviceDataItem.MESSAGES
            )
        )
        execution.apps.add(skippedItem(DeviceDataItem.WIFI, "Wi-Fi.shift"))
        execution.finishedAt = System.currentTimeMillis()

        assertEquals(1, execution.successCount)
        assertEquals(1, execution.skippedCount)
        assertEquals(ResultState.SKIPPED, execution.apps.last().result)
        assertTrue(execution.apps.last().reason.contains("Wi-Fi.shift"))
    }

    @Test fun deviceRecordsRoundTripThroughCodec() {
        // What the runner records must survive persistence for the drill-down.
        val execution = ConfigExecution(
            id = "e3", configId = "c1", configName = "Device Restore",
            trigger = TriggerType.SCHEDULED, operation = ConfigOperation.RESTORE
        )
        execution.apps.add(skippedItem(DeviceDataItem.WALLPAPER, "Wallpaper.shift"))
        val json = com.phoenix.droidvault.utils.AutomationCodec.appRecordsTo(execution.apps)
        val back = com.phoenix.droidvault.utils.AutomationCodec.appRecordsFrom(json)
        assertEquals(1, back.size)
        assertEquals(DeviceDataItem.WALLPAPER, back.single().deviceItem)
        assertEquals(ResultState.SKIPPED, back.single().result)
        assertTrue(back.single().reason.contains("Wallpaper.shift"))
    }

    @Test fun pipelineFailureRecordsFailedNotSuccess() {
        // The SAME failure signal the Migrate tab folds into its completion
        // message (DeviceManager's onItemFailure) must never land in history
        // as a success — a Messages restore that refused to run is the exact
        // "reported success, wrote nothing" bug this path has had.
        val rec = AutomationRunner.deviceItemRecord(
            DeviceDataItem.MESSAGES,
            "cannot read existing Messages, refusing to restore (duplicates)"
        )
        assertEquals(ResultState.FAILED, rec.result)
        assertEquals(
            "cannot read existing Messages, refusing to restore (duplicates)",
            rec.reason
        )
        assertEquals(DeviceDataItem.MESSAGES, rec.deviceItem)
        assertEquals("", rec.packageName)
    }

    @Test fun cleanItemRecordsSuccessWithEmptyReason() {
        val rec = AutomationRunner.deviceItemRecord(DeviceDataItem.CALLS, null)
        assertEquals(ResultState.SUCCESS, rec.result)
        assertEquals("", rec.reason)
        assertEquals(DeviceDataItem.CALLS, rec.deviceItem)
    }

    @Test fun archiveFilenamesCoverEveryDeviceItem() {
        // The skip reasons are only honest if the checked names are the real ones.
        assertEquals("Messages.shift", AutomationRunner.deviceBackupFileFor(DeviceDataItem.MESSAGES))
        assertEquals("CallLogs.shift", AutomationRunner.deviceBackupFileFor(DeviceDataItem.CALLS))
        assertEquals("Contacts.shift", AutomationRunner.deviceBackupFileFor(DeviceDataItem.CONTACTS))
        assertEquals("Wi-Fi.shift", AutomationRunner.deviceBackupFileFor(DeviceDataItem.WIFI))
        assertEquals("Wallpaper.shift", AutomationRunner.deviceBackupFileFor(DeviceDataItem.WALLPAPER))
        assertEquals("Bluetooth.shift", AutomationRunner.deviceBackupFileFor(DeviceDataItem.BLUETOOTH))
    }

    @Test fun restoreOutputCoversEveryDeviceItem() {
        // The restore-side "did anything actually come back?" check only fires
        // for items that HAVE a documented output. A device item added later
        // must not fall through to a clean success for an empty restore.
        val outputs = DeviceDataItem.entries.associateWith {
            AutomationRunner.deviceRestoreOutputFor(it)
        }
        assertEquals(DeviceDataItem.entries.size, outputs.size)
        assertTrue(outputs.values.all { it.isNotBlank() })
        assertEquals("messages.json", outputs[DeviceDataItem.MESSAGES])
        assertEquals("calls.json", outputs[DeviceDataItem.CALLS])
        assertEquals("Contacts.vcf", outputs[DeviceDataItem.CONTACTS])
        assertEquals("Wi-Fi", outputs[DeviceDataItem.WIFI])
        assertEquals("Wallpaper", outputs[DeviceDataItem.WALLPAPER])
        assertEquals("Bluetooth", outputs[DeviceDataItem.BLUETOOTH])
        assertEquals("Folders", outputs[DeviceDataItem.FOLDERS])
    }

    @Test fun emptyRestoreReasonNamesTheRealArchiveAndOutput() {
        // The Migrate tab prints this string in its completion message and
        // automation records it as the failure reason — one string, both tabs.
        // It must name the archive the pipeline looks for and the entry that
        // should have come out of it, and it must NEVER read as a success.
        DeviceDataItem.entries.forEach { item ->
            val reason = AutomationRunner.missingRestoreReason(item)
            assertTrue(
                "$reason names its archive",
                reason.contains(AutomationRunner.deviceBackupFileFor(item))
            )
            assertTrue(
                "$reason names its output",
                reason.contains(AutomationRunner.deviceRestoreOutputFor(item))
            )
            assertEquals(
                ResultState.FAILED,
                AutomationRunner.deviceItemRecord(item, reason).result
            )
        }
    }

    @Test fun refusalKeepsTitleAndDetailSeparate() {
        // Both callers show `title` as the action and `message` as the detail
        // — the same shape the app phase's refusal already uses. Returning
        // instead (what this replaced) was reported as success on both tabs.
        val refused = DeviceManager.DeviceDataRefused(
            "Another operation is in progress",
            "automation:c1 is still running. Please wait for it to finish and try again."
        )
        assertEquals("Another operation is in progress", refused.title)
        assertTrue(refused.message!!.startsWith("automation:c1 is still running"))
    }
}
