package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * One device-data archive, as a verification found it.
 *
 * The engine reports device archives with one `VERIFY_DEVICE_ITEM` line per file
 * and a summary, exactly as the app half reports one line per app. These rows are
 * the device half of that report: the same `ok` / `changed` / `trustworthy` shape
 * as [AppVerification], so the card, the dialog, the banner and the pre-restore
 * gate can all read a device finding the way they read an app finding.
 *
 * They live in [VerificationReport] rather than in a report type of their own.
 * Two types carrying one verdict is how a finding becomes visible on the screen
 * that merges them and invisible on the screen that does not (invariant 18: one
 * gate, one implementation) — and it is exactly what happened here: the device
 * rows were parsed into a parallel report nothing ever read, so a damaged
 * `Messages.shift` reached the restore gate as an anonymous count and passed.
 *
 * The file name is the identity. [item] is the plan-side key when this build
 * knows the archive; a `.shift` this build has no item for is still a finding,
 * and reporting it as some other item — the old parser defaulted an unknown name
 * to Messages — would put a confident label on the wrong file.
 */
@Immutable
data class DeviceVerification(
    /** The item a restore plan selects, or null for an archive with no item. */
    val item: DeviceDataItem?,
    /** The archive file name the engine verified, e.g. "Messages.shift". */
    val archiveName: String,
    /** Whether the engine could read the archive back at all. */
    val ok: Boolean,
    /** Files the engine could not read. For a device archive that is normally the
     * one file, but it is kept as a list so it renders like an app row. */
    val corruptParts: List<String> = emptyList(),
    /** Bytes the engine actually read back successfully. */
    val bytesReadable: Long = 0L,
    /** Files whose bytes changed under the recorded digest — readable, but not the
     * archive that was written. */
    val hashMismatchedParts: List<String> = emptyList(),
    /**
     * When this finding was first seen, in wall-clock ms; 0 when this row is fine
     * or its age is unknown.
     *
     * Same rule as the app rows, and for the same reason: a finding is not
     * withdrawn by a later run, so without an age the card says "Messages.shift
     * changed" in the present tense about something four runs ago. Carried
     * forward by `VerificationReport.withFindingAges`, matched on the archive
     * name, since that is this row's identity.
     */
    val firstSeenMs: Long = 0L
) {
    /** Readable, and still the archive that was written. */
    val trustworthy: Boolean get() = ok && hashMismatchedParts.isEmpty()

    /** Readable, but not the archive that was written. */
    val changed: Boolean get() = hashMismatchedParts.isNotEmpty()

    /** What to call this on screen: the item's name, or the file itself. */
    val label: String get() = item?.label ?: archiveName
}
