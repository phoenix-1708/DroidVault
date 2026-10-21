package com.phoenix.droidvault

import com.phoenix.droidvault.utils.BackupFolderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The folder picker opens at the vault's own folder, and the hint it carries is
 * the ExternalStorageProvider's own document id for that path.
 *
 * The mapping is the EXACT inverse of `BackupFolderStore.pathFromTreeUri`, and
 * it has to be: the two directions are a name shared between a writer and a
 * reader, and two copies of a rule drift. A hint that names a different folder
 * than the app would read back would send the user to the wrong place — which
 * is worse than not hinting at all.
 */
class VaultFolderHintTest {

    /** The internal storage root, which is what the platform reports. */
    private val primary = "/storage/emulated/0"

    @Test
    fun `a folder on internal storage is the primary volume`() {
        assertEquals(
            "primary:DroidVault",
            BackupFolderStore.documentIdForPath("$primary/DroidVault", primary)
        )
    }

    @Test
    fun `a nested folder keeps its whole relative path`() {
        assertEquals(
            "primary:Download/DroidVault",
            BackupFolderStore.documentIdForPath("$primary/Download/DroidVault", primary)
        )
    }

    @Test
    fun `a folder on a card is that card's volume id`() {
        assertEquals(
            "1AEF-2E00:DroidVault",
            BackupFolderStore.documentIdForPath("/storage/1AEF-2E00/DroidVault", primary)
        )
    }

    @Test
    fun `a path that is not on external storage has no document id`() {
        // The picker can only be told about a folder the provider knows.
        assertNull(BackupFolderStore.documentIdForPath("/data/local/tmp/whatever", primary))
        assertNull(BackupFolderStore.documentIdForPath("", primary))
        assertNull(BackupFolderStore.documentIdForPath("   ", primary))
        // A volume root is not a folder to pick, and the provider has no
        // document for it.
        assertNull(BackupFolderStore.documentIdForPath("/storage/1AEF-2E00", primary))
    }

    @Test
    fun `the storage root itself is the primary volume with nothing under it`() {
        // It round-trips, so it is a real id — but it is never the hint: the
        // picker already opens there.
        assertEquals("primary:", BackupFolderStore.documentIdForPath(primary, primary))
    }

    @Test
    fun `a trailing slash does not change the answer`() {
        assertEquals(
            BackupFolderStore.documentIdForPath("$primary/DroidVault", primary),
            BackupFolderStore.documentIdForPath("$primary/DroidVault/", primary)
        )
    }

    /**
     * The round trip that matters: the id the picker is given must read back as
     * the path it came from.
     */
    @Test
    fun `the id and the path read back as each other`() {
        val path = "$primary/DroidVault"
        val docId = BackupFolderStore.documentIdForPath(path, primary)
        assertEquals("primary:DroidVault", docId)
        // pathFromTreeUri reads a TREE document id; the tree id of a folder is
        // the same id, so the mapping is the same either way.
        val back = when {
            "primary".equals(docId!!.substringBefore(':'), true) ->
                "$primary/${docId.substringAfter(':')}"
            else -> "/storage/${docId.substringBefore(':')}/${docId.substringAfter(':')}"
        }
        assertEquals(path, back)
    }
}
