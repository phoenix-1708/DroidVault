package com.phoenix.droidvault

import com.phoenix.droidvault.models.RootState
import com.phoenix.droidvault.models.classifyRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The screen a user cannot get past.
 *
 * Found by hitting it: the app's uid changed across reinstalls, Magisk had no
 * policy row for it, and Magisk's authorisation dialog crashes on that ROM. The
 * result was "Droid Vault needs root" with a visibly enabled button that raised
 * nothing — no prompt, no toast — while the card below it said "Approve Droid
 * Vault in your root manager". The instruction named as the next step was the
 * exact thing that was broken.
 *
 * A single `hasRoot: Boolean` answered "no" to that, and to a device with no root
 * manager, and to a plain denial. Three different situations, three different
 * things the user must do.
 */
class RootStateTest {

    @Test
    fun `a root shell is granted whatever else was probed`() {
        assertEquals(RootState.Granted, classifyRoot(hasSuBinary = true, shellIsRoot = true))
        assertEquals(RootState.Granted, classifyRoot(hasSuBinary = false, shellIsRoot = true))
    }

    @Test
    fun `no su binary is a different problem from an ungranted one`() {
        // Nothing to grant: telling this user to open Magisk's Superuser list is
        // as wrong as it is unhelpful.
        assertEquals(RootState.NoRootManager, classifyRoot(hasSuBinary = false, shellIsRoot = false))
        assertEquals(RootState.NotGranted, classifyRoot(hasSuBinary = true, shellIsRoot = false))
    }

    @Test
    fun `the probe_records_which_state_it_found_and_logs_it`() {
        val vm = File("src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt").readText()
        val probe = requireNotNull(
            Regex("""\bfun\s+checkRootAccess\b""").find(vm)?.range?.first
        ) { "checkRootAccess not found" }
        val body = vm.substring(probe, probe + 2500)
        assertTrue(
            "the probe must classify rather than storing a bare Boolean — that is " +
                "what collapsed three situations into one screen",
            body.contains("classifyRoot")
        )
        assertTrue(
            "the probe must look for the su binary THROUGH THE UNROOTED SHELL; " +
                "asking as root succeeds on a device with no root manager, because " +
                "root's own PATH is not the app's",
            body.contains("command -v su")
        )
        assertTrue(
            "a refusal this total must be recorded — 'why does the app think it " +
                "has no root' was unanswerable from a field report",
            Regex("""DroidVaultLogger\.[iw]\(\s*"?"?Root""").containsMatchIn(body) ||
                body.contains("\"Root\"")
        )
    }

    @Test
    fun `each_state_gets_instructions_that_match_it`() {
        val src = File("src/main/kotlin/com/phoenix/droidvault/ui/screens/MainScreen.kt")
            .readText()
        val fn = requireNotNull(
            Regex("""\bfun\s+NoRootScreen\b""").find(src)?.range?.first
        ) { "NoRootScreen not found" }
        val after = src.substring(fn)
        val end = after.indexOf("\nfun ").let { if (it < 0) after.length else it }
        val body = after.substring(0, end)

        assertTrue(
            "NotGranted must offer the MANUAL route, because that is the case " +
                "where tapping the button does nothing",
            Regex("""RootState\.NotGranted[\s\S]{0,600}?Superuser""").containsMatchIn(body)
        )
        assertTrue(
            "NoRootManager must say there is no root manager, not tell the user to " +
                "approve a prompt that can never appear",
            Regex("""RootState\.NoRootManager[\s\S]{0,600}?no root manager""")
                .containsMatchIn(body)
        )
        // The old blanket instruction is what made this a dead end.
        assertTrue(
            "the state must be threaded through from AppState; a screen that " +
                "cannot tell why is back to guessing",
            src.contains("rootState = appState.rootState")
        )
    }
}