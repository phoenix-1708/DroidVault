package com.phoenix.droidvault.utils

/** Central shell-quoting + validation helpers. All `su -mm -c '...'` interpolations must use these. */
object ShellQuote {
    /** POSIX single-quote a string: ' -> '"'"' */
    fun q(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]+(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val PARTITION = Regex("^[A-Za-z0-9_\\-]+$")
    private val COMPS = Regex("^[0-9 ]*$")

    fun requirePackage(pkg: String): String {
        require(PKG.matches(pkg)) { "Invalid package: $pkg" }
        return pkg
    }

    fun requirePartition(partition: String): String {
        require(PARTITION.matches(partition)) { "Invalid partition: $partition" }
        return partition
    }

    fun requireComps(comps: String): String {
        require(COMPS.matches(comps)) { "Invalid comps: $comps" }
        return comps
    }

    /**
     * Build one line of the engine's pipe-delimited install-target file.
     *
     * The engine runs as root and installs EVERY line it reads, so the line
     * protocol must be unbreakable: a crafted APK label containing `\n`
     * previously injected an extra install line (audit A-02 — silent root
     * install of an arbitrary APK). Returns null when any field could break
     * the protocol; callers drop that entry instead of writing it.
     */
    fun installerTargetLine(path: String, pkg: String, label: String): String? {
        if (path.isBlank() || '\n' in path || '\r' in path || '|' in path) return null
        val safePkg = try {
            requirePackage(pkg)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return "$path|$safePkg|${safeLabel(label)}"
    }

    fun requireAbsPath(path: String): String {
        require(path.startsWith("/") && path.length < 512) { "Invalid path: $path" }
        // Quote-breakout class: every interpolation site single-quotes via q(),
        // so quote characters must never appear in a validated path. Parens and
        // globs intentionally allowed (legit names like "My Folder (1)").
        require(
            '\'' !in path && '"' !in path && '$' !in path && '`' !in path &&
                '\n' !in path && '\r' !in path
        ) { "Invalid path: $path" }
        return path
    }

    /**
     * Sanitize a user-visible label for use as a DIRECTORY name and as a
     * field in the engine's pipe-delimited TARGETS file. The backend derives
     * real paths from this value ("$BACKUP_BASE/$type/$label"), so a label
     * containing '/' or '..' would write outside the vault folder, and a '|'
     * would shift the TARGETS parse fields. Strip everything structural,
     * collapse to a sane single-line name, and never return something empty
     * or dot-only (".." must not survive as a directory name).
     *
     * `"`, `$` and a backtick are *removed* rather than merely rejected: a
     * label comes from another app's `android:label`, so it is fully
     * attacker-controlled, and these three are the characters that turn an
     * interpolation into code execution. Removing them (instead of throwing)
     * keeps a legitimately-named app usable while making the value inert in
     * every context it reaches — shell, TARGETS fields and directory names.
     *
     * ## Portable, because the vault is meant to leave the phone
     *
     * This value names the app's folder in the vault, and the vault is the one
     * thing a user carries across a ROM flash. Android's `/sdcard` FUSE policy
     * refuses `: * ? < > | " \` for every writer that is not root — measured on
     * the reference device: root creates all of them, the adb shell (and so
     * `adb push`, MTP, SAF and every file manager) gets EPERM, and a `tar`
     * extracting as the shell uid fails file by file. Windows and exFAT
     * cannot hold them either. A label carrying one therefore produced a
     * folder that could be COPIED OFF the phone and never put back — the
     * recovery path this app exists for — which is why 35 folders in the
     * reference vault (`Tachiyomi: Mangakakalot` and friends) made an
     * otherwise-perfect vault impossible to restore by any ordinary means.
     *
     * The five that reach a directory name are substituted here (`|`, `"` and
     * `\` are already removed above). Trailing dots and spaces and the
     * reserved DOS device names go the same way: the phone accepts them,
     * Windows and FAT do not.
     *
     * A label that is already portable comes back BYTE-IDENTICAL — no case
     * change, no re-spacing. Every folder already in a user's vault is named
     * by this function, and renaming 77 working folders to fix 35 would be
     * churn on data that is not broken; `VaultExtensionMigration` renames only
     * the folders whose name this function changes.
     */
    fun safeLabel(label: String): String {
        val stripped = label
            .replace('/', '_')
            .replace('\\', '_')
            .replace("|", "")
            .replace("\"", "")
            .replace("$", "")
            .replace("`", "")
            .replace("'", "")
            .replace("\n", "")
            .replace("\r", "")
            .replace("\t", " ")
            // Storage- and Windows-refused characters. `": "` is replaced
            // before the bare colon so "Tachiyomi: X" reads "Tachiyomi - X"
            // and not "Tachiyomi- X".
            .replace(": ", " - ")
            .replace(':', '-')
            .replace('*', '-')
            .replace('?', '-')
            .replace('<', '-')
            .replace('>', '-')
            .trim()
            .trimStart('.')
            .take(128)
            .trim()
            // Accepted here, refused by Windows and FAT.
            .trimEnd('.', ' ')
        // All-underscore results (a label of "/" or "|") are as useless
        // as empty ones — fall back to the same stable name.
        val portable = stripped
            .takeIf { it.isNotEmpty() && it.any { c -> c != '_' && c != '.' } }
            ?: "app"
        // A reserved device name is a name Windows cannot create, whatever
        // extension follows it.
        return if (RESERVED_DOS_NAMES.matches(portable)) "_$portable" else portable
    }

    /**
     * `CON`, `PRN`, `AUX`, `NUL`, `COM1`-`COM9`, `LPT1`-`LPT9`, with or
     * without an extension.
     */
    private val RESERVED_DOS_NAMES =
        Regex("(?i)^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$")
}
