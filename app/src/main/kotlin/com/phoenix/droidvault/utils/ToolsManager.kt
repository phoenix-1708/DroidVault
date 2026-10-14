package com.phoenix.droidvault.utils

import android.content.Context
import android.os.PowerManager
import com.phoenix.droidvault.BuildConfig
import com.phoenix.droidvault.models.AppInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ToolsManager {

    private const val TAG = "ToolsManager"

    suspend fun runDebloatOperation(
        context: Context,
        selectedApps: List<AppInfo>,
        isRestore: Boolean,
        vaultPath: String = "",
        keepData: Boolean = false,
        updateLog: (String) -> Unit, updateProgress: (String, String, Int) -> Unit, onComplete: (String, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidVault::DebloatWakelock")
            wakeLock.acquire(15 * 60 * 1000L)

            if (isRestore) {
                var ok = 0
                selectedApps.forEachIndexed { index, app ->
                    updateProgress("Restoring Apps", "${app.label} (${index + 1}/${selectedApps.size})", ((index + 1) * 100) / selectedApps.size)
                    val pkg = ShellQuote.requirePackage(app.packageName)
                    // The vault path is what lets the engine reinstall an app the
                    // package manager has forgotten: `install-existing` only works
                    // on a package that still exists, and a debloat with Keep Data
                    // off deletes it outright.
                    val out = Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --restore-debloat ${ShellQuote.q(pkg)} ${ShellQuote.q(vaultPath)}")
                        .exec().out.joinToString("\n")
                    if (out.contains("RESTORED|PKG:$pkg")) {
                        ok++
                        updateLog("Restored: ${app.label}")
                    } else {
                        // The engine reports WHY, and an empty list here is the
                        // worst possible answer to "restore this app I removed".
                        val reason = Regex("MSG:(.*)").find(out)?.groupValues?.get(1)?.trim()
                        updateLog(
                            if (reason.isNullOrBlank()) "Failed to restore: ${app.label}"
                            else "Failed to restore ${app.label} — $reason"
                        )
                    }
                }
                val failed = selectedApps.size - ok
                val restored = com.phoenix.droidvault.models.AppParts.count(selectedApps.size, "app")
                if (failed == 0) onComplete("Restore Complete!", "Successfully restored $restored.")
                else onComplete("Restore Finished with Errors", "Restored $ok of $restored.")
            } else {
                var ok = 0
                selectedApps.forEachIndexed { index, app ->
                    updateProgress("Debloating Apps", "${app.label} (${index + 1}/${selectedApps.size})", ((index + 1) * 100) / selectedApps.size)
                    val flag = if (keepData) "-k" else ""
                    require(flag == "" || flag == "-k") { "Invalid flag" }
                    val pkg = ShellQuote.requirePackage(app.packageName)
                    val out = Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --remove ${ShellQuote.q(pkg)} ${ShellQuote.q(flag)}")
                        .exec().out.joinToString("\n")
                    if (out.contains("REMOVED|PKG:$pkg")) {
                        ok++
                        updateLog("Uninstalled: ${app.label}")
                    } else {
                        updateLog("Failed to remove: ${app.label}")
                    }
                }
                val failed = selectedApps.size - ok
                val removed = com.phoenix.droidvault.models.AppParts.count(selectedApps.size, "app")
                if (failed == 0) onComplete("Debloat Complete!", "Successfully removed $removed.")
                else onComplete("Debloat Finished with Errors", "Removed $ok of $removed.")
            }
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    suspend fun runSystemizeOperation(
        context: Context,
        selectedApps: List<AppInfo>,
        updateLog: (String) -> Unit, updateProgress: (String, String, Int) -> Unit, onComplete: (String, String) -> Unit
    ) = withContext(Dispatchers.IO) {
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidVault::SystemizeWakelock")
            wakeLock.acquire(15 * 60 * 1000L)

            var ok = 0
            selectedApps.forEachIndexed { index, app ->
                updateProgress("Systemizing Apps", "${app.label} (${index + 1}/${selectedApps.size})", ((index + 1) * 100) / selectedApps.size)

                val result =
                    Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --systemize ${ShellQuote.q(ShellQuote.requirePackage(app.packageName))} ${ShellQuote.q(ShellQuote.safeLabel(app.label))} ${ShellQuote.q(BuildConfig.VERSION_NAME)} ${ShellQuote.q(BuildConfig.VERSION_CODE.toString())}")
                        .exec().out.joinToString("\n")

                // Engine emits SYSTEMIZED|PKG:<pkg> on success (audit A-11).
                if (result.contains("SYSTEMIZED|")) {
                    ok++
                    updateLog("Systemized: ${app.label}")
                } else {
                    updateLog("Failed to systemize: ${app.label}")
                }
            }
            val failed = selectedApps.size - ok
            val total = com.phoenix.droidvault.models.AppParts.count(selectedApps.size, "app")
            if (failed == 0) onComplete("Systemization Complete!", "Please REBOOT to apply System Apps.")
            else onComplete("Systemization Finished with Errors", "Systemized $ok of $total. Reboot to apply.")
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    /**
     * Packages that must never be frozen, because doing so leaves the phone
     * unusable or the fix unreachable.
     *
     * Disabling Droid Vault itself is a SELF-LOCKOUT: the app cannot be opened
     * to undo it, so it takes ADB — and the user who just asked for help has no
     * app to ask with. SystemUI disabled is a soft brick with no launcher to
     * navigate from. Play Services disabled breaks the account, notifications
     * and the store for most of what is on the device.
     *
     * The active launcher is excluded separately, because it is whatever the ROM
     * shipped rather than a fixed name — see [freezeRefusal].
     */
    val NEVER_FREEZE: Set<String> = setOf(
        "com.phoenix.droidvault",
        "com.android.systemui",
        "com.google.android.gms"
    )

    /**
     * Why this package must not be frozen, or `null` when freezing is safe.
     *
     * Pure, so the rule is testable without a device. UNFREEZE is deliberately
     * never refused: if a package somehow ended up frozen, the user must always
     * be able to bring it back, and refusing that would strand them.
     */
    internal fun freezeRefusal(
        packageName: String,
        freezing: Boolean,
        launchers: Set<String> = emptySet()
    ): String? {
        if (!freezing) return null
        if (packageName == OWN_PACKAGE) return "Droid Vault cannot freeze itself"
        if (packageName in NEVER_FREEZE) return "$packageName is required for the phone to work"
        if (packageName == "android") return "The system package cannot be frozen"
        if (packageName in launchers) return "That is your home screen app"
        return null
    }

    /**
     * Freeze (disable, reversible) / unfreeze an app. Safer than debloat:
     * the APK stays, the app just can't run. Returns true on success.
     *
     * Refuses the packages [freezeRefusal] names, so the dangerous case is
     * stopped in the engine as well as in the UI — a screen is not a gate.
     */
    suspend fun setFrozen(packageName: String, frozen: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val pkg = ShellQuote.requirePackage(packageName)
                if (freezeRefusal(pkg, frozen, activeLaunchers()) != null) {
                    android.util.Log.w(TAG, "refusing to freeze $pkg")
                    return@withContext false
                }
                val cmd = if (frozen) {
                    "pm disable --user 0 $pkg"
                } else {
                    "pm enable --user 0 $pkg"
                }
                val ok = Shell.cmd(cmd).exec().isSuccess
                if (!ok) {
                    android.util.Log.w(TAG, "freeze $pkg -> frozen=$frozen did not succeed")
                }
                ok
            } catch (_: Exception) {
                false
            }
        }

    /** Home-screen packages currently installed, for [freezeRefusal]. */
    private suspend fun activeLaunchers(): Set<String> = withContext(Dispatchers.IO) {
        try {
            Shell.cmd("cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null")
                .exec().out
                .mapNotNull { line ->
                    Regex("""packageName=([A-Za-z0-9_.]+)""").find(line)?.groupValues?.get(1)
                }
                .toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    /** This app's own package, read once. */
    const val OWN_PACKAGE: String = "com.phoenix.droidvault"

    /** True when the app is currently disabled for user 0. */
    suspend fun isFrozen(context: Context, packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val info = context.packageManager.getApplicationInfo(packageName, 0)
                !info.enabled
            } catch (_: Exception) {
                false
            }
        }
}