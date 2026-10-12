package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.DeviceDataItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings' shared BackupFolderStore registry is the SINGLE source of truth;
 * every config's folderPaths is a live subset of it. These tests pin the
 * contract behind the field-reported resurrection bug: a folder removed in
 * Settings must disappear from every configuration and can never be re-added
 * by any heal/save path.
 */
class BackupFolderSyncTest {

    private val whatsapp = "/storage/emulated/0/Android/media/com.whatsapp"
    private val integrityFix = "/storage/emulated/0/IntegrityFix"
    private val magisk = "/storage/emulated/0/Magisk"

    private val registry = listOf(whatsapp, integrityFix, magisk)

    private fun config(
        name: String,
        selection: List<String>,
        emptySelection: Boolean = false
    ) = BackupConfig(
        id = "cfg-$name",
        name = name,
        deviceData = setOf(DeviceDataItem.FOLDERS),
        // emptySelection=true models the "no explicit folders picked" config
        // that follows the whole registry at run time.
        folderPaths = if (emptySelection) emptyList() else selection
    )

    @Test
    fun pruneDropsRemovedFolderFromReferencingConfigsOnly() {
        val configs = listOf(
            config("Full", listOf(whatsapp, integrityFix)),
            config("WhatsAppOnly", listOf(whatsapp)),
            config("Unrelated", listOf(magisk)),
            config("FollowsStore", emptyList(), emptySelection = true)
        )

        val pruned = pruneConfigsFolder(configs, integrityFix)!!

        // Referencing config loses exactly the removed path.
        assertEquals(listOf(whatsapp), pruned[0].folderPaths)
        assertEquals("Full", pruned[0].name)
        // Non-referencing configs pass through untouched.
        assertEquals(configs[1], pruned[1])
        assertEquals(configs[2], pruned[2])
        assertEquals(configs[3], pruned[3])
        // Every other field survives the copy.
        assertEquals(configs[0].copy(folderPaths = listOf(whatsapp)), pruned[0])
    }

    @Test
    fun pruneReturnsNullWhenNoConfigReferencesThePath() {
        val configs = listOf(
            config("A", listOf(whatsapp)),
            config("B", listOf(magisk))
        )
        assertNull(pruneConfigsFolder(configs, integrityFix))
        // A config with an empty (follow-the-store) selection never blocks the
        // no-op: it holds no explicit reference to prune.
        assertNull(pruneConfigsFolder(emptyList(), integrityFix))
    }

    @Test
    fun removedFolderNeverRunsAgainViaAnyConfigShape() {
        // The end-to-end invariant, expressed purely:
        // 1. Config with explicit selection containing the removed path.
        // 2. Config with empty selection (follows the whole store).
        val configs = listOf(
            config("Explicit", listOf(whatsapp, integrityFix)),
            config("Implicit", emptyList(), emptySelection = true)
        )
        val registryAfterRemoval = registry - integrityFix

        val pruned = pruneConfigsFolder(configs, integrityFix)!!

        // Explicit: pruned at rest (editors can no longer re-offer the path —
        // the resurrection loop is cut at the source).
        assertEquals(listOf(whatsapp), pruned[0].folderPaths)
        // Run-time resolution against the post-removal registry: nothing
        // references the dead path anymore.
        pruned.forEach { cfg ->
            val resolved = BackupConfigFolders.resolveAgainstStore(
                cfg.folderPaths, registryAfterRemoval
            )
            assertFalse(resolved.contains(integrityFix))
            assertTrue(resolved.all { it in registryAfterRemoval })
        }
        // Implicit config still follows the whole (now smaller) registry.
        assertEquals(registryAfterRemoval, BackupConfigFolders.resolveAgainstStore(pruned[1].folderPaths, registryAfterRemoval))
    }

    @Test
    fun reAddingAfterRemovalIsAnExplicitUserAction() {
        // The only sanctioned way a removed folder comes back: the user adds
        // it again (Settings "Add folder" or an editor's "Add folder", both of
        // which register into the shared store first). Removing again prunes
        // symmetrically.
        val configs = listOf(config("C", listOf(whatsapp)))
        val pruned = pruneConfigsFolder(configs, whatsapp)!!
        assertEquals(emptyList<String>(), pruned[0].folderPaths)

        // User re-adds via an editor → registered, then selected.
        val reRegistered = registry // simulate ensureRegistered(whatsapp)
        val reSelected = pruned[0].copy(folderPaths = pruned[0].folderPaths + whatsapp)
        val resolved = BackupConfigFolders.resolveAgainstStore(reSelected.folderPaths, reRegistered)
        assertEquals(listOf(whatsapp), resolved)
    }

    @Test
    fun emptyRegistryMeansWipeRecoveryNotDeliberateRemoval() {
        // resolveAgainstStore with an EMPTY store models the pre-heal state:
        // explicit selections collapse to nothing. The bind-time heal only
        // re-seeds the registry when it is empty (uninstall signature), so a
        // live non-empty registry never resurrects removed paths.
        assertEquals(
            emptyList<String>(),
            BackupConfigFolders.resolveAgainstStore(listOf(whatsapp), emptyList())
        )
    }
}
