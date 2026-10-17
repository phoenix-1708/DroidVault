package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable

/**
 * A sub-screen of the Migrate tab.
 *
 * [IMPORT_TRANSFERS] is the only one that lists no apps at all: it lists the
 * exports this phone holds, one row each, because an export is seventeen files
 * and a person made one of them.
 */
enum class MigratorMode {
    MENU, BACKUP_APPS, RESTORE_APPS, MANAGE, DEBLOAT, SYSTEMIZE, IMPORT_TRANSFERS
}

/**
 * True when this mode's app list is sourced from the VAULT rather than from
 * the installed set.
 *
 * The single `_appList` field is shared by the Auto tab (which wants installed
 * apps) and the Migrate submenus (which want either installed apps or vault
 * archives, depending on the mode). Anything that seeds the installed set has to
 * check this first: filling a vault-sourced list with installed apps makes
 * Restore offer to restore archives that do not exist — which is exactly what
 * happened, because an empty (and correct) backup list is indistinguishable
 * from "not loaded yet".
 */
val MigratorMode.isVaultSourced: Boolean
    get() = this == MigratorMode.RESTORE_APPS || this == MigratorMode.MANAGE

@Immutable
data class AppInfo(
    val label: String,
    val packageName: String,
    val version: String = "",
    val isSystem: Boolean = false,
    val isSelected: Boolean = false,
    val iconPath: String? = null,
    val backupTime: String = "",
    val size: String = "",
    val isInstalled: Boolean = true,
    val isSystemized: Boolean = false,
    val appSizeKb: Long = 0,
    val dataSizeKb: Long = 0,
    val mediaSizeKb: Long = 0,
    val diskSizeKb: Long = 0,
    val versionCode: Long = 0,
    val apkPath: String? = null,
    val isLaunchable: Boolean = false,
    val availableInBackup: Set<Int> = emptySet(),
    /**
     * Sizes the EXISTING archive recorded in `Meta.txt`, per component, in KB.
     * `-1` means "not recorded / unknown", which is treated as changed.
     *
     * These are what the engine compares the live size against to decide
     * whether a part needs re-writing, so mirroring them in Kotlin is what lets
     * the UI estimate honestly and offer "changed only" selection.
     */
    val backedUpAppKb: Long = -1L,
    val backedUpDataKb: Long = -1L,
    val backedUpMediaKb: Long = -1L,
    /**
     * The parts chosen for THIS app, or null for "use the current default".
     *
     * A null is legitimate: it is what a not-yet-touched row holds, and it keeps
     * the default chip row live for bulk selection. Every consumer must still
     * fall back rather than assume non-null.
     */
    val activeComponents: Set<Int>? = null
)

@Immutable
data class AppInstallInfo(
    val label: String = "Analyzing...",
    val packageName: String = "",
    val version: String = "",
    val versionCode: Long = 0,
    val size: String = "",
    val path: String = "",
    val uriString: String? = null,
    val installedVersion: String? = null,
    val installedVersionCode: Long? = null,
    val installedSize: String? = null,
    val isInstalled: Boolean = false,
    val status: String = "Analyzing",
    val iconPath: String? = null,
    val isAnalysisComplete: Boolean = false,
    val isSelected: Boolean = true,
    val minSdk: String = "",
    val targetSdk: String = "",
    val architecture: String = "",
    val signature: String = ""
)

@Immutable
data class FlashZip(val name: String, val path: String, val category: String)

sealed class FlashAction {
    @Immutable
    data class InstallZip(val zip: FlashZip) : FlashAction()
    @Immutable
    data class Wipe(val partitions: Set<String>) : FlashAction()
    @Immutable
    object FormatData : FlashAction()

    val id: String
        get() = when (this) {
            is InstallZip -> zip.path
            is Wipe -> "WIPE_${partitions.joinToString(",")}"
            is FormatData -> "FORMAT_DATA"
        }
}

@Immutable
/**
 * Why the app does or does not have root.
 *
 * A single Boolean answered "no" for three situations that need three different
 * things from the user, and one of them is a dead end: `su` is present, Magisk
 * has the app in its Superuser list with no policy row, and Magisk's
 * authorisation dialog fails to open on that ROM — so tapping the button raises
 * nothing, no toast appears, and the screen keeps saying "Approve Droid Vault in
 * your root manager", which is the very step that is broken. Found on the
 * reference device by hitting it, not by reading it.
 */
enum class RootState {
    /** Not probed yet. */
    Unknown,

    /** `su` answered and the shell is root. */
    Granted,

    /** No `su` on the device at all — no Magisk, no KernelSU, nothing to grant. */
    NoRootManager,

    /**
     * `su` exists but did not grant: denied, never prompted, or the prompt
     * failed to appear. This is the case that needs manual instructions.
     */
    NotGranted
}

/**
 * The state implied by what the device actually answered.
 *
 * Pure, so the three-way distinction is tested rather than asserted. Ordering
 * matters: the absence of `su` explains everything, so it is decided first; and
 * a root shell is the only answer that grants, whatever else was probed.
 */
fun classifyRoot(hasSuBinary: Boolean, shellIsRoot: Boolean): RootState = when {
    shellIsRoot -> RootState.Granted
    !hasSuBinary -> RootState.NoRootManager
    else -> RootState.NotGranted
}

data class AppState(
    val migratorMode: MigratorMode = MigratorMode.MENU,
    val isRunning: Boolean = false,
    val currentAction: String = "",
    val currentStep: String = "",
    val progress: Int = 0,
    val isFetchingApps: Boolean = false,
    val rootState: RootState = RootState.Unknown,
    val systemAppsFetched: Boolean = false,
    val searchQuery: String = "",
    val showUserApps: Boolean = true,
    val showSystemApps: Boolean = false,
    val actionFilterState: Int = 0,
    val globalComponents: Set<Int> = setOf(1, 2, 3, 4, 5),
    val hasRoot: Boolean = true,
    val flashWizardStep: Int = 0,
    val flashWipePartitions: Set<String> = setOf("dalvik", "cache"),
    val flashFormatData: Boolean = false,
    val isProcessingZips: Boolean = false,
    val hasLockscreen: Boolean = false,
    val flashRebootOption: String = "system",
    val totalRequiredKb: Long = 0,
    val totalBackupsSizeKb: Long = 0,
    /**
     * How many selected apps a run would actually process.
     *
     * Not the raw `isSelected` count: system apps are excluded unless the user
     * turned the system-app filter on, so the two disagree whenever a system
     * app is ticked with the filter off. The button used the raw count, so it
     * read "3 Selected" and enabled a run that then quietly processed one app.
     */
    val selectedRunCount: Int = 0,
    val storageUsedKb: Long = 0,
    val storageFreeKb: Long = 0,
    val batchInstallApps: List<AppInstallInfo> = emptyList(),
    val showAppInstaller: Boolean = false,
    val isAnalyzingApps: Boolean = false,
    val isInstallerIntent: Boolean = false,
    val installStartTime: Long = 0,
    val totalInstallTimeSeconds: Long = 0,
    val requestDefaultSms: Boolean = false,
    val keepDebloatData: Boolean = true,
    // Debloat with Keep Data off uninstalls for EVERY user and deletes /data,
    // after which `install-existing` answers NameNotFoundException and this app
    // can no longer put it back (measured on device). That is the one Debloat
    // action that is not undoable from inside Droid Vault, so it is confirmed
    // rather than run on a single tap like every other mode.
    val pendingDebloatConfirm: Boolean = false,
    val showPartialSystemApps: Boolean = false,
    val allowFastInstall: Boolean = false,
    val allowSsaidRestore: Boolean = false,
    val allowPermRestore: Boolean = false,
    // Automation label filter for the Migrate list (null = no filter).
    val filterLabelId: String? = null,
    // True after an SMS restore completes: the messaging role handoff kills our
    // process (platform revokes role perms), so the UI closes itself first.
)
