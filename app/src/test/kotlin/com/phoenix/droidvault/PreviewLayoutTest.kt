package com.phoenix.droidvault

import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.BackupPlan
import com.phoenix.droidvault.models.Capability
import com.phoenix.droidvault.models.CapabilitySet
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.PlannableApp
import com.phoenix.droidvault.models.previewLayout
import com.phoenix.droidvault.utils.BackupPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dry run shows what the configuration WILL do.
 *
 * Two decisions came out of the field screenshots, and both are about the same
 * mistake: a preview that lists what a configuration will NOT do.
 *
 *  - The apps a configuration does not cover were a LIST: 336 rows of "no
 *    matching rule" for a configuration covering eight apps. The number is
 *    worth keeping; the rows were not.
 *  - A configuration that only does device data was shown 344 apps it does
 *    not cover, under a headline that read "0 processable", and its Run
 *    button was DEAD — enabled on the app count alone.
 */
class PreviewLayoutTest {

    private fun config(
        operation: ConfigOperation = ConfigOperation.BACKUP,
        rules: List<ConfigRule> = emptyList(),
        fallback: com.phoenix.droidvault.models.FallbackPolicy =
            com.phoenix.droidvault.models.FallbackPolicy.SKIP,
        deviceData: Set<DeviceDataItem> = emptySet(),
        excluded: Set<String> = emptySet()
    ) = BackupConfig(
        id = "cfg-1",
        name = "Test",
        operation = operation,
        rules = rules,
        fallback = fallback,
        deviceData = deviceData,
        excludedPackages = excluded
    )

    private fun rule(
        id: String = "rule-1",
        exclude: Boolean = false,
        components: Set<BackupComponent> = setOf(BackupComponent.APK)
    ) = ConfigRule(
        id = id,
        labelId = "label-1",
        priority = 0,
        components = components,
        exclude = exclude
    )

    private val caps = CapabilitySet(
        mode = Capability.ROOT,
        available = setOf(BackupComponent.APK)
    )

    /** A plan of [apps] installed apps, [covered] of them covered by the rule. */
    private fun plan(
        cfg: BackupConfig,
        apps: Int = 10,
        covered: Int = 2,
        excluded: Set<String> = emptySet()
    ): BackupPlan {
        val assignments = (0 until covered).map { i ->
            com.phoenix.droidvault.models.LabelAssignment(
                packageName = "com.covered.$i",
                labelId = "label-1",
                labelName = "FULL",
                createdAt = 0L
            )
        }
        val plannable = (0 until apps).map { i ->
            PlannableApp(
                packageName = if (i < covered) "com.covered.$i" else "com.other.$i",
                label = "App $i",
                isSystem = false,
                isInstalled = true
            )
        } + excluded.map { PlannableApp(it, it, isSystem = false, isInstalled = true) }
        return BackupPlanner.plan(plannable, assignments, cfg, caps, dryRun = true)
    }

    // --- the shape of a preview -------------------------------------------

    @Test
    fun `a device-data configuration shows no app rows`() {
        val cfg = config(deviceData = setOf(DeviceDataItem.MESSAGES, DeviceDataItem.CALLS))
        val layout = previewLayout(plan(cfg, apps = 344, covered = 0), ConfigOperation.BACKUP)

        assertFalse(
            "a configuration that does no apps must not show an app list — " +
                "the rows were 344 skips of 'no matching rule', which says " +
                "nothing about the configuration",
            layout.showApps
        )
        assertTrue(layout.showDeviceData)
        assertEquals(
            listOf(DeviceDataItem.MESSAGES, DeviceDataItem.CALLS),
            layout.deviceData
        )
    }

    @Test
    fun `a device-data configuration says it has no apps`() {
        val cfg = config(deviceData = setOf(DeviceDataItem.MESSAGES))
        val layout = previewLayout(plan(cfg, apps = 344, covered = 0), ConfigOperation.BACKUP)

        assertEquals("1 device data item", layout.headline)
        assertTrue(
            "the note must not promise an app switch that does not exist",
            layout.note.contains("device data")
        )
        assertFalse(layout.note.contains("Switching an app off"))
    }

    @Test
    fun `a device-data configuration can be run`() {
        // The button used to be enabled on the app count alone, so the
        // preview of "Device Data Backup" could not start it.
        val cfg = config(deviceData = setOf(DeviceDataItem.MESSAGES))
        val layout = previewLayout(plan(cfg, apps = 344, covered = 0), ConfigOperation.BACKUP)

        assertTrue(
            "a configuration with device data to do has a run to start",
            layout.canRun
        )
    }

    @Test
    fun `a configuration with nothing in it cannot be run`() {
        val cfg = config()
        val layout = previewLayout(plan(cfg, apps = 344, covered = 0), ConfigOperation.BACKUP)

        assertFalse(layout.canRun)
        assertFalse(layout.showApps)
        assertFalse(layout.showDeviceData)
    }

    @Test
    fun `a configuration with a rule but no match still shows its app part`() {
        // The rule covers a label that nothing carries today. The preview
        // must not claim the configuration does nothing: that is a different
        // and false statement.
        val cfg = config(rules = listOf(rule()))
        val layout = previewLayout(plan(cfg, apps = 344, covered = 0), ConfigOperation.BACKUP)

        assertTrue(layout.showApps)
        assertTrue("0 processable" in layout.headline)
    }

    @Test
    fun `a configuration whose only rule excludes a label covers no apps`() {
        // An exclusion is not a grant: with a SKIP fallback, a configuration
        // that only excludes a label does no apps at all.
        val cfg = config(rules = listOf(rule(exclude = true)))
        val layout = previewLayout(plan(cfg, apps = 344, covered = 0), ConfigOperation.BACKUP)

        assertFalse(layout.showApps)
    }

    @Test
    fun `a default fallback covers the apps no rule matched`() {
        val cfg = config(
            rules = emptyList(),
            fallback = com.phoenix.droidvault.models.FallbackPolicy.USE_DEFAULT
        )
        val layout = previewLayout(plan(cfg, apps = 10, covered = 0), ConfigOperation.BACKUP)

        assertTrue(
            "USE_DEFAULT grants the default components to every unmatched " +
                "app, so the configuration does do apps",
            layout.showApps
        )
    }

    // --- the apps a configuration does not cover ---------------------------

    @Test
    fun `the uncovered apps are a count and a sentence, never a list`() {
        val cfg = config(rules = listOf(rule()))
        val layout = previewLayout(plan(cfg, apps = 10, covered = 2), ConfigOperation.BACKUP)

        assertEquals(8, layout.uncoveredCount)
        assertNotNull(
            "the sentence that says what to do about them must survive",
            layout.uncoveredNote
        )
        val note = layout.uncoveredNote ?: ""
        assertTrue("8 other apps" in note)
        assertTrue(
            "the sentence must name the fix",
            note.contains("matching label")
        )
    }

    @Test
    fun `the uncovered count is in the headline`() {
        val cfg = config(rules = listOf(rule()))
        val layout = previewLayout(plan(cfg, apps = 10, covered = 2), ConfigOperation.BACKUP)

        assertTrue("8 skipped" in layout.headline)
    }

    @Test
    fun `an excluded app is not counted as uncovered`() {
        val cfg = config(rules = listOf(rule()), excluded = setOf("com.other.2"))
        val layout = previewLayout(plan(cfg, apps = 10, covered = 2, excluded = setOf("com.other.2")), ConfigOperation.BACKUP)

        assertEquals(
            "an app the user excluded is their own decision, not an app the " +
                "configuration failed to cover",
            7,
            layout.uncoveredCount
        )
        assertTrue("1 excluded" in layout.headline)
    }

    @Test
    fun `a configuration that covers everything says nothing about what it misses`() {
        val cfg = config(rules = listOf(rule()))
        val layout = previewLayout(plan(cfg, apps = 2, covered = 2), ConfigOperation.BACKUP)

        assertEquals(0, layout.uncoveredCount)
        assertNull(
            "a sentence about apps that are not covered would be noise",
            layout.uncoveredNote
        )
    }

    @Test
    fun `a restore names the vault and a backup names the phone`() {
        val cfg = config(rules = listOf(rule()))
        val backup = previewLayout(plan(cfg, apps = 10, covered = 2), ConfigOperation.BACKUP)
        val restore = previewLayout(
            plan(cfg.copy(operation = ConfigOperation.RESTORE), apps = 10, covered = 2),
            ConfigOperation.RESTORE
        )

        assertTrue("on this phone" in (backup.uncoveredNote ?: ""))
        assertTrue("in the vault" in (restore.uncoveredNote ?: ""))
        assertTrue("restorable" in restore.headline)
        assertTrue("processable" in backup.headline)
    }
}
