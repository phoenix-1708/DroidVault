package com.phoenix.droidvault.utils

import android.content.Context
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ROM-survival snapshots: after runs/imports, mirror the automation state and
 * our own APK into the Droid Vault backup folder (which survives app uninstall and
 * clean flashes). Writes go through root shell — direct java.io writes to
 * shared storage throw EPERM under scoped storage.
 */
object AutomationSnapshot {

    const val STATE_FILE = "automation-state.json"
    const val APK_FILE = "DroidVault.apk"
    const val BACKEND_FILE = "DroidVault.engine"
    const val LAUNCHER_FILE = "DroidVault.sh"

    private const val PREFS_BACKUP = "droidvault_backup_prefs"
    private const val KEY_SELF_HASH = "self_apk_hash"
    private const val KEY_BACKEND_HASH = "backend_script_hash"
    private const val KEY_LAUNCHER_HASH = "backend_launcher_hash"
    private const val BACKEND_SRC = "/data/adb/DroidVault/droidvault-engine"
    private const val LAUNCHER_SRC = "/data/adb/DroidVault/DroidVault.sh"

    data class StateBackupInfo(val path: String, val modifiedAt: Long)

    /** Write current export JSON to `$savedPath/automation-state.json`. Null on failure. */
    suspend fun snapshotState(context: Context, savedPath: String): String? =
        withContext(Dispatchers.IO) {
            val tmp = File(context.cacheDir, STATE_FILE)
            try {
                ShellQuote.requireAbsPath(savedPath)
                val json = AutomationStore.exportJson(context).toString()
                tmp.writeText(json)
                val dest = "$savedPath/$STATE_FILE"
                val res = Shell.cmd(
                    "cp ${ShellQuote.q(tmp.absolutePath)} ${ShellQuote.q(dest)}" +
                        // 600: package lists + labels are fingerprintable; shared
                        // storage is readable by any app with storage permission.
                        " && chmod 600 ${ShellQuote.q(dest)}"
                ).exec()
                if (res.isSuccess) dest else null
            } catch (_: Exception) {
                null
            } finally {
                try {
                    tmp.delete()
                } catch (_: Exception) {
                }
            }
        }

    /**
     * Copy our APK into the backup folder, but only when it changed
     * (SHA-256 vs saved hash). Returns the path when (re)written, null otherwise.
     */
    suspend fun snapshotSelfApk(context: Context, savedPath: String): String? =
        withContext(Dispatchers.IO) {
            try {
                ShellQuote.requireAbsPath(savedPath)
                val source = File(context.applicationInfo.sourceDir)
                if (!source.exists()) return@withContext null
                val hash = sha256(source) ?: return@withContext null
                val prefs = context.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE)
                val dest = "$savedPath/$APK_FILE"
                val hashMatches = hash == prefs.getString(KEY_SELF_HASH, "")
                if (hashMatches) {
                    // Hash matches, but the mirror may still be GONE — the user
                    // moved the Droid Vault folder (Settings), deleted the file to
                    // free space, or the folder was replaced. A missing mirror
                    // must be re-copied, not skipped: probe before trusting
                    // the pref.
                    val present = Shell.cmd("[ -s ${ShellQuote.q(dest)} ] && echo y").exec()
                    if (shouldSkipMirror(hashMatches, present.out.firstOrNull()?.trim() == "y")) {
                        return@withContext null
                    }
                }
                val res = Shell.cmd(
                    "mkdir -p ${ShellQuote.q(savedPath)}" +
                        " && cp ${ShellQuote.q(source.absolutePath)} ${ShellQuote.q(dest)}" +
                        " && chmod 644 ${ShellQuote.q(dest)}"
                ).exec()
                if (res.isSuccess) {
                    prefs.edit().putString(KEY_SELF_HASH, hash).apply()
                    dest
                } else null
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Mirror the Rust engine and its compatibility launcher into the backup
     * folder. The launcher alone is not executable without the native engine;
     * keep both beside the APK for the ROM-survival recovery kit.
     */
    suspend fun snapshotBackend(context: Context, savedPath: String): String? =
        withContext(Dispatchers.IO) {
            try {
                ShellQuote.requireAbsPath(savedPath)
                val prefs = context.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE)
                fun mirror(source: String, fileName: String, key: String): Boolean {
                    val sourceCheck = Shell.cmd("[ -f ${ShellQuote.q(source)} ]").exec()
                    if (!sourceCheck.isSuccess) return false
                    val hash = Shell.cmd("sha256sum ${ShellQuote.q(source)} 2>/dev/null")
                        .exec().out.joinToString("").trim().substringBefore(" ")
                    if (hash.isEmpty()) return false
                    val dest = "$savedPath/$fileName"
                    val hashMatches = hash == prefs.getString(key, "")
                    if (hashMatches) {
                        val present = Shell.cmd("[ -s ${ShellQuote.q(dest)} ] && echo y").exec()
                        if (shouldSkipMirror(hashMatches, present.out.firstOrNull()?.trim() == "y")) return true
                    }
                    val copy = Shell.cmd(
                        "cp ${ShellQuote.q(source)} ${ShellQuote.q(dest)} && chmod 644 ${ShellQuote.q(dest)}"
                    ).exec()
                    if (copy.isSuccess) prefs.edit().putString(key, hash).apply()
                    return copy.isSuccess
                }
                val engineReady = mirror(BACKEND_SRC, BACKEND_FILE, KEY_BACKEND_HASH)
                val launcherReady = mirror(LAUNCHER_SRC, LAUNCHER_FILE, KEY_LAUNCHER_HASH)
                if (engineReady && launcherReady) "$savedPath/$BACKEND_FILE" else null
            } catch (_: Exception) {
                null
            }
        }

    /**
     * Write raw state JSON to `$savedPath/automation-state.json` (PRIMARY store).
     * Blocking; callers wrap in IO dispatchers. Atomic: tmp-file + mv so a
     * crash mid-write never leaves a truncated primary store. Creates the
     * folder if missing. Returns false when the folder path is invalid or
     * the write failed (caller keeps prefs authoritative in that case).
     */
    /**
     * Delete `automation-state.json.tmp-*` files left in the vault root by a
     * run that was killed before its rename.
     *
     * Depth 1 and this exact shape, so the real state file and anything a
     * concurrent writer is staging under its own unique suffix are untouched —
     * the unique suffix is what makes "mine" and "someone else's"
     * distinguishable here at all.
     */
    private fun reapStaleStateTemps(savedPath: String) {
        Shell.cmd(
            "find ${ShellQuote.q(savedPath)} -maxdepth 1 -type f " +
                "-name '${ShellQuote.q("$STATE_FILE.tmp-")}*' -delete 2>/dev/null"
        ).exec()
    }

    fun writeStateFileBlocking(context: Context, savedPath: String, json: String): Boolean {
        try {
            ShellQuote.requireAbsPath(savedPath)
            // Two bugs lived here.
            //
            // 1) The old code wrote the temp into cacheDir (/data) and `mv`d it
            //    to the vault (/sdcard). That is a cross-filesystem move, so it
            //    degrades to copy-then-unlink — NOT atomic, and a kill
            //    mid-copy left a truncated automation-state.json, exactly the
            //    failure the "Atomic" comment claimed to prevent. Fix: stage
            //    through a temp file *inside the destination directory* so the
            //    only step that touches the real name is a same-FS rename.
            // 2) The temp name was a fixed constant, so two concurrent writers
            //    overwrote each other's temp mid-write and one could publish a
            //    partial file (or fail silently). Fix: a unique suffix.
            // Reap temps a KILLED run left in the vault before writing our own.
            //
            // The cleanup below only fires when the command REPORTS failure, so
            // a process killed between the `cat` and the `mv` — which is exactly
            // what a kill does — leaves `automation-state.json.tmp-<nanos>`
            // sitting in the vault root. The vault is never cleared, so it was
            // carried into every subsequent export and removed by nothing.
            reapStaleStateTemps(savedPath)
            val tmpLocal = File(context.cacheDir, "$STATE_FILE.tmp-${System.nanoTime()}")
            val tmpRemote = "$savedPath/$STATE_FILE.tmp-${System.nanoTime()}"
            val dest = "$savedPath/$STATE_FILE"
            try {
                tmpLocal.writeText(json)
                val res = Shell.cmd(
                    "mkdir -p ${ShellQuote.q(savedPath)}" +
                        " && cat ${ShellQuote.q(tmpLocal.absolutePath)} > ${ShellQuote.q(tmpRemote)}" +
                        // 600: package lists + labels are fingerprintable; shared
                        // storage is readable by any app with storage permission.
                        " && chmod 600 ${ShellQuote.q(tmpRemote)}" +
                        " && mv -f ${ShellQuote.q(tmpRemote)} ${ShellQuote.q(dest)}" +
                        " && chmod 600 ${ShellQuote.q(dest)}"
                ).exec()
                if (!res.isSuccess) {
                    Shell.cmd("rm -f ${ShellQuote.q(tmpRemote)}").exec()
                }
                return res.isSuccess
            } finally {
                try {
                    tmpLocal.delete()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Read raw state JSON from `$savedPath/automation-state.json`.
     * Blocking; callers wrap in IO dispatchers. Null when missing/unreadable.
     * 2 MB cap: a hostile oversized file must not OOM the parser.
     */
    fun readStateFileBlocking(savedPath: String): String? {
        try {
            ShellQuote.requireAbsPath(savedPath)
            val dest = "$savedPath/$STATE_FILE"
            val out = Shell.cmd("head -c 2000000 ${ShellQuote.q(dest)} 2>/dev/null").exec()
            if (!out.isSuccess) return null
            val text = out.out.joinToString("\n")
            if (text.isBlank()) return null
            return text
        } catch (_: Exception) {
            return null
        }
    }

    /** Read a state backup from the folder (root shell: scoped storage blocks java.io). */
    suspend fun readStateBackup(context: Context, savedPath: String): StateBackupInfo? =
        withContext(Dispatchers.IO) {
            try {
                ShellQuote.requireAbsPath(savedPath)
                val dest = "$savedPath/$STATE_FILE"
                val check = Shell.cmd("[ -f ${ShellQuote.q(dest)} ]").exec()
                if (!check.isSuccess) return@withContext null
                val stat = Shell.cmd("stat -c '%Y %s' ${ShellQuote.q(dest)} 2>/dev/null").exec()
                val parts = stat.out.joinToString("").trim().split(" ")
                val mtime = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                val size = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                if (size <= 0) return@withContext null
                StateBackupInfo(dest, mtime * 1000)
            } catch (_: Exception) {
                null
            }
        }

    /** Load + parse the state backup content. Null when missing/unreadable/invalid. */
    suspend fun loadStateBackup(context: Context, savedPath: String): org.json.JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                ShellQuote.requireAbsPath(savedPath)
                val dest = "$savedPath/$STATE_FILE"
                // Cap: a hostile oversized file must not OOM the parser.
                val out = Shell.cmd("head -c 2000000 ${ShellQuote.q(dest)} 2>/dev/null").exec()
                if (!out.isSuccess) return@withContext null
                val text = out.out.joinToString("\n")
                if (text.isBlank()) return@withContext null
                org.json.JSONObject(text)
            } catch (_: Exception) {
                null
            }
        }

    // Shared hashing (P6 dedupe).
    private fun sha256(file: File): String? = Hashing.sha256Hex(file)

    /**
     * One self-backup artifact in the vault folder, for the Settings
     * "What's in my vault folder" explainer. [present]/[sizeBytes]/
     * [modifiedAt] come from a root-shell stat — scoped storage hides these
     * files from java.io. Absent files keep present=false (size 0, mtime 0)
     * so the UI can say "not created yet" instead of hiding the row.
     */
    data class VaultFileInfo(
        val name: String,
        val path: String,
        val present: Boolean,
        val sizeBytes: Long,
        val modifiedAt: Long
    )

    /** Self-backup artifacts in explainer order. */
    val vaultFileNames: List<String> = listOf(APK_FILE, BACKEND_FILE, LAUNCHER_FILE, STATE_FILE)

    /** Pure stat parsing: "size mtime" -> (sizeBytes, mtimeSeconds). Null on garbage. */
    fun parseStatLine(line: String): Pair<Long, Long>? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 2) return null
        val size = parts[0].toLongOrNull() ?: return null
        val mtime = parts[1].toLongOrNull() ?: return null
        return size to mtime
    }

    // ---------- Vault-folder health (Settings explainer) ----------

    /** Health of one self-backup artifact in the vault folder. */
    enum class VaultHealth { OK, STALE, MISSING }

    /** Pure: APK mirror is stale when the INSTALLED app is newer than the copy. */
    fun apkMirrorHealth(present: Boolean, mirrorModifiedAt: Long, installedUpdatedAt: Long): VaultHealth =
        when {
            !present -> VaultHealth.MISSING
            installedUpdatedAt > 0 && mirrorModifiedAt > 0 &&
                mirrorModifiedAt < installedUpdatedAt -> VaultHealth.STALE
            else -> VaultHealth.OK
        }

    /**
     * Pure: engine mirror is stale when the deployed source (/data/adb) exists
     * and differs. A missing source (engine not yet deployed — e.g. root
     * grant still pending) leaves the mirror's presence as the only verdict.
     */
    fun engineMirrorHealth(
        present: Boolean,
        sourcePresent: Boolean,
        hashesMatch: Boolean
    ): VaultHealth = when {
        !present -> VaultHealth.MISSING
        !sourcePresent -> VaultHealth.OK
        hashesMatch -> VaultHealth.OK
        else -> VaultHealth.STALE
    }

    /**
     * Pure health map for the explainer card: APK mirror vs installed-app
     * update time, engine and launcher mirrors vs deployed hashes, state by
     * presence. Keyed by file name for direct UI lookup.
     */
    fun computeVaultHealth(
        files: List<VaultFileInfo>,
        apkUpdatedAt: Long,
        engineMirrorHash: String?,
        engineSourceHash: String?,
        launcherMirrorHash: String? = null,
        launcherSourceHash: String? = null
    ): Map<String, VaultHealth> {
        val byName = files.associateBy { it.name }
        val apk = byName[APK_FILE]
        val backend = byName[BACKEND_FILE]
        val launcher = byName[LAUNCHER_FILE]
        val state = byName[STATE_FILE]
        return mapOf(
            APK_FILE to apkMirrorHealth(
                present = apk?.present ?: false,
                mirrorModifiedAt = apk?.modifiedAt ?: 0L,
                installedUpdatedAt = apkUpdatedAt
            ),
            BACKEND_FILE to engineMirrorHealth(
                present = backend?.present ?: false,
                sourcePresent = engineSourceHash != null,
                hashesMatch = engineMirrorHash != null && engineMirrorHash == engineSourceHash
            ),
            LAUNCHER_FILE to engineMirrorHealth(
                present = launcher?.present ?: false,
                sourcePresent = launcherSourceHash != null,
                hashesMatch = launcherMirrorHash != null && launcherMirrorHash == launcherSourceHash
            ),
            STATE_FILE to (if (state?.present == true) VaultHealth.OK else VaultHealth.MISSING)
        )
    }

    /**
     * sha256 of the vault-folder engine mirror and the deployed Rust source
     * (/data/adb/DroidVault/droidvault-engine). Either side null when the file
     * is missing or unreadable. One shell call, matched by filename order.
     */
    fun engineMirrorVsSourceHash(savedPath: String): Pair<String?, String?> {
        return mirrorVsSourceHash(savedPath, BACKEND_FILE, BACKEND_SRC)
    }

    /** Hash the compatibility launcher mirror against the deployed launcher. */
    fun launcherMirrorVsSourceHash(savedPath: String): Pair<String?, String?> =
        mirrorVsSourceHash(savedPath, LAUNCHER_FILE, LAUNCHER_SRC)

    private fun mirrorVsSourceHash(savedPath: String, fileName: String, sourcePath: String): Pair<String?, String?> {
        return try {
            ShellQuote.requireAbsPath(savedPath)
            val mirror = "$savedPath/$fileName"
            val res = Shell.cmd(
                "sha256sum ${ShellQuote.q(mirror)} ${ShellQuote.q(sourcePath)} 2>/dev/null"
            ).exec()
            var mirrorHash: String? = null
            var sourceHash: String? = null
            for (line in res.out) {
                val t = line.trim()
                if (t.length < 64) continue
                val hash = t.substringBefore(" ")
                val path = t.substringAfter(" ", "")
                when (path) {
                    mirror -> mirrorHash = hash
                    sourcePath -> sourceHash = hash
                }
            }
            mirrorHash to sourceHash
        } catch (_: Exception) {
            null to null
        }
    }

    /**
     * One-tap repair (Settings): re-copy the APK and both backend files even when the saved
     * content hashes say "unchanged" — the stale case is exactly a mirror
     * whose content no longer matches its source while the hash prefs were
     * never updated (newer APK installed without a run, drifted file).
     */
    suspend fun forceRefreshMirrors(context: Context, savedPath: String) {
        try {
            ShellQuote.requireAbsPath(savedPath)
            val prefs = context.getSharedPreferences(PREFS_BACKUP, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_SELF_HASH).remove(KEY_BACKEND_HASH).remove(KEY_LAUNCHER_HASH).apply()
            snapshotSelfApk(context, savedPath)
            snapshotBackend(context, savedPath)
        } catch (_: Exception) {
        }
    }

    /**
     * List the self-backup artifacts in the vault folder with existence,
     * size, and mtime (root shell; one stat per file — the proven
     * [readStateBackup] pattern, which avoids parsing a path that may
     * contain spaces). Empty list only on a malformed path.
     */
    suspend fun listVaultContents(savedPath: String): List<VaultFileInfo> =
        withContext(Dispatchers.IO) {
            try {
                ShellQuote.requireAbsPath(savedPath)
                // ONE stat for all four files instead of a `[ -f ]` probe plus a
                // stat per file (5+ forks). stat is silent for a missing file,
                // so its absence from the output IS the absence of the file.
                val byPath = HashMap<String, Pair<Long, Long>>()
                val cmd = buildString {
                    append("stat -c '%n %s %Y'")
                    for (name in vaultFileNames) append(' ').append(ShellQuote.q("$savedPath/$name"))
                    append(" 2>/dev/null")
                }
                for (line in Shell.cmd(cmd).exec().out) {
                    val parts = line.trim().split(' ')
                    if (parts.size < 3) continue
                    val size = parts[parts.size - 2].toLongOrNull() ?: continue
                    val mtime = parts[parts.size - 1].toLongOrNull() ?: continue
                    // Path may contain spaces, so re-join everything but the
                    // last two fields rather than trusting the split.
                    val path = parts.dropLast(2).joinToString(" ")
                    byPath[path] = size to mtime
                }
                vaultFileNames.map { name ->
                    val path = "$savedPath/$name"
                    val stat = byPath[path]
                    if (stat == null || stat.first <= 0L) {
                        VaultFileInfo(name, path, present = false, 0L, 0L)
                    } else {
                        VaultFileInfo(
                            name = name,
                            path = path,
                            present = true,
                            sizeBytes = stat.first,
                            modifiedAt = stat.second * 1000
                        )
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

    /**
     * Pure mirror-skip decision (unit-testable): skip ONLY when the content
     * hash is unchanged AND the mirror file is actually present in THIS
     * folder. A hash match with a missing mirror (folder moved, file deleted,
     * folder replaced) must re-copy, never skip.
     */
    fun shouldSkipMirror(hashMatchesSaved: Boolean, mirrorPresent: Boolean): Boolean =
        hashMatchesSaved && mirrorPresent

    /**
     * Pure restore-offer decision (unit-testable): offer the "Automation
     * backup found" dialog ONLY when the saved document actually holds rows
     * (a fresh-install shell file — every section null/empty — has nothing
     * to restore and must never nag), the local store is empty (the offer
     * exists to recover a wiped install, not to fight live data), and the
     * user has not already skipped THIS backup (path + mtime identity).
     */
    fun shouldOfferRestore(fileRows: Int, localHasData: Boolean, alreadyDeclined: Boolean): Boolean =
        fileRows > 0 && !localHasData && !alreadyDeclined
}
