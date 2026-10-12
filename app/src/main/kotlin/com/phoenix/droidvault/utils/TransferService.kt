package com.phoenix.droidvault.utils

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.phoenix.droidvault.MainActivity
import com.phoenix.droidvault.R
import com.phoenix.droidvault.models.VaultTransfer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Which half of a transfer is running. */
enum class TransferOp { EXPORT, IMPORT }

/**
 * What the transfer is doing right now, and how it ended.
 *
 * Held here rather than in the ViewModel because the work no longer lives in
 * one: [TransferService] owns it, so the progress has to outlive whichever
 * Activity happened to start it.
 */
data class TransferStatus(
    val op: TransferOp? = null,
    val percent: Int = -1,
    val detail: String = "",
    val running: Boolean = false
) {
    /** True only once something real has happened. */
    val started: Boolean get() = op != null
}

/**
 * The bridge between [TransferService] and whatever UI is watching.
 *
 * A manual export used to run in `viewModelScope`, which meant pressing Back —
 * finishing the Activity, clearing the ViewModel, cancelling the scope — killed
 * a thirty-gigabyte pack halfway and left it on disk. The service owns the work
 * now; this is how the screen finds out about it, and how it asks it to stop.
 */
object TransferBus {
    private val _status = MutableStateFlow(TransferStatus())
    val status: StateFlow<TransferStatus> = _status.asStateFlow()

    /** Terminal results, consumed once by whoever is listening. */
    private val _done = MutableSharedFlow<VaultTransferManager.Result>(replay = 0, extraBufferCapacity = 4)
    val done = _done.asSharedFlow()

    /** The running operation's cancel flag. Null when idle. */
    @Volatile
    private var cancelFlag: AtomicBoolean? = null

    internal fun begin(op: TransferOp, flag: AtomicBoolean) {
        cancelFlag = flag
        _status.value = TransferStatus(op = op, percent = -1, detail = "Starting…", running = true)
    }

    internal fun report(percent: Int, detail: String) {
        _status.value = _status.value.copy(percent = percent, detail = detail)
    }

    internal fun finish(result: VaultTransferManager.Result) {
        cancelFlag = null
        _status.value = TransferStatus()
        _done.tryEmit(result)
    }

    /** Ask the running transfer to stop. Harmless when nothing is running. */
    fun requestCancel() {
        cancelFlag?.set(true)
        AutomationRunner.requestCancel()
    }

    internal val isRunning: Boolean get() = cancelFlag != null
}

const val TRANSFER_CHANNEL_ID = "transfer"

/**
 * Runs a vault transfer as foreground work.
 *
 * Foreground rather than a plain coroutine because this is the part of the app
 * that most needs to outlive the UI: the user starts a thirty-gigabyte export,
 * switches to a browser to check something, and Android quietly evicts a
 * process with no foreground component. It did, and it left the pack on disk
 * (§2.41).
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Cancel from the notification. Stopping the flag is not enough: the
        // manager unwinds, removes what it staged, and only then stops the
        // service, so tearing the service down here would kill the cleanup.
        if (intent?.action == ACTION_CANCEL) {
            TransferBus.requestCancel()
            // NOT sticky. A cancel is the opposite of "keep going": recreating
            // the service to re-run it would re-raise a cancel flag for a
            // transfer that may already have finished and started something
            // else.
            return START_NOT_STICKY
        }
        val op = intent?.getStringExtra(EXTRA_OP)
        if (op == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // The run gate, taken HERE because this is where the work now lives.
        //
        // It has to be non-blocking: a second transfer must be REFUSED, not
        // queued behind the first. An export that waited its turn would sit
        // holding a foreground notification, holding a wake lock, and holding
        // the user's attention, while doing nothing — and a user who taps
        // twice did not ask to queue.
        if (job?.isActive == true) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!RunCoordinator.tryAcquire("transfer")) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startInForeground(TransferOp.valueOf(op))
        val vault = intent.getStringExtra(EXTRA_VAULT).orEmpty()
        val archive = intent.getStringExtra(EXTRA_ARCHIVE).orEmpty()
        val parts = intent.getStringArrayListExtra(EXTRA_PARTS).orEmpty()
        val split = intent.getLongExtra(EXTRA_SPLIT, 0L)
        val merge = intent.getStringExtra(EXTRA_MERGE)
        val countsJson = intent.getStringExtra(EXTRA_COUNTS)
        // The SAF picker's private copy of the chosen archive. Freed here
        // rather than in the ViewModel, because the ViewModel may not outlive
        // this run and a multi-gigabyte cache file is not something to leave to
        // a scope that might already be gone.
        val stagedCopy = intent.getStringExtra(EXTRA_STAGED).orEmpty()

        val flag = AtomicBoolean(false)
        val operation = TransferOp.valueOf(op)
        TransferBus.begin(operation, flag)
        AutomationRunner.clearCancel()

        job = scope.launch {
            val result = try {
                when (operation) {
                    TransferOp.EXPORT -> VaultTransferManager.export(
                        this@TransferService, vault,
                        cancel = flag, splitBytes = split
                    ) { pct, detail, _ ->
                        TransferBus.report(pct, detail)
                        refresh(TransferOp.EXPORT, pct, detail)
                    }

                    TransferOp.IMPORT -> {
                        val counts = countsJson?.let {
                            runCatching {
                                val o = org.json.JSONObject(it)
                                VaultTransfer.ConfigCounts(
                                    o.optInt("labels"), o.optInt("configs"), o.optInt("schedules")
                                )
                            }.getOrNull()
                        } ?: VaultTransfer.ConfigCounts(0, 0, 0)
                        VaultTransferManager.import(
                            this@TransferService, counts, vault, archive,
                            VaultTransfer.Merge.valueOf(merge ?: VaultTransfer.Merge.entries.first().name),
                            cancel = flag,
                            sourceParts = parts
                        ) { pct, detail, _ ->
                            TransferBus.report(pct, detail)
                            refresh(TransferOp.IMPORT, pct, detail)
                        }
                    }
                }
            } catch (e: Exception) {
                // The service is the last line of defence: whatever the manager
                // did not turn into a Result must still stop the run cleanly and
                // say so, rather than leaving a silent transfer behind.
                Log.w(TAG, "transfer service failed", e)
                VaultTransferManager.Result.Failed(e.message ?: "Transfer failed")
            }
            // Everything this run occupied is freed BEFORE the gate opens.
            //
            // The order is the point. The manager's own `finally` blocks have
            // already removed the in-progress archive and the staging tree by
            // the time we get here; the SAF picker's private copy is the last
            // thing, and it can be gigabytes. Releasing the gate first would
            // let the next transfer start allocating while this one is still
            // handing space back — and the moment that matters most is exactly
            // when the disk was too full for this run to begin with.
            if (stagedCopy.isNotEmpty()) {
                runCatching { java.io.File(stagedCopy).delete() }
                runCatching {
                    java.io.File(stagedCopy).parentFile
                        ?.takeIf { it.list()?.isEmpty() == true }?.delete()
                }
            }
            stopForegroundCompat()
            TransferBus.finish(result)
            RunCoordinator.release()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(op: TransferOp) {
        acquireWakeLock()
        val notification = build(op, -1, "Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun build(op: TransferOp, percent: Int, detail: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancel = PendingIntent.getService(
            this, 1,
            Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (op == TransferOp.EXPORT) "Exporting backups" else "Importing backups"
        return NotificationCompat.Builder(this, TRANSFER_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Cancel", cancel)
            .apply { if (percent >= 0) setProgress(100, percent, false) }
            .build()
    }

    /** Push a fresh progress line into the running notification. */
    internal fun refresh(op: TransferOp, percent: Int, detail: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, build(op, percent, detail))
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidVault:transfer").apply {
            setReferenceCounted(false)
            // A safety net, not the mechanism: the foreground notification is
            // what keeps the process alive. This covers the CPU going to sleep
            // mid-write, which would stall a shell job with no error.
            acquire(MAX_WAKE_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private fun stopForegroundCompat() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        releaseWakeLock()
    }

    override fun onDestroy() {
        // The work is cancelled with the scope, and each manager call has its
        // own `finally` that removes what it staged — so a destroyed service
        // leaves no half-written archive and no staging tree behind.
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TransferService"
        private const val NOTIFICATION_ID = 4201
        const val EXTRA_OP = "op"
        const val EXTRA_VAULT = "vault"
        const val EXTRA_ARCHIVE = "archive"
        const val EXTRA_PARTS = "parts"
        const val EXTRA_SPLIT = "split"
        const val EXTRA_MERGE = "merge"
        const val EXTRA_COUNTS = "counts"
        const val EXTRA_STAGED = "staged"
        const val ACTION_CANCEL = "com.phoenix.droidvault.CANCEL_TRANSFER"

        /** Long enough for any real transfer; the notification is the real net. */
        private const val MAX_WAKE_MS = 6L * 60 * 60 * 1000

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(TRANSFER_CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    TRANSFER_CHANNEL_ID, "Vault transfers", NotificationManager.IMPORTANCE_LOW
                ).apply { setShowBadge(false) }
            )
        }

        /** Start an export. [split] is 0 for a single file. */
        fun startExport(context: Context, vault: String, split: Long) {
            ensureChannel(context)
            val i = Intent(context, TransferService::class.java)
                .putExtra(EXTRA_OP, TransferOp.EXPORT.name)
                .putExtra(EXTRA_VAULT, vault)
                .putExtra(EXTRA_SPLIT, split)
            runCatching { context.startForegroundService(i) }
        }

        /** Start an import of one archive or a verified set of pieces. */
        fun startImport(
            context: Context,
            vault: String,
            archive: String,
            parts: List<String>,
            merge: VaultTransfer.Merge,
            counts: VaultTransfer.ConfigCounts,
            stagedCopy: String? = null
        ) {
            ensureChannel(context)
            val i = Intent(context, TransferService::class.java)
                .putExtra(EXTRA_OP, TransferOp.IMPORT.name)
                .putExtra(EXTRA_VAULT, vault)
                .putExtra(EXTRA_ARCHIVE, archive)
                .putStringArrayListExtra(EXTRA_PARTS, ArrayList(parts))
                .putExtra(EXTRA_MERGE, merge.name)
                .putExtra(EXTRA_STAGED, stagedCopy ?: "")
                .putExtra(
                    EXTRA_COUNTS,
                    org.json.JSONObject()
                        .put("labels", counts.labels)
                        .put("configs", counts.configs)
                        .put("schedules", counts.schedules)
                        .toString()
                )
            runCatching { context.startForegroundService(i) }
        }
    }
}