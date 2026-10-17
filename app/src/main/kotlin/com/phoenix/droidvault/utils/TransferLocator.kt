package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.TransferCandidates
import com.phoenix.droidvault.models.TransferCandidate
import com.phoenix.droidvault.models.TransferFile
import com.phoenix.droidvault.models.VaultTransfer
import com.topjohnwu.superuser.Shell

/**
 * Finds the exports this phone already holds, so the user never has to go and
 * find them.
 *
 * Import used to mean: open the system picker, navigate to
 * `Download/DroidVault/<export>/`, and tap one of seventeen identical
 * `.part-aa`…`.part-aq` files. The picker accepted every file type on the
 * device, so it offered everything with no filter and no starting folder — the user was
 * navigating blind, and nothing said which of the seventeen pieces was the right
 * one to pick. All of them were.
 *
 * This lists them instead. It reads through the root shell, which the app
 * already does for every other privileged read — including *writing* this very
 * folder. It is what makes the page possible without a permission:
 *
 *  - Android 11+ refuses `ACTION_OPEN_DOCUMENT_TREE` on `Download` outright, so
 *    the usual "grant the folder once and browse it yourself" pattern is
 *    unavailable in exactly the folder we need.
 *  - `MediaStore` can enumerate the entries on API 33+, but not open a stream
 *    for one the app did not create.
 *  - `MANAGE_EXTERNAL_STORAGE` would work and would also be a permanent, broad,
 *    user-visible grant for a read the root shell already performs.
 *
 * So no new permission, and `AGENTS.md` §6's posture is untouched.
 */
object TransferLocator {

    /** One `find`, following the shape `VaultTransferManager` already uses. */
    private const val MAX_DEPTH = "2"

    /**
     * Paths per `stat` call.
     *
     * Every path is quoted into one command line, so this bounds argv rather
     * than being a batching concern. 400 is far above any real export — 17
     * pieces covered a 34 GB transfer — and below where a shell complains.
     */
    private const val STAT_BATCH = 400

    /**
     * The export folder, plus the Downloads root itself for archives that
     * predate the per-export folders — `reapInProgressExports` documents that
     * those exist, and a picker can open one today, so not listing them would
     * be a regression against what already works.
     */
    fun searchRoots(): List<String> = listOf(
        VaultTransfer.EXPORT_DIR,
        VaultTransfer.EXPORT_DIR.substringBeforeLast('/')
    ).distinct()

    /**
     * Every transfer-shaped file under the export roots, plus each manifest.
     *
     * `find` for the names and `stat` for the numbers, in that order, and never
     * `find -printf`. This device's toybox does have `-printf`, and it is the
     * obvious way to do this in one command — but it is a comparatively recent
     * addition, and this app runs across ROMs at versions where its absence
     * would produce a silently EMPTY import page on exactly the hardware the
     * HyperOS reference device does not represent. `AGENTS.md` has two recorded
     * cases of a toybox feature that worked on the reference phone and not
     * elsewhere (`tar -C`, `split`'s numeric suffix), and a listing that
     * degrades to nothing is worse than one that costs an extra command.
     *
     * `-maxdepth 2` is what keeps this metadata-only: nothing reads a single
     * byte of an archive.
     */
    fun list(): Pair<List<TransferFile>, List<String>> {
        val roots = searchRoots()
        val rootsArg = roots.joinToString(" ") { ShellQuote.q(it) }

        val paths = Shell.cmd(
            "find $rootsArg -maxdepth $MAX_DEPTH -type f " +
                "'(' -name '*.dvbackup.tar*' -o -name '*.parts.json' ')' 2>/dev/null"
        ).exec().out.map { it.trim() }.filter { it.isNotEmpty() }

        if (paths.isEmpty()) return emptyList<TransferFile>() to emptyList()

        // One stat per batch: `stat -c` takes many operands and prints one line
        // each, so the whole listing costs a couple of calls rather than one per
        // file.
        val stats = LinkedHashMap<String, Pair<Long, Long>>()
        paths.take(STAT_BATCH).chunked(STAT_BATCH).forEach { batch ->
            val args = batch.joinToString(" ") { ShellQuote.q(it) }
            for (line in Shell.cmd("stat -c '%s|%Y|%n' $args 2>/dev/null").exec().out) {
                val f = line.split('|')
                if (f.size < 3) continue
                val bytes = f[0].trim().toLongOrNull() ?: continue
                val mtime = f[1].trim().toLongOrNull() ?: 0L
                stats[f.drop(2).joinToString("|").trim()] = bytes to mtime * 1000L
            }
        }

        val files = mutableListOf<TransferFile>()
        val manifests = mutableListOf<String>()
        for (path in paths) {
            val name = path.substringAfterLast('/')
            val (bytes, mtime) = stats[path] ?: continue
            if (name.endsWith(".parts.json")) {
                // Read the manifest's own bytes — it is ~100 bytes. An export is
                // measured against what it RECORDED, and a directory listing
                // cannot know that.
                val json = Shell.cmd("cat ${ShellQuote.q(path)} 2>/dev/null").exec().out
                    .joinToString("\n")
                if (json.isNotBlank()) manifests += json
            } else {
                files += TransferFile(path, bytes, mtime)
            }
        }

        // Loose archives sitting in a folder with no manifest of their own are
        // still exports; discover() treats "no manifest, single file" as whole.
        return files to manifests
    }

    /**
     * The exports on this phone, newest first.
     *
     * A shell failure yields an empty list rather than an exception: the page
     * then shows its empty state and offers the system picker, which is a
     * working import path. A listing that cannot be read must not become a
     * dead end.
     */
    fun discover(cap: Int = TransferCandidates.DEFAULT_CAP): List<TransferCandidate> =
        runCatching {
            val (files, manifests) = list()
            TransferCandidates.discover(files, manifests, cap)
        }.getOrElse { emptyList() }
}
