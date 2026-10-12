package com.phoenix.droidvault.ui.screens

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bridge between notifications/intents and the [ProcessingScreen] overlay in
 * MainScreen. MainActivity sets [requestShow] when launched with the run
 * notification's content intent (cold start or onNewIntent); MainScreen
 * observes and presents the overlay.
 */
object ProcessingScreenController {

    private val _showRequests = MutableStateFlow(0)
    val showRequests: StateFlow<Int> = _showRequests.asStateFlow()

    fun requestShow() {
        _showRequests.value += 1
    }

    fun consume() {
        // no-op hook for symmetry; presentation clears via showProcessing=false
    }
}
