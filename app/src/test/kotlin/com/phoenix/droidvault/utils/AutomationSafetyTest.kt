package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppHistoryRecord
import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.ErrorCode
import com.phoenix.droidvault.models.ResultState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AutomationSafetyTest {

    @Test fun spaceGateRefusesBelowFloor() {
        val s = AutomationSafety.evaluateSpace(50L * 1024L) // 50 MB
        assertFalse(s.ok)
        assertEquals(ErrorCode.STORAGE_FULL, s.error)
    }

    @Test fun spaceGateAllowsAboveFloorWarnsBelowComfort() {
        val s = AutomationSafety.evaluateSpace(300L * 1024L) // 300 MB
        assertTrue(s.ok)
        assertTrue(s.warn)
        assertEquals(ErrorCode.NONE, s.error)
    }

    @Test fun spaceGateComfortable() {
        val s = AutomationSafety.evaluateSpace(10L * 1024L * 1024L) // 10 GB
        assertTrue(s.ok)
        assertFalse(s.warn)
    }

    @Test fun spaceGateBoundaryExact() {
        assertTrue(AutomationSafety.evaluateSpace(AutomationSafety.MIN_FREE_KB).ok)
        assertFalse(AutomationSafety.evaluateSpace(AutomationSafety.MIN_FREE_KB - 1).ok)
    }

    @Test fun appRecordRoundtrip() {
        val rec = AppHistoryRecord(
            packageName = "com.example.app",
            appName = "Example",
            ruleId = "rule-1",
            requested = setOf(BackupComponent.APK, BackupComponent.PRIVATE_DATA),
            processed = setOf(BackupComponent.APK),
            result = ResultState.PARTIAL_SUCCESS,
            reason = "data component unavailable",
            durationMs = 134_000L
        )
        val json = AutomationCodec.appRecordTo(rec)
        val back = AutomationCodec.appRecordFrom(json)
        assertEquals(rec, back)
    }

    @Test fun appRecordLegacyJsonWithoutDurationDefaultsToZero() {
        // Records written before per-app durations existed must still parse.
        val legacy = org.json.JSONObject()
            .put("pkg", "com.old").put("app", "Old").put("ruleId", "")
            .put("result", "SUCCESS").put("reason", "")
        val rec = AutomationCodec.appRecordFrom(legacy)
        assertEquals(0L, rec.durationMs)
    }

    @Test fun deviceDataItemRoundtrip() {
        val rec = AppHistoryRecord(
            packageName = "",
            appName = "Messages",
            ruleId = null,
            requested = emptySet(),
            processed = emptySet(),
            result = ResultState.SUCCESS,
            deviceItem = DeviceDataItem.MESSAGES
        )
        val back = AutomationCodec.appRecordFrom(AutomationCodec.appRecordTo(rec))
        assertEquals(rec, back)
    }

    @Test fun configDeviceDataRoundtripAndLegacy() {
        val cfg = BackupConfig(
            id = "c1", name = "Device only",
            deviceData = setOf(DeviceDataItem.MESSAGES, DeviceDataItem.WALLPAPER)
        )
        val json = AutomationCodec.configTo(cfg)
        val back = AutomationCodec.configFrom(json)
        assertEquals(cfg.deviceData, back.deviceData)
        // Legacy configs never carried deviceData: must parse as empty.
        val legacy = org.json.JSONObject(json.toString())
        legacy.remove("deviceData")
        assertEquals(emptySet<DeviceDataItem>(), AutomationCodec.configFrom(legacy).deviceData)
    }

    @Test fun deviceDataOnlyConfigIsValidWithoutRules() {
        val cfg = BackupConfig(
            id = "c1", name = "Nightly device data",
            deviceData = setOf(DeviceDataItem.CONTACTS, DeviceDataItem.WIFI)
        )
        assertTrue(validateAutomationConfig(cfg, labelIds = emptySet()).isEmpty())
        // And a config with neither rules nor device data stays invalid.
        val empty = BackupConfig(id = "c2", name = "x")
        assertTrue(validateAutomationConfig(empty, emptySet()).isNotEmpty())
    }

    @Test fun deviceDataUnknownNamesIgnoredOnParse() {
        val json = org.json.JSONObject()
            .put("id", "c").put("name", "n")
            .put("deviceData", org.json.JSONArray(listOf("MESSAGES", "NOT_A_REAL_ITEM")))
        val cfg = AutomationCodec.configFrom(json)
        assertEquals(setOf(DeviceDataItem.MESSAGES), cfg.deviceData)
    }

    @Test fun appRecordCapEnforced() {
        val many = (0 until 500).map {
            AppHistoryRecord(
                "pkg.$it", "App $it", null,
                setOf(BackupComponent.APK), setOf(BackupComponent.APK),
                ResultState.SUCCESS
            )
        }
        val arr = AutomationCodec.appRecordsTo(many)
        assertEquals(AutomationCodec.MAX_LOGGED_APPS, arr.length())
    }

    @Test fun appRecordListRoundtrip() {
        val records = listOf(
            AppHistoryRecord(
                "com.a", "A", null, setOf(BackupComponent.APK), emptySet(),
                ResultState.SKIPPED, "not matching any rule"
            ),
            AppHistoryRecord(
                "com.b", "B", "r1", setOf(BackupComponent.APK), setOf(BackupComponent.APK),
                ResultState.SUCCESS
            )
        )
        val arr = AutomationCodec.appRecordsTo(records)
        val back = AutomationCodec.appRecordsFrom(arr)
        assertEquals(records, back)
    }

    @Test fun appRecordsFromJunkReturnsEmpty() {
        assertTrue(AutomationCodec.appRecordsFrom(null).isEmpty())
    }

    @Test fun formatBytesTiers() {
        assertEquals("0 B", Formatters.formatBytes(0))
        assertEquals("512 B", Formatters.formatBytes(512))
        assertEquals("1.00 KB", Formatters.formatBytes(1024))
        assertEquals("1.50 MB", Formatters.formatBytes((1.5 * 1024 * 1024).toLong()))
        assertEquals("2.00 GB", Formatters.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test fun formatKbMatchesLegacyBehavior() {
        // Legacy formatSize: KB < 1024 -> "N KB"; >= 1GiB KB -> GB.
        assertEquals("512.00 KB", Formatters.formatKb(512))
        assertEquals("1.00 MB", Formatters.formatKb(1024))
    }

    @Test fun sha256HexMatchesKnownVector() {
        val tmp = File.createTempFile("hash", ".bin").apply { writeBytes("hello".toByteArray()) }
        try {
            // sha256("hello")
            assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Hashing.sha256Hex(tmp)
            )
        } finally {
            tmp.delete()
        }
    }

    @Test fun sha256HexMissingFileNull() {
        assertEquals(null, Hashing.sha256Hex(File("/nonexistent/file")))
    }
}
