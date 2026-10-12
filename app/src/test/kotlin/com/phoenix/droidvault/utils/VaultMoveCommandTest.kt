package com.phoenix.droidvault.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests for the P0 vault-move data-loss bug (audit A-01): the old
 * command single-quoted a `*` glob in `mv`, the shell never expanded it,
 * `mv` always failed — and an unconditional `rm -rf` of the source still ran.
 * These tests pin the new safety contract: no glob, copy/rename must succeed
 * before any delete, and the source is never touched on failure.
 */
class VaultMoveCommandTest {

    private val old = "/storage/emulated/0/Shifter"
    private val new = "/storage/emulated/0/DroidVault"

    private val cmd = SettingsManager.buildMoveCommand(old, new)

    @Test
    fun `no glob is ever generated`() {
        // The old code emitted `'$old/*'` — a glob that cannot expand inside
        // single quotes. Any glob in the move command is a regression.
        assertFalse("command must not contain a glob: $cmd", cmd.contains("*"))
        assertFalse(cmd.contains("/*'"))
    }

    @Test
    fun `merge copy uses the dot form and includes dotfiles`() {
        assertTrue(cmd.contains("cp -a '$old/.' '$new/'"))
        // Portable fallback for filesystems that reject preserve flags.
        assertTrue(cmd.contains("cp -r '$old/.' '$new/'"))
    }

    @Test
    fun `delete is chained behind a successful copy`() {
        val copyIdx = cmd.indexOf("cp -a")
        val rmIdx = cmd.indexOf("rm -rf")
        assertTrue("copy must appear before delete", copyIdx in 0 until rmIdx)
        // The copy must be the guard of the delete: `if cp ... ; then touch
        // ... && rm -rf ...; else exit 1; fi`.
        assertTrue(cmd.contains("if cp -a"))
        assertTrue(cmd.contains("else exit 1"))
        // Marker written before the source is removed.
        assertTrue(cmd.indexOf("touch") in 0 until rmIdx)
    }

    @Test
    fun `destination-absent case is an atomic rename guarded by success`() {
        assertTrue(cmd.contains("elif [ ! -e '$new' ]"))
        assertTrue(cmd.contains("mv '$old' '$new' && touch"))
    }

    @Test
    fun `missing source only creates the destination`() {
        assertTrue(cmd.startsWith("if [ ! -e '$old' ]; then mkdir -p '$new' && touch"))
    }

    @Test
    fun `paths with spaces stay quoted everywhere`() {
        val spacedOld = "/storage/1234-5678/My Vault"
        val spacedNew = "/storage/emulated/0/My New Vault"
        val c = SettingsManager.buildMoveCommand(spacedOld, spacedNew)
        assertTrue(c.contains("'$spacedOld'"))
        assertTrue(c.contains("'$spacedNew'"))
        assertTrue(c.contains("'$spacedOld/.'"))
        assertFalse(c.contains("mv $spacedOld"))
    }

    @Test
    fun `marker is written in both success branches`() {
        // Else-branch (source missing): create only.
        assertTrue(cmd.contains("mkdir -p '$new' && touch '$new/.droidvault_dir'"))
        // Rename branch.
        assertTrue(cmd.contains("&& touch '$new/.droidvault_dir'"))
    }

    /**
     * Merging a vault into a subfolder of itself would `cp` the tree inward and
     * then `rm -rf` the source — deleting the copy along with it, i.e. total
     * loss of every app backup plus the automation state. GNU mv/cp refuse
     * self-copy (verified), but Android ships toybox, so the pure command
     * builder must refuse to emit such a command at all.
     */
    @Test
    fun `destination inside the source vault is refused`() {
        listOf(
            "$old/backup",
            "$old/",
            "$old/Apps",
            "$old/a/b/c"
        ).forEach { nested ->
            try {
                SettingsManager.buildMoveCommand(old, nested)
                fail("expected rejection for nested destination $nested")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `destination that is a parent of the source vault is refused`() {
        listOf("/storage/emulated/0", "/storage", "/").forEach { parent ->
            try {
                SettingsManager.buildMoveCommand(old, parent)
                fail("expected rejection for parent destination $parent")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `identical source and destination is refused`() {
        try {
            SettingsManager.buildMoveCommand(old, old)
            fail("expected rejection for identical paths")
        } catch (_: IllegalArgumentException) {
        }
    }

    /** A sibling whose name merely shares a prefix is NOT self-contained. */
    @Test
    fun `sibling directory sharing a name prefix is allowed`() {
        assertFalse(SettingsManager.isSelfContained("/sdcard/DroidVault", "/sdcard/DroidVault2"))
        assertFalse(SettingsManager.isSelfContained("/sdcard/DroidVault", "/sdcard/DroidVaultOld"))
        assertTrue(SettingsManager.isSelfContained("/sdcard/DroidVault", "/sdcard/DroidVault"))
        assertTrue(SettingsManager.isSelfContained("/sdcard/DroidVault", "/sdcard/DroidVault/x"))
        // Trailing slashes are normalised, so they must not defeat the check in
        // either direction: a nested path with a trailing slash is still nested,
        // and a doubled slash on the source is still the source itself.
        assertTrue(SettingsManager.isSelfContained("/sdcard/DroidVault", "/sdcard/DroidVault/sub/"))
        assertTrue(SettingsManager.isSelfContained("/sdcard/DroidVault/", "/sdcard/DroidVault//"))
        // This helper checks destination-inside-source only; the complementary
        // ancestor guard is asserted separately and applied by the move builder.
        assertFalse(SettingsManager.isSelfContained("/sdcard/DroidVault", "/sdcard"))
        assertTrue(SettingsManager.isStrictAncestorPath("/sdcard", "/sdcard/DroidVault"))
    }
}
