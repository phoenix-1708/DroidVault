package com.phoenix.droidvault

import com.phoenix.droidvault.models.HomeShortcut
import com.phoenix.droidvault.models.ShortcutTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the editable Home quick-access row.
 *
 * The selection is persisted as a comma-joined list of ids, so the parsing and
 * storage round trip is the whole contract: an id that changes or disappears
 * silently drops a user's shortcut, and a parse that throws takes the landing
 * page down with it.
 */
class HomeShortcutTest {

    @Test
    fun `ids are unique`() {
        // The storage format is a comma-joined id list, so a duplicate id would
        // round trip to a row the user can never remove.
        val ids = HomeShortcut.entries.map { it.id }
        assertEquals("shortcut ids must be unique", ids.size, ids.distinct().size)
    }

    @Test
    fun `ids survive a storage round trip in order`() {
        val chosen = listOf(
            HomeShortcut.SCHEDULES,
            HomeShortcut.DEVICE_BACKUP,
            HomeShortcut.DEBOOT
        )
        val raw = HomeShortcut.toStorage(chosen)
        assertEquals(chosen, HomeShortcut.parseList(raw))
    }

    @Test
    fun `a blank or null value yields the default row`() {
        assertEquals(HomeShortcut.DEFAULT, HomeShortcut.parseList(null))
        assertEquals(HomeShortcut.DEFAULT, HomeShortcut.parseList(""))
        assertEquals(HomeShortcut.DEFAULT, HomeShortcut.parseList("   "))
    }

    @Test
    fun `an unknown id is dropped without losing the rest`() {
        // A shortcut retired in a later build must degrade to "not shown",
        // never take the landing page down with it.
        val parsed = HomeShortcut.parseList("deboot,retired_from_a_future_build,schedules")
        assertEquals(listOf(HomeShortcut.DEBOOT, HomeShortcut.SCHEDULES), parsed)
    }

    @Test
    fun `an entirely unrecognised list falls back to the default`() {
        val parsed = HomeShortcut.parseList("nope,also_nope")
        assertEquals(HomeShortcut.DEFAULT, parsed)
    }

    @Test
    fun `duplicates in storage collapse`() {
        val parsed = HomeShortcut.parseList("deboot,deboot,deboot")
        assertEquals(listOf(HomeShortcut.DEBOOT), parsed)
    }

    @Test
    fun `whitespace around ids is tolerated`() {
        assertEquals(
            listOf(HomeShortcut.HISTORY, HomeShortcut.DEBOOT),
            HomeShortcut.parseList(" history , deboot ")
        )
    }

    @Test
    fun `an id that resolves to nothing returns null`() {
        assertNull(HomeShortcut.fromId("not_a_real_shortcut"))
        assertEquals(HomeShortcut.DEBOOT, HomeShortcut.fromId("deboot"))
    }

    @Test
    fun `every shortcut has display text and a valid tab`() {
        for (s in HomeShortcut.entries) {
            assertTrue("${s.id} needs a title", s.title.isNotBlank())
            assertTrue("${s.id} needs a subtitle", s.subtitle.isNotBlank())
            assertTrue("${s.id} tab out of range: ${s.tab}", s.tab in 0..4)
        }
    }

    @Test
    fun `migrator shortcuts name a real submenu`() {
        // A shortcut pointing at MENU would tap through to a screen identical to
        // the dashboard, which reads as a dead card.
        for (s in HomeShortcut.entries.filter { it.kind == ShortcutTarget.MIGRATOR }) {
            assertTrue(
                "${s.id} must target a submenu, not the menu itself",
                s.mode != com.phoenix.droidvault.models.MigratorMode.MENU
            )
        }
    }

    @Test
    fun `device data shortcuts declare a direction`() {
        val device = HomeShortcut.entries.filter { it.kind == ShortcutTarget.DEVICE_DATA }
        assertTrue("expected device-data shortcuts", device.isNotEmpty())
        assertTrue(
            "expected both directions",
            device.any { it.deviceBackup } && device.any { !it.deviceBackup }
        )
    }

    @Test
    fun `the default row keeps the primary actions reachable`() {
        // The two big Back up apps / Restore buttons were replaced by the
        // overview card, so those actions must be one tap away in the default
        // row — a backup app whose front door cannot start a backup regressed.
        assertTrue(
            "default row must include Back up apps",
            HomeShortcut.DEFAULT.contains(HomeShortcut.BACKUP_APPS)
        )
        assertTrue(
            "default row must include Restore apps",
            HomeShortcut.DEFAULT.contains(HomeShortcut.RESTORE_APPS)
        )
        assertTrue(
            "default row must keep the pre-existing two",
            HomeShortcut.DEFAULT.containsAll(
                listOf(HomeShortcut.MANAGE_BACKUPS, HomeShortcut.AUTOMATION)
            )
        )
    }

    @Test
    fun `the default row has no duplicates`() {
        assertEquals(
            HomeShortcut.DEFAULT.size,
            HomeShortcut.DEFAULT.distinct().size
        )
    }

    @Test
    fun `storage fraction is clamped and never divides by zero`() {
        // Used by the overview bar; a zero total must report 0, not NaN.
        val zero = MainViewModel.StorageInfo(freeBytes = 0L, totalBytes = 0L)
        assertEquals(0f, zero.usedFraction, 0.0001f)
        val half = MainViewModel.StorageInfo(freeBytes = 50L, totalBytes = 100L)
        assertEquals(0.5f, half.usedFraction, 0.0001f)
        // Free can exceed total on some FUSE mounts when a quota is in play.
        val over = MainViewModel.StorageInfo(freeBytes = 120L, totalBytes = 100L)
        assertEquals(0f, over.usedFraction, 0.0001f)
        assertEquals(0L, over.usedBytes)
    }
}
