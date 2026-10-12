package com.phoenix.droidvault.utils

import org.json.JSONArray
import org.json.JSONObject

/**
 * Helpers for [com.phoenix.droidvault.models.DeviceDataItem.FOLDERS]:
 * validation of user-picked absolute paths and the manifest that travels
 * inside every Folders.shift archive so a restore can put each folder back
 * to its exact original location.
 *
 * Kept free of Android dependencies so the logic is unit-testable.
 */
object BackupConfigFolders {

    /** Folders.shift archive file name inside `<savedPath>/Device/`. */
    const val ARCHIVE_FILE = "Folders.shift"

    /** Manifest entry name inside the tar. */
    const val MANIFEST_NAME = "folders_manifest.json"

    /** Stable per-config default when no folders were picked yet. */
    val DEFAULT_PATHS: List<String> = emptyList()

    /**
     * A folder is a valid backup source iff:
     *  - it is absolute ("/..." — relative paths would depend on the shell CWD)
     *  - it contains no quotes or shell metacharacters (paths are always
     *    passed through [ShellQuote.q] too; this keeps prefs/JSON clean and
     *    rejects strings that only round-trip by accident)
     *  - it is a real user-visible storage path, not a system dir we must
     *    never recurse into (/data, /system, /proc, /sys, /dev, /vendor ...)
     */
    fun isValidPath(path: String): Boolean {
        if (path.isBlank()) return false
        if (!path.startsWith("/")) return false
        if (path.contains('\'') || path.contains('"') || path.contains('\\')) return false
        if (path.contains('$') || path.contains('`') || path.contains(';') ||
            path.contains('|') || path.contains('&') || path.contains('<') ||
            path.contains('>') || path.contains('\n') || path.contains('\r')
        ) return false
        if (path.contains("//") || path.endsWith("/")) return false
        if (path.any { it.code < 32 }) return false
        val deniedRoots = listOf(
            "/data", "/system", "/proc", "/sys", "/dev", "/vendor", "/odm",
            "/product", "/apex", "/sbin", "/bin", "/etc", "/cache"
        )
        if (deniedRoots.any { path == it || path.startsWith("$it/") }) return false
        return true
    }

    /** Normalize + de-duplicate a user-provided list (order preserved). */
    fun sanitize(paths: List<String>): List<String> =
        paths.map { it.trim().trimEnd('/') }.filter { isValidPath(it) }.distinct()

    /**
     * Manifest JSON written into the archive: source folder per archive
     * prefix, so a restore can relocate each folder even if the same archive
     * is moved between devices or the config is edited in between.
     */
    fun manifestJson(folders: List<String>): String {
        val arr = JSONArray()
        folders.forEach { arr.put(it) }
        return JSONObject().put("version", 1).put("folders", arr).toString()
    }

    /** Parse the manifest from a restore; returns empty list on any damage. */
    fun manifestFolders(json: String): List<String> = try {
        val o = JSONObject(json)
        val arr = o.optJSONArray("folders") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val p = arr.optString(i)
            if (isValidPath(p)) p else null
        }
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * User-friendly name of a folder for progress labels: enough of the tail
     * to be unambiguous ("Android/media/com.whatsapp"), the full path only
     * when it is already short. Never a generic "Folder".
     */
    /**
     * Compact display name for a folder path: the FINAL segment only
     * ("/storage/emulated/0/IntegrityFIX" → "IntegrityFIX"), with an
     * ambiguous-prefix ellipsis when the parent differs across the list, so
     * same-named folders in different parents stay tellable apart.
     */
    fun shortDisplayName(path: String): String {
        val clean = path.trim().trimEnd('/')
        val last = clean.substringAfterLast('/')
        val siblings = _displaySiblings
        // Disambiguate only when a same-named folder exists with a different
        // parent — then keep the distinguishing parent segment.
        if (siblings != null) {
            val clash = siblings.any {
                it.trim().trimEnd('/').substringAfterLast('/') == last &&
                    it.trim().trimEnd('/') != clean
            }
            if (clash) {
                val parts = clean.split('/')
                val parent = parts.getOrNull(parts.size - 2) ?: ""
                return "$parent/$last"
            }
        }
        return last
    }

    /** Sibling paths for [shortDisplayName] disambiguation; set by list renderers. */
    @Volatile
    private var _displaySiblings: List<String>? = null

    /** Provide the full list being rendered so same-named folders stay distinct. */
    fun setDisplayContext(paths: List<String>) {
        _displaySiblings = paths
    }

    /**
     * Universal folder source of truth (pure, unit-tested):
     *
     * - Empty selection → the whole shared store list (a folder added in
     *   Settings is picked up by every FOLDERS-enabled config immediately).
     * - Non-empty selection → the selection pruned to what still exists in
     *   the store (a folder removed in Settings stops running everywhere —
     *   no stale/cached selections survive a removal).
     */
    fun resolveAgainstStore(selection: List<String>, store: List<String>): List<String> {
        val storeSet = store.toSet()
        val explicit = sanitize(selection)
        return if (explicit.isEmpty()) sanitize(store) else explicit.filter { it in storeSet }
    }
}
