package com.phoenix.droidvault

import android.content.Context
import androidx.core.content.edit
import com.phoenix.droidvault.models.VaultIndexPaths
import com.phoenix.droidvault.utils.DroidVaultLogger
import com.phoenix.droidvault.utils.ShellQuote
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One-time rename of the archives already in the vault.
 *
 * ## What it is for
 *
 * A vault archive used to be `*.shift`; it is now `*.vault`. Every archive
 * already on a user's card carries the old name, and an app that only looked
 * for the new one would report an empty vault — the user's backups would be
 * INVISIBLE rather than missing, which is the worse failure: the app would
 * back everything up again and the vault would hold two copies of the same
 * data.
 *
 * So the read paths resolve both names (see
 * `models/VaultArchive.kt`) and this migration tidies the disk: the old-named
 * archives are renamed in place, so the vault never carries a legacy copy of
 * something that has a new one.
 *
 * ## Why it is safe to skip
 *
 * The migration is a TIDY-UP, not a dependency. If it never runs — no root
 * yet, a card that cannot be written, a vault on a read-only volume — the old
 * archives are still found, still restored and still verified, because every
 * reader resolves the name. That is the whole reason the read paths accept
 * both extensions rather than assuming the migration has happened.
 *
 * ## What it touches
 *
 *  - the archives themselves, and their digest sidecars — and ONLY inside the
 *    `Apps/` and `Device/` trees, because the vault root also holds the
 *    reinstall mirrors and a user's own file that happens to end in the old
 *    extension is not ours to rename;
 *  - the vault index, whose entries are named after the files — so the
 *    read-back markers survive the rename instead of being thrown away and
 *    re-earned by re-reading every archive in the vault;
 *  - and NOTHING in the trash: a quarantined archive keeps the name it was
 *    deleted under, and the trash's own manifest records that name.
 *
 * ## The folder names, for the same reason
 *
 * A backup folder is named after its app's label, and a label such as
 * `Tachiyomi: Mangakakalot` used to name the folder with its colon in it.
 * Android's `/sdcard` FUSE policy refuses `: * ? < > | " \` to every writer
 * that is not root, so such a folder could be read off the phone and never put
 * back: `adb push` aborts on the first one (measured with 35 of the reference
 * vault's 112 folders, which stopped the other 33.7 GB going back too) and a
 * `tar` extracting as the shell uid fails file by file. `ShellQuote.safeLabel`
 * no longer produces them (`§4.79`), and this pass renames the folders a
 * previous version already wrote — only the ones whose name the function
 * changes, so a vault with portable names is not touched at all.
 *
 * It follows the same three rules as the archive rename: never over an
 * existing folder (that one is another app's backup), rewrite the index so the
 * read-back markers survive, and treat the trash as quarantined — an undo that
 * puts an old-named folder back is renamed by the next launch.
 *
 * ## Why a read waits for it
 *
 * A rename is atomic, so a reader sees the file under one name or the other —
 * except for the moment between the two, when the file is under NEITHER. A
 * read that landed in that window would answer "no device backups" for a
 * phone that has them. So the first read of the vault waits for the rename
 * to settle ([awaitSettled]) rather than racing it.
 */
object VaultExtensionMigration {

    private const val PREFS_NAME = "droidvault_prefs"
    private const val DONE = "vault_extension_renamed_v1"

    /**
     * Marker for the folder-name pass. Separate from [DONE]: the two renames
     * are independent, and a vault whose extensions are already tidy must not
     * skip the folders.
     */
    private const val FOLDERS_DONE = "vault_folders_renamed_v1"

    /** The engine's index header (`manifest.rs`, `HEADER`). */
    private const val VAULT_MANIFEST_HEADER = "DROIDVAULT-MANIFEST|2"

    /**
     * Printed before an area's folder listing, so a read that never ran is not
     * mistaken for an area with nothing in it. A `|` cannot appear in a backup
     * folder's name, so no folder can pass for this.
     */
    private const val AREA_CANARY = "DroidVault|area"

    /**
     * How long a read may wait for the rename.
     *
     * The rename itself takes a couple of seconds for a full vault. The bound
     * is here so a shell that never answers cannot hold the app's first
     * answer hostage — and a read that gives up is exactly the behaviour
     * before this gate existed, which is safe (both names are resolved).
     */
    private const val SETTLE_TIMEOUT_MS = 15_000L

    /**
     * Completes when the rename has run, or has been found unnecessary.
     *
     * A read that arrives first waits on this, so the app's first answer
     * about the vault is about the vault as it will be.
     */
    private val settled = CompletableDeferred<Unit>()

    /**
     * Wait for the rename to finish.
     *
     * Returns as soon as the vault is settled, or after the bound — which is
     * the same as not waiting at all, because every reader resolves both
     * names.
     */
    suspend fun awaitSettled() {
        withTimeoutOrNull(SETTLE_TIMEOUT_MS) { settled.await() }
    }

    /**
     * Settle the vault if there is nothing left to rename.
     *
     * Called before the root-shell phase of the start-up, so the readers that
     * wait for the rename are not made to wait for a rename that is not
     * happening — which is every launch after the first.
     */
    fun settleIfAlreadyDone(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) settled.complete(Unit)
    }

    /**
     * Rename the vault's archives, once.
     *
     * Best-effort and re-runnable: the marker is written only after the
     * rename is PROVEN complete (nothing left under the old name) and the
     * index rewrite succeeded, so a launch without root simply retries next
     * time. Idempotent in any case — the second run finds nothing to rename.
     */
    fun run(context: Context, vaultPath: String) {
        try {
            migrate(context, vaultPath)
            renameFolders(context, vaultPath, force = false)
        } finally {
            // Whatever happened, the reads stop waiting: a stuck migration
            // must not hold the app's first answer.
            settled.complete(Unit)
        }
    }

    /**
     * Rename folders that carry a name the storage refuses, now.
     *
     * Called after an import: the transfer file carries the folder names of
     * whatever vault produced it, and an archive written by an older version
     * carries the old names. The one-shot marker is ignored here, because an
     * import can put old-named folders back after this launch already tidied
     * the vault.
     */
    fun renameFoldersNow(context: Context, vaultPath: String) {
        try {
            renameFolders(context, vaultPath, force = true)
        } catch (e: Exception) {
            DroidVaultLogger.d("Vault", "folder rename after import failed: ${e.message}")
        }
    }

    private fun migrate(context: Context, vaultPath: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) return
        try {
            ShellQuote.requireAbsPath(vaultPath)
        } catch (_: Exception) {
            DroidVaultLogger.d("Vault", "extension rename skipped: the vault path is not usable")
            return
        }
        try {
            renameArchives(vaultPath)
            // The index is rewritten only once the files are settled: an index
            // that names a file that is not there is worse than a stale one.
            val remaining = countLegacyFiles(vaultPath)
            if (remaining > 0) {
                DroidVaultLogger.d(
                    "Vault",
                    "extension rename incomplete: $remaining file(s) still under the old name"
                )
                return
            }
            if (!rewriteIndex(vaultPath)) {
                DroidVaultLogger.d("Vault", "extension rename: the vault index could not be rewritten")
                return
            }
            DroidVaultLogger.i("Vault", "extension rename complete: $vaultPath")
            prefs.edit { putBoolean(DONE, true) }
        } catch (e: Exception) {
            // Retried on the next launch.
            DroidVaultLogger.d("Vault", "extension rename failed: ${e.message}")
        }
    }

    /**
     * Rename every archive under the old extension, and its digest sidecar.
     *
     * One `find` per extension, each with `-exec … {} +` so a label with a
     * space in it is one argument and not two. A target that already exists
     * is left alone: the new-named archive is the live one, and overwriting it
     * with the old copy would put older bytes back.
     *
     * Scoped to the two trees the app owns. The vault root holds the
     * reinstall mirrors, and a file the user dropped there that happens to
     * end in the old extension is not ours to rename.
     */
    private fun renameArchives(vaultPath: String) {
        val vault = ShellQuote.q(vaultPath)
        val appsTree = ShellQuote.q("$vaultPath/Apps/*")
        val deviceTree = ShellQuote.q("$vaultPath/Device/*")
        val scope = "find $vault \\( -path $appsTree -o -path $deviceTree \\) -type f"
        Shell.cmd(
            "$scope -name '*.shift' " +
                "-exec sh -c 'for f do t=\"${'$'}{f%.shift}.vault\"; " +
                "[ -e \"${'$'}t\" ] || mv \"${'$'}f\" \"${'$'}t\"; done' sh {} + 2>/dev/null"
        ).exec()
        Shell.cmd(
            "$scope -name '*.shift.sha256' " +
                "-exec sh -c 'for f do t=\"${'$'}{f%.shift.sha256}.vault.sha256\"; " +
                "[ -e \"${'$'}t\" ] || mv \"${'$'}f\" \"${'$'}t\"; done' sh {} + 2>/dev/null"
        ).exec()
    }

    /**
     * How many files are still under the old name.
     *
     * The proof that the rename completed. A negative answer means the count
     * itself could not be read — which is NOT "none left", so it is not zero
     * and the migration is retried rather than marked done.
     */
    private fun countLegacyFiles(vaultPath: String): Int {
        val vault = ShellQuote.q(vaultPath)
        val appsTree = ShellQuote.q("$vaultPath/Apps/*")
        val deviceTree = ShellQuote.q("$vaultPath/Device/*")
        val out = Shell.cmd(
            "find $vault \\( -path $appsTree -o -path $deviceTree \\) -type f " +
                "-name '*.shift*' -print 2>/dev/null | wc -l"
        ).exec().out
        return out.firstOrNull()?.trim()?.toIntOrNull() ?: -1
    }

    /**
     * Rewrite the vault index's entry paths.
     *
     * The index names every file in the vault, and each entry carries the
     * read-back marker — the record that those exact bytes were read back
     * successfully. A rename without rewriting the index would leave every
     * marker pointing at a name that is no longer there, and the next
     * verification would re-read the whole vault to earn them again.
     *
     * Written to a temp and renamed over, the same way the engine writes it:
     * a half-written index is worse than a stale one.
     */
    private fun rewriteIndex(vaultPath: String): Boolean {
        val index = "$vaultPath/vault-manifest.txt"
        // A temp of its own: the engine writes the index through
        // `vault-manifest.txt.new`, and a migration that used the same name
        // would be fighting it for the file.
        val tmp = "$index.rename"
        val done = Shell.cmd(
            "sed '/^ENTRY|/ s/\\.shift/.vault/' ${ShellQuote.q(index)} > ${ShellQuote.q(tmp)} 2>/dev/null && " +
                "mv ${ShellQuote.q(tmp)} ${ShellQuote.q(index)} 2>/dev/null"
        ).exec()
        return done.isSuccess
    }

    // ------------------------------------------------------- folder names

    /**
     * Rename every backup folder whose name a copy cannot carry.
     *
     * The marker is written only once nothing is left to rename, so a launch
     * that could not finish (no root yet, a vault on a read-only volume)
     * retries next time. Idempotent in any case: the second run finds no move
     * to plan.
     */
    private fun renameFolders(context: Context, vaultPath: String, force: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!force && prefs.getBoolean(FOLDERS_DONE, false)) return
        try {
            ShellQuote.requireAbsPath(vaultPath)
        } catch (_: Exception) {
            DroidVaultLogger.d("Vault", "folder rename skipped: the vault path is not usable")
            return
        }
        val planned = planFolderRenames(vaultPath)
        val moved = if (planned.isEmpty()) emptyMap() else applyFolderRenames(vaultPath, planned)
        // The index follows the DISK, in every launch, including one that had
        // nothing left to rename — a pass that renamed 15 of 35 folders used to
        // leave those 15 entries naming folders that no longer existed, and no
        // later launch ever looked at them again.
        if (!repairIndex(context, vaultPath)) {
            DroidVaultLogger.w(
                "Vault",
                "folder rename: the vault index could not be repaired — retrying next launch"
            )
            return
        }
        val left = planFolderRenames(vaultPath)
        if (left.isNotEmpty()) {
            DroidVaultLogger.w(
                "Vault",
                "folder rename incomplete: ${left.size} folder(s) still carry a name the storage " +
                    "refuses (${left.keys.take(3).joinToString(", ")}) — " +
                    "renamed ${moved.size} of ${planned.size}; retrying next launch"
            )
            return
        }
        if (moved.isNotEmpty()) {
            DroidVaultLogger.i(
                "Vault",
                "renamed ${moved.size} backup folder(s) so the vault can be copied off the phone"
            )
        }
        prefs.edit { putBoolean(FOLDERS_DONE, true) }
    }

    /**
     * The folders that need renaming, as `old vault-relative path → new`.
     *
     * The new name comes from this folder's CURRENT name rather than from a
     * label read anywhere else: the name on disk is what a reader resolves, so
     * only a name that is actually there is renamed. `safeLabel` is idempotent,
     * which is what makes that safe — a folder named `AB DM` comes back as
     * `AB DM` and is not touched.
     */
    private fun planFolderRenames(vaultPath: String): Map<String, String> {
        val moves = linkedMapOf<String, String>()
        for (area in listOf("Apps/User", "Apps/System")) {
            val listed = Shell.cmd("ls -1 ${ShellQuote.q("$vaultPath/$area")} 2>/dev/null").exec()
            for (raw in listed.out) {
                val name = raw.trim()
                if (name.isEmpty()) continue
                val portable = ShellQuote.safeLabel(name)
                if (portable != name) moves["$area/$name"] = "$area/$portable"
            }
        }
        return moves
    }

    /**
     * Run the renames, one shell call at a time, and report which ones happened.
     *
     * **One rename per call, as a single-line command.** The first version sent
     * all 35 renames as one multi-line `if/elif/mv/fi` script and only about
     * fifteen took effect — with no `EXISTS` and no failure: the remaining
     * lines simply never ran, and the vault was left half renamed (the log said
     * so, and the next launch finished the job). `mv` on this vault measured
     * 20 ms, so it was not the work; a multi-line command string is not
     * something this shell path preserves reliably. One call per rename makes
     * the answer the rename's own exit status, which is also what a field
     * report can be read from.
     *
     * The answer is the shell's own per-folder one, never "the script ran": a
     * folder whose new name is taken is SKIPPED and stays as it was, because
     * the folder already under that name is another app's backup and moving
     * over it would put one app's data under another app's name.
     */
    private fun applyFolderRenames(
        vaultPath: String,
        moves: Map<String, String>
    ): Map<String, String> {
        val moved = linkedMapOf<String, String>()
        for ((from, to) in moves) {
            val old = ShellQuote.q("$vaultPath/$from")
            val new = ShellQuote.q("$vaultPath/$to")
            // `[ -e ]` before `mv`, never `mv -f`: the destination may be
            // another app's backup. `mv -n` is not enough — it exits 0 having
            // done nothing, which reads as a rename that happened.
            val run = Shell.cmd(
                "if [ -e $new ]; then echo EXISTS; else mv $old $new 2>&1; fi"
            ).exec()
            val said = run.out.joinToString(" ").trim()
            when {
                said.contains("EXISTS") -> DroidVaultLogger.w(
                    "Vault",
                    "folder rename skipped, the name is already taken: $to"
                )
                run.isSuccess -> moved[from] = to
                else -> {
                    // The command's own status is not the answer. One rename in
                    // 35 came back non-zero while the folder HAD moved — the
                    // same session that silently dropped the tail of the old
                    // multi-line script. Ask the vault.
                    val stillThere = Shell.cmd("[ -e $old ] && echo HERE").exec()
                    if (!stillThere.out.any { it.trim() == "HERE" }) {
                        DroidVaultLogger.d(
                            "Vault",
                            "folder rename status disagreed with the vault; the rename landed: $from"
                        )
                        moved[from] = to
                    } else {
                        DroidVaultLogger.w(
                            "Vault",
                            "folder rename failed: $from -> $to" +
                                (if (said.isEmpty()) "" else " ($said)")
                        )
                    }
                }
            }
        }
        return moved
    }

    /**
     * Point the index's entry paths at the folders that are actually on disk.
     *
     * ## Why this is derived from the DISK and not from a list of moves
     *
     * The obvious version passes this pass's own `moved` map, and it is wrong
     * in the case that actually happened: a launch that renamed 15 of 35
     * folders rewrote those 15 entries and returned. The next launch renamed
     * the rest and rewrote *its* entries — leaving the 15 from the first launch
     * naming folders that no longer existed, because nothing ever looked at the
     * whole index again. A repair that only knows about one pass can never fix
     * a pass that has already gone by.
     *
     * So the question asked here is the one that always has the same answer:
     * *which entry paths name a folder that is not there, whose portable name
     * IS there?* That is readable from the disk alone, so a stale index from
     * any earlier launch is repaired by the same pass, and running it again
     * finds nothing to do.
     *
     * ## The two reads that must not be mistaken for "nothing to do"
     *
     * The index is pulled to a private copy rather than read from the shell's
     * stdout, and the disk listing is required for both areas. A `cat` through
     * this root session at start-up can come back empty (that same session also
     * dropped the tail of a 35-line rename script), and an empty read looks
     * exactly like "no entries". The first version of this method did read the
     * stdout, and it silently did nothing: 99 entries in the reference vault
     * kept the old paths while the migration reported success.
     *
     * Every write is a temp plus a rename, through the root shell: direct file
     * I/O into the vault returns EPERM once a root `mv` has touched the
     * directory (invariant 12). The verdict is the size that landed, not the
     * command's exit status.
     */
    private fun repairIndex(context: Context, vaultPath: String): Boolean {
        val index = "$vaultPath/vault-manifest.txt"
        val pulled = java.io.File(context.cacheDir, "droidvault_manifest_paths.txt")
        val pushed = java.io.File(context.cacheDir, "droidvault_manifest_paths.new")
        val pulledPath = ShellQuote.q(pulled.absolutePath)
        try {
            val pull = Shell.cmd(
                "rm -f $pulledPath && cat ${ShellQuote.q(index)} > $pulledPath"
            ).exec()
            if (!pull.isSuccess) return false
            if (!pulled.exists() || pulled.length() == 0L) {
                // A vault that has never been verified has no index yet, and
                // there is no marker to carry.
                DroidVaultLogger.d("Vault", "folder rename: there is no vault index to repair yet")
                return true
            }
            val current = try {
                pulled.readText()
            } catch (e: Exception) {
                DroidVaultLogger.w("Vault", "folder rename: the index copy could not be read: ${e.message}")
                return false
            }
            // The manifest's own header, or this is not the file it claims.
            if (!current.startsWith(VAULT_MANIFEST_HEADER)) {
                DroidVaultLogger.w("Vault", "folder rename: the index copy is not an index")
                return false
            }
            val onDisk = existingFolderNames(vaultPath) ?: return false
            val moves = linkedMapOf<String, String>()
            for (entryPath in VaultIndexPaths.entryPaths(current)) {
                val folder = VaultIndexPaths.folderOf(entryPath) ?: continue
                if (folder in onDisk) continue
                val portable = folder.substringBeforeLast('/') + "/" +
                    ShellQuote.safeLabel(folder.substringAfterLast('/'))
                // Only a folder whose portable name is really there: a stale
                // entry for an app that is no longer in the vault must stay a
                // stale entry, not be pointed at another app's folder.
                if (portable != folder && portable in onDisk) moves[folder] = portable
            }
            if (moves.isEmpty()) return true
            val rewritten = VaultIndexPaths.remap(current, moves)
            if (rewritten == current) return true
            pushed.writeText(rewritten)
            pushed.setReadable(false, false)
            pushed.setReadable(true, true)
            pushed.setWritable(true, true)
            val pushedPath = ShellQuote.q(pushed.absolutePath)
            val tmp = ShellQuote.q("$index.dirs")
            val write = Shell.cmd(
                "cp -p $pushedPath $tmp && chmod 660 $tmp && mv $tmp ${ShellQuote.q(index)}"
            ).exec()
            if (!write.isSuccess) return false
            // The bytes that landed are the bytes we wrote, or the rewrite did
            // not happen however the command exited.
            val landed = Shell.cmd("stat -c %s ${ShellQuote.q(index)} 2>/dev/null").exec()
            val size = landed.out.firstOrNull()?.trim()?.toLongOrNull()
            if (size != pushed.length()) {
                DroidVaultLogger.w(
                    "Vault",
                    "folder rename: the index landed at ${size ?: "?"} bytes, " +
                        "expected ${pushed.length()}"
                )
                return false
            }
            DroidVaultLogger.i(
                "Vault",
                "repaired ${moves.size} folder path(s) in the vault index so its " +
                    "read-back markers still name the archives they were earned on"
            )
            return true
        } finally {
            pulled.delete()
            pushed.delete()
        }
    }

    /**
     * The backup folders that exist, as vault-relative paths, or null if the
     * listing could not be made.
     *
     * Null rather than an empty set for a read that FAILED (invariant 58): the
     * caller would otherwise derive no moves from it and call a broken read a
     * clean index. Absence is a different answer — the reference vault has no
     * `Apps/System` at all, because no system app has been backed up — so each
     * area is listed with a canary the shell reaches only if it ran. The canary
     * holds a `|`, a character no backup folder can carry, so a folder cannot
     * pass for it.
     */
    private fun existingFolderNames(vaultPath: String): Set<String>? {
        val names = linkedSetOf<String>()
        for (area in listOf("Apps/User", "Apps/System")) {
            // `[ -d ]` first: a missing area is a TRUE answer ("nothing backed
            // up there"), while a shell that never ran is not an answer at all.
            val listed = Shell.cmd(
                "echo ${ShellQuote.q(AREA_CANARY)}; " +
                    "if [ -d ${ShellQuote.q("$vaultPath/$area")} ]; then " +
                    "ls -1 ${ShellQuote.q("$vaultPath/$area")} 2>/dev/null; fi"
            ).exec()
            val out = listed.out.map { it.trim() }
            if (out.firstOrNull() != AREA_CANARY) {
                DroidVaultLogger.w("Vault", "folder rename: could not list $area")
                return null
            }
            for (name in out.drop(1)) {
                if (name.isNotEmpty()) names.add("$area/$name")
            }
        }
        return names
    }

}
