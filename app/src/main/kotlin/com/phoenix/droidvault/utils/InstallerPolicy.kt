package com.phoenix.droidvault.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Consent gates for dangerous operations.
 *
 * Posture since 2.0.4: the two RESTORE-side consents (Android ID cloning,
 * silent permission restore) default ON so a fresh install restores a
 * migration faithfully out of the box — a failed silent restore is recoverable
 * (re-run with the toggle off), whereas a silently broken SSAID is not
 * discoverable at all. The INSTALL-side consent (fast install: verifier
 * disable + installer spoof) stays default OFF: it weakens a security check
 * for every install, not just restores, so it remains strict opt-in.
 */
object InstallerPolicy {
    const val KEY_FAST_INSTALL = "allow_fast_install"      // verifier-disable + Play-source spoof + dexopt assume-verified
    const val KEY_SSAID_RESTORE = "allow_ssaid_restore"    // Android ID cloning
    const val KEY_PERM_RESTORE = "allow_perm_restore"      // silent grant/appops restore

    /** Restore-side consents default ON (see class kdoc); install-side stays OFF. */
    const val DEFAULT_SSAID_RESTORE = true
    const val DEFAULT_PERM_RESTORE = true
    const val DEFAULT_FAST_INSTALL = false

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("droidvault_consent_prefs", Context.MODE_PRIVATE)

    fun isFastInstallAllowed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FAST_INSTALL, DEFAULT_FAST_INSTALL)

    fun isSsaidAllowed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SSAID_RESTORE, DEFAULT_SSAID_RESTORE)

    fun isPermRestoreAllowed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERM_RESTORE, DEFAULT_PERM_RESTORE)

    fun setFastInstall(context: Context, allowed: Boolean) =
        prefs(context).edit { putBoolean(KEY_FAST_INSTALL, allowed) }

    fun setSsaid(context: Context, allowed: Boolean) =
        prefs(context).edit { putBoolean(KEY_SSAID_RESTORE, allowed) }

    fun setPermRestore(context: Context, allowed: Boolean) =
        prefs(context).edit { putBoolean(KEY_PERM_RESTORE, allowed) }

    /** Env prefix for shell backend, e.g. "ALLOW_VERIFIER_DISABLE=1 ALLOW_SSAID=0 ..." */
    fun shellEnv(context: Context): String {
        val v = if (isFastInstallAllowed(context)) "1" else "0"
        val s = if (isSsaidAllowed(context)) "1" else "0"
        val p = if (isPermRestoreAllowed(context)) "1" else "0"
        return "ALLOW_VERIFIER_DISABLE=$v ALLOW_SSAID=$s ALLOW_PERM=$p"
    }
}
