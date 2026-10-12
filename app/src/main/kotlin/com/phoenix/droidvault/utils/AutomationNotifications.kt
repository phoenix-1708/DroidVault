package com.phoenix.droidvault.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.phoenix.droidvault.R
import com.phoenix.droidvault.models.ConfigOperation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Single owner of the automation notification channel + ids (P6 dedupe: was
 * duplicated in AutomationViewModel and now the scheduled worker). Also owns
 * the scheduled-run Cancel affordance (P2): a pending broadcast that flips the
 * backend's cooperative cancel flag — the same mechanism manual runs use.
 */
object AutomationNotifications {

    const val CHANNEL_ID = "automation"
    const val NOTIFICATION_ID = 2001

    /** Distinct ids: the SMS cards must never replace the run-progress card. */
    const val SMS_PRE_RUN_NOTIFICATION_ID = 2003
    const val SMS_HANDBACK_NOTIFICATION_ID = 2004

    /** Manifest-declared receiver that relays the Cancel action. */
    const val CANCEL_ACTION = "com.phoenix.droidvault.ACTION_CANCEL_AUTOMATION"

    /** Manifest-declared receiver for the handback card's Confirm action. */
    const val HANDBACK_DONE_ACTION = "com.phoenix.droidvault.ACTION_SMS_HANDBACK_CONFIRM"
    const val EXTRA_HANDBACK_TARGET = "handback_target"

    /**
     * High-importance channel for the SMS role cards: a default-SMS handoff is
     * exactly the kind of thing the user must see immediately, not whenever
     * the default channel's slot frees up.
     */
    const val ALERT_CHANNEL_ID = "automation_alerts"

    private const val TAG = "AutomationNotif"

    private fun ensureChannel(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Automation",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                "Automation Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun canNotify(context: Context): Boolean =
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU

    /**
     * Ongoing progress notification with a Cancel action. Used by scheduled
     * runs (worker) so a runaway 50 GB nightly backup can be stopped from the
     * shade even though no UI is open.
     */
    // Runtime guard is explicit above: canNotify() checks POST_NOTIFICATIONS on
    // T+ and the whole body is try/caught, so a revoked permission can only
    // skip the notify — never crash. Lint cannot see through the helper;
    // suppression is scoped to these two verified call sites.
    @android.annotation.SuppressLint("MissingPermission")
    fun notifyProgress(context: Context, title: String, text: String, operation: ConfigOperation) {
        try {
            if (!canNotify(context)) return
            ensureChannel(context)
            val cancelIntent = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, RunCancelReceiver::class.java).setAction(CANCEL_ACTION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Tap → open the live Processing screen (background runs included).
            val contentIntent = PendingIntent.getActivity(
                context,
                2002,
                Intent(context, com.phoenix.droidvault.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(com.phoenix.droidvault.MainActivity.EXTRA_SHOW_PROGRESS, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .addAction(
                    0, "Cancel", cancelIntent
                )
            builder.setProgress(0, 0, true)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            // Never swallow silently: a swallowed notify() is invisible (the
            // shade keeps the previous card) and took real debugging to find.
            android.util.Log.w(TAG, "notifyProgress failed", e)
        }
    }

    /** Terminal notification replacing the progress one. */
    // Same verified runtime guard as notifyProgress (see comment there).
    @android.annotation.SuppressLint("MissingPermission")
    fun notifyDone(context: Context, title: String, text: String) {
        try {
            if (!canNotify(context)) return
            ensureChannel(context)
            val contentIntent = PendingIntent.getActivity(
                context,
                2002,
                Intent(context, com.phoenix.droidvault.MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(com.phoenix.droidvault.MainActivity.EXTRA_SHOW_PROGRESS, true)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setOngoing(false)
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "notifyDone failed", e)
        }
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }

    // ---------- SMS default-app handoff cards ----------
    //
    // LEGACY PATH. Nothing takes the default-SMS role any more: the Migrate
    // tab and the automation runner both read and write Messages through
    // SmsViaRoot (root snapshot + provider write), so a run starts and ends
    // without touching the role. These cards remain only for markers an older
    // build left behind — MainViewModel.healPendingSmsHandoff() and the Confirm
    // action below still finish a handback that a pre-root run started.

    /**
     * Posted BEFORE an automation restore flips Droid Vault to the default SMS
     * app (root handoff). The whole point: the user must never discover the
     * role change by accident — manual and headless scheduled runs alike.
     *
     * No longer called: roles are never taken. Kept so an in-flight old-build
     * card can still be cancelled by the handback cards below.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun notifySmsPreRun(context: Context) {
        try {
            if (!canNotify(context)) return
            ensureChannel(context)
            val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle("Restoring Messages")
                .setContentText("Droid Vault is temporarily your default SMS app")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Droid Vault is temporarily your default SMS app. " +
                            "It hands the role back automatically when the restore finishes."
                    )
                )
                .setAutoCancel(true)
                .setOngoing(false)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
            NotificationManagerCompat.from(context)
                .notify(SMS_PRE_RUN_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "notifySmsPreRun failed", e)
        }
    }

    /**
     * The automatic handback could not be confirmed (role change still
     * propagating, platform killed us mid-handoff, root hiccups). Persistent
     * card with a one-tap Confirm action that retries the handoff — headless
     * runs have no UI, so the shade is the only place to finish this.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun notifySmsHandbackPending(context: Context, targetPkg: String) {
        try {
            if (!canNotify(context)) return
            ensureChannel(context)
            cancelSmsPreRun(context)
            val confirmIntent = PendingIntent.getBroadcast(
                context,
                2005,
                Intent(context, SmsHandbackReceiver::class.java).apply {
                    action = HANDBACK_DONE_ACTION
                    putExtra(EXTRA_HANDBACK_TARGET, targetPkg)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle("Default SMS app not returned yet")
                .setContentText("Tap Confirm to hand the role back to your messages app")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "The automatic handback could not be confirmed. " +
                            "Droid Vault may still be your default SMS app — " +
                            "tap Confirm to hand the role back now."
                    )
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(0, "Confirm", confirmIntent)
            NotificationManagerCompat.from(context)
                .notify(SMS_HANDBACK_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "notifySmsHandbackPending failed", e)
        }
    }

    /**
     * Handback verified: the previous default SMS app holds the role again.
     * Transient card so the user gets the closure; goes away on tap.
     */
    @android.annotation.SuppressLint("MissingPermission")
    fun notifySmsHandbackConfirmed(context: Context, targetPkg: String) {
        try {
            if (!canNotify(context)) return
            ensureChannel(context)
            cancelSmsPreRun(context)
            val label = runCatching {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(targetPkg, 0)
                ).toString()
            }.getOrDefault(targetPkg)
            val builder = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_home)
                .setContentTitle("Default SMS app restored")
                .setContentText("$label is your default SMS app again")
                .setAutoCancel(true)
                .setOngoing(false)
            NotificationManagerCompat.from(context)
                .notify(SMS_HANDBACK_NOTIFICATION_ID, builder.build())
        } catch (e: Exception) {
            android.util.Log.w(TAG, "notifySmsHandbackConfirmed failed", e)
        }
    }

    /** Both SMS-role cards (pre-run + handback). Never throws. */
    fun cancelSmsCards(context: Context) {
        try {
            NotificationManagerCompat.from(context).apply {
                cancel(SMS_PRE_RUN_NOTIFICATION_ID)
                cancel(SMS_HANDBACK_NOTIFICATION_ID)
            }
        } catch (_: Exception) {
        }
    }

    fun cancelSmsPreRun(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(SMS_PRE_RUN_NOTIFICATION_ID)
        } catch (_: Exception) {
        }
    }
}

/**
 * Relay for the handback card's Confirm action. Not exported: only our own
 * notification's PendingIntent can target it. Retries the root role handoff,
 * then verifies via Settings.Secure and either confirms or re-arms the card.
 */
class SmsHandbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutomationNotifications.HANDBACK_DONE_ACTION) return
        val appContext = context.applicationContext
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = appContext.getSharedPreferences("droidvault_prefs", Context.MODE_PRIVATE)
                val target = intent.getStringExtra(AutomationNotifications.EXTRA_HANDBACK_TARGET)
                    ?: prefs.getString("pending_sms_restore", null)
                    ?: AutomationRunner.defaultSmsTarget()
                try {
                    DeviceManager.releaseSmsRoleTo(target, appContext.packageName)
                } catch (e: Exception) {
                    android.util.Log.w("SmsHandback", "confirm handback failed: ${e.message}")
                }
                // Role-holder changes propagate asynchronously; give them a beat.
                kotlinx.coroutines.delay(2500)
                val now = try {
                    android.provider.Settings.Secure.getString(
                        appContext.contentResolver, "sms_default_application"
                    )
                } catch (_: Exception) {
                    null
                }
                if (now == target) {
                    prefs.edit().remove("pending_sms_restore").apply()
                    AutomationNotifications.notifySmsHandbackConfirmed(appContext, target)
                } else {
                    // Still not handed back: refresh the card so the user can retry.
                    AutomationNotifications.notifySmsHandbackPending(appContext, target)
                }
            } finally {
                try {
                    pending.finish()
                } catch (_: Exception) {
                }
            }
        }
    }
}

/**
 * Relay for the shade's Cancel action on scheduled runs. Not exported: only
 * our own notification's PendingIntent can target it. Signals the backend
 * (cooperative flag + kill hammer) exactly like the in-app cancel button.
 */
class RunCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutomationNotifications.CANCEL_ACTION) return
        // Order matters (audit A-06): take goAsync() FIRST so the system keeps
        // the process alive, then run the cancel on IO. haltBackend() blocks up
        // to 20 s on a cold su — doing that on the main thread guaranteed an
        // ANR, and calling goAsync().finish() afterwards protected nothing.
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                AutomationRunner.requestCancel()
                AutomationRunner.haltBackend()
                AutomationNotifications.notifyDone(
                    context, "Automation", "Cancelling… finished apps are kept"
                )
            } catch (e: Exception) {
                android.util.Log.w("RunCancel", "cancel failed: ${e.message}")
            } finally {
                try {
                    pending.finish()
                } catch (_: Exception) {
                }
            }
        }
    }
}
