package com.phoenix.droidvault.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Thread-safe Toast.
 *
 * `Toast.makeText` CRASHES when called from a thread with no `Looper`, which is
 * every `Dispatchers.IO` worker:
 *
 * ```
 * FATAL EXCEPTION: DefaultDispatcher-worker-4
 * java.lang.NullPointerException: Can't toast on a thread that has not called
 * Looper.prepare()
 *     at android.widget.Toast.makeText(Toast.java:501)
 * ```
 *
 * Found on device via the reported "undo crashed but the app came back" — the
 * undo had actually already succeeded, then the process died showing its own
 * success message. Android 11 made this a hard throw rather than a silent
 * no-op, and every background `viewModelScope.launch(Dispatchers.IO)` in this
 * app qualifies.
 *
 * Two consequences that made it look like something other than a crash:
 *  - The work is durable and completes; only the message kills the process, so
 *    reopening shows the expected end state and the user concludes "it worked,
 *    but it also crashed".
 *  - A crash drops all navigation state, so the app relaunches on Home. That is
 *    the *same* crash as the reported "Manage closes and jumps to Home after
 *    deleting", which is why it only happened "sometime": it depends on whether
 *    the completion path happened to toast from a worker.
 *
 * This helper always posts to the main Looper, so call sites do not have to
 * remember to wrap themselves in `withContext(Dispatchers.Main)`. `ConventionGuardTest`
 * enforces that no bare `Toast.makeText` survives in main source.
 */
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Show a Toast from any thread.
 *
 * Posting rather than calling inline is deliberate: it also fixes ordering when
 * a burst of results arrives from different workers, since the queue is FIFO.
 */
fun Context.safeToast(text: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        Toast.makeText(this, text, duration).show()
    } else {
        mainHandler.post { Toast.makeText(this, text, duration).show() }
    }
}