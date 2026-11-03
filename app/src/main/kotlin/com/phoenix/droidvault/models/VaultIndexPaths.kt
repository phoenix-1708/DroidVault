package com.phoenix.droidvault.models

/**
 * Rewrite the vault index's entry paths after a backup folder is renamed.
 *
 * ## Why the index has to follow a rename
 *
 * The index names every file in the vault, and each entry carries the
 * read-back marker — the record that those exact bytes were read back
 * successfully. Renaming a folder without rewriting the index leaves every
 * marker pointing at a name that is no longer there, and the next verification
 * re-reads those archives to earn them again. On the reference vault that is
 * the difference between a check that reports the truth in a second and one
 * that decompresses tens of gigabytes for the same answer (invariant 40).
 *
 * The engine writes one line per file:
 *
 * ```
 * ENTRY|<vault-relative path>|<size>|<digest>|<verified epoch>
 * ```
 *
 * and every other line (`DROIDVAULT-MANIFEST|2`, `GENERATED|…`, `END|…`) is
 * left exactly as it was.
 *
 * ## Why this is a prefix match on a whole path component
 *
 * `Apps/User/AB DM` must not touch the entry for `Apps/User/AB DM2`. The match
 * is therefore `old + "/"`, so only a path that continues with the folder it
 * names is rewritten.
 *
 * Pure, and tested against the engine's real line shape, because the two sides
 * have to agree: a remap that produced a line the engine's parser rejects
 * would discard the whole index — the marker loss this exists to prevent,
 * made total.
 */
object VaultIndexPaths {

    private const val ENTRY = "ENTRY|"

    /**
     * Rewrite every entry whose path sits inside a renamed folder.
     *
     * @param moves old vault-relative folder → new vault-relative folder, e.g.
     *   `Apps/User/Tachiyomi: X` → `Apps/User/Tachiyomi - X`.
     */
    fun remap(text: String, moves: Map<String, String>): String {
        if (moves.isEmpty() || text.isEmpty()) return text
        val lines = text.split("\n")
        val out = StringBuilder(text.length + 64)
        lines.forEachIndexed { index, line ->
            if (index > 0) out.append('\n')
            // Split on "\n" rather than `lineSequence()` so a trailing newline
            // survives the round trip byte for byte.
            out.append(if (line.startsWith(ENTRY)) remapLine(line, moves) else line)
        }
        return out.toString()
    }

    /**
     * The backup folder an entry path sits in, or null for anything else.
     *
     * Only `Apps/<User|System>/<folder>` is a backup folder. `Device/Messages.vault`
     * is one level deep and the vault root's own files are none at all, so a
     * caller looking for stale folder names is not handed a path to rewrite.
     */
    fun folderOf(path: String): String? {
        val parts = path.split('/')
        if (parts.size < 4) return null
        if (parts[0] != "Apps") return null
        if (parts[1] != "User" && parts[1] != "System") return null
        return "${parts[0]}/${parts[1]}/${parts[2]}"
    }

    /** Every entry path in the index, in file order. Used to look for stale names. */
    fun entryPaths(text: String): List<String> =
        text.lineSequence()
            .filter { it.startsWith(ENTRY) }
            .mapNotNull { line ->
                val end = line.indexOf('|', ENTRY.length)
                if (end < 0) null else line.substring(ENTRY.length, end)
            }
            .toList()

    private fun remapLine(line: String, moves: Map<String, String>): String {
        val start = ENTRY.length
        val end = line.indexOf('|', start)
        if (end < 0) return line
        val path = line.substring(start, end)
        for ((from, to) in moves) {
            if (!path.startsWith("$from/")) continue
            return buildString(line.length + 32) {
                append(ENTRY)
                append(to)
                append(path, from.length, path.length)
                append(line, end, line.length)
            }
        }
        return line
    }
}
