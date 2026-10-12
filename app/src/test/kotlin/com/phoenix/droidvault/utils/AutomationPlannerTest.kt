package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.Capability
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.ErrorCode
import com.phoenix.droidvault.models.FallbackPolicy
import com.phoenix.droidvault.models.LabelAssignment
import com.phoenix.droidvault.models.PlannableApp
import com.phoenix.droidvault.models.toEngineIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Planner priority / exclusion / fallback / dry-run semantics (spec §7, §8, §14, §44). */
class AutomationPlannerTest {

    private val full = setOf(
        BackupComponent.APK, BackupComponent.PRIVATE_DATA,
        BackupComponent.MEDIA
    )
    private val apkData = setOf(BackupComponent.APK, BackupComponent.PRIVATE_DATA)
    private val apkOnly = setOf(BackupComponent.APK)

    private fun rootCaps(
        perm: Boolean = true,
        ssaid: Boolean = true
    ) = AutomationCapability.resolve(hasRoot = true, allowPermRestore = perm, allowSsaidRestore = ssaid)

    private val fullCaps = rootCaps()

    private fun cfg(vararg rules: ConfigRule, fallback: FallbackPolicy = FallbackPolicy.SKIP) =
        BackupConfig(id = "c1", name = "Daily", rules = rules.toList(), fallback = fallback)

    private fun app(pkg: String, label: String = "") = PlannableApp(pkg, label)

    @Test fun highestPriorityWins() {
        val config = cfg(
            ConfigRule("r1", "APKDATA", 2, apkData),
            ConfigRule("r2", "FULL", 1, full)
        )
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")),
            listOf(LabelAssignment("com.x", "FULL"), LabelAssignment("com.x", "APKDATA")),
            config, fullCaps
        )
        val e = plan.entries.single()
        assertEquals("r2", e.ruleId)
        assertEquals(full, e.granted)
        assertEquals(1, plan.processable.size) // backed up once, not twice
    }

    @Test fun reversedPrioritySelectsOtherRule() {
        val config = cfg(
            ConfigRule("r1", "APKDATA", 1, apkData),
            ConfigRule("r2", "FULL", 2, full)
        )
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")),
            listOf(LabelAssignment("com.x", "FULL"), LabelAssignment("com.x", "APKDATA")),
            config, fullCaps
        )
        assertEquals("r1", plan.entries.single().ruleId)
        assertEquals(apkData, plan.entries.single().granted)
    }

    @Test fun exclusionBeatsBackupRule() {
        val config = cfg(
            ConfigRule("r1", "FULL", 1, full),
            ConfigRule("r2", "NOBACKUP", 2, emptySet(), exclude = true)
        )
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")),
            listOf(LabelAssignment("com.x", "FULL"), LabelAssignment("com.x", "NOBACKUP")),
            config, fullCaps
        )
        val e = plan.entries.single()
        assertTrue(e.skipped)
        assertEquals("r2", e.ruleId)
        assertTrue(plan.processable.isEmpty())
    }

    @Test fun noMatchSkipFallback() {
        val config = cfg(ConfigRule("r1", "FULL", 1, full), fallback = FallbackPolicy.SKIP)
        val plan = BackupPlanner.plan(listOf(app("com.x", "X")), emptyList(), config, fullCaps)
        assertTrue(plan.entries.single().skipped)
    }

    @Test fun noMatchUseDefaultFallback() {
        val config = cfg(
            ConfigRule("r1", "FULL", 1, full),
            fallback = FallbackPolicy.USE_DEFAULT
        ).copy(defaultComponents = apkOnly)
        val plan = BackupPlanner.plan(listOf(app("com.x", "X")), emptyList(), config, fullCaps)
        val e = plan.entries.single()
        assertFalse(e.skipped)
        assertEquals(apkOnly, e.granted)
    }

    @Test fun capabilityDowngradeReportedNotSilent() {
        val caps = AutomationCapability.resolve(hasRoot = false, allowPermRestore = false, allowSsaidRestore = false)
        assertEquals(Capability.NORMAL, caps.mode)
        val config = cfg(ConfigRule("r1", "FULL", 1, full))
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")), listOf(LabelAssignment("com.x", "FULL")), config, caps
        )
        val e = plan.entries.single()
        assertTrue(e.granted.isEmpty())
        assertEquals(full, e.unavailable)
        assertEquals(ErrorCode.COMPONENT_UNSUPPORTED, e.error)
        assertTrue(plan.withUnavailable.size == 1)
    }

    @Test fun consentGatesAffectAvailability() {
        val caps = rootCaps(perm = false, ssaid = false)
        assertFalse(caps.available.contains(BackupComponent.PERMISSIONS))
        assertFalse(caps.available.contains(BackupComponent.ANDROID_ID))
        val want = setOf(BackupComponent.APK, BackupComponent.PERMISSIONS)
        val config = cfg(ConfigRule("r1", "L", 1, want))
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")), listOf(LabelAssignment("com.x", "L")), config, caps
        )
        val e = plan.entries.single()
        assertEquals(setOf(BackupComponent.APK), e.granted)
        assertEquals(setOf(BackupComponent.PERMISSIONS), e.unavailable)
    }

    @Test fun dryRunCounts() {
        val config = cfg(
            ConfigRule("r1", "FULL", 1, full),
            ConfigRule("r2", "APK", 2, apkOnly),
            ConfigRule("r3", "NO", 3, emptySet(), exclude = true)
        )
        val plan = BackupPlanner.plan(
            listOf(app("com.a", "A"), app("com.b", "B"), app("com.c", "C"), app("com.d", "D")),
            listOf(
                LabelAssignment("com.a", "FULL"),
                LabelAssignment("com.b", "APK"),
                LabelAssignment("com.c", "FULL"), LabelAssignment("com.c", "NO")
            ),
            config, fullCaps, dryRun = true
        )
        assertTrue(plan.dryRun)
        assertEquals(2, plan.processable.size) // a, b
        assertEquals(2, plan.skipped.size) // c excluded, d no-match
    }

    @Test fun uninstalledAppSkipped() {
        val config = cfg(ConfigRule("r1", "FULL", 1, full))
        val plan = BackupPlanner.plan(
            listOf(PlannableApp("com.x", "X", isInstalled = false)),
            listOf(LabelAssignment("com.x", "FULL")), config, fullCaps
        )
        val e = plan.entries.single()
        assertTrue(e.skipped)
        assertEquals(ErrorCode.APP_NOT_INSTALLED, e.error)
    }

    @Test fun unavailableReasonsExplainRootVsConsent() {
        assertEquals("needs root", AutomationCapability.reasonFor(BackupComponent.APK, false, false, false))
        assertEquals(
            "needs consent (Safety Consents → Restore permissions)",
            AutomationCapability.reasonFor(BackupComponent.PERMISSIONS, true, false, false)
        )
        assertEquals(
            "needs consent (Safety Consents → Restore Android ID)",
            AutomationCapability.reasonFor(BackupComponent.ANDROID_ID, true, true, false)
        )
        val config = cfg(ConfigRule("r1", "L", 1, setOf(BackupComponent.APK, BackupComponent.PERMISSIONS)))
        val caps = AutomationCapability.resolve(hasRoot = true, allowPermRestore = false, allowSsaidRestore = true)
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")), listOf(LabelAssignment("com.x", "L")), config, caps,
            unavailableReason = { c -> AutomationCapability.reasonFor(c, true, false, true) }
        )
        val e = plan.entries.single()
        assertEquals(
            "needs consent (Safety Consents → Restore permissions)",
            e.unavailableReasons[BackupComponent.PERMISSIONS]
        )
    }

    @Test fun restoreSkipsAppsWithoutBackupOnDisk() {
        val config = cfg(ConfigRule("r1", "L", 1, full)).copy(operation = ConfigOperation.RESTORE)
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")),
            listOf(LabelAssignment("com.x", "L")),
            config, fullCaps,
            availableOnDisk = mapOf("com.x" to emptySet())
        )
        val e = plan.entries.single()
        assertTrue(e.skipped)
        assertEquals(ErrorCode.BACKUP_SOURCE_MISSING, e.error)
    }

    @Test fun restoreGracefulKeepsOnlyPartsOnDisk() {
        val config = cfg(ConfigRule("r1", "L", 1, full)).copy(operation = ConfigOperation.RESTORE)
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")),
            listOf(LabelAssignment("com.x", "L")),
            config, fullCaps,
            availableOnDisk = mapOf("com.x" to setOf(1)) // only the APK shift exists
        )
        val e = plan.entries.single()
        assertFalse(e.skipped)
        assertEquals(apkOnly, e.granted)
    }

    @Test fun restoreStrictPlansFullRequest() {
        val config = cfg(ConfigRule("r1", "L", 1, full))
            .copy(operation = ConfigOperation.RESTORE, restoreGraceful = false)
        val plan = BackupPlanner.plan(
            listOf(app("com.x", "X")),
            listOf(LabelAssignment("com.x", "L")),
            config, fullCaps,
            availableOnDisk = mapOf("com.x" to setOf(1)) // only the APK shift exists
        )
        val e = plan.entries.single()
        assertFalse(e.skipped)
        assertEquals(full, e.granted)
    }

    @Test fun restoreIncludesUninstalledApps() {
        val config = cfg(ConfigRule("r1", "L", 1, full)).copy(operation = ConfigOperation.RESTORE)
        val plan = BackupPlanner.plan(
            listOf(PlannableApp("com.x", "X", isInstalled = false)),
            listOf(LabelAssignment("com.x", "L")),
            config, fullCaps,
            availableOnDisk = mapOf("com.x" to setOf(1, 2, 4))
        )
        val e = plan.entries.single()
        assertFalse(e.skipped)
        assertEquals(full, e.granted)
    }

    @Test fun backupStillSkipsUninstalledAndIgnoresDiskInfo() {
        val config = cfg(ConfigRule("r1", "L", 1, full))
        val plan = BackupPlanner.plan(
            listOf(PlannableApp("com.x", "X", isInstalled = false)),
            listOf(LabelAssignment("com.x", "L")),
            config, fullCaps,
            availableOnDisk = mapOf("com.x" to setOf(1, 2, 4))
        )
        val e = plan.entries.single()
        assertTrue(e.skipped)
        assertEquals(ErrorCode.APP_NOT_INSTALLED, e.error)
    }

    @Test fun engineIdMappingRoundTrip() {
        assertEquals(setOf(1, 2, 3, 4, 5), BackupComponent.entries.map { it.engineId }.toSet())
        assertEquals(
            setOf(BackupComponent.APK, BackupComponent.MEDIA),
            BackupComponent.fromEngineIds(setOf(1, 4, 99))
        )
        assertEquals(setOf(1, 4), setOf(BackupComponent.APK, BackupComponent.MEDIA).toEngineIds())
    }

    @Test fun validationCatchesProblems() {
        val bad = BackupConfig(
            id = "c", name = " ",
            rules = listOf(
                ConfigRule("r1", "MISSING", 1, apkOnly),
                ConfigRule("r2", "L", 1, emptySet())
            )
        )
        val errors = validateAutomationConfig(bad, setOf("L"))
        assertTrue(errors.any { it.contains("name") })
        assertTrue(errors.any { it.contains("unknown label") })
        assertTrue(errors.any { it.contains("no components") })
        assertTrue(errors.any { it.contains("duplicate priorities") })

        val ok = BackupConfig(
            id = "c", name = "Daily",
            rules = listOf(ConfigRule("r1", "L", 1, apkOnly))
        )
        assertTrue(validateAutomationConfig(ok, setOf("L")).isEmpty())
        // Exclusion rules need no components.
        val excl = ok.copy(rules = listOf(ConfigRule("r1", "L", 1, emptySet(), exclude = true)))
        assertTrue(validateAutomationConfig(excl, setOf("L")).isEmpty())
    }
}
