package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.LabelAssignment
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.models.PlannableApp
import com.phoenix.droidvault.models.toEngineIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationRunnerTest {

    private fun appInfo(pkg: String) = AppInfo(
        label = pkg.substringAfterLast("."),
        packageName = pkg
    )

    private val caps = AutomationCapability.resolve(
        hasRoot = true, allowPermRestore = true, allowSsaidRestore = true
    )

    @Test fun mapsPlanToEngineAppsWithGrantedComponents() {
        val config = BackupConfig(
            id = "c", name = "n",
            rules = listOf(ConfigRule("r", "L", 1, setOf(BackupComponent.APK, BackupComponent.MEDIA)))
        )
        val plan = BackupPlanner.plan(
            listOf(PlannableApp("com.a", "A"), PlannableApp("com.b", "B")),
            listOf(LabelAssignment("com.a", "L")),
            config, caps
        )
        val engine = AutomationRunner.toEngineApps(
            plan, listOf(appInfo("com.a"), appInfo("com.b"), appInfo("com.c"))
        )
        // Only processable + matched apps; com.b skipped (no rule), com.c not in plan.
        assertEquals(listOf("com.a"), engine.map { it.packageName })
        assertEquals(setOf(1, 4), engine.single().activeComponents)
    }

    @Test fun buildStateRespectsOperation() {
        val config = BackupConfig(id = "c", name = "n")
        val plan = BackupPlanner.plan(emptyList(), emptyList(), config, caps)
        assertEquals(
            MigratorMode.BACKUP_APPS,
            AutomationRunner.buildState(plan, ConfigOperation.BACKUP).migratorMode
        )
        assertEquals(
            MigratorMode.RESTORE_APPS,
            AutomationRunner.buildState(plan, ConfigOperation.RESTORE).migratorMode
        )
        // Empty plan falls back to APK-only global set.
        assertEquals(setOf(1), AutomationRunner.buildState(plan, ConfigOperation.BACKUP).globalComponents)
    }

    @Test fun operationCodecRoundTripAndDefault() {
        val c = BackupConfig(id = "c", name = "n", operation = ConfigOperation.RESTORE)
        val back = AutomationCodec.configFrom(AutomationCodec.configTo(c))
        assertEquals(ConfigOperation.RESTORE, back.operation)
        // Old exports without the field default to BACKUP.
        val legacy = AutomationCodec.configFrom(
            org.json.JSONObject().put("id", "c").put("name", "n")
                .put("rules", org.json.JSONArray())
        )
        assertEquals(ConfigOperation.BACKUP, legacy.operation)
        assertTrue(legacy.rules.isEmpty())
    }

    @Test fun grantedMappingMatchesEngineIds() {
        val want = setOf(BackupComponent.APK, BackupComponent.PRIVATE_DATA, BackupComponent.ANDROID_ID)
        assertEquals(setOf(1, 2, 5), want.toEngineIds())
    }
}
