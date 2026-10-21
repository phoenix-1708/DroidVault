package com.phoenix.droidvault.ui.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between notifications/intents and the [ProcessingScreen] overlay in
 * MainScreen.
 *
 * PRESENTATION IS DERIVED FROM THE RUN, NOT REMEMBERED BY THE COMPOSITION.
 *
 * This used to be a transition detector (`lastPhase`/`showProcessing` as local
 * `remember` in the overlay) that showed the page only when it happened to
 * observe the Idle -> Running step. Anything that recreated the Activity while
 * a run was live — a rotation, or simply reopening the app from the launcher —
 * entered the composition at phase "R", recorded `lastPhase = "R"` with
 * `show = false`, and the page stayed invisible for the rest of the run. That
 * is the "Apps Restoration page not appearing" field report: the run's own
 * status line ("Running Apps Restore — Starting...") sat on the screen it was
 * started from, and the notification was the only way back in.
 *
 * The only decision that genuinely has to outlive a composition is "the user
 * hid this page", so that is the only thing kept here. It is stamped with the
 * tracker's `runId`, which can never leak into the next run, and it only
 * suppresses the RUNNING page: a run the user backed out of still gets its
 * completion screen, which is the behaviour the Back dialog promises.
 */
object ProcessingScreenController {

    /** No run is hidden. Also what a notification tap restores. */
    private const val NONE = -1L

    private val _hiddenRunId = MutableStateFlow(NONE)

    /** Tracker runId whose Processing page the user left (Back). */
    val hiddenRunId: StateFlow<Long> = _hiddenRunId.asStateFlow()

    /**
     * Notification content intent / cold start: bring the page back even when
     * the user had left it. MainActivity calls this from onCreate/onNewIntent.
     */
    fun requestShow() {
        _hiddenRunId.value = NONE
    }

    /**
     * The user left the Processing page while its run was still going. The run
     * keeps running (the notification is how it is watched); the page returns
     * by itself once the run reaches a terminal state.
     */
    fun hide(runId: Long) {
        _hiddenRunId.value = runId
    }

    /** True once the notification's content intent has asked for the page this process. */
    fun isHidden(runId: Long): Boolean = _hiddenRunId.value == runId
}