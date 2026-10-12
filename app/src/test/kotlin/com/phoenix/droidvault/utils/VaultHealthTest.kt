package com.phoenix.droidvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the §20 batch: skip classification (run-status
 * accuracy), the restore-hint row count, and vault-mirror health.
 */
class VaultHealthTest {

    // ---------- skip classification (partial_success fix) ----------

    @Test
    fun `scope skips are by design`() {
        assertTrue(BackupPlanner.isByDesignSkip(BackupPlanner.SKIP_NO_MATCHING_RULE))
        assertTrue(BackupPlanner.isByDesignSkip("excluded by rule abc-123"))
    }

    @Test
    fun `shortfall skips are genuine`() {
        assertFalse(BackupPlanner.isByDesignSkip(BackupPlanner.SKIP_NOT_INSTALLED))
        assertFalse(BackupPlanner.isByDesignSkip(BackupPlanner.SKIP_NO_BACKUP_ON_DEVICE))
    }

    @Test
    fun `exclusion prefix does not swallow other reasons`() {
        assertFalse(BackupPlanner.isByDesignSkip("not installed"))
        assertFalse(BackupPlanner.isByDesignSkip(""))
    }

    @Test
    fun `rule config covering one app of many is not a shortfall`() {
        // The field scenario: 1 matching app, 467 "no matching rule" skips,
        // 0 shortfalls -> a SUCCESS run, not PARTIAL_SUCCESS.
        val reasons = listOf("no matching rule") + List(466) { BackupPlanner.SKIP_NO_MATCHING_RULE }
        val shortfalls = reasons.count { !BackupPlanner.isByDesignSkip(it) }
        assertEquals(0, shortfalls)
    }

    // ---------- restore hint ----------

    @Test
    fun `restore hint totals all row types`() {
        val hint = AutomationStore.RestoreHint(labels = 1, assignments = 1, configs = 1, schedules = 2, at = 0L)
        assertEquals(5, hint.totalRows)
    }

    @Test
    fun `restore hint zero rows still computes`() {
        val hint = AutomationStore.RestoreHint(0, 0, 0, 0, 0L)
        assertEquals(0, hint.totalRows)
    }

    // ---------- vault health ----------

    private fun info(
        name: String,
        present: Boolean,
        modifiedAt: Long = 0L
    ): AutomationSnapshot.VaultFileInfo =
        AutomationSnapshot.VaultFileInfo(
            name = name,
            path = "/storage/emulated/0/DroidVault/$name",
            present = present,
            sizeBytes = if (present) 100L else 0L,
            modifiedAt = modifiedAt
        )

    private fun files(
        apkAt: Long = 0L,
        backendPresent: Boolean = true,
        launcherPresent: Boolean = true,
        statePresent: Boolean = true
    ) = listOf(
        info(AutomationSnapshot.APK_FILE, present = true, modifiedAt = apkAt),
        info(AutomationSnapshot.BACKEND_FILE, present = backendPresent),
        info(AutomationSnapshot.LAUNCHER_FILE, present = launcherPresent),
        info(AutomationSnapshot.STATE_FILE, present = statePresent)
    )

    @Test
    fun `all present and current is healthy`() {
        val health = AutomationSnapshot.computeVaultHealth(
            files = files(apkAt = 5_000L),
            apkUpdatedAt = 4_000L,
            engineMirrorHash = "aa",
            engineSourceHash = "aa",
            launcherMirrorHash = "cc",
            launcherSourceHash = "cc"
        )
        assertEquals(
            AutomationSnapshot.VaultHealth.OK,
            health[AutomationSnapshot.APK_FILE]
        )
        assertEquals(
            AutomationSnapshot.VaultHealth.OK,
            health[AutomationSnapshot.BACKEND_FILE]
        )
        assertEquals(AutomationSnapshot.VaultHealth.OK, health[AutomationSnapshot.LAUNCHER_FILE])
        assertEquals(
            AutomationSnapshot.VaultHealth.OK,
            health[AutomationSnapshot.STATE_FILE]
        )
    }

    @Test
    fun `apk mirror older than installed app is stale`() {
        val health = AutomationSnapshot.computeVaultHealth(
            files = files(apkAt = 3_000L),
            apkUpdatedAt = 9_000L,
            engineMirrorHash = "aa",
            engineSourceHash = "aa"
        )
        assertEquals(AutomationSnapshot.VaultHealth.STALE, health[AutomationSnapshot.APK_FILE])
    }

    @Test
    fun `missing mirrors are missing`() {
        val health = AutomationSnapshot.computeVaultHealth(
            files = files(backendPresent = false, launcherPresent = false, statePresent = false),
            apkUpdatedAt = 0L,
            engineMirrorHash = null,
            engineSourceHash = "bb"
        )
        assertEquals(AutomationSnapshot.VaultHealth.MISSING, health[AutomationSnapshot.APK_FILE].let {
            // APK row still present in files(); exercised separately below.
            AutomationSnapshot.computeVaultHealth(
                files = listOf(info(AutomationSnapshot.APK_FILE, present = false)),
                apkUpdatedAt = 0L, engineMirrorHash = null, engineSourceHash = null
            )[AutomationSnapshot.APK_FILE]
        })
        assertEquals(AutomationSnapshot.VaultHealth.MISSING, health[AutomationSnapshot.BACKEND_FILE])
        assertEquals(AutomationSnapshot.VaultHealth.MISSING, health[AutomationSnapshot.LAUNCHER_FILE])
        assertEquals(AutomationSnapshot.VaultHealth.MISSING, health[AutomationSnapshot.STATE_FILE])
    }

    @Test
    fun `engine mirror stale when source differs`() {
        val health = AutomationSnapshot.computeVaultHealth(
            files = files(),
            apkUpdatedAt = 0L,
            engineMirrorHash = "aa",
            engineSourceHash = "bb"
        )
        assertEquals(AutomationSnapshot.VaultHealth.STALE, health[AutomationSnapshot.BACKEND_FILE])
    }

    @Test
    fun `engine mirror ok when source not yet deployed`() {
        val health = AutomationSnapshot.computeVaultHealth(
            files = files(),
            apkUpdatedAt = 0L,
            engineMirrorHash = "aa",
            engineSourceHash = null
        )
        assertEquals(AutomationSnapshot.VaultHealth.OK, health[AutomationSnapshot.BACKEND_FILE])
    }

    @Test
    fun `unknown installed-update time never marks stale`() {
        val health = AutomationSnapshot.computeVaultHealth(
            files = files(apkAt = 1_000L),
            apkUpdatedAt = 0L,
            engineMirrorHash = "aa",
            engineSourceHash = "aa"
        )
        assertEquals(AutomationSnapshot.VaultHealth.OK, health[AutomationSnapshot.APK_FILE])
    }

    // ---------- pure helpers parity ----------

    @Test
    fun `apkMirrorHealth pure function matches table`() {
        assertEquals(
            AutomationSnapshot.VaultHealth.MISSING,
            AutomationSnapshot.apkMirrorHealth(present = false, mirrorModifiedAt = 0L, installedUpdatedAt = 5L)
        )
        assertEquals(
            AutomationSnapshot.VaultHealth.STALE,
            AutomationSnapshot.apkMirrorHealth(present = true, mirrorModifiedAt = 4L, installedUpdatedAt = 5L)
        )
        assertEquals(
            AutomationSnapshot.VaultHealth.OK,
            AutomationSnapshot.apkMirrorHealth(present = true, mirrorModifiedAt = 5L, installedUpdatedAt = 5L)
        )
    }

    @Test
    fun `engineMirrorHealth pure function matches table`() {
        assertEquals(
            AutomationSnapshot.VaultHealth.MISSING,
            AutomationSnapshot.engineMirrorHealth(present = false, sourcePresent = true, hashesMatch = false)
        )
        assertEquals(
            AutomationSnapshot.VaultHealth.STALE,
            AutomationSnapshot.engineMirrorHealth(present = true, sourcePresent = true, hashesMatch = false)
        )
        assertEquals(
            AutomationSnapshot.VaultHealth.OK,
            AutomationSnapshot.engineMirrorHealth(present = true, sourcePresent = true, hashesMatch = true)
        )
        assertEquals(
            AutomationSnapshot.VaultHealth.OK,
            AutomationSnapshot.engineMirrorHealth(present = true, sourcePresent = false, hashesMatch = false)
        )
    }

    @Test
    fun `parseStatLine unchanged contract`() {
        assertEquals(7L to 9L, AutomationSnapshot.parseStatLine("7 9"))
        assertEquals(null, AutomationSnapshot.parseStatLine("x y"))
    }
}
