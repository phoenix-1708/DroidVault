package com.phoenix.droidvault.models

import androidx.compose.runtime.Immutable

/**
 * Destructive operations are moved, not erased.
 *
 * `rm -rf` on a backup archive is the one action in this app with no recovery
 * path: everything else can be re-derived from the device, but a deleted backup
 * is gone until the next full backup of every app. So deletion renames into a
 * quarantine folder instead. A rename on the same filesystem is instantaneous
 * and costs no space, so the "safe" behaviour is free.
 *
 * Entries are purged by [KEEP_MILLIS] or when the trash is explicitly emptied —
 * never silently, and never by a timer that could race a user about to undo.
 */
object Trash {

    /** Quarantine lives beside the data it protects, so it moves with the vault. */
    const val DIR_NAME = ".trash"

    /**
     * How long a batch stays recoverable.
     *
     * Long enough to notice a mistake and reach the device again; short enough
     * that the quarantine does not silently double the vault's footprint
     * forever.
     */
    const val KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** Batches retained regardless of age; older ones beyond this are purged. */
    const val MAX_BATCHES = 10

    /** One quarantined item. */
    @Immutable
    data class Entry(
        /** Absolute path it lived at, so undo restores the exact location. */
        val originalPath: String,
        val trashPath: String,
        val label: String,
        val sizeKb: Long
    )

    @Immutable
    data class Batch(
        val id: String,
        val createdAt: Long,
        val entries: List<Entry>,
        val kind: String
    ) {
        val totalKb: Long get() = entries.sumOf { it.sizeKb }
        val count: Int get() = entries.size
        fun isRecoverable(now: Long): Boolean = now - createdAt < KEEP_MILLIS
        fun ageMillis(now: Long): Long = now - createdAt
    }

    // --- durable format ------------------------------------------------------
    // A flat line format rather than JSON: one path per line, tab separated.
    // Paths may contain spaces and tabs are therefore the only separator, and a
    // path containing a literal tab cannot round-trip. Tab is rejected on write
    // (see requireEncodable) instead of being silently mangled.

    private const val HEADER = "DVTRASH1"

    fun requireEncodable(path: String) {
        require('\t' !in path && '\n' !in path) {
            "Path cannot be stored in the trash manifest"
        }
    }

    fun encode(batches: List<Batch>): String = buildString {
        appendLine(HEADER)
        for (b in batches) {
            appendLine("B\t${b.id}\t${b.createdAt}\t${b.kind}\t${b.entries.size}")
            for (e in b.entries) {
                requireEncodable(e.originalPath)
                requireEncodable(e.trashPath)
                appendLine("E\t${e.originalPath}\t${e.trashPath}\t${e.label}\t${e.sizeKb}")
            }
        }
    }

    fun decode(text: String): List<Batch> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != HEADER) return emptyList()
        val out = mutableListOf<Batch>()
        var id = ""
        var created = 0L
        var kind = "apps"
        var entries = mutableListOf<Entry>()
        var inBatch = false

        fun flush() {
            if (inBatch) out += Batch(id, created, entries.toList(), kind)
            entries = mutableListOf()
            inBatch = false
        }

        for (raw in lines.drop(1)) {
            val parts = raw.split('\t')
            if (parts.isEmpty() || raw.isBlank()) continue
            when (parts[0]) {
                "B" -> {
                    flush()
                    if (parts.size < 5) continue
                    id = parts[1]
                    created = parts[2].toLongOrNull() ?: 0L
                    kind = parts[3]
                    inBatch = true
                }
                "E" -> if (inBatch && parts.size >= 5) {
                    entries += Entry(
                        originalPath = parts[1],
                        trashPath = parts[2],
                        label = parts[3],
                        sizeKb = parts[4].toLongOrNull() ?: 0L
                    )
                }
            }
        }
        flush()
        return out
    }

    /**
     * Which batches to purge now.
     *
     * Only genuinely unrecoverable ones — i.e. past [KEEP_MILLIS]. Never
     * performed by this function — it returns the DECISION so the caller can
     * report what it removed instead of silently shrinking.
     */
    fun expiredBatchIds(batches: List<Batch>, now: Long): List<String> {
        // AGE only. The predicate also had `index >= MAX_BATCHES`, which evicted by
        // COUNT: deleting 11 apps one at a time irreversibly erased the first
        // batch with the 11th, typically seconds after it was made — while every
        // surface advertised "recoverable for 7 days". A cap belongs on the
        // user-initiated "Empty trash", where the eviction is what they asked for.
        // It also contradicted this function's own docstring two lines above.
        return batches.filter { !it.isRecoverable(now) }.map { it.id }
    }

    /** Total bytes currently held in the quarantine, for an honest warning. */
    fun heldKb(batches: List<Batch>): Long = batches.sumOf { it.totalKb }

    /**
     * Human label for a batch: "1 device backup", "3 device backups", "2 apps".
     *
     * Deriving this from the stored kind alone produced "1 device backups" and
     * "1 apps" — found on device the first time a single item was quarantined.
     * The kinds are few and fixed, so an explicit table beats guessing: an
     * unknown kind falls back to the raw plural form rather than being
     * silently swallowed.
     */
    /**
     * Display names of the items in a batch, in deletion order.
     *
     * A count alone is not an answer to "what did I just delete?". The user
     * deleted one app, the card said "1 app", and with several batches on screen
     * there was no way to tell which was which — reported on device alongside
     * two identical "1 app" rows.
     *
     * The stored label is the source directory relative to its own parent
     * ("User/1DM+", "Device/Messages.shift"), so the name is the last segment.
     * Archive suffixes are dropped because "Messages" reads better than
     * "Messages.shift" next to a size.
     */
    fun entryNames(batch: Batch): List<String> =
        batch.entries.map { entry ->
            entry.label.substringAfterLast('/').let { name ->
                ARCHIVE_SUFFIXES.firstOrNull { name.endsWith(it) }
                    ?.let { name.dropLast(it.length) } ?: name
            }.ifBlank { entry.label }
        }.distinct()

    /** "1DM+, AB DM" — or "1DM+ and 2 more" once the list stops fitting. */
    fun entrySummary(batch: Batch, limit: Int = 3): String {
        val names = entryNames(batch)
        if (names.isEmpty()) return batchLabel(batch.count, batch.kind)
        return if (names.size <= limit) {
            names.joinToString(", ")
        } else {
            names.take(limit).joinToString(", ") + " and ${names.size - limit} more"
        }
    }

    private val ARCHIVE_SUFFIXES = listOf(".shift", ".txt")

    fun batchLabel(count: Int, kind: String): String = when (kind) {
        "apps" -> if (count == 1) "1 app" else "$count apps"
        "device backups" -> if (count == 1) "1 device backup" else "$count device backups"
        else -> "$count $kind"
    }
}