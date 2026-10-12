package com.phoenix.droidvault

import android.app.Application
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DroidVaultApp : Application() {

    /** Process-scoped IO jobs: must outlive any activity for app-start work. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Diagnostics logger FIRST so every later step (migration, engine
        // deploy) is already being recorded, including uncaught crashes.
        com.phoenix.droidvault.utils.DroidVaultLogger.install(this)
        com.phoenix.droidvault.utils.DroidVaultLogger.i(
            "App",
            "Droid Vault ${com.phoenix.droidvault.BuildConfig.VERSION_NAME} " +
                "(${com.phoenix.droidvault.BuildConfig.VERSION_CODE}) start"
        )
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10)
        )
        // One-time pre-rebrand layout migration (prefs, storage folder,
        // engine dir, module id). No-op after the first success.
        //
        // Prefs-only phase SYNCHRONOUS: MainViewModel reads base_path during
        // construction, so the heal must land before the first activity.
        LegacyMigration.migratePrefsOnly(this)
        // Root-shell phase on IO: a vault move (potentially multi-GB) must
        // never block app start (field ANR fix). Re-runnable if the process
        // dies midway.
        appScope.launch { LegacyMigration.complete(this@DroidVaultApp) }
        // Folder backup sources: one process-wide list for Settings, Migrate
        // and Automation config editors.
        com.phoenix.droidvault.utils.BackupFolderStore.init(this)
        // Clear any stale automation progress notification left by an older
        // build that never replaced it (same id the runner reuses).
        try {
            androidx.core.app.NotificationManagerCompat.from(this)
                .cancel(com.phoenix.droidvault.utils.AutomationNotifications.NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}
