package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.FlashAction
import com.phoenix.droidvault.models.FlashZip
import com.phoenix.droidvault.models.EngineEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextPhaseTest {
    @Test fun flashOpsBuildActionsValidates() {
        val zips = listOf(FlashZip("rom.zip", "/sdcard/rom.zip", "ROM"))
        val actions = FlashOps.buildActions(zips, setOf("dalvik", "cache", ";rm -rf /"), false)
        val wipes = actions.filterIsInstance<FlashAction.Wipe>()
        // Only allowlisted wipes survive; injection string is not in allowlist
        assertTrue(wipes.single().partitions.contains("dalvik"))
        assertFalse(wipes.single().partitions.any { it.contains(";") })
        assertEquals(2, actions.size)
    }

    @Test fun shellQuoteRoundTrip() {
        val evil = "a'; id; echo '"
        val q = ShellQuote.q(evil)
        assertTrue(q.startsWith("'") && q.endsWith("'"))
        assertTrue(ShellQuote.safeLabel("a|b\nc").length <= 128)
    }

    @Test fun installEventsParsed() {
        val done = ShellEngine.parseLine("ACTION:INSTALL_DONE|PKG:com.example.app")
        assertTrue(done is EngineEvent.InstallDone)
        assertEquals("com.example.app", (done as EngineEvent.InstallDone).pkg)
        val err = ShellEngine.parseLine("ACTION:INSTALL_ERROR|PKG:com.example.app|MSG:Failure [INSTALL_FAILED: bad]")
        assertTrue(err is EngineEvent.InstallError)
        assertEquals("Failure [INSTALL_FAILED: bad]", (err as EngineEvent.InstallError).msg)
    }

    @Test fun cancelEventParsed() {
        val event = ShellEngine.parseLine("ACTION:CANCELLED")
        assertTrue(event is EngineEvent.Cancelled)
    }
}
