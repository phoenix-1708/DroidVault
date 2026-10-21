package com.phoenix.droidvault.models

/**
 * What a dry run shows, decided from the plan.
 *
 * ## Why this is a value and not a screen
 *
 * Two screens render a dry run — the backup one and the restore one — and
 * they used to be near-copies of each other, so every rule about what a
 * preview shows had to be written twice. The rules are here instead, in one
 * pure place, and both screens render what this says. A rule that lives in
 * two copies drifts, and the drift is invisible because the two sides only
 * have to agree with each other.
 *
 * ## The two decisions
 *
 * **1. A configuration that covers no apps shows no app rows.**
 *
 * `Device Data Backup` and `Device Data Restore` carry three device items and
 * no app rules. Their plan holds one entry per app on the device — 344 of
 * them, every one skipped with "no matching rule" — which says nothing about
 * the configuration and only that the configuration does not do apps. The
 * preview showed all 344 as a wall of identical rows, under a headline that
 * read "0 processable • 344 skipped", and its **Run button was dead**: the
 * button was enabled only when the plan had a processable app, so the one
 * screen that says "here is what this configuration will do" was also the
 * screen that refused to start it.
 *
 * The shape of a preview therefore comes from the CONFIGURATION
 * ([BackupConfig.coversApps]) and not from the plan's contents. A
 * configuration that has app rules but whose label matches nothing today
 * still shows its app part — otherwise the preview would claim it does
 * nothing, which is a different and false statement.
 *
 * **2. The apps a configuration does not cover are a COUNT, never a list.**
 *
 * The old preview listed every skipped app: 336 rows of "no matching rule"
 * for a configuration covering eight apps. The number is worth keeping — it
 * is what tells the user the configuration covers 8 of 344 apps — and the
 * rows were worth nothing beyond the sentence above them. So the rows are
 * gone and the sentence stays.
 */
data class PreviewLayout(
    /** The app plan is part of this preview. */
    val showApps: Boolean,
    /** The device-data part is part of this preview. */
    val showDeviceData: Boolean,
    /** The device-data items, in the enum's own order. */
    val deviceData: List<DeviceDataItem>,
    /** The folders packed alongside the device data. */
    val folders: List<String>,
    /** Apps the configuration does not cover — a count, never the rows. */
    val uncoveredCount: Int,
    /** The line under the title. */
    val headline: String,
    /** The line under the headline. */
    val note: String,
    /** The line that replaces the list of uncovered apps; null when there is none. */
    val uncoveredNote: String?,
    /** The run button is live: there is something for the run to do. */
    val canRun: Boolean
)

/**
 * The layout of a dry run for [plan].
 *
 * [operation] decides the wording ("restorable" vs "processable") and where
 * the uncovered apps are said to live — the vault for a restore, the phone
 * for a backup.
 */
fun previewLayout(plan: BackupPlan, operation: ConfigOperation): PreviewLayout {
    val showApps = plan.coversApps
    val deviceItems = DeviceDataItem.entries.filter { it in plan.deviceData }
    val showDeviceData = deviceItems.isNotEmpty() || plan.folderPaths.isNotEmpty()

    val excluded = plan.excluded.size
    val uncovered = (plan.skipped.size - excluded).coerceAtLeast(0)

    val headline = buildString {
        if (showApps) {
            append(plan.processable.size)
            append(if (operation == ConfigOperation.RESTORE) " restorable" else " processable")
            if (excluded > 0) append(" • $excluded excluded")
            if (uncovered > 0) append(" • $uncovered skipped")
            if (plan.withUnavailable.isNotEmpty()) {
                append(" • ${plan.withUnavailable.size} with unavailable components")
            }
        }
        if (showDeviceData) {
            if (isNotEmpty()) append(" • ")
            append(deviceItems.size)
            append(if (deviceItems.size == 1) " device data item" else " device data items")
        }
    }

    val note = if (showApps) {
        "Nothing in the vault is touched here. Switching an app off tells this " +
            "configuration to leave it alone, and is saved."
    } else {
        "Nothing in the vault is touched here. This configuration only touches " +
            "device data, so no apps are selected."
    }

    val uncoveredNote = if (showApps && uncovered > 0) {
        val where = if (operation == ConfigOperation.RESTORE) {
            "in the vault"
        } else {
            "on this phone"
        }
        "$uncovered other apps $where are not covered by this configuration. " +
            "Assign them a matching label, or change the fallback, to include them."
    } else {
        null
    }

    return PreviewLayout(
        showApps = showApps,
        showDeviceData = showDeviceData,
        deviceData = deviceItems,
        folders = plan.folderPaths,
        uncoveredCount = uncovered,
        headline = headline,
        note = note,
        uncoveredNote = uncoveredNote,
        canRun = plan.processable.isNotEmpty() || deviceItems.isNotEmpty() || plan.folderPaths.isNotEmpty()
    )
}
