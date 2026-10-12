package com.phoenix.droidvault.utils

import android.content.Context
import android.os.PowerManager
import com.phoenix.droidvault.BuildConfig
import com.phoenix.droidvault.models.AppInfo
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ToolsManager {

    suspend fun runDebloatOperation(
        context: Context,
        selectedApps: List<AppInfo>,
        isRestore: Boolean,
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
                    val out = Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --restore-debloat ${ShellQuote.q(pkg)}")
                        .exec().out.joinToString("\n")
                    if (out.contains("RESTORED|PKG:$pkg")) {
                        ok++
                        updateLog("Restored: ${app.label}")
                    } else {
                        updateLog("Failed to restore: ${app.label}")
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
                if (failed == 0) onComplete("Debloat Complete!", "Successfully removed ${selectedApps.size} apps.")
                else onComplete("Debloat Finished with Errors", "Removed $ok of ${selectedApps.size} apps.")
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
            if (failed == 0) onComplete("Systemization Complete!", "Please REBOOT to apply System Apps.")
            else onComplete("Systemization Finished with Errors", "Systemized $ok of ${selectedApps.size} apps. Reboot to apply.")
        } finally {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
    }

    /**
     * Freeze (disable, reversible) / unfreeze an app. Safer than debloat:
     * the APK stays, the app just can't run. Returns true on success.
     */
    suspend fun setFrozen(packageName: String, frozen: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val pkg = ShellQuote.requirePackage(packageName)
                val cmd = if (frozen) {
                    "pm disable --user 0 $pkg"
                } else {
                    "pm enable --user 0 $pkg"
                }
                Shell.cmd(cmd).exec().isSuccess
            } catch (_: Exception) {
                false
            }
        }

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