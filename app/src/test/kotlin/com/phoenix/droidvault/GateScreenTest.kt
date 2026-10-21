package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppSeed
import com.phoenix.droidvault.models.Parts
import com.phoenix.droidvault.models.ProgressState
import com.phoenix.droidvault.utils.ProgressTracker
import com.phoenix.droidvault.utils.startGateScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pre-restore gate runs BEFORE the restore, and it used to run before the
 * tracker was seeded — so the Processing page did not exist yet and the wait
 * was a config list with a "Cancel" button and no explanation (measured: 85
 * seconds of hashing the whole vault before an eight-app restore began).
 *
 * This asserts the BEHAVIOUR, not that a call is present: the tracker really
 * is Running with the plan's apps on it, and it is released again so the
 * restore's own run can take the tracker over.
 */
class GateScreenTest {

    private fun seed(label: String) = AppSeed(
        packageName = "com.example.$label",
        label = label,
        isSystem = false,
        iconPath = null,
        parts = listOf(Parts.APP),
        sizeKb = 1024L,
    )

    @Test
    fun `the gate puts the run screen up with the plan's apps on it`() {
        ProgressTracker.dismiss()
        val started = startGateScreen(
            ownerId = "gate-owner",
            seeds = listOf(seed("Brave"), seed("Duolingo")),
            interactive = true,
        )
        assertTrue("the gate must put the run screen up", started)
        val state = ProgressTracker.state.value
        assertTrue(
            "the run screen must be Running while the gate works, was ${state::class.simpleName}",
            state is ProgressState.Running
        )
        val running = state as ProgressState.Running
        assertEquals("both planned apps must be on the screen", 2, running.apps.size)
        assertEquals("Brave", running.apps[0].label)
        assertEquals("the screen must belong to the gate", true, running.interactive)
    }

    @Test
    fun `the gate releases the tracker so the restore can take it over`() {
        ProgressTracker.dismiss()
        startGateScreen("gate-owner", listOf(seed("Brave")), interactive = true)
        // Released: the app phase's own run starts next, and `beginRun` refuses
        // while another run holds the tracker.
        ProgressTracker.endRun("gate-owner")
        val started = startGateScreen("next-owner", listOf(seed("Brave")), interactive = true)
        assertTrue(
            "the restore's run must be able to take the tracker over from the gate",
            started
        )
        ProgressTracker.endRun("next-owner")
        ProgressTracker.dismiss()
    }

    @Test
    fun `a gate with nothing to check does not claim the screen`() {
        ProgressTracker.dismiss()
        assertFalse(
            "a plan with no apps has nothing to verify and must not take the tracker",
            startGateScreen("gate-owner", emptyList(), interactive = true)
        )
    }
}
