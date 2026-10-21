package com.phoenix.droidvault

import android.content.Context
import androidx.core.content.edit
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
        } finally {
            // Whatever happened, the reads stop waiting: a stuck migration
            // must not hold the app's first answer.
            settled.complete(Unit)
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
}
