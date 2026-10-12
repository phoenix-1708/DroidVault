package com.phoenix.droidvault.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Live freshness of the standing grants Droid Vault relies on. These are the
 * four silent-failure points the system UI does not surface:
 *
 *  - PACKAGE_USAGE_STATS appop: granted via root `pm grant` + `appops set` at
 *    startup (MainViewModel.checkRootAccess); a ROM switch or package clear
 *    wipes it and app size listings silently lose their data.
 *  - POST_NOTIFICATIONS: revocation mutes run progress/completion cards —
 *    headless scheduled runs become invisible while still executing.
 *  - deviceidle whitelist: exemption from battery optimization; without it
 *    WorkManager defers schedules unpredictably.
 *  - engine hash: /data/adb/DroidVault/droidvault-engine must be byte-identical
 *    to the bundled build or every restore runs a stale engine.
 *
 * Decision cores ([appopsAllows], [isWhitelisted]) are pure and unit-tested;
 * the probes themselves run through the root shell established at startup.
 */
object PermissionChecker {

    // ------------------------------------------------------------------
    // Runtime permission dialogs (clean-install field bug: none appeared)
    // ------------------------------------------------------------------

    /**
     * The runtime permissions behind each Device Data slice, requested
     * through the REAL system dialogs on first launch and again whenever a
     * run needs a permission the user has not granted yet.
     *
     * WRITE_SMS is deliberately absent: it is signature|privileged on modern
     * Android (verified on-device: `pm grant WRITE_SMS` answers "not a
     * changeable permission type"), so it can never appear in a runtime
     * dialog — root assigns it at run start instead.
     */
    val SMS_RUNTIME: Array<String> = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.RECEIVE_SMS
    )
    val CALL_LOG_RUNTIME: Array<String> = arrayOf(
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.WRITE_CALL_LOG
    )
    val CONTACTS_RUNTIME: Array<String> = arrayOf(
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.WRITE_CONTACTS
    )

    /** Every requestable permission the recovery features rely on. */
    fun allRuntime(): Array<String> =
        SMS_RUNTIME + CALL_LOG_RUNTIME + CONTACTS_RUNTIME

    /** [allRuntime] plus POST_NOTIFICATIONS on Android 13+. */
    fun allRuntimeWithNotifications(): Array<String> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.POST_NOTIFICATIONS) + allRuntime()
        else allRuntime()

    /** Filters [wanted] down to the permissions the user has not granted. */
    fun missing(context: Context, wanted: Array<String>): Array<String> =
        wanted.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

    /** One snapshot of the Settings self-check card. Every field is a row. */
    data class CheckState(
        val appopUsageStats: Boolean,
        val postNotifications: Boolean,
        val deviceidleWhitelisted: Boolean,
        val engineMatch: Boolean,
        /**
         * "Allow restricted settings" is ON (or the OS predates it).
         *
         * Android 13 introduced restricted settings for sideloaded apps
         * (accessibility / notification listener); **Android 15 expanded the
         * list to the SMS runtime permission and the default-SMS ROLE** — the
         * exact pair a message restore needs to take over the SMS database.
         * Droid Vault is always sideloaded (installerPackageName =
         * com.android.packageinstaller), so on Android 15+ an unrestricted
         * app cannot be handed the SMS role at all until the user flips this.
         * Root can flip it silently (see MainViewModel.checkRootAccess).
         */
        val restrictedSettingsAllowed: Boolean = true
    )

    /**
     * Pure: does `cmd appops get <pkg>` output show GET_USAGE_STATS allowed?
     * Matches the on-device line shape "GET_USAGE_STATS: allow; time=…".
     * The op token must be EXACT (a hypothetical "*_ALLOWED" look-alike must
     * not count); ignore/deny/default and a missing line all read as false —
     * UsageStatsManager gates on exactly this appop (MODE_ALLOWED).
     */
    internal fun appopsAllows(output: String?): Boolean {
        val line = output?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.substringBefore(':').trim() == "GET_USAGE_STATS" }
            ?: return false
        return line.substringAfter(':', "").trim().startsWith("allow")
    }

    /**
     * Pure: does the `dumpsys deviceidle` output name the package as a whole
     * word? Word boundaries keep a sibling package ("…droidvaultsibling")
     * from satisfying the check for the app itself.
     */
    internal fun isWhitelisted(output: String?, pkg: String): Boolean {
        if (output.isNullOrEmpty()) return false
        return Regex("\\b${Regex.escape(pkg)}\\b").containsMatchIn(output)
    }

    /**
     * Pure: does `cmd appops get <pkg> ACCESS_RESTRICTED_SETTINGS` show the
     * restriction LIFTED? `allow` = unrestricted. `ignore`/`default` = the
     * app is still restricted (Android 15+ blocks the SMS role + SMS runtime
     * permission in that state). A missing line (OS without the feature)
     * reads as allowed — nothing to gate on.
     */
    internal fun restrictedSettingsAllowed(output: String?): Boolean {
        if (output.isNullOrBlank()) return true
        val line = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.substringBefore(':').trim() == "ACCESS_RESTRICTED_SETTINGS" }
            ?: return true
        return line.substringAfter(':', "").trim().startsWith("allow")
    }

    /**
     * Blocking snapshot — every probe is a blocking shell/PM call. Call from a
     * background dispatcher only (both call sites are: the Settings
     * LaunchedEffect wraps it in Dispatchers.IO, and the Re-grant callback
     * runs on checkRootAccess's IO coroutine).
     */
    fun check(context: Context): CheckState {
        val pkg = context.packageName
        val postNotifications = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU

        val appopRaw = try {
            com.topjohnwu.superuser.Shell.cmd(
                "cmd appops get ${ShellQuote.requirePackage(pkg)}"
            ).exec().out.joinToString("\n")
        } catch (_: Exception) {
            ""
        }
        val appopUsageStats = appopsAllows(appopRaw)

        val idleRaw = try {
            com.topjohnwu.superuser.Shell.cmd("dumpsys deviceidle").exec().out.joinToString("\n")
        } catch (_: Exception) {
            ""
        }
        val deviceidleWhitelisted = isWhitelisted(idleRaw, pkg)

        val engineMatch = try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(10_000L) {
                    BackendInstaller.isEngineUpToDate(context)
                }
            } ?: false
        } catch (_: Exception) {
            false
        }

        val restrictedRaw = try {
            com.topjohnwu.superuser.Shell.cmd(
                "cmd appops get ${ShellQuote.requirePackage(pkg)} ACCESS_RESTRICTED_SETTINGS"
            ).exec().out.joinToString("\n")
        } catch (_: Exception) {
            ""
        }
        val restrictedSettingsAllowed = restrictedSettingsAllowed(restrictedRaw)

        return CheckState(
            appopUsageStats, postNotifications, deviceidleWhitelisted, engineMatch,
            restrictedSettingsAllowed
        )
    }

    /**
     * Root-side self-heal for the restricted-settings gate.
     *
     * Android 15+ requires an unrestricted app to take the default-SMS role
     * (and its SMS runtime permission). The user-facing toggle lives three
     * taps deep in App Info, and Droid Vault is a root tool — so lift it from
     * the shell, where it is a plain app-op write. Verified on-device:
     * `appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow` succeeds as root and
     * the subsequent role-grant is accepted. Best-effort: older Androids have
     * no such op and the command simply does nothing.
     */
    fun grantRestrictedSettings(pkg: String) {
        try {
            ShellQuote.requirePackage(pkg)
            com.topjohnwu.superuser.Shell.cmd(
                "cmd appops set $pkg ACCESS_RESTRICTED_SETTINGS allow"
            ).exec()
        } catch (_: Exception) {
        }
    }
}
