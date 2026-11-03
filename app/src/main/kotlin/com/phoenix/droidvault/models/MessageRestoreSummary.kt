package com.phoenix.droidvault.models

/**
 * What a Messages restore says when the whole item is finished.
 *
 * The item restores two stores — SMS and MMS — and each reported its own count
 * under the same words, "Restored N Messages.". The MMS pass runs second and
 * usually has nothing to restore, so the line a user was left looking at after
 * every device-data restore read **"Restored 0 Messages."** on a run that had
 * just put 94 messages back. A verdict that describes the last pass instead of
 * the work is invariant 22's defect in its plainest form, and it is the line the
 * restored-count question is asked from.
 *
 * Pure, so the wording is a value with an expected output rather than a template
 * written inline in a pipeline (§4.53).
 */
object MessageRestoreSummary {

    /**
     * @param restored rows this run wrote — SMS and MMS together.
     * @param alreadyPresent rows the dedupe skipped because the phone held them
     *   already. Not a failure: it is what an idempotent restore looks like.
     */
    fun of(restored: Int, alreadyPresent: Int): String = when {
        restored > 0 && alreadyPresent > 0 ->
            "Restored $restored Messages ($alreadyPresent already on the phone)."
        restored > 0 -> "Restored $restored Messages."
        alreadyPresent > 0 -> "All $alreadyPresent Messages are already on the phone."
        else -> "No Messages in the archive."
    }
}
