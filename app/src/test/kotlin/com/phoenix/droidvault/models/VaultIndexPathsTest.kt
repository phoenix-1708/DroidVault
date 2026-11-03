package com.phoenix.droidvault.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The index rewrite that carries a folder rename.
 *
 * The fixtures are the engine's real line shape (`engine/src/manifest.rs`,
 * `to_text`): a header, `GENERATED|`, one `ENTRY|path|size|digest|verified` per
 * file, then `END|count`. A remap that produced a line the engine's parser
 * rejects would discard the whole index — and with it every read-back marker,
 * which is exactly what this exists to preserve.
 */
class VaultIndexPathsTest {

    private fun index(vararg entries: String): String {
        val body = entries.joinToString("") { e -> "ENTRY|$e\n" }
        return "DROIDVAULT-MANIFEST|2\nGENERATED|1793000000\n" +
            body + "END|${entries.size}\n"
    }

    private fun entry(path: String, size: Long = 1234, digest: String = "AB:CD", verified: Long = 99) =
        "$path|$size|$digest|$verified"

    @Test
    fun onlyTheRenamedFoldersPathsChange() {
        val before = index(
            entry("Apps/User/Tachiyomi: Mangakakalot/App.vault"),
            entry("Apps/User/Tachiyomi: Mangakakalot/Meta.txt"),
            entry("Apps/User/Apple Music/App.vault"),
            entry("Device/Messages.vault")
        )
        val after = VaultIndexPaths.remap(
            before,
            mapOf("Apps/User/Tachiyomi: Mangakakalot" to "Apps/User/Tachiyomi - Mangakakalot")
        )
        assertEquals(
            index(
                entry("Apps/User/Tachiyomi - Mangakakalot/App.vault"),
                entry("Apps/User/Tachiyomi - Mangakakalot/Meta.txt"),
                entry("Apps/User/Apple Music/App.vault"),
                entry("Device/Messages.vault")
            ),
            after
        )
    }

    /** Size, digest and the read-back epoch travel with the path. */
    @Test
    fun theMarkerFieldsAreCarried() {
        val after = VaultIndexPaths.remap(
            index(entry("Apps/User/A: B/App.vault", size = 77, digest = "DE:AD", verified = 4242)),
            mapOf("Apps/User/A: B" to "Apps/User/A - B")
        )
        assertTrue(after, after.contains("ENTRY|Apps/User/A - B/App.vault|77|DE:AD|4242\n"))
    }

    /** A folder whose name is a prefix of another's must not be swept along. */
    @Test
    fun aPrefixOfAnotherFolderIsNotRenamed() {
        val after = VaultIndexPaths.remap(
            index(
                entry("Apps/User/AB DM/App.vault"),
                entry("Apps/User/AB DM2/App.vault")
            ),
            mapOf("Apps/User/AB DM" to "Apps/User/AB-DM")
        )
        assertEquals(
            index(
                entry("Apps/User/AB-DM/App.vault"),
                entry("Apps/User/AB DM2/App.vault")
            ),
            after
        )
    }

    /** Several folders move in one pass, and each takes only its own entries. */
    @Test
    fun severalMovesResolveIndependently() {
        val after = VaultIndexPaths.remap(
            index(
                entry("Apps/User/Aniyomi: AnimeGG/App.vault"),
                entry("Apps/User/Aniyomi: AnimeFLV/App.vault"),
                entry("Apps/System/X: Y/App.vault")
            ),
            mapOf(
                "Apps/User/Aniyomi: AnimeGG" to "Apps/User/Aniyomi - AnimeGG",
                "Apps/User/Aniyomi: AnimeFLV" to "Apps/User/Aniyomi - AnimeFLV",
                "Apps/System/X: Y" to "Apps/System/X - Y"
            )
        )
        assertTrue(after, after.contains("Apps/User/Aniyomi - AnimeGG/App.vault"))
        assertTrue(after, after.contains("Apps/User/Aniyomi - AnimeFLV/App.vault"))
        assertTrue(after, after.contains("Apps/System/X - Y/App.vault"))
    }

    /** The header, the generation stamp and the count are not ours to rewrite. */
    @Test
    fun theFramingLinesAreUntouched() {
        val before = index(entry("Apps/User/A: B/App.vault"))
        val after = VaultIndexPaths.remap(before, mapOf("Apps/User/A: B" to "Apps/User/A - B"))
        assertTrue(after, after.startsWith("DROIDVAULT-MANIFEST|2\nGENERATED|1793000000\n"))
        assertTrue(after, after.endsWith("END|1\n"))
        assertEquals(before.count { it == '\n' }, after.count { it == '\n' })
    }

    /** No move, no rewrite: the file must come back byte-identical. */
    @Test
    fun anEmptyMoveListIsTheIdentity() {
        val before = index(entry("Apps/User/A: B/App.vault"))
        assertEquals(before, VaultIndexPaths.remap(before, emptyMap()))
    }

    /** A folder that is in the vault but not in the index changes nothing. */
    @Test
    fun aMoveThatMatchesNoEntryIsTheIdentity() {
        val before = index(entry("Apps/User/Apple Music/App.vault"))
        assertEquals(
            before,
            VaultIndexPaths.remap(before, mapOf("Apps/User/A: B" to "Apps/User/A - B"))
        )
    }

    /** Only `Apps/<User|System>/<folder>` is a backup folder whose name can go stale. */
    @Test
    fun onlyBackupFoldersAreNamed() {
        assertEquals("Apps/User/Tachiyomi - X", VaultIndexPaths.folderOf("Apps/User/Tachiyomi - X/App.vault"))
        assertEquals("Apps/System/WPS Office", VaultIndexPaths.folderOf("Apps/System/WPS Office/Data.vault"))
        // The device trees are one level deep, and the vault root's files are
        // not in a folder at all.
        assertEquals(null, VaultIndexPaths.folderOf("Device/Messages.vault"))
        assertEquals(null, VaultIndexPaths.folderOf("Device/Folders/DCIM.Android.vault"))
        assertEquals(null, VaultIndexPaths.folderOf("vault-manifest.txt"))
        assertEquals(null, VaultIndexPaths.folderOf("Apps/User/Tachiyomi - X"))
        // A folder called "Apps" inside the vault root is not an area.
        assertEquals(null, VaultIndexPaths.folderOf("Meta/Apps/User/X"))
    }

    /** The paths a stale-name search walks, and only the paths. */
    @Test
    fun entryPathsAreJustThePaths() {
        val text = index(
            entry("Apps/User/Tachiyomi: X/App.vault"),
            entry("Device/Messages.vault"),
            entry("Apps/User/Tachiyomi: X/Meta.txt")
        )
        assertEquals(
            listOf(
                "Apps/User/Tachiyomi: X/App.vault",
                "Device/Messages.vault",
                "Apps/User/Tachiyomi: X/Meta.txt"
            ),
            VaultIndexPaths.entryPaths(text)
        )
        assertEquals(emptyList<String>(), VaultIndexPaths.entryPaths(""))
        assertEquals(emptyList<String>(), VaultIndexPaths.entryPaths("DROIDVAULT-MANIFEST|2\nEND|0\n"))
    }

    /** No trailing newline in, no trailing newline out — and one in, one out. */
    @Test
    fun theTrailingNewlineSurvives() {
        val withNewline = index(entry("Apps/User/A: B/App.vault"))
        assertTrue(withNewline.endsWith("\n"))
        assertEquals(
            true,
            VaultIndexPaths.remap(withNewline, mapOf("Apps/User/A: B" to "Apps/User/A - B"))
                .endsWith("\n")
        )
        val without = withNewline.trimEnd('\n')
        assertEquals(
            false,
            VaultIndexPaths.remap(without, mapOf("Apps/User/A: B" to "Apps/User/A - B"))
                .endsWith("\n")
        )
    }
}
