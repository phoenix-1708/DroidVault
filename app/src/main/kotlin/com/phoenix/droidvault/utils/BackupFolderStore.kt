package com.phoenix.droidvault.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide store of the user's registered folder backup sources
 * ([com.phoenix.droidvault.models.DeviceDataItem.FOLDERS]). Backed by the
 * `backup_folders` key in droidvault_prefs.
 *
 * A singleton (not per-ViewModel state) because three surfaces edit the same
 * list — Settings manages it, the Migrate tab backs it up, and Automation
 * config editors pick from it. A shared store means an add in one tab is
 * visible everywhere immediately, and every FOLDERS-enabled config run reads
 * one source of truth.
 */
object BackupFolderStore {

    private const val PREFS_NAME = "droidvault_prefs"
    private const val KEY = "backup_folders"

    private val _folders = MutableStateFlow<List<String>>(emptyList())

    /** Registered absolute folder paths, deduplicated, order preserved. */
    val folders: StateFlow<List<String>> = _folders.asStateFlow()

    /** Idempotent; call from any process entry point. */
    fun init(context: Context) {
        val raw = try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY, null)
        } catch (_: Exception) {
            null
        } ?: return
        _folders.value = parse(raw)
    }

    private fun parse(raw: String): List<String> = try {
        val arr = org.json.JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val p = arr.optString(i)
            if (BackupConfigFolders.isValidPath(p)) p else null
        }.distinct()
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Add a picked path. Returns false when the path is not a valid backup
     * source (relative, shell-unsafe, or a protected system dir) — the caller
     * surfaces that to the user; nothing is persisted.
     */
    fun add(context: Context, path: String): Boolean {
        val clean = BackupConfigFolders.sanitize(listOf(path))
        if (clean.isEmpty()) return false
        return replace(context, _folders.value + clean)
    }

    fun remove(context: Context, path: String): Boolean =
        replace(context, _folders.value - path)

    /**
     * Register [paths] that are not present yet (ROM-survival restore path:
     * a reinstalled app rebuilds the registry from the automation state
     * file's configured folder paths, because resolveAgainstStore() prunes
     * every FOLDERS-enabled config's selection against this registry).
     * Invalid paths are dropped; already-registered paths are untouched, so
     * deliberate removals survive. Returns false only when a write failed.
     */
    fun ensureRegistered(context: Context, paths: List<String>): Boolean {
        val missing = BackupConfigFolders.sanitize(paths).filter { it !in _folders.value }
        if (missing.isEmpty()) return true
        return replace(context, _folders.value + missing)
    }

    private fun replace(context: Context, next: List<String>): Boolean {
        val distinct = next.distinct()
        val arr = org.json.JSONArray()
        distinct.forEach { arr.put(it) }
        return try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, arr.toString())
                .commit() // durable: backup runs may read it from another process entry
            _folders.value = distinct
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Serialise the registry for a transfer file.
     *
     * The registry is prefs-only by design, which is exactly why it did not
     * survive a cross-device import: it can only heal from the automation state
     * file, so a transfer that carried no state file left the folders behind
     * too. Pure — no prefs, no I/O — so it is the same value the app would have
     * written anyway.
     */
    fun snapshotJson(): String {
        val arr = org.json.JSONArray()
        _folders.value.forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * Adopt a registry carried by a transfer file.
     *
     * UNION, not replace: the folders on this phone are the ones this phone's
     * users chose, and a transfer from another device may name paths that do not
     * exist here. Merging can only ever add; a restore never silently drops a
     * folder the user is relying on. Returns the paths actually added.
     */
    fun restoreFromJson(context: Context, json: String): List<String> {
        val incoming = parse(json)
        if (incoming.isEmpty()) return emptyList()
        val merged = (_folders.value + incoming).distinct()
        val added = incoming.filter { it !in _folders.value }
        return if (replace(context, merged)) added else emptyList()
    }

    /**
     * Convert a SAF tree URI from [androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree]
     * into a real filesystem path. Returns null when the tree cannot be
     * resolved to primary/secondary storage.
     */
    fun pathFromTreeUri(uri: Uri): String? = try {
        val docId = DocumentsContract.getTreeDocumentId(uri) ?: return null
        val split = docId.split(":")
        if (split.size < 2) return null
        val base = android.os.Environment.getExternalStorageDirectory().absolutePath
        val path = when {
            "primary".equals(split[0], true) -> "$base/${split[1]}"
            else -> "/storage/${split[0]}/${split[1]}"
        }.trimEnd('/')
        path.ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}
