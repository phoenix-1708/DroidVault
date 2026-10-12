package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.BackupPlan
import com.phoenix.droidvault.models.CapabilitySet
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.ErrorCode
import com.phoenix.droidvault.models.FallbackPolicy
import com.phoenix.droidvault.models.LabelAssignment
import com.phoenix.droidvault.models.PlannableApp
import com.phoenix.droidvault.models.PlannedApp

/**
 * Pure planner: installed apps + labels + config + capabilities -> plan.
 * No I/O, no root, no side effects. Dry run produces the identical plan with
 * [dryRun]=true; the runner must execute nothing when it is set.
 *
 * Determinism: rules sorted by (priority, id); highest priority wins; each app
 * planned exactly once; exclusions beat restore/backup rules; no-match ->
 * fallback. BACKUP plans the exact rule set; RESTORE additionally knows which
 * components exist on disk (via [availableOnDisk]) and applies the same
 * graceful "restore what's there" behavior as the Migrate tab.
 */
object BackupPlanner {

    // Skip reasons produced by planOne. "No matching rule" and exclusions are
    // the config's scope working as designed; the other two mean the config
    // wanted the app but the device cannot provide it. Keep in sync with the
    // classifier below — the runner's aggregate uses it to decide
    // SUCCESS vs PARTIAL_SUCCESS.
    const val SKIP_NOT_INSTALLED = "not installed"
    const val SKIP_NO_MATCHING_RULE = "no matching rule"
    const val SKIP_EXCLUDED_PREFIX = "excluded by rule"
    const val SKIP_NO_BACKUP_ON_DEVICE = "no backup on device"

    /**
     * Pure aggregate-seam decision (unit-testable): is this plan skip "by
     * design" (app outside the config's scope, or explicitly excluded — the
     * run did exactly what the config asked) or a genuine shortfall the user
     * should see (BACKUP of an uninstalled labelled app; RESTORE with nothing
     * on disk)? A rule-based config that matches 1 of 468 apps must report
     * SUCCESS, not PARTIAL_SUCCESS.
     */
    fun isByDesignSkip(reason: String): Boolean =
        reason == SKIP_NO_MATCHING_RULE || reason.startsWith(SKIP_EXCLUDED_PREFIX)

    fun plan(
        apps: List<PlannableApp>,
        assignments: List<LabelAssignment>,
        config: BackupConfig,
        capabilities: CapabilitySet,
        dryRun: Boolean = false,
        /** Human reason per unavailable component (root vs consent vs unsupported). */
        unavailableReason: (BackupComponent) -> String = { "unavailable" },
        /**
         * RESTORE only: packageName -> engine IDs of components that actually
         * exist in the backup folder. Null (BACKUP, or callers without disk
         * info) plans the rule set as-is.
         */
        availableOnDisk: Map<String, Set<Int>>? = null
    ): BackupPlan {
        val byPkg: Map<String, Set<String>> = assignments
            .groupBy({ it.packageName }, { it.labelId })
            .mapValues { it.value.toSet() }
        val sortedRules = config.rules.sortedWith(compareBy({ it.priority }, { it.id }))
        val entries = apps.distinctBy { it.packageName }.map { app ->
            planOne(
                app, byPkg[app.packageName] ?: emptySet(), config, sortedRules, capabilities,
                unavailableReason, availableOnDisk?.get(app.packageName)
            )
        }
        return BackupPlan(
            configId = config.id,
            configName = config.name,
            dryRun = dryRun,
            entries = entries
        )
    }

    private fun planOne(
        app: PlannableApp,
        labelIds: Set<String>,
        config: BackupConfig,
        sortedRules: List<com.phoenix.droidvault.models.ConfigRule>,
        capabilities: CapabilitySet,
        unavailableReason: (BackupComponent) -> String,
        onDisk: Set<Int>?
    ): PlannedApp {
        // BACKUP: an uninstalled app has nothing on the device to back up.
        // RESTORE: an uninstalled app is exactly the target (restore = install
        // from the backup folder, same as the Migrate tab's restore list).
        if (!app.isInstalled && config.operation != ConfigOperation.RESTORE) {
            return PlannedApp(
                packageName = app.packageName,
                label = app.label,
                ruleId = null,
                requested = emptySet(),
                granted = emptySet(),
                unavailable = emptySet(),
                skipped = true,
                skipReason = SKIP_NOT_INSTALLED,
                error = ErrorCode.APP_NOT_INSTALLED
            )
        }
        val matching = sortedRules.filter { it.labelId in labelIds }
        // Explicit exclusion wins over every backup rule.
        val exclusion = matching.firstOrNull { it.exclude }
        if (exclusion != null) {
            return PlannedApp(
                packageName = app.packageName,
                label = app.label,
                ruleId = exclusion.id,
                requested = emptySet(),
                granted = emptySet(),
                unavailable = emptySet(),
                skipped = true,
                skipReason = "$SKIP_EXCLUDED_PREFIX ${exclusion.id}",
                error = ErrorCode.NONE
            )
        }
        val winner = matching.firstOrNull { !it.exclude }
        if (winner == null) {
            return when (config.fallback) {
                FallbackPolicy.SKIP -> PlannedApp(
                    packageName = app.packageName,
                    label = app.label,
                    ruleId = null,
                    requested = emptySet(),
                    granted = emptySet(),
                    unavailable = emptySet(),
                    skipped = true,
                    skipReason = SKIP_NO_MATCHING_RULE,
                    error = ErrorCode.NONE
                )
                FallbackPolicy.USE_DEFAULT -> split(
                    app, null, config.defaultComponents, capabilities,
                    ErrorCode.NONE, unavailableReason, config, onDisk
                )
            }
        }
        return split(app, winner.id, winner.components, capabilities, ErrorCode.NONE, unavailableReason, config, onDisk)
    }

    private fun split(
        app: PlannableApp,
        ruleId: String?,
        requested: Set<BackupComponent>,
        capabilities: CapabilitySet,
        error: ErrorCode,
        unavailableReason: (BackupComponent) -> String,
        config: BackupConfig,
        onDisk: Set<Int>?
    ): PlannedApp {
        // RESTORE: intersect the requested set with what the backup folder
        // actually holds, mirroring the Migrate tab (it restores whatever
        // shift files exist). Not requested + not on disk => skip with an
        // honest reason ("no backup on device").
        val effectiveRequested = if (config.operation == ConfigOperation.RESTORE && onDisk != null) {
            if (onDisk.isEmpty()) {
                return PlannedApp(
                    packageName = app.packageName,
                    label = app.label,
                    ruleId = ruleId,
                    requested = requested,
                    granted = emptySet(),
                    unavailable = emptySet(),
                    unavailableReasons = emptyMap(),
                    skipped = true,
                    skipReason = SKIP_NO_BACKUP_ON_DEVICE,
                    error = ErrorCode.BACKUP_SOURCE_MISSING
                )
            }
            if (config.restoreGraceful) {
                requested.filter { it.engineId in onDisk }.toSet()
            } else {
                requested
            }
        } else {
            requested
        }
        val granted = effectiveRequested intersect capabilities.available
        val unavailable = effectiveRequested - capabilities.available
        val err = when {
            error != ErrorCode.NONE -> error
            effectiveRequested.isNotEmpty() && granted.isEmpty() -> ErrorCode.COMPONENT_UNSUPPORTED
            else -> ErrorCode.NONE
        }
        return PlannedApp(
            packageName = app.packageName,
            label = app.label,
            ruleId = ruleId,
            requested = requested,
            granted = granted,
            unavailable = unavailable,
            unavailableReasons = unavailable.associateWith(unavailableReason),
            skipped = false,
            skipReason = "",
            error = err
        )
    }
}
