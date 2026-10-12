package com.phoenix.droidvault

import android.content.Context
import androidx.core.content.edit
import com.phoenix.droidvault.utils.SettingsManager
import com.phoenix.droidvault.utils.ShellQuote
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * One-time migration from the pre-rebrand layout to the Droid Vault layout.
 * Split into two phases, both no-ops after the first success:
 *  - [migratePrefsOnly] runs on the main thread at process start — prefs-only,
 *    so MainViewModel always constructs against healed values.
 *  - [complete] runs on an IO thread — root shell work (storage folder move,
 *    engine dir); the vault move must never block app start. Preserves across the rename:
 *
 *  - app preferences (base path, consents, automation store cache, theme)
 *  - the storage folder + every archive inside it (App.shift, the Device
 *    archives, automation-state.json, run history)
 *  - the /data/adb engine directory (script + zapdos binary)
 *  - the Magisk module directory (module id changes take effect at reboot;
 *    the legacy dir is kept so the CURRENT boot keeps working)
 *
 * The old-named self-backup mirrors (ROM-Shifter.apk/.sh) are REMOVED rather
 * than renamed: a renamed APK mirror would masquerade as the Droid Vault
 * build while containing the old app. They regenerate automatically on the
 * next backup/restore run.
 *
 * Legacy prefs FILES are deleted after their contents are merged into the
 * new names — keeping them would leave both stale recorded paths and the
 * old brand on disk.
 *
 * Failure policy: every step is best-effort and re-runnable; the completion
 * marker is written only at the very end, so a launch without root (denied
 * su prompt) simply retries on the next launch. Nothing is deleted that the
 * current boot still needs.
 *
 * NOTE: the old-name string constants below are REQUIRED — this object is
 * the only place that must know the pre-rebrand layout in order to move
 * users off it.
 */
object LegacyMigration {

    private const val DONE = "legacy_layout_migrated_v2"

    const val OLD_DEFAULT_DIR = "/storage/emulated/0/Shifter"
    const val NEW_DEFAULT_DIR = "/storage/emulated/0/DroidVault"
    private const val OLD_ENGINE_DIR = "/data/adb/Shifter"
    private const val NEW_ENGINE_DIR = "/data/adb/DroidVault"
    private const val OLD_MODULE_DIR = "/data/adb/modules/ROM-Shifter"
    private const val NEW_MODULE_DIR = "/data/adb/modules/DroidVault"
    private const val OLD_MODULE_UPDATE_DIR = "/data/adb/modules_update/ROM-Shifter"
    private const val NEW_MODULE_UPDATE_DIR = "/data/adb/modules_update/DroidVault"
    private const val OLD_TMP_DIR = "/data/local/tmp/shifter_apps"
    private const val NEW_TMP_DIR = "/data/local/tmp/droidvault_apps"

    /** Legacy marker keys from earlier migration attempts, removed on success. */
    private val STALE_MARKERS = listOf("legacy_romshifter_migrated_v1")

    /** old prefs file name -> new prefs file name (merged, then old deleted). */
    private val PREFS_TO_MIGRATE = mapOf(
        "shifter_prefs" to "droidvault_prefs",
        "shifter_automation_prefs" to "droidvault_automation_prefs",
        "shifter_consent_prefs" to "droidvault_consent_prefs",
        "shifter_backend_prefs" to "droidvault_backend_prefs",
        "shifter_backup_prefs" to "droidvault_backup_prefs",
    )

    private const val PREFS_NAME = "droidvault_prefs"

    private fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(DONE, false)

    /**
     * Fast, prefs-only phase. Safe to call on the main thread during
     * Application.onCreate: a few small XML merges and no root shell. It MUST
     * complete before the first activity starts, because MainViewModel reads
     * `base_path` while it is constructed; the root-shell heavy lifting (vault
     * folder move, engine dir) runs in [complete] on an IO thread instead — a
     * multi-GB vault move must never block app start (field ANR fix).
     */
    fun migratePrefsOnly(context: Context) {
        if (isDone(context)) return
        try {
            migratePrefs(context)
            healRecordedPaths(context)
        } catch (_: Exception) {
        }
    }

    /**
     * Root-shell phase: move the storage folder, re-seed the engine dir and
     * scrub orphaned tmp dirs. Call from an IO thread. Best-effort and
     * re-runnable; the completion marker is written only after the storage
     * folder step succeeded, so a launch without root (denied su prompt) or a
     * failed move simply retries on the next launch. Nothing is deleted that
     * the current boot still needs.
     */
    fun complete(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) return
        try {
            if (!migrateStorageFolder(prefs)) return
            migrateEngineDir()
            cleanupTmpDirs()
            prefs.edit {
                putBoolean(DONE, true)
                STALE_MARKERS.forEach { remove(it) }
            }
        } catch (_: Exception) {
            // Best-effort: retried on the next launch (marker only set on full success).
        }
    }

    /**
     * Copy every entry from each legacy prefs file into its new name (merge),
     * then wipe and DELETE the legacy file: remnants would keep both the old
     * brand and stale recorded paths (e.g. an old auto_import_path) on disk.
     */
    private fun migratePrefs(context: Context) {
        PREFS_TO_MIGRATE.forEach { (oldName, newName) ->
            val old = context.getSharedPreferences(oldName, Context.MODE_PRIVATE)
            val entries = old.all
            if (entries.isNotEmpty()) {
                val new = context.getSharedPreferences(newName, Context.MODE_PRIVATE)
                new.edit {
                    entries.forEach { (key, value) ->
                        when (value) {
                            is String -> putString(key, healLegacyPath(value))
                            is Int -> putInt(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Set<*> -> @Suppress("UNCHECKED_CAST") {
                                putStringSet(
                                    key,
                                    (value as Set<String>).mapTo(mutableSetOf()) { healLegacyPath(it) }
                                )
                            }
                        }
                    }
                }
            }
            // Wipe content, flush, then remove the file so no legacy-named
            // prefs XML lingers in shared_prefs/.
            old.edit().clear().commit()
            try {
                File(context.applicationInfo.dataDir, "shared_prefs/$oldName.xml").delete()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Defensive pass over the NEW prefs files: rewrite any recorded value
     * still pointing at the old default storage dir. History summaries and
     * caches written before the migration may embed the old path.
     */
    private fun healRecordedPaths(context: Context) {
        PREFS_TO_MIGRATE.values.forEach { name ->
            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val edits = mutableMapOf<String, String>()
            p.all.forEach { (key, value) ->
                if (value is String) {
                    val healed = healLegacyPath(value)
                    if (healed != value) edits[key] = healed
                }
            }
            if (edits.isNotEmpty()) {
                p.edit { edits.forEach { (k, v) -> putString(k, v) } }
            }
        }
    }

    /**
     * Pure path healer (unit-testable): replace the old default storage dir
     * prefix with the new one wherever a recorded string embeds it. Only the
     * exact legacy prefix is rewritten — nothing else is touched.
     */
    fun healLegacyPath(value: String): String = when {
        value == OLD_DEFAULT_DIR -> NEW_DEFAULT_DIR
        value.startsWith("$OLD_DEFAULT_DIR/") ->
            NEW_DEFAULT_DIR + value.substring(OLD_DEFAULT_DIR.length)
        else -> value
    }

    /**
     * Move the storage folder to the new default name and re-point base_path.
     * Custom paths keep their location (the user chose them deliberately) but
     * get the new marker and lose any old-named mirrors.
     *
     * Returns false when the old default folder could NOT be moved/merged —
     * the marker stays unwritten and the migration retries on the next launch
     * (the P0 bug deleted the vault after a failed move; this never does).
     */
    private fun migrateStorageFolder(prefs: android.content.SharedPreferences): Boolean {
        val current = prefs.getString("base_path", null)?.trim().orEmpty()
        return when {
            current.isEmpty() -> {
                // Fresh app state: only rename when the old default exists.
                if (!Shell.cmd("[ -d $OLD_DEFAULT_DIR ]").exec().isSuccess) {
                    true
                } else {
                    val ok = Shell.cmd(
                        SettingsManager.buildMoveCommand(OLD_DEFAULT_DIR, NEW_DEFAULT_DIR)
                    ).exec().isSuccess
                    if (ok) {
                        removeOldNamedMirrors(NEW_DEFAULT_DIR)
                        // Pin the pref so the app and the migration agree on the folder.
                        prefs.edit { putString("base_path", NEW_DEFAULT_DIR) }
                    }
                    ok
                }
            }
            current == OLD_DEFAULT_DIR -> {
                val ok = Shell.cmd(
                    SettingsManager.buildMoveCommand(OLD_DEFAULT_DIR, NEW_DEFAULT_DIR)
                ).exec().isSuccess
                if (ok) {
                    removeOldNamedMirrors(NEW_DEFAULT_DIR)
                    prefs.edit { putString("base_path", NEW_DEFAULT_DIR) }
                }
                ok
            }
            else -> {
                Shell.cmd("touch ${ShellQuote.q("$current/.droidvault_dir")}").exec()
                removeOldNamedMirrors(current)
                true
            }
        }
    }

    /**
     * Old-named self-backup mirrors must not survive the rebrand: a
     * ROM-Shifter.apk mirror contains the OLD app and would masquerade as a
     * Droid Vault self-backup. They regenerate under the new names on the
     * next backup/restore run.
     */
    private fun removeOldNamedMirrors(folder: String) {
        Shell.cmd(
            "rm -f ${ShellQuote.q("$folder/ROM-Shifter.apk")} ${ShellQuote.q("$folder/ROM-Shifter.sh")}"
        ).exec()
    }

    /**
     * Copy (never move) the engine dir to the new name: a copy is idempotent
     * if a previous attempt died midway. Once the new dir verifiably holds
     * both the script and zapdos, the old one is removed — zero traces, and
     * BackendInstaller re-seeds the new dir on every launch anyway.
     */
    private fun migrateEngineDir() {
        Shell.cmd(
            "if [ -d $OLD_ENGINE_DIR ] && [ ! -f $NEW_ENGINE_DIR/DroidVault.sh ]; then " +
                "mkdir -p $NEW_ENGINE_DIR && cp -f $OLD_ENGINE_DIR/* $NEW_ENGINE_DIR/ 2>/dev/null; " +
                "chmod 755 $NEW_ENGINE_DIR $NEW_ENGINE_DIR/DroidVault.sh $NEW_ENGINE_DIR/zapdos 2>/dev/null; " +
                "chown -R root:root $NEW_ENGINE_DIR 2>/dev/null; fi; " +
                // Old-named engine + APK mirrors must not linger: a ROM-Shifter.apk
                // mirror would contain the OLD app, not Droid Vault.
                "rm -f $NEW_ENGINE_DIR/ROM-Shifter.sh $NEW_ENGINE_DIR/ROM-Shifter.apk"
        ).exec()

        // Magisk module: copy both the live dir and the staged-update dir.
        Shell.cmd(
            "if [ -d $OLD_MODULE_DIR ] && [ ! -d $NEW_MODULE_DIR ]; then " +
                "cp -a $OLD_MODULE_DIR $NEW_MODULE_DIR 2>/dev/null; fi; " +
                "if [ -d $OLD_MODULE_UPDATE_DIR ] && [ ! -d $NEW_MODULE_UPDATE_DIR ]; then " +
                "cp -a $OLD_MODULE_UPDATE_DIR $NEW_MODULE_UPDATE_DIR 2>/dev/null; fi"
        ).exec()
        // NOTE: legacy MODULE dirs are intentionally KEPT for the current boot
        // (a module id is baked into the mount namespace at boot time); the
        // module.prop id=DroidVault takes over at the next reboot.

        // The legacy ENGINE dir is plain files (no mounts): remove it once the
        // new copy verifies.
        Shell.cmd(
            "if [ -f $NEW_ENGINE_DIR/DroidVault.sh ] && [ -f $NEW_ENGINE_DIR/zapdos ]; then " +
                "rm -rf $OLD_ENGINE_DIR; fi"
        ).exec()
    }

    /** Re-seed the new tmp dirs; old ones are orphaned scratch space. */
    private fun cleanupTmpDirs() {
        Shell.cmd(
            "rm -rf $OLD_TMP_DIR /data/local/tmp/shifter_targets.txt /data/local/tmp/shifter_cancel /data/local/tmp/shifter_install; " +
                "mkdir -p $NEW_TMP_DIR && chmod 711 $NEW_TMP_DIR && chown shell:shell $NEW_TMP_DIR 2>/dev/null; true"
        ).exec()
    }
}
