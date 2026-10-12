package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * Phase 1 automation foundation: labels (WHICH apps) vs configurations (WHAT to do).
 * Labels never carry behavior; rules map labelId -> structured components.
 * Engine IDs reused at the boundary: 1=APK, 2=Data, 3=Perm, 4=Media, 5=AndID.
 */

enum class BackupComponent(val engineId: Int) {
    APK(1),
    PRIVATE_DATA(2),
    PERMISSIONS(3),
    MEDIA(4),
    ANDROID_ID(5);

    companion object {
        fun fromEngineId(id: Int): BackupComponent? = entries.find { it.engineId == id }
        fun fromEngineIds(ids: Set<Int>): Set<BackupComponent> = ids.mapNotNull(::fromEngineId).toSet()
    }

    fun toEngineId(): Int = engineId
}

fun Set<BackupComponent>.toEngineIds(): Set<Int> = map { it.engineId }.toSet()

/**
 * Device-level data kinds (Messages, Calls, Contacts, Wi-Fi, Wallpaper,
 * Bluetooth, user-picked Folders) that a configuration can carry in addition
 * to app rules. Names are persisted; never reorder — appending is the only
 * safe change.
 */
enum class DeviceDataItem(val label: String) {
    MESSAGES("Messages"),
    CALLS("Calls"),
    CONTACTS("Contacts"),
    WIFI("Wi-Fi"),
    WALLPAPER("Wallpaper"),
    BLUETOOTH("Bluetooth"),
    FOLDERS("Folders");

    companion object {
        fun fromName(name: String): DeviceDataItem? = entries.find { it.name == name }
    }
}

@Immutable
data class AppLabel(
    val id: String,
    val name: String,
    val description: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Immutable
data class LabelAssignment(
    val packageName: String,
    val labelId: String,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Stable join hint: the label's name as of the last write. labelId is a
     * local UUID a reinstall re-issues, so relink paths need the name to
     * re-resolve rows after a restore. Codec reads/writes it; equality and
     * hashing ignore it (it is derived data, not identity).
     */
    val labelName: String = ""
) {
    override fun equals(other: Any?): Boolean =
        other is LabelAssignment && packageName == other.packageName && labelId == other.labelId
    override fun hashCode(): Int = 31 * packageName.hashCode() + labelId.hashCode()
}

enum class FallbackPolicy { SKIP, USE_DEFAULT }

enum class ConfigOperation { BACKUP, RESTORE }

enum class TriggerType { MANUAL, SCHEDULED, INTENT }

enum class ResultState {
    QUEUED, RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED, CANCELLED, SKIPPED
}

enum class ErrorCode {
    NONE,
    ROOT_UNAVAILABLE,
    PERMISSION_DENIED,
    APP_NOT_INSTALLED,
    BACKUP_SOURCE_MISSING,
    STORAGE_FULL,
    IO_ERROR,
    PACKAGE_INSTALL_FAILED,
    DATA_RESTORE_FAILED,
    COMPONENT_UNSUPPORTED,
    CONFIGURATION_INVALID,
    DESTINATION_UNAVAILABLE,
    CONSENT_DENIED
}

@Immutable
data class ConfigRule(
    val id: String,
    val labelId: String,
    /** Lower number = higher priority. Rules evaluated in priority order. */
    val priority: Int,
    val components: Set<BackupComponent> = emptySet(),
    /** Explicit exclusion: matching apps are skipped even if other rules match. */
    val exclude: Boolean = false,
    /**
     * Human label name recorded when the rule is persisted (redundant with
     * [labelId] by design): a reinstall re-issues label UUIDs, so the name is
     * the stable join key the dangling-rule self-heal relinks with. Never
     * displayed; purely a repair hint.
     */
    val labelName: String = ""
)

/** Stable destination ID resolving to the app's savedPath (internal/SD). */
const val DEST_DEFAULT_ID = "default"

/**
 * User-arranged position of a configuration inside its list (Backup or
 * Restore Configurations). DEFAULT = sort alphabetically after any ordered
 * entries, so legacy data and new configs land at the bottom unchanged.
 */
const val CONFIG_LIST_ORDER_DEFAULT = Long.MAX_VALUE

@Immutable
data class BackupConfig(
    val id: String,
    val name: String,
    val description: String = "",
    val enabled: Boolean = true,
    /** BACKUP or RESTORE run mode (default keeps old exports working). */
    val operation: ConfigOperation = ConfigOperation.BACKUP,
    val rules: List<ConfigRule> = emptyList(),
    val fallback: FallbackPolicy = FallbackPolicy.SKIP,
    /** Components used when fallback == USE_DEFAULT and no rule matches. */
    val defaultComponents: Set<BackupComponent> = setOf(BackupComponent.APK),
    /**
     * RESTORE only: apps whose backup lacks a requested component run the
     * parts that exist instead of being skipped (mirrors the Migrate tab,
     * which restores whatever shift files are present). BACKUP always plans
     * the exact rule set.
     */
    val restoreGraceful: Boolean = true,
    /** Stable destination ID; "default" resolves to the app's savedPath. */
    val destinationId: String = DEST_DEFAULT_ID,
    /**
     * Device-level data (Messages/Calls/Contacts/Wi-Fi/Wallpaper/Bluetooth)
     * processed on every run of this configuration, in addition to app rules.
     * A config may carry device data only (rules can then be empty).
     */
    val deviceData: Set<DeviceDataItem> = emptySet(),
    /**
     * Absolute directory paths backed up when [DeviceDataItem.FOLDERS] is in
     * [deviceData]. Root-tarred recursively, so paths under /storage/<user>/Android
     * (blocked from normal apps on Android 11+) work exactly like any other
     * folder. Restored to their original absolute paths from a manifest inside
     * the archive. Must be absolute and shell-safe (validated).
     */
    val folderPaths: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Position among configs of the same operation; see [CONFIG_LIST_ORDER_DEFAULT]. */
    val listOrder: Long = CONFIG_LIST_ORDER_DEFAULT
)

/** Minimal app view the planner needs (mapped from AppInfo at the boundary). */
@Immutable
data class PlannableApp(
    val packageName: String,
    val label: String = "",
    val isSystem: Boolean = false,
    val isInstalled: Boolean = true
)

enum class Capability { ROOT, NORMAL }

@Immutable
data class CapabilitySet(
    val mode: Capability,
    /** Components the device+consent state can actually process. */
    val available: Set<BackupComponent>
)

@Immutable
data class PlannedApp(
    val packageName: String,
    val label: String,
    /** Rule that won, null when fallback/default/skip applied. */
    val ruleId: String?,
    val requested: Set<BackupComponent>,
    /** Requested ∩ available. */
    val granted: Set<BackupComponent>,
    /** Requested − available, explicitly reported, never silent. */
    val unavailable: Set<BackupComponent>,
    /** Why each unavailable component can't run (root vs consent vs unsupported). */
    val unavailableReasons: Map<BackupComponent, String> = emptyMap(),
    val skipped: Boolean,
    val skipReason: String = "",
    val error: ErrorCode = ErrorCode.NONE
)

@Immutable
data class BackupPlan(
    val configId: String,
    val configName: String,
    val dryRun: Boolean,
    val entries: List<PlannedApp>,
    val createdAt: Long = System.currentTimeMillis()
) {
    val processable: List<PlannedApp> get() = entries.filter { !it.skipped && it.granted.isNotEmpty() }
    val skipped: List<PlannedApp> get() = entries.filter { it.skipped }
    val withUnavailable: List<PlannedApp> get() = entries.filter { it.unavailable.isNotEmpty() }
}

@Immutable
data class AppHistoryRecord(
    val packageName: String,
    val appName: String,
    val ruleId: String?,
    val requested: Set<BackupComponent>,
    val processed: Set<BackupComponent>,
    val result: ResultState,
    val reason: String = "",
    /**
     * Wall-clock time this app took inside the run (first started → done),
     * measured by the live progress bus; 0 = unknown (older records or the
     * app never started). Displayed on the History detail screen.
     */
    val durationMs: Long = 0L,
    /**
     * Non-null for device-data records (Messages/Wi-Fi/…): the row describes
     * a device data item, not an app. [packageName] is empty for these.
     */
    val deviceItem: DeviceDataItem? = null,
)

data class ConfigExecution(
    val id: String,
    val configId: String,
    val configName: String,
    val trigger: TriggerType,
    /**
     * Persisted with the execution so backup vs restore is distinguishable in
     * History even after the configuration itself is deleted (older entries
     * default to BACKUP).
     */
    val operation: ConfigOperation = ConfigOperation.BACKUP,
    val startedAt: Long = System.currentTimeMillis(),
    var finishedAt: Long = 0L,
    var result: ResultState = ResultState.RUNNING,
    val apps: MutableList<AppHistoryRecord> = mutableListOf(),
    var errorSummary: String = ""
) {
    val successCount: Int get() = apps.count { it.result == ResultState.SUCCESS }
    val failureCount: Int get() = apps.count { it.result == ResultState.FAILED }
    val skippedCount: Int get() = apps.count { it.result == ResultState.SKIPPED }
    val durationMs: Long get() = if (finishedAt > 0) finishedAt - startedAt else 0L
}

enum class ScheduleType { ONCE, DAILY, WEEKLY, INTERVAL }

/**
 * Automation schedule. Timing is inexact by design (Doze/standby may defer);
 * unmet conditions defer the run via WorkManager constraints (recorded as
 * deferred, not skipped — deviation from spec §20 documented in AUTOMATION.md).
 */
@Immutable
data class AutomationSchedule(
    val id: String,
    /**
     * Configurations this schedule runs, IN ORDER. Resolved against the live
     * store at fire time: deleted/disabled configurations are skipped ("run
     * what is still available"), so editing configs never breaks a schedule.
     * A schedule left with zero entries is removed by [AutomationStore.deleteConfig].
     */
    val configIds: List<String>,
    /** Custom display name; falls back to the configuration name(s) when blank. */
    val name: String = "",
    val enabled: Boolean = true,
    val type: ScheduleType = ScheduleType.DAILY,
    /** For DAILY/WEEKLY: local hour/minute. For ONCE: absolute epoch millis. */
    val hour: Int = 2,
    val minute: Int = 0,
    val triggerAtMillis: Long = 0L,
    /** For INTERVAL: minutes between runs (clamped to >= 15 by validation). */
    val intervalMinutes: Long = 24 * 60,
    /** For WEEKLY: Calendar.SUNDAY..SATURDAY, empty = every day. */
    val weekdays: Set<Int> = emptySet(),
    val requiresCharging: Boolean = false,
    val requiresUnmetered: Boolean = false,
    val requiresBatteryNotLow: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Pure schedule validation (unit-testable). */
fun validateAutomationSchedule(s: AutomationSchedule, configIds: Set<String>): List<String> {
    val errors = mutableListOf<String>()
    if (s.configIds.isEmpty()) errors.add("pick at least one configuration")
    val unknown = s.configIds.filter { it !in configIds }
    if (unknown.size == s.configIds.size && unknown.isNotEmpty()) errors.add("unknown config")
    when (s.type) {
        ScheduleType.ONCE -> if (s.triggerAtMillis <= 0) errors.add("one-time needs a future time")
        ScheduleType.DAILY -> {
            if (s.hour !in 0..23 || s.minute !in 0..59) errors.add("invalid time")
        }
        ScheduleType.WEEKLY -> {
            if (s.hour !in 0..23 || s.minute !in 0..59) errors.add("invalid time")
            if (s.weekdays.any { it !in 1..7 }) errors.add("invalid weekday")
        }
        ScheduleType.INTERVAL -> if (s.intervalMinutes < 15) errors.add("interval below 15 min minimum")
    }
    return errors
}

/** Display title: custom schedule name, or the configuration(s) it runs. */
fun AutomationSchedule.displayName(configNames: List<String>): String =
    name.ifBlank {
        when {
            configNames.isEmpty() -> "(no configuration)"
            configNames.size == 1 -> configNames.single()
            else -> "${configNames.first()} +${configNames.size - 1}"
        }
    }

/** 12-hour clock, e.g. 02:30 PM. */
fun formatScheduleTime(hour: Int, minute: Int): String {
    val h12 = when (hour % 12) {
        0 -> 12
        else -> hour % 12
    }
    val amPm = if (hour < 12) "AM" else "PM"
    return "%02d:%02d %s".format(h12, minute, amPm)
}

/** Short weekday code (Calendar.SUNDAY=1 → "Sun"); for UI reuse. */
fun weekdayShort(calendarDay: Int): String =
    listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").getOrElse(calendarDay - 1) { "?" }

/**
 * When a schedule fires, as a plain string ("02:30 PM", "Sun, Tue • 09:00 AM",
 * "every 45 min", "one-time"). Pure and unit-testable — the schedule card's
 * START TIME / REPEAT rows share it.
 */
fun scheduleWhenText(s: AutomationSchedule): String = when (s.type) {
    ScheduleType.ONCE -> {
        if (s.triggerAtMillis > 0) {
            try {
                java.text.SimpleDateFormat("EEE, dd MMM • hh:mm a", java.util.Locale.getDefault())
                    .format(java.util.Date(s.triggerAtMillis))
            } catch (_: Exception) {
                "one-time"
            }
        } else "one-time"
    }
    ScheduleType.DAILY -> formatScheduleTime(s.hour, s.minute)
    ScheduleType.WEEKLY -> {
        val days = (1..7).filter { it in s.weekdays }.joinToString(", ") { weekdayShort(it) }
        if (days.isEmpty()) formatScheduleTime(s.hour, s.minute) else "$days • ${formatScheduleTime(s.hour, s.minute)}"
    }
    ScheduleType.INTERVAL -> "every ${s.intervalMinutes} min"
}

/** Recurrence line for the schedule card ("Everyday", specific days, interval). */
fun scheduleRepeatText(s: AutomationSchedule): String = when (s.type) {
    ScheduleType.ONCE -> "once"
    ScheduleType.DAILY -> "Everyday"
    ScheduleType.WEEKLY -> {
        val days = (1..7).filter { it in s.weekdays }.joinToString(", ") { weekdayShort(it) }
        if (days.isEmpty()) "Everyday" else days
    }
    ScheduleType.INTERVAL -> "Every ${s.intervalMinutes} min"
}

/** Live status verb for a schedule row, computed from its persisted fields. */
fun scheduleStatus(s: AutomationSchedule): String =
    if (!s.enabled) "Paused"
    else when (s.type) {
        ScheduleType.ONCE -> if (s.triggerAtMillis > 0) "Armed" else "No time set"
        else -> "Active"
    }

/** Pure schedule description for lists/notifications (unit-tested). */
fun describeAutomationSchedule(s: AutomationSchedule, configNames: List<String>): String {
    val title = s.displayName(configNames)
    val whenText = when (s.type) {
        ScheduleType.ONCE -> "once"
        ScheduleType.DAILY -> "daily " + formatScheduleTime(s.hour, s.minute)
        ScheduleType.WEEKLY -> {
            val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
                .filterIndexed { i, _ -> (i + 1) in s.weekdays }
            "weekly " + (if (days.isEmpty()) "daily" else days.joinToString(",")) +
                " " + formatScheduleTime(s.hour, s.minute)
        }
        ScheduleType.INTERVAL -> "every ${s.intervalMinutes} min"
    }
    val conds = buildList {
        if (s.requiresCharging) add("charging")
        if (s.requiresUnmetered) add("unmetered")
        if (s.requiresBatteryNotLow) add("battery ok")
    }
    return "$title • $whenText" + if (conds.isEmpty()) "" else " • ${conds.joinToString(", ")}"
}

/** Pure timing math (unit-testable, local timezone). */
object ScheduleMath {
    fun initialDelayMs(s: AutomationSchedule, nowMillis: Long = System.currentTimeMillis()): Long {
        if (s.type == ScheduleType.ONCE) return (s.triggerAtMillis - nowMillis).coerceAtLeast(0)
        if (s.type == ScheduleType.INTERVAL) return 0L
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMillis }
        repeat(8) { dayOffset ->
            val c = (cal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, dayOffset) }
            val weekdayOk = s.type != ScheduleType.WEEKLY ||
                s.weekdays.isEmpty() ||
                c.get(java.util.Calendar.DAY_OF_WEEK) in s.weekdays
            if (weekdayOk) {
                c.set(java.util.Calendar.HOUR_OF_DAY, s.hour)
                c.set(java.util.Calendar.MINUTE, s.minute)
                c.set(java.util.Calendar.SECOND, 0)
                c.set(java.util.Calendar.MILLISECOND, 0)
                if (c.timeInMillis > nowMillis) return c.timeInMillis - nowMillis
            }
        }
        return 24 * 60 * 60 * 1000L
    }

    fun periodicIntervalMs(s: AutomationSchedule): Long = when (s.type) {
        ScheduleType.DAILY -> 24 * 60 * 60 * 1000L
        ScheduleType.WEEKLY -> 7 * 24 * 60 * 60 * 1000L
        ScheduleType.INTERVAL -> (s.intervalMinutes.coerceAtLeast(15)) * 60 * 1000L
        ScheduleType.ONCE -> 0L
    }

    /**
     * The period the scheduler must actually enqueue for [s]. WEEKLY without
     * picked weekdays means "every day at this time" — an empty weekdays set
     * does NOT mean "once a week". Callers that enqueue a periodic work
     * request must use this (7-day period on a daily schedule was a real bug:
     * the run fired, then silently waited a week).
     */
    fun effectivePeriodMs(s: AutomationSchedule): Long =
        if (s.type == ScheduleType.WEEKLY) {
            // EVERY weekly schedule ticks daily; the worker records an explicit
            // SKIPPED on non-matching days. An empty set means every day — and
            // all seven days picked ALSO means every day (audit A-15: the
            // 7-day period made an every-day schedule run only once a week).
            24 * 60 * 60 * 1000L
        } else {
            periodicIntervalMs(s)
        }
}
