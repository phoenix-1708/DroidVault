package com.phoenix.droidvault

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The path healer rewrote recorded strings that embedded the pre-rebrand
 * default storage dir. Only the exact legacy prefix may be rewritten —
 * custom folders and unrelated strings must pass through untouched.
 */
class LegacyPathHealTest {

    @Test
    fun `exact old dir maps to new dir`() {
        assertEquals("/storage/emulated/0/DroidVault", LegacyMigration.healLegacyPath("/storage/emulated/0/Shifter"))
    }

    @Test
    fun `paths under the old dir remap their prefix`() {
        assertEquals(
            "/storage/emulated/0/DroidVault/Apps/App.shift",
            LegacyMigration.healLegacyPath("/storage/emulated/0/Shifter/Apps/App.shift")
        )
    }

    @Test
    fun `unrelated paths and values pass through`() {
        val untouched = listOf(
            "/storage/emulated/0/DroidVault",
            "/storage/emulated/0/MyFolder/Shifter", // different parent — user's own choice
            "/sdcard/backup.zip",
            "APK",
            ""
        )
        untouched.forEach { assertEquals(it, LegacyMigration.healLegacyPath(it)) }
    }

    @Test
    fun `new dir is not double-rewritten`() {
        val s = "/storage/emulated/0/DroidVault/automation-state.json"
        assertEquals(s, LegacyMigration.healLegacyPath(s))
    }
}
