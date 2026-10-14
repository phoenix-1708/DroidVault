package com.phoenix.droidvault.utils

import android.content.Context
import com.phoenix.droidvault.models.VaultVerdict
import com.phoenix.droidvault.models.VerificationReport

/**
 * Where the last verdict is kept between launches.
 *
 * App-private prefs rather than the vault: a verdict is this install's
 * observation of the archives, not part of the backup set, and the vault is
 * shared with the transfer format. Nothing in the vault reads this, and a clean
 * install simply has no verdict — which is honest, because a fresh install has
 * not checked anything.
 *
 * `commit()`, not `apply()`: the whole value of this file is that the record is
 * already on disk when the process dies, and a check is a user-initiated action
 * that happens once — a few milliseconds of I/O there buys the durability, and
 * there is no per-frame write to protect. A failed write is not an error the
 * user must see: the effect is the state they would have had without the file,
 * "not checked yet".
 */
object VaultVerdictStore {

    private const val PREFS = "droidvault_verdict_prefs"
    private const val KEY_RECORD = "last_verdict"

    /**
     * Remember [report] as the verdict for [vaultPath].
     *
     * Only conclusive reports are worth keeping — see [saveIfConclusive], which is
     * what callers use, because an inconclusive run is not a verdict and must not
     * replace one that is.
     */
    fun save(context: Context, vaultPath: String, report: VerificationReport) {
        val json = runCatching { VaultVerdict.toJson(vaultPath, report) }.getOrNull() ?: return
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RECORD, json).commit()
        }
    }

    /**
     * Persist [report] when it is a verdict, and leave the stored one alone when
     * it is not.
     *
     * A run that was cancelled, errored or found nothing conclusive observed
     * nothing to remember; overwriting the previous record with it would delete a
     * finding the restore gate is still holding (§2.46's rule — a finding is not
     * withdrawn). Keeping the old record is the same decision, made across a
     * process boundary.
     */
    fun saveIfConclusive(context: Context, vaultPath: String, report: VerificationReport) {
        if (report.conclusive) save(context, vaultPath, report)
    }

    /**
     * The stored verdict for [vaultPath], or null when there is none for it.
     *
     * Marked restored by the model layer, so nothing here can accidentally return
     * a report that looks as if this launch observed it.
     */
    fun load(context: Context, vaultPath: String): VerificationReport? {
        val raw = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RECORD, null)
        }.getOrNull()
        return VaultVerdict.forVault(VaultVerdict.fromJson(raw), vaultPath)
    }

    /** Forget the stored verdict — the vault folder changed, or the user cleared it. */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_RECORD).commit()
        }
    }
}
