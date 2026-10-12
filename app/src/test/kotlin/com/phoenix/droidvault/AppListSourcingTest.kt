package com.phoenix.droidvault

import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.models.isVaultSourced
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Migrate modes own the app list from the vault rather than the device.
 *
 * Guards the fix for a live bug: Restore Apps listed every installed app as
 * "No backup on device" on a vault with zero backups. The Auto tab seeds the
 * shared app list with installed apps on launch, and an empty backup list is
 * indistinguishable from "not loaded yet", so the seed overwrote it.
 */
class AppListSourcingTest {

    @Test
    fun `restore and manage read from the vault`() {
        assertTrue(MigratorMode.RESTORE_APPS.isVaultSourced)
        assertTrue(MigratorMode.MANAGE.isVaultSourced)
    }

    @Test
    fun `backup debloat and systemize read from the device`() {
        // These back up installed apps, so an installed-set seed is harmless —
        // and in fact is exactly what they want.
        assertFalse(MigratorMode.BACKUP_APPS.isVaultSourced)
        assertFalse(MigratorMode.DEBLOAT.isVaultSourced)
        assertFalse(MigratorMode.SYSTEMIZE.isVaultSourced)
    }

    @Test
    fun `the menu is not vault sourced`() {
        // The Auto tab seeds the list while the menu is showing; that is the
        // behaviour being preserved, not suppressed.
        assertFalse(MigratorMode.MENU.isVaultSourced)
    }
}