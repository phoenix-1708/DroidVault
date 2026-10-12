package com.phoenix.droidvault.utils

import android.content.SharedPreferences
import android.os.Environment
import androidx.core.content.edit
import com.topjohnwu.superuser.Shell

object SettingsManager {

    fun getDefaultPath(): String {
        // Platform type, and AOSP returns null when StorageManager.getVolumeList() is
    // empty. This is the initialiser for MainViewModel's _savedPath AND a
    // companion-object value evaluated at class-load, so a null here throws inside
    // the AndroidViewModel constructor and EVERY launch fails — not just this one.
    return "${Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"}/DroidVault"
    }

    /**
     * Build the vault-move shell command (pure — unit-testable).
     *
     * Safety contract — replaces the data-loss bug this app shipped with:
     *  - NO glob anywhere: the old code single-quoted a `*` glob in `mv`, so
     *    the shell never expanded it, `mv` always failed, and the next
     *    statement still deleted the vault.
     *  - the source tree is removed ONLY after an `&&`-chained copy whose
     *    exit status must be success; a failed copy leaves the old vault
     *    completely untouched.
     *  - the destination marker is written only after the copy/rename
     *    succeeded; a failure here is a failed move, reported to the caller.
     */
    fun buildMoveCommand(oldPath: String, newPath: String): String {
        // Refuse self-containment in the PURE core so no caller can build a
        // self-destructive command. Merging a vault into a subfolder of itself
        // would `cp` the tree inward and then `rm -rf` the source — deleting
        // the copy along with it. GNU mv/cp refuse self-copy (verified), but
        // Android ships toybox, so this must not depend on that.
        require(newPath != oldPath) { "Destination equals source" }
        require(!isSelfContained(oldPath, newPath)) { "Destination is inside the source vault" }
        require(!isStrictAncestorPath(newPath, oldPath)) {
            "Destination is a parent of the source vault"
        }
        val old = ShellQuote.q(oldPath)
        val new = ShellQuote.q(newPath)
        val newSlash = ShellQuote.q("$newPath/")
        val oldDot = ShellQuote.q("$oldPath/.")
        val marker = ShellQuote.q("$newPath/.droidvault_dir")
        val newParent = ShellQuote.q(newPath.substringBeforeLast('/', "/"))
        return "if [ ! -e $old ]; then " +
            // Nothing to move (fresh install, or already moved): just create.
            "mkdir -p $new && touch $marker; " +
            "elif [ ! -e $new ]; then " +
            // Destination absent: atomic rename — instant even for a big vault.
            "mkdir -p $newParent && mv $old $new && touch $marker; " +
            "else " +
            // Destination exists (merge): copy contents — the trailing `/.`
            // includes dotfiles without needing a glob — then verify and only
            // then delete the source. `cp -a` keeps timestamps/ownership; the
            // `-r` fallback covers mounts that reject preserve flags (some
            // SD-card filesystems).
            "if cp -a $oldDot $newSlash 2>/dev/null || cp -r $oldDot $newSlash; then " +
            "touch $marker && rm -rf $old; else exit 1; fi; " +
            "fi"
    }

    /**
     * True when [child] is [parent] itself or lives underneath it.
     * Trailing slashes are normalised so `/a/b` and `/a/b/` compare equal.
     */
    fun isSelfContained(parent: String, child: String): Boolean {
        val p = parent.trimEnd('/')
        val c = child.trimEnd('/')
        if (p.isEmpty()) return true
        return c == p || c.startsWith("$p/")
    }

    /** True when [ancestor] strictly contains [descendant] in the path tree. */
    fun isStrictAncestorPath(ancestor: String, descendant: String): Boolean {
        val a = ancestor.trimEnd('/')
        val d = descendant.trimEnd('/')
        if (a == d) return false
        if (a.isEmpty()) return d.startsWith('/')
        return d.startsWith("$a/")
    }

    /**
     * Move or merge the vault folder to [newPath].
     *
     * Returns true only when the new folder verifiably holds a marker and the
     * old tree has been removed. On failure the old vault is left untouched
     * and `base_path` is NOT re-pointed — callers must surface the failure
     * instead of pretending the move succeeded.
     */
    fun migrateFolder(oldPath: String, newPath: String, prefs: SharedPreferences): Boolean {
        if (oldPath == newPath || oldPath.isBlank() || newPath.isBlank()) return false
        ShellQuote.requireAbsPath(newPath)
        ShellQuote.requireAbsPath(oldPath)
        // Never merge a vault into itself — that would delete the copy.
        if (isSelfContained(oldPath, newPath) || isStrictAncestorPath(newPath, oldPath)) return false
        val res = Shell.cmd(buildMoveCommand(oldPath, newPath)).exec()
        if (!res.isSuccess) return false
        prefs.edit { putString("base_path", newPath) }
        return true
    }

    fun autoDetectFolder(prefs: SharedPreferences): String? {
        val out = Shell.cmd("find /storage /data/media/0 /mnt/media_rw -maxdepth 5 -type f -name '.droidvault_dir' 2>/dev/null | head -n 1").exec().out.joinToString("").trim()
        if (out.isNotEmpty()) {
            val detectedPath = out.substringBeforeLast("/")
            prefs.edit { putString("base_path", detectedPath) }
            return detectedPath
        }
        return null
    }
}
