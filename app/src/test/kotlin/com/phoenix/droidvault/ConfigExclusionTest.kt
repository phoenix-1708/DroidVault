package com.phoenix.droidvault

import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.Capability
import com.phoenix.droidvault.models.CapabilitySet
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.LabelAssignment
import com.phoenix.droidvault.models.PlannableApp
import com.phoenix.droidvault.utils.BackupPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A configuration covers a whole LABEL — and the user wants to leave out the
 * individual apps they do not want, without giving the label up.
 *
 * The exclusion is recorded by PACKAGE NAME, and it wins over every rule: an
 * app the user named is not touched, whatever the labels say.
 */
class ConfigExclusionTest {

    private val labelId = "label-1"

    private fun assignment(pkg: String) = LabelAssignment(
        packageName = pkg,
        labelId = labelId,
        labelName = "FULL",
        createdAt = 0L
    )

    private fun config(excluded: Set<String> = emptySet()) = BackupConfig(
        id = "cfg-1",
        name = "Apps",
        operation = com.phoenix.droidvault.models.ConfigOperation.RESTORE,
        rules = listOf(
            ConfigRule(
                id = "rule-1",
                labelId = labelId,
                priority = 0,
                components = setOf(BackupComponent.APK, BackupComponent.PRIVATE_DATA)
            )
        ),
        excludedPackages = excluded
    )

    private val caps = CapabilitySet(
        mode = Capability.ROOT,
        available = setOf(BackupComponent.APK, BackupComponent.PRIVATE_DATA)
    )

    private fun plan(excluded: Set<String> = emptySet()) = BackupPlanner.plan(
        apps = listOf(
            PlannableApp("com.keep", "Keep", isSystem = false, isInstalled = true),
            PlannableApp("com.leave", "Leave", isSystem = false, isInstalled = true)
        ),
        assignments = listOf(assignment("com.keep"), assignment("com.leave")),
        config = config(excluded),
        capabilities = caps,
        dryRun = true
    )

    @Test
    fun `an excluded app is not in the plan`() {
        val p = plan(setOf("com.leave"))
        assertEquals(
            "only the app the user did not exclude may be restored",
            listOf("com.keep"),
            p.processable.map { it.packageName }
        )
    }

    @Test
    fun `an excluded app says so, and says it differently from a rule skip`() {
        val p = plan(setOf("com.leave"))
        val excluded = p.entries.first { it.packageName == "com.leave" }
        assertTrue("the excluded app must be skipped", excluded.skipped)
        assertTrue(
            "the exclusion must be marked as the user's own decision, so the " +
                "preview can offer to undo it",
            excluded.excluded
        )
        assertEquals(
            BackupPlanner.SKIP_EXCLUDED_BY_CONFIG,
            excluded.skipReason
        )
        assertEquals(
            "the excluded apps get a group of their own",
            listOf("com.leave"),
            p.excluded.map { it.packageName }
        )
    }

    @Test
    fun `an exclusion wins over the rule that would have covered the app`() {
        // The label covers it and the rule grants it everything — and the app is
        // still not in the plan.
        val p = plan(setOf("com.leave"))
        val excluded = p.entries.first { it.packageName == "com.leave" }
        assertTrue(excluded.skipped)
        assertTrue("nothing may be granted to an excluded app", excluded.granted.isEmpty())
        assertTrue("nothing may be requested of an excluded app", excluded.requested.isEmpty())
    }

    @Test
    fun `an exclusion is a skip by design, not a shortfall`() {
        // The run did exactly what the configuration was told, so it must not be
        // reported as a partial failure.
        assertTrue(
            BackupPlanner.isByDesignSkip(BackupPlanner.SKIP_EXCLUDED_BY_CONFIG)
        )
    }

    @Test
    fun `an app that is not excluded is untouched by the exclusion list`() {
        val p = plan(setOf("com.leave"))
        val kept = p.entries.first { it.packageName == "com.keep" }
        assertFalse("an app the user did not exclude must still be planned", kept.skipped)
        assertFalse(kept.excluded)
        assertTrue(
            "and it must still be granted what its rule grants",
            kept.granted.contains(BackupComponent.PRIVATE_DATA)
        )
    }

    @Test
    fun `an exclusion does not need the app to be in the plan at all`() {
        // Naming an app that no label covers is harmless: it is excluded, and
        // nothing else moves.
        val p = plan(setOf("com.not.in.any.label"))
        assertEquals(2, p.entries.size)
        assertEquals(listOf("com.keep", "com.leave"), p.processable.map { it.packageName })
    }
}
