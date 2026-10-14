package com.phoenix.droidvault.utils

import com.topjohnwu.superuser.Shell

/**
 * The digest that travels beside every archive in the vault.
 *
 * A `.shift` file is a zstd stream, and a stream that decompresses is not proof
 * that it is the archive that was written: a same-length edit, a truncated write
 * that still parses, or a file swapped for an older copy all read back fine. The
 * digest is the only record that can see those — and it is the one thing in the
 * vault a user can check without this app:
 *
 * ```sh
 * cd /storage/emulated/0/DroidVault/Device
 * sha256sum -c Messages.shift.sha256      # as root, or over MTP on a copy
 * ```
 *
 * ## The format is an interface, not a detail
 *
 * The line is coreutils' own: `<digest>  <base name>`. The engine's `checksum`
 * module writes and parses exactly this, and the two must keep agreeing — a
 * reader and a writer that name the same file differently is how the
 * export/import pair came to write `configurations` and read `configs`, with a
 * count that was permanently zero.
 *
 * The recorded name is the archive's BASE NAME, never an absolute path: a vault
 * moves (move vault, an SD card, a fresh ROM with the same external storage), and
 * a recorded path would verify the old location and call a perfectly good archive
 * corrupt.
 *
 * ## The stale-claim rule
 *
 * When no digest can be recorded, any sidecar left over from a previous version
 * of the file is REMOVED. An absent record reads as "unknown"; one describing the
 * previous bytes reads as "changed", which claims a good archive was altered.
 * Only one of those is true, and the engine's `checksum::record` makes the same
 * choice for the archives it writes — both writers in the vault follow one rule.
 */
object VaultDigest {

    const val SUFFIX = ".sha256"

    /** `<archive>.sha256` — the file the engine and `sha256sum -c` both read. */
    fun sidecarFor(archivePath: String): String = archivePath + SUFFIX

    /** Whether [text] is a sha256 digest and not, say, an error message. */
    fun isDigest(text: String): Boolean =
        text.length == 64 && text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }

    /**
     * The line to write, or null when it cannot be expressed.
     *
     * Pure, and the counterpart of the engine's `checksum::sidecar_line`. A name
     * carrying a newline would end the line early and leave a sidecar that
     * vouches for a different file, so such a name is refused rather than
     * escaped: a silently altered name is worse than no record.
     */
    fun sidecarLine(digest: String, archiveName: String): String? {
        if (!isDigest(digest)) return null
        if (archiveName.isEmpty() || archiveName.contains('\n') || archiveName.contains('\r')) {
            return null
        }
        // Two spaces: exactly what `sha256sum` writes and `sha256sum -c` parses.
        return "$digest  $archiveName\n"
    }

    /**
     * The digest the platform computes for [archivePath], or null when it cannot
     * be had (no `sha256sum`, unreadable file).
     */
    fun digestOf(archivePath: String): String? {
        val line = Shell.cmd("sha256sum ${ShellQuote.q(archivePath)}")
            .exec().out.firstOrNull()?.trim().orEmpty()
        val digest = line.substringBefore(' ').trim()
        return if (isDigest(digest)) digest.lowercase() else null
    }

    /**
     * Record [archivePath]'s digest beside it, and report whether one is now
     * recorded.
     *
     * Best-effort by contract: a caller must never fail an archive that WAS
     * written because its index entry could not be. `false` means "not recorded",
     * which every reader treats as unknown rather than as unchanged.
     *
     * Called immediately after the archive is moved into place — the moment its
     * bytes are still in page cache, the same moment, for the same reason, as the
     * engine's writer.
     */
    fun record(archivePath: String): Boolean {
        val name = archivePath.substringAfterLast('/')
        val line = digestOf(archivePath)?.let { sidecarLine(it, name) }
        if (line == null) {
            forget(archivePath)
            return false
        }
        val sidecar = sidecarFor(archivePath)
        // Temp then rename, like every other writer in this project: a partial
        // sidecar would be a claim about bytes nobody hashed.
        val wrote = Shell.cmd(
            "printf %s ${ShellQuote.q(line)} > ${ShellQuote.q("$sidecar.new")} && " +
                "mv ${ShellQuote.q("$sidecar.new")} ${ShellQuote.q(sidecar)}"
        ).exec().isSuccess
        if (!wrote) forget(archivePath)
        return wrote
    }

    /** Remove a sidecar that can no longer describe its archive. */
    fun forget(archivePath: String) {
        val sidecar = sidecarFor(archivePath)
        Shell.cmd("rm -f ${ShellQuote.q(sidecar)} ${ShellQuote.q("$sidecar.new")}").exec()
    }
}
