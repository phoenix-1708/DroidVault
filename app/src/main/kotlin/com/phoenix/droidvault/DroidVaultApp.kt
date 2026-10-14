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

    /**
     * How long to wait for the root shell to become ready, in seconds.
     *
     * A floor, not a hope: comfortably longer than reading a permission dialog
     * and deciding, and far longer than anything technical needs (a warm `su`
     * answers in milliseconds). Raising it costs nothing when root is absent or
     * already granted — those paths never reach the bound — and it is the
     * difference between "you granted it" and "Droid Vault needs root".
     *
     * 10s was half of libsu's own 20s default and was the bug.
     */
    companion object {
        const val ROOT_SHELL_TIMEOUT_SECONDS = 60L
    }

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
                // Seconds to wait for the shell to become ready.
                //
                // This is NOT a bound on anything technical. When `su` is absent
                // the attempt fails immediately, so no-root is still reported in
                // a moment. The bound only bites when `su` EXISTS and is waiting
                // on the user to answer Magisk's dialog — so it is a bound on
                // human reaction time, and a human reading "Allow Droid Vault to
                // have superuser access?" is not a machine.
                //
                // At 10s (libsu's own default is 20) the sequence is a trap.
                // Verified by decompiling libsu 6.0.0: `BuilderImpl.start()` tries
                // `su --mount-master`, then `su`, then falls back to `sh` — each
                // bounded by this number. A user who took longer than 10s to read
                // the first dialog had that dialog DISCARDED, was shown a second
                // one, and if slow again was told "Droid Vault needs root" while
                // Magisk was holding the grant. The grant existed the whole time;
                // the app just stopped waiting for it.
                .setTimeout(ROOT_SHELL_TIMEOUT_SECONDS)
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
