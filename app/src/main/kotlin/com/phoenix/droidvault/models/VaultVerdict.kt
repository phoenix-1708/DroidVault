package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/** The last verdict, with the vault it describes. */
@Immutable
data class StoredVerdict(
    val vaultPath: String,
    val report: VerificationReport
)

/**
 * The last check, written down so it outlives the process.
 *
 * A verdict held only in memory disappears with the app: the user checks their
 * backups, sees damage, restarts the phone (or the app is killed in the
 * background), and the app forgets — the health row goes back to "not checked
 * yet" and the pre-restore gate has nothing to refuse with. That is the failure
 * §2.46 fixed inside one session and this fixes across launches, which is the
 * interval a wipe-then-restore actually spans.
 *
 * ## What is stored, and what it is allowed to claim
 *
 * The whole report, because the gate needs the per-app findings: "3 of 107
 * damaged" is useless at restore time — the names are what blocks a package.
 *
 * What comes back is marked `restoredFromDisk`, and [VerdictStaleness] turns that
 * into a withdrawn good-news reading. That flag is the reason this store needs no
 * durable counter: a restored verdict can never be rendered as current, whatever
 * the epoch says, so a run that released the gate while the process was dead
 * cannot leave stale reassurance behind. The finding is unaffected and still
 * blocks — that is the whole point of keeping it.
 *
 * ## The key names are one set of constants
 *
 * A writer and a reader that spell the same key differently is how the transfer
 * count came to read `configs` while the export wrote `configurations`, with a
 * permanently zero result and a unit test that agreed with the code because its
 * fixture was written from the same wrong assumption (§2.24, T-16). Here both
 * directions name the same `K_*` constants, so they cannot drift apart — and a
 * round-trip test with every field non-default pins the values, so a dropped
 * field fails rather than silently reading back as the default.
 *
 * Nothing here throws: a record that cannot be understood is `null`, which the
 * caller reads as "no verdict", the same state as a fresh install. A corrupt
 * prefs blob must not be able to crash a launch.
 */
object VaultVerdict {

    /**
     * Bumped when the shape changes in a way an older build could misread.
     * A record from a FUTURE version is refused rather than approximated: a
     * downgrade must say "not checked yet", never invent a verdict.
     */
    const val VERSION = 1

    private const val K_VERSION = "version"
    private const val K_VAULT = "vault"
    private const val K_REPORT = "report"
    private const val K_TAKEN_AT = "takenAtEpoch"
    private const val K_TOTAL = "total"
    private const val K_OK = "okCount"
    private const val K_CORRUPT = "corruptCount"
    private const val K_BYTES = "bytesReadable"
    private const val K_CANCELLED = "cancelled"
    private const val K_ERROR = "error"
    private const val K_SCOPE = "scope"
    private const val K_DEVICE_CHANGED = "deviceChanged"
    private const val K_READ = "readParts"
    private const val K_SKIPPED = "skippedParts"
    private const val K_APPS = "apps"
    private const val K_LABEL = "label"
    private const val K_PKG = "packageName"
    private const val K_PARTS = "partsChecked"
    private const val K_CORRUPT_PARTS = "corruptParts"
    private const val K_CHANGED_PARTS = "hashMismatchedParts"
    private const val K_DEVICE_ITEMS = "deviceItems"
    private const val K_ARCHIVE = "archiveName"
    private const val K_ITEM_OK = "ok"
    private const val K_TAKEN_AT_MS = "takenAtMs"
    private const val K_FIRST_SEEN = "firstSeenMs"
    private const val K_RECONCILED = "reconciledRecords"

    fun toJson(vaultPath: String, report: VerificationReport): String {
        val apps = JSONArray()
        for (app in report.apps) {
            apps.put(
                JSONObject()
                    .put(K_LABEL, app.label)
                    .put(K_PKG, app.packageName)
                    .put(K_PARTS, app.partsChecked)
                    .put(K_BYTES, app.bytesReadable)
                    .put(K_CORRUPT_PARTS, JSONArray(app.corruptParts))
                    .put(K_CHANGED_PARTS, JSONArray(app.hashMismatchedParts))
                    // How long this finding has stood. Stored with the finding
                    // because it is the one field a later check cannot derive: a
                    // restart that lost it would report an old finding as new,
                    // and "just now" is exactly what makes a user re-check.
                    .put(K_FIRST_SEEN, app.firstSeenMs)
            )
        }
        // The device rows travel too. A finding that survives a restart as a
        // count but loses the archive it is about blocks nothing: the gate is
        // asked about a specific item, and "1 damaged" cannot answer for it.
        // The item itself is not stored — it is derived from the file name, which
        // is what the plan and the engine both name.
        val device = JSONArray()
        for (row in report.deviceItems) {
            device.put(
                JSONObject()
                    .put(K_ARCHIVE, row.archiveName)
                    .put(K_ITEM_OK, row.ok)
                    .put(K_BYTES, row.bytesReadable)
                    .put(K_CORRUPT_PARTS, JSONArray(row.corruptParts))
                    .put(K_CHANGED_PARTS, JSONArray(row.hashMismatchedParts))
                    .put(K_FIRST_SEEN, row.firstSeenMs)
            )
        }
        val body = JSONObject()
            .put(K_TAKEN_AT, report.takenAtEpoch)
            .put(K_TOTAL, report.total)
            .put(K_OK, report.okCount)
            .put(K_CORRUPT, report.corruptCount)
            .put(K_BYTES, report.bytesReadable)
            .put(K_CANCELLED, report.cancelled)
            .put(K_ERROR, report.error)
            .put(K_SCOPE, report.scope.name)
            .put(K_DEVICE_CHANGED, report.deviceChanged)
            .put(K_READ, report.readParts)
            .put(K_SKIPPED, report.skippedParts)
            .put(K_TAKEN_AT_MS, report.takenAtMs)
            .put(K_RECONCILED, report.reconciledRecords)
            .put(K_APPS, apps)
            .put(K_DEVICE_ITEMS, device)
        return JSONObject()
            .put(K_VERSION, VERSION)
            .put(K_VAULT, vaultPath)
            .put(K_REPORT, body)
            .toString()
    }

    /** `null` for anything that is not a record this build can trust. */
    fun fromJson(text: String?): StoredVerdict? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = JSONObject(text)
            if (root.optInt(K_VERSION, -1) != VERSION) return null
            val vault = root.optString(K_VAULT, "")
            if (vault.isEmpty()) return null
            val body = root.optJSONObject(K_REPORT) ?: return null
            val scope = VerifyScope.entries.firstOrNull { it.name == body.optString(K_SCOPE) }
                ?: return null
            val apps = mutableListOf<AppVerification>()
            val rows = body.optJSONArray(K_APPS)
            for (i in 0 until (rows?.length() ?: 0)) {
                val row = rows?.optJSONObject(i) ?: continue
                apps += AppVerification(
                    label = row.optString(K_LABEL, ""),
                    packageName = row.optString(K_PKG, ""),
                    partsChecked = row.optInt(K_PARTS, 0),
                    corruptParts = strings(row.optJSONArray(K_CORRUPT_PARTS)),
                    bytesReadable = row.optLong(K_BYTES, 0L),
                    hashMismatchedParts = strings(row.optJSONArray(K_CHANGED_PARTS)),
                    firstSeenMs = row.optLong(K_FIRST_SEEN, 0L)
                )
            }
            val device = mutableListOf<DeviceVerification>()
            val deviceRows = body.optJSONArray(K_DEVICE_ITEMS)
            for (i in 0 until (deviceRows?.length() ?: 0)) {
                val row = deviceRows?.optJSONObject(i) ?: continue
                val archive = row.optString(K_ARCHIVE, "")
                // A row with no file name is not a finding. Inventing one would
                // put a name on the card that no plan can act on.
                if (archive.isEmpty()) continue
                device += DeviceVerification(
                    item = DeviceDataItem.entries.firstOrNull { it.archiveFile == archive },
                    archiveName = archive,
                    ok = row.optBoolean(K_ITEM_OK, false),
                    corruptParts = strings(row.optJSONArray(K_CORRUPT_PARTS)),
                    bytesReadable = row.optLong(K_BYTES, 0L),
                    hashMismatchedParts = strings(row.optJSONArray(K_CHANGED_PARTS)),
                    firstSeenMs = row.optLong(K_FIRST_SEEN, 0L)
                )
            }
            StoredVerdict(
                vaultPath = vault,
                report = VerificationReport(
                    apps = apps,
                    total = body.optInt(K_TOTAL, 0),
                    okCount = body.optInt(K_OK, 0),
                    corruptCount = body.optInt(K_CORRUPT, 0),
                    bytesReadable = body.optLong(K_BYTES, 0L),
                    cancelled = body.optBoolean(K_CANCELLED, false),
                    error = body.optString(K_ERROR, ""),
                    scope = scope,
                    deviceChanged = body.optInt(K_DEVICE_CHANGED, 0),
                    deviceItems = device,
                    readParts = body.optInt(K_READ, 0),
                    skippedParts = body.optInt(K_SKIPPED, 0),
                    takenAtEpoch = body.optLong(K_TAKEN_AT, 0L),
                    // A record written before this build has no age, which reads
                    // as 0 — "unknown" — and never as "just now".
                    takenAtMs = body.optLong(K_TAKEN_AT_MS, 0L),
                    reconciledRecords = body.optInt(K_RECONCILED, 0)
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * What the screens and the restore gate should use after a run whose report
     * is [fresh], given what was already stored.
     *
     * A conclusive run replaces the verdict. An inconclusive one — cancelled,
     * errored, or a vault with nothing in it — observed nothing, and must not take
     * away what the last successful look found: the record is still on disk, and
     * dropping it here would leave the pre-restore gate with no reason to refuse
     * seconds after the user was shown "1 damaged".
     *
     * Falls back to [fresh] when nothing is stored, which is the honest state on a
     * fresh install: "not checked yet", never a passed check nobody ran.
     */
    fun shownAfter(fresh: VerificationReport, stored: VerificationReport?): VerificationReport =
        if (fresh.conclusive) fresh else (stored ?: fresh)

    /**
     * The report in [record], if it is about [vaultPath] — otherwise null.
     *
     * The vault folder is chosen by the user and can be moved or repointed, and a
     * verdict describes ONE vault's archives. Showing a stale check from the
     * folder they used to use would be a claim about files it never read, so a
     * path that does not match is not a verdict here.
     *
     * Sets [VerificationReport.restoredFromDisk] — this is the only place that
     * does, which is why the flag can be trusted to mean "nobody in this launch
     * has looked".
     */
    fun forVault(record: StoredVerdict?, vaultPath: String): VerificationReport? =
        if (record != null && record.vaultPath == vaultPath) {
            record.report.copy(restoredFromDisk = true)
        } else {
            null
        }

    private fun strings(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val s = array.optString(i, "")
            // An empty element is not a part name; the engine's empty field reads
            // back as one empty string, and a part called "" would be a finding
            // nobody can act on.
            if (s.isNotEmpty()) out += s
        }
        return out
    }
}
