package com.phoenix.droidvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the "What's in my vault folder" explainer:
 * stat output parsing and the artifact ordering (APK mirror, Rust engine,
 * compatibility launcher, state file). Shell access itself is device-only.
 */
class VaultContentsTest {

    // ---------- parseStatLine ----------

    @Test
    fun `parseStatLine reads size and mtime`() {
        assertEquals(1234L to 1696176000L, AutomationSnapshot.parseStatLine("1234 1696176000"))
    }

    @Test
    fun `parseStatLine tolerates surrounding and repeated whitespace`() {
        assertEquals(
            42L to 1000L,
            AutomationSnapshot.parseStatLine("  42   1000  ")
        )
    }

    @Test
    fun `parseStatLine rejects garbage and empty input`() {
        assertNull(AutomationSnapshot.parseStatLine(""))
        assertNull(AutomationSnapshot.parseStatLine("not numbers"))
        assertNull(AutomationSnapshot.parseStatLine("42"))
        assertNull(AutomationSnapshot.parseStatLine("42 only-first-is-number"))
    }

    @Test
    fun `parseStatLine accepts zero size so caller can treat it as absent`() {
        assertEquals(0L to 0L, AutomationSnapshot.parseStatLine("0 0"))
    }

    // ---------- vaultFileNames ordering ----------

    @Test
    fun `explainer lists APK mirror then Rust engine and launcher then state file`() {
        assertEquals(
            listOf(
                AutomationSnapshot.APK_FILE,
                AutomationSnapshot.BACKEND_FILE,
                AutomationSnapshot.LAUNCHER_FILE,
                AutomationSnapshot.STATE_FILE
            ),
            AutomationSnapshot.vaultFileNames
        )
    }

    @Test
    fun `artifact names are stable for the vault layout`() {
        assertEquals("DroidVault.apk", AutomationSnapshot.APK_FILE)
        assertEquals("DroidVault.engine", AutomationSnapshot.BACKEND_FILE)
        assertEquals("DroidVault.sh", AutomationSnapshot.LAUNCHER_FILE)
        assertEquals("automation-state.json", AutomationSnapshot.STATE_FILE)
    }

    @Test
    fun `absent artifact keeps present false with zero size and mtime`() {
        // Mirror the ViewModel/UI contract: missing files are listed with
        // present=false so the card can explain "not created yet".
        val info = AutomationSnapshot.VaultFileInfo(
            name = AutomationSnapshot.APK_FILE,
            path = "/storage/emulated/0/DroidVault/DroidVault.apk",
            present = false,
            sizeBytes = 0L,
            modifiedAt = 0L
        )
        assertTrue(!info.present && info.sizeBytes == 0L && info.modifiedAt == 0L)
    }
}
