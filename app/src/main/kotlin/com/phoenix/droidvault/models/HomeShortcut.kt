package com.phoenix.droidvault.models

import com.phoenix.droidvault.AutoMode

/**
 * Every destination the Home dashboard can offer as an editable shortcut.
 *
 * The Quick access row used to be two hard-coded cards. It is now a user-set of
 * these, persisted in order, so the landing page can be shaped around the way
 * each person actually works: a restore-only user does not want "Debloat"
 * taking a slot next to their most-used action.
 *
 * [id] is the stable persisted key. It must never change for an existing entry
 * — the stored selection is a list of ids, so renaming an id silently drops a
 * user's shortcut. Display text is carried on the enum itself so the picker and
 * the card cannot disagree.
 *
 * [tab] and [mode]/[autoMode] are the navigation target. [kind] says which of
 * them is meaningful so the launcher has one `when` instead of three.
 */
enum class ShortcutTarget {
    TAB,
    MIGRATOR,
    AUTOMATION,
    DEVICE_DATA
}

enum class HomeShortcut(
    val id: String,
    val title: String,
    val subtitle: String,
    val kind: ShortcutTarget,
    val tab: Int = 0,
    val mode: MigratorMode = MigratorMode.MENU,
    val autoMode: AutoMode = AutoMode.MENU,
    /** Device-data entries open that dialog in this direction. */
    val deviceBackup: Boolean = true
) {
    BACKUP_APPS(
        "backup_apps", "Back up apps", "Choose apps and components",
        ShortcutTarget.MIGRATOR, tab = 2, mode = MigratorMode.BACKUP_APPS
    ),
    RESTORE_APPS(
        "restore_apps", "Restore apps", "Put apps back from the vault",
        ShortcutTarget.MIGRATOR, tab = 2, mode = MigratorMode.RESTORE_APPS
    ),
    MANAGE_BACKUPS(
        "manage_backups", "Manage backups", "Review saved app and device data",
        ShortcutTarget.MIGRATOR, tab = 2, mode = MigratorMode.MANAGE
    ),
    DEBOOT(
        "deboot", "Debloat", "Remove bloatware, reversible",
        ShortcutTarget.MIGRATOR, tab = 2, mode = MigratorMode.DEBLOAT
    ),
    SYSTEMIZE(
        "systemize", "Systemize", "Turn user apps into system apps",
        ShortcutTarget.MIGRATOR, tab = 2, mode = MigratorMode.SYSTEMIZE
    ),
    DEVICE_BACKUP(
        "device_backup", "Back up device data", "Messages, calls, Wi-Fi and more",
        ShortcutTarget.DEVICE_DATA, tab = 1, deviceBackup = true
    ),
    DEVICE_RESTORE(
        "device_restore", "Restore device data", "Messages, calls, contacts",
        ShortcutTarget.DEVICE_DATA, tab = 1, deviceBackup = false
    ),
    PARTITION_TOOLS(
        "partition_tools", "Partition tools", "Flash, back up and wipe partitions",
        ShortcutTarget.TAB, tab = 1
    ),
    UTILITIES(
        "utilities", "Utilities", "Freeze apps and system tools",
        ShortcutTarget.TAB, tab = 3
    ),
    AUTOMATION(
        "automation", "Automation", "Schedules, configs and history",
        ShortcutTarget.AUTOMATION, tab = 4
    ),
    APP_LABELS(
        "app_labels", "App Labels", "Group apps once, reuse everywhere",
        ShortcutTarget.AUTOMATION, tab = 4, autoMode = AutoMode.LABELS
    ),
    BACKUP_CONFIGS(
        "backup_configs", "Backup configs", "Reusable backup recipes",
        ShortcutTarget.AUTOMATION, tab = 4, autoMode = AutoMode.CONFIGS
    ),
    RESTORE_CONFIGS(
        "restore_configs", "Restore configs", "Reusable restore recipes",
        ShortcutTarget.AUTOMATION, tab = 4,
        autoMode = AutoMode.RESTORE_CONFIGS
    ),
    SCHEDULES(
        "schedules", "Schedules", "Automated backup and restore",
        ShortcutTarget.AUTOMATION, tab = 4, autoMode = AutoMode.SCHEDULES
    ),
    HISTORY(
        "history", "History", "Past runs and their results",
        ShortcutTarget.AUTOMATION, tab = 4, autoMode = AutoMode.HISTORY
    );

    companion object {
        private val byId = entries.associateBy { it.id }

        /** Unknown ids are dropped rather than crashing a launch. */
        fun fromId(id: String): HomeShortcut? = byId[id]

        /**
         * The default row. Back up apps / Restore apps are here because the
         * landing page's two big buttons were replaced by the overview card, and
         * those primary actions must stay one tap away — a backup app whose
         * front door cannot start a backup has regressed.
         */
        val DEFAULT = listOf(
            BACKUP_APPS, RESTORE_APPS, MANAGE_BACKUPS, AUTOMATION
        )

        /**
         * Parse a persisted id list, dropping anything unrecognised and
         * duplicates. A shortcut retired in a later build therefore degrades to
         * "not shown" instead of breaking the whole row.
         */
        fun parseList(raw: String?): List<HomeShortcut> {
            if (raw.isNullOrBlank()) return DEFAULT
            val seen = LinkedHashSet<HomeShortcut>()
            for (token in raw.split(',')) {
                fromId(token.trim())?.let { seen.add(it) }
            }
            return if (seen.isEmpty()) DEFAULT else seen.toList()
        }

        fun toStorage(list: List<HomeShortcut>): String =
            list.map { it.id }.joinToString(",")
    }
}


