package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * One device-data archive as it actually is on disk, for the Manage screen.
 *
 * The question this exists to answer is the one nothing on that screen could:
 * **is this thing backed up at all?** It used to be answerable only by opening
 * the "Erase Native" dialog, which is a destructive dialog — a control whose
 * first job is to tell you what you have is a control that will eventually be
 * opened by someone who meant to look rather than to delete, and the failure is
 * a tap too late rather than a wrong number.
 *
 * Deliberately [DeviceDataItem]-keyed rather than filename-keyed. That enum has
 * fourteen consumers across Migrate, Automation, Settings, the codec and the
 * verifier, and an item listed here that a restore could not select would be a
 * row promising something the rest of the app does not agree with. The rows are
 * `DeviceDataItem.entries` — the same list, same order and same labels the
 * Automation tab's device selector renders — so there is exactly one place that
 * decides what a device item is called.
 *
 * The parse is pure and separate from the shell read for the reason every other
 * model in this app is: `stat` output cannot be produced in a unit test, and the
 * decisions about it must not depend on being able to.
 */
@Immutable
data class DeviceArchiveInfo(
    val item: DeviceDataItem,
    /** The archive is in `Device/`. Not the same claim as "reads back". */
    val present: Boolean,
    val sizeBytes: Long = 0L,
    val modifiedAt: Long = 0L
) {
    /** "7.5 KB" — or empty when there is nothing there to measure. */
    val sizeLabel: String
        get() = if (present && sizeBytes > 0L) TransferProgress.humanBytes(sizeBytes) else ""

    /**
     * What the row says about the BYTES, which is a different question from
     * whether the file is there.
     *
     * "Backed up" is the whole claim when there is nothing else to say, and it
     * is deliberately the *only* claim: a file's existence is a fact about the
     * folder, not a promise that the archive reads back. The health word beside
     * it is where that promise would go, and it says "not checked" until a check
     * has run.
     */
    val presenceLabel: String
        get() = if (present) "Backed up" else "Not backed up"
}

/**
 * The outcome of asking the vault folder what it holds.
 *
 * This type exists because of one specific lie. The read was originally
 * `runCatching { … }.getOrDefault(emptyList())`, and the read it wraps calls
 * `ShellQuote.requireAbsPath`, which **throws** on a moved or malformed vault
 * path. So a folder the user had renamed rendered as a confident
 * **"0 of 7 backed up"** — the app stating, in the past tense, that a user has
 * no device backups, because it could not open the door.
 *
 * That is invariant 16 (*absence is not failure*) in its worst form, and
 * invariant 22's *"treat a fallback that runs before the specific answer as the
 * bug it is"*: an empty list is a valid answer and an unreadable folder is a
 * different one, and collapsing them makes the app confidently wrong about the
 * one thing the user cannot check without opening Settings.
 *
 * So there are three states, named, and the middle one is not representable as
 * "nothing there". The empty list is a real answer and stays one.
 */
@Immutable
sealed interface DeviceArchiveRead {
    /** The folder was read. [archives] is all seven items, present or not. */
    @Immutable
    data class Ok(val archives: List<DeviceArchiveInfo>) : DeviceArchiveRead

    /**
     * The folder could not be read — no root, a path that is not absolute, a
     * volume that is not mounted.
     *
     * [reason] is a short, non-technical phrase for the screen and is also what
     * the diagnostics log records, so "the card says it cannot read" is
     * answerable without asking the user to describe their folder.
     */
    @Immutable
    data class Unreadable(val reason: String) : DeviceArchiveRead

    /**
     * The archives, or none at all.
     *
     * Every consumer that only wants "is this file there" — the Home card, the
     * erase dialog's Folders check — reads through this, and a folder it could
     * not open answers "no" rather than throwing. That is the right answer for
     * a *predicate* and the wrong one for a *report*, which is why the Manage
     * card does not use it.
     */
    fun archivesOrEmpty(): List<DeviceArchiveInfo> =
        (this as? Ok)?.archives ?: emptyList()

    val readable: Boolean get() = this is Ok
}

/**
 * The health of one archive, as far as anything has actually observed it.
 *
 * Four words, and the fourth is the important one. [NOT_CHECKED] is not a
 * synonym for healthy: it is what a row says until somebody runs a check, and a
 * tick shown for a check nobody ran is the exact failure this app's verdict
 * rules exist to prevent (invariant 41 — a check that has not finished has
 * measured nothing; invariant 52 — a summary that claims everything is fine may
 * only be made when every part of it was read).
 */
enum class DeviceArchiveHealth(val label: String) {
    /** Reads back, and is still the archive that was written. */
    READABLE("Readable"),

    /** Reads back, but the bytes differ from the recorded digest. */
    CHANGED("Changed"),

    /** Could not be read back at all. */
    DAMAGED("Damaged"),

    /** Nobody has checked. The honest default. */
    NOT_CHECKED("Not checked");

    val isProblem: Boolean get() = this == DAMAGED
}

/**
 * One row of the Manage screen's device list: the fact, the health, and the one
 * thing a screen may not do — imply a check that did not happen.
 *
 * [health] is derived, never stored, and the derivation is here so the rule
 * cannot differ between the card, a future row and a future dialog.
 *
 * A finding is also never withdrawn by a later run. [stale] is what the vault's
 * change epoch says about the report being offered; a verdict describes the
 * moment its bytes were read, so once the vault has moved the honest state is
 * back to [DeviceArchiveHealth.NOT_CHECKED] — and a *finding* (damaged) is
 * carried by the report itself, which is why the caller passes the report
 * rather than a pre-computed word.
 */
@Immutable
data class DeviceArchiveRow(
    val info: DeviceArchiveInfo,
    val health: DeviceArchiveHealth
) {
    val item: DeviceDataItem get() = info.item
    val present: Boolean get() = info.present

    /** "7.5 KB · 14 Oct", or "Not backed up" for an absent archive. */
    val subtitle: String
        get() = when {
            !info.present -> info.presenceLabel
            info.sizeLabel.isNotEmpty() && info.modifiedAt > 0L ->
                info.sizeLabel + " · " + dateLabel(info.modifiedAt)
            info.sizeLabel.isNotEmpty() -> info.sizeLabel
            else -> info.presenceLabel
        }

    companion object {
        /**
         * Day and month only.
         *
         * A year is what a settings screen shows; this is a row in a list of
         * things you backed up, where "14 Oct" is the answer and a four-digit
         * year is noise that pushes the rest of the row off the line.
         */
        fun dateLabel(modifiedAt: Long): String =
            java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                .format(java.util.Date(modifiedAt))
    }
}

/**
 * Builds one row per device item, always all seven, in `entries` order.
 *
 * Always all seven is the point: a list that only shows what exists cannot
 * answer "is Contacts backed up", because an absent item and an item nobody has
 * heard of look identical in a list that omits them. The absent row is the
 * answer.
 *
 * [verification] is the report, or null when no check has run in this process —
 * and null is what produces [DeviceArchiveHealth.NOT_CHECKED] rather than an
 * assumed pass.
 */
fun deviceArchiveRows(
    archives: List<DeviceArchiveInfo>,
    verification: VerificationReport?
): List<DeviceArchiveRow> {
    val byItem = archives.associateBy { it.item }
    val deviceFindings = verification?.deviceItems.orEmpty()
        .associateBy { it.item }
    return DeviceDataItem.entries.map { item ->
        val info = byItem[item] ?: DeviceArchiveInfo(item, present = false)
        val finding = deviceFindings[item]
        val health = when {
            // A report can cover an item the folder does not have — the file was
            // deleted after the check. Presence is read fresh and presence wins:
            // the screen must not describe bytes that are not on the phone.
            !info.present -> DeviceArchiveHealth.NOT_CHECKED
            finding == null -> DeviceArchiveHealth.NOT_CHECKED
            !finding.ok -> DeviceArchiveHealth.DAMAGED
            finding.changed -> DeviceArchiveHealth.CHANGED
            else -> DeviceArchiveHealth.READABLE
        }
        DeviceArchiveRow(info, health)
    }
}

/**
 * Parse one `stat -c '%n %s %Y'` line set into archive facts.
 *
 * Pure, and split out from the shell for the same reason the rest of this file
 * is: the shell call cannot be exercised in a unit test, so the part that can be
 * wrong is the part that can be tested.
 *
 * The path may contain spaces, so the last TWO fields are taken as size and
 * mtime and everything before them rejoined as the name — `TransferLocator` and
 * `AutomationSnapshot.listVaultContents` both had to learn that separately, and
 * a vault folder the user renamed to something with a space in it is a normal
 * thing, not an edge case.
 *
 * A name that is not one of the seven known archives is ignored rather than
 * guessed at, for the reason `DeviceVerification` gives: reporting an unknown
 * file as some other item puts a confident label on the wrong archive.
 */
object DeviceArchiveReader {

    /** `stat` is silent for a missing file, so its absence IS the absence. */
    fun parse(output: List<String>, vaultPath: String): List<DeviceArchiveInfo> {
        val known = DeviceDataItem.entries.associateBy { it.archiveFile }
        val sizes = HashMap<String, Pair<Long, Long>>()
        for (line in output) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val parts = trimmed.split(' ')
            if (parts.size < 3) continue
            val size = parts[parts.size - 2].toLongOrNull() ?: continue
            val mtime = parts[parts.size - 1].toLongOrNull() ?: continue
            val name = parts.dropLast(2).joinToString(" ").substringAfterLast('/')
            if (name in known) sizes[name] = size to mtime
        }
        return DeviceDataItem.entries.map { item ->
            val stat = sizes[item.archiveFile]
            if (stat == null || stat.first <= 0L) {
                DeviceArchiveInfo(item, present = false)
            } else {
                DeviceArchiveInfo(
                    item = item,
                    present = true,
                    sizeBytes = stat.first,
                    modifiedAt = stat.second * 1000L
                )
            }
        }
    }
}
