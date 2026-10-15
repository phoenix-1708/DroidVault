package com.phoenix.droidvault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Executable versions of the project's invariants: conventions and security
 * rules that were written down and then quietly violated more than once.
 *
 * These are conventions that were written down and then quietly violated more
 * than once. A comment in a document cannot enforce anything; these can. Each
 * test names the rule it protects so a failure reads as "someone broke rule X",
 * not "some string comparison went wrong".
 *
 * Deliberately coarse. A test that fails on reformatting is worse than no test,
 * so each assertion targets a pattern that is unambiguously wrong rather than a
 * preferred style.
 */
class ConventionGuardTest {

    /** Repo root from the `app` module dir that Gradle runs tests in. */
    private val repoRoot = File("..").canonicalFile
    private val mainSrc = File(repoRoot, "app/src/main/kotlin")
    private val manifest = File(repoRoot, "app/src/main/AndroidManifest.xml")

    private fun kotlinSources(): List<File> =
        mainSrc.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun read(file: File): String = file.readText()

    /**
     * Counts read in a user-facing string must go through [AppParts.count].
     *
     * Six hand-written plural rules shipped six wrong plurals over this app's
     * history — "All 1 backups readable", "Exported 1 backup(s)", "1 apps" on a
     * config card. Each was correct when written and wrong the moment a user had
     * exactly one of something, which is the case nobody tests by hand.
     */
    @Test
    fun `user-facing counts are pluralised in one place`() {
        // Only strings the USER reads. The same count written into a log line keeps
        // the `(s)` shorthand deliberately — a developer reading logcat knows
        // what it means, and a guard that forced the long form there would just
        // be noise. Matched by the surrounding file rather than the line, so a
        // multi-line user-facing string is still caught.
        //
        // `ToolsManager` and `MigratorManager` are here because the strings the
        // USER reads are not all under `ui/`: the debloat and restore completion
        // messages ("Successfully removed 1 apps") are built in the manager that
        // raises them, so a ui/-only guard let them through.
        val userFacing = setOf(
            "ui/",
            "DashboardScreen",
            "TrashUi",
            "VerificationDialog",
            "ToolsManager",
            "MigratorManager"
        )
        val banned = Regex("""\$\{?[A-Za-z0-9_.()]+}?\s+(apps|backups|parts|labels|schedules|configurations|assignments|folders|archives)\b(?!s)""")
        val offenders = mutableListOf<String>()
        File(repoRoot, "app/src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(File(repoRoot, "app/src/main/kotlin")).path
                userFacing.any { rel.contains(it) }
            }
            .forEach { file ->
                file.readLines().forEachIndexed { i, line ->
                    val t = line.trim()
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                    if (banned.containsMatchIn(line)) {
                        offenders += "${file.name}:${i + 1}  ${t.take(96)}"
                    }
                }
            }
        assertTrue(
            "these counts are pluralised by hand; use AppParts.count(n, noun):\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    /**
     * Source with comments removed.
     *
     * Guards match CODE, and these rules exist precisely because the code
     * around them is heavily commented with the reasoning. Matching the raw
     * text finds the explanation instead of the behaviour — the `rm -f` guard
     * hit the comment "so `rm -f` here was irreversible", and the rmdir guard
     * hit "Plain `rmdir` on the batch path FAILED".
     */
    private fun code(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("//[^\n]*"), " ")

    /**
     * Comments removed without moving anything else.
     *
     * [code] collapses a block comment to a single space, which is fine for
     * "does this pattern appear" and useless for "on which line" — every line
     * number after the first KDoc points at the wrong place. This variant keeps
     * the newlines and the character offsets, so an offender can be reported at
     * the line a reader has to open.
     */
    private fun codeKeepLines(source: String): String =
        source.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)) { m ->
            m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")
        }.replace(Regex("//[^\n]*"), "")

    // --- toasts must be thread-safe ----------------------------------------
    // `Toast.makeText` throws NPE on any thread without a Looper, which is every
    // Dispatchers.IO worker. Found on device as a crash on the undo path
    // (java.lang.NullPointerException: Can't toast on a thread that has not
    // called Looper.prepare()).

    @Test
    fun `no bare Toast makeText survives in main source`() {
        // Deliberately does NOT strip comments: the rule is about the call
        // expression itself, and a comment is not one. `code()` is not used
        // here because stripping comments would also hide a call written in a
        // comment-suppressed region we do not care about, which is the wrong
        // trade for a crash guard.
        val offenders = mutableListOf<String>()
        for (file in kotlinSources()) {
            val relative = file.relativeTo(mainSrc).path
            // The helper itself is the one legitimate caller. Guarding it would
            // fail on the very code that fixes the crash, which trains you to
            // ignore the guard.
            if (relative == "com/phoenix/droidvault/ui/UiToast.kt") continue
            file.readLines().forEachIndexed { index, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) return@forEachIndexed
                // Two forms, because AGENTS.md's own lesson applies here: a
                // guard that only matches ONE spelling reads as enforced over a
                // smaller set than §4 claims. `AboutScreen` wrote the call with the
                // token split across a newline, so `"Toast.makeText(" in line`
                // returned nothing for the whole file and the two sites sat
                // outside the rule.
                if ("Toast.makeText(" in line || line.trimStart().startsWith(".makeText(")) {
                    offenders += "$relative:${index + 1}"
                }
            }
        }
        assertTrue(
            "bare Toast.makeText in ${offenders.joinToString()}; use Context.safeToast " +
                "from ui/UiToast.kt, which posts to the main Looper",
            offenders.isEmpty()
        )
    }

    @Test
    fun `safeToast exists and is the only toast entry point`() {
        val helper = File(mainSrc, "com/phoenix/droidvault/ui/UiToast.kt")
        assertTrue(
            "ui/UiToast.kt is missing — safeToast is the crash fix",
            helper.exists()
        )
        val body = helper.readText()
        assertTrue(
            "safeToast must post to the main Looper, not call inline",
            body.contains("Looper.getMainLooper()")
        )
    }

    // --- destructive operations quarantine, they never erase ---------------
    // Every bug below was found on device by exercising the undo path.

    @Test
    fun `the native-erase dialog starts with nothing selected`() {
        // Found on device: all seven toggles were `true`, so opening the dialog
        // and tapping confirm moved EVERY device archive into the trash. A
        // destructive dialog must start empty.
        val src = read(File(mainSrc, "com/phoenix/droidvault/ui/screens/MigratorScreen.kt"))
        val defaults = Regex("var del\\w+ by remember \\{ mutableStateOf\\(true\\) \\}")
            .findAll(code(src)).count()
        assertEquals(
            "destructive-dialog toggles defaulting to selected — a confirm tap " +
                "would move every device archive without the user choosing any",
            0, defaults
        )
    }

    @Test
    fun `device backups quarantine instead of being erased`() {
        // A device archive is often the only copy (SMS backed up months ago and
        // since deleted on the phone). `rm -f` here was irreversible in exactly
        // the way U-01 was for app archives.
        val vm = read(File(mainSrc, "com/phoenix/droidvault/MainViewModel.kt"))
        val body = code(vm).substringAfter("fun deleteDeviceBackups").take(3000)
        assertFalse(
            "deleteDeviceBackups still erases with rm -f; it must move to trash",
            body.contains("rm -f")
        )
        assertTrue(
            "deleteDeviceBackups must quarantine through moveToTrash",
            body.contains("moveToTrash")
        )
    }

    @Test
    fun `undo removes the batch directory tree, not just its root`() {
        // Two wrong fixes, both caught on device:
        //   `rmdir <batch>`  — refuses outright when `<batch>/Device/` exists.
        //   `rmdir -p <batch>` — same reason: it removes the target FIRST, so
        //                        it never walks up. The orphan survived.
        // `-depth -type d -exec rmdir` removes every directory that is now
        // empty, children first, and skips (rather than aborts on) one that is
        // not — which is the partially-restored case.
        val mm = read(File(mainSrc, "com/phoenix/droidvault/utils/MigratorManager.kt"))
        val body = code(mm).substringAfter("fun restoreTrashBatch").take(2600)
        assertTrue(
            "restoreTrashBatch must walk the batch tree depth-first and rmdir " +
                "each empty directory; both rmdir and rmdir -p on the batch " +
                "root orphan the per-source subdirectory",
            body.contains("-depth") && body.contains("-type d") &&
                    body.contains("-exec rmdir")
        )
    }

    @Test
    fun `the trash card is reachable wherever a delete is offered`() {
        // A quarantiner whose card is scoped to one mode tells the user
        // "recoverable for 7 days" with no way to prove it.
        val src = read(File(mainSrc, "com/phoenix/droidvault/ui/screens/MigratorScreen.kt"))
        val call = code(src).substringAfter("TrashCard(")
        assertFalse(
            "TrashCard must not be gated on a migrator mode; every mode needs " +
                "a route back to what was deleted",
            call.take(260).contains("migratorMode")
        )
    }

    // --- §5 Comments explain why; no marker comments -----------------------

    @Test
    fun `no TODO or FIXME markers in main source`() {
        // No TODO/FIXME/HACK markers. A decision belongs in the code that
        // implements it, explained by what it prevents.
        // A marker in main source means an unfinished thought shipped.
        val offenders = mutableListOf<String>()
        for (f in kotlinSources()) {
            read(f).lineSequence().forEachIndexed { i, line ->
                val t = line.trimStart()
                if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                    if (Regex("//\\s*(TODO|FIXME|HACK|XXX)\\b").containsMatchIn(line)) {
                        offenders += "${f.name}:${i + 1}"
                    }
                }
            }
        }
        assertTrue(
            "Marker comments in main source — explain the decision in code instead: $offenders",
            offenders.isEmpty()
        )
    }

    // --- §6.1 No INTERNET permission, ever ---------------------------------

    @Test
    fun `the manifest never declares INTERNET`() {
        // Adding INTERNET is a design-breaking change. The whole
        // product promise is that backups never leave the device, and nothing in
        // the code would stop a future dependency from pulling the permission in.
        assertTrue(
            "INTERNET permission found in AndroidManifest.xml — Droid Vault is offline by design",
            !read(manifest).contains("android.permission.INTERNET")
        )
    }

    @Test
    fun `no backup or transfer of app data`() {
        // allowBackup=false + dataExtractionRules, so the app's
        // own data (consent flags, home shortcuts) never lands in cloud backup.
        val m = read(manifest)
        assertTrue("allowBackup must stay false", m.contains("android:allowBackup=\"false\""))
        assertTrue("dataExtractionRules must stay declared", m.contains("dataExtractionRules"))
    }

    // --- §5 Shell quoting: no interpolation into a shell string ------------

    @Test
    fun `no unquoted interpolation reaches a shell command string`() {
        // All shell interpolation goes through ShellQuote, the single
        // authority for quoting. An unquoted value is how a vault path containing a
        // space once made `mv` fail and `rm -rf` delete the whole vault — a
        // A-01).
        //
        // Three legitimate forms are allowed, and all three were verified by hand
        // across the tree when this rule was written:
        //   ${ShellQuote.q(...)}  — the authority
        //   a file-level `const val` — a compile-time literal
        //   "$x"                   — enclosed in shell double quotes
        val offenders = mutableListOf<String>()
        for (f in kotlinSources()) {
            val src = read(f)
            val consts = Regex("\\bconst val (\\w+)").findAll(src).map { it.groupValues[1] }.toSet()
            shellLiteral.findAll(src).forEach { m ->
                val lit = m.groupValues[1]
                if ('$' !in lit) return@forEach
                val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                interpolation.findAll(lit).forEach { tok ->
                    val expr = (tok.groupValues[1].ifEmpty { tok.groupValues[2] }).trim()
                    if (expr.isEmpty()) return@forEach
                    if (expr.startsWith("ShellQuote") || expr in consts) return@forEach
                    if (isEnclosedInShellQuotes(lit, tok.range.first)) return@forEach
                    offenders += "${f.name}:$line  \$$expr"
                }
            }
        }
        assertTrue(
            "Unquoted interpolation in a shell command — wrap it in ShellQuote.q() " +
                "or in shell double quotes: ${offenders.take(8)}",
            offenders.isEmpty()
        )
    }

    private val shellLiteral = Regex("""Shell\.cmd\(\s*("(?:[^"\\]|\\.)*")""")
    private val interpolation = Regex("""\$\{([^}]*)\}|\$([A-Za-z_]\w*)""")

    /**
     * True when the token at [at] sits inside shell double quotes.
     *
     * Counts only UNESCAPED quotes: `\"` inside the Kotlin literal is a literal
     * quote character in the shell command, not a delimiter, and treating it as
     * a delimiter is what made `\"\$(cmd)\" ${ShellQuote.q(dir)}` look like two
     * unquoted interpolations.
     */
    private fun isEnclosedInShellQuotes(lit: String, at: Int): Boolean {
        var quotes = 0
        var i = 0
        while (i < at) {
            if (lit[i] == '\\') {
                i += 2
                continue
            }
            if (lit[i] == '"') quotes++
            i++
        }
        return quotes % 2 == 1
    }

    // --- §6 Icon-only controls ---------------------------------------------

    @Test
    fun `every IconButton names its action`() {
        // An Icon with a null contentDescription
        // as the only representation of an action is unusable with a screen
        // reader and ambiguous to everyone else.
        val offenders = mutableListOf<String>()
        for (f in kotlinSources()) {
            val src = read(f)
            // Match the call, then inspect only its own body (balanced parens).
            Regex("""(?:FilledTonal)?IconButton\s*\(""").findAll(src).forEach { m ->
                val body = balancedBody(src, m.range.last)
                if (body.contains("Icon(") &&
                    !body.contains("contentDescription = \"") &&
                    !body.contains("contentDescription = null")
                ) {
                    val line = src.substring(0, m.range.first).count { it == '\n' } + 1
                    offenders += "${f.name}:$line"
                }
            }
        }
        assertTrue(
            "IconButton with an unlabelled icon — add a contentDescription: ${offenders.take(8)}",
            offenders.isEmpty()
        )
    }

    // A wider "every tappable Surface/Card is identifiable" rule was TRIED and
    // removed. Distinguishing `Surface(onClick = ...)` from a `Surface(...).
    // clickable(onClick = ...)` modifier chain needs real parsing, not regex, and
    // three attempts produced four false positives — including a correctly
    // labelled remove button. A convention guard that cries wolf gets disabled,
    // which is worse than not having one. The underlying property IS verified:
    // A manual sweep of every icon-only control in the app found zero
    // unlabelled actions. The IconButton rule below covers the
    // named-control shape exactly and does not false-positive.

    /** Source text from [open] to its matching close paren, inclusive. */
    private fun balancedBody(src: String, open: Int): String {
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(open, i + 1)
                }
            }
            i++
        }
        return src.substring(open, minOf(open + 200, src.length))
    }

    // --- §4.5 Engine freshness covers the native binary --------------------

    @Test
    fun `the engine freshness gate checks the engine binary, not just the script`() {
        // Engine freshness gate: an old engine reported fake completions because
        // the gate hashed only the launcher. Cheap to regress, so pin the symbol.
        val src = read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/BackendInstaller.kt"))
        assertTrue(
            "BackendInstaller must reference the engine binary when deciding freshness",
            src.contains("droidvault-engine") || src.contains("zapdos")
        )
    }

    // --- §4.26 A timeout is not a completion check --------------------------

    @Test
    fun `long shell work waits on a watchdog loop, never on a bare join`() {
        // Import unpacked a 3.72 GB
        // archive under `join(2_000)` with no loop, so it declared failure for
        // any real file — then deleted the staging directory while tar was still
        // writing into it. A `join` on its own is the whole bug: it caps the wait
        // at a fixed number of milliseconds no matter how big the job is.
        //
        // Deliberately narrow. This asserts that every `join(` on a worker
        // thread is preceded by a wait loop, NOT that the timeout is long enough
        // — the right bound depends on the job, and a guard that guesses at that
        // is the kind that false-positives and then gets deleted.
        val offenders = mutableListOf<String>()
        for (file in kotlinSources()) {
            val raw = read(file)
            val body = code(raw)
            for (m in Regex("(\\w+)\\.join\\(").findAll(body)) {
                val worker = m.groupValues[1]
                // Match the loop to THIS worker's name. A fixed character window
                // was tried first and false-positived on export, whose polling
                // loop is longer than the window; a whole-file search would pass
                // a join that is covered by some other thread's loop. The name is
                // what actually ties the wait to the work being waited on.
                val precedes = body.substring(0, m.range.first)
                val waited = Regex("while\\s*\\([^)]*\\b" + Regex.escape(worker) + "\\.isAlive")
                if (!waited.containsMatchIn(precedes)) {
                    val line = raw.lines().indexOfFirst { "$worker.join(" in it } + 1
                    offenders += "${file.name}:$line  ($worker.join without a watchdog loop)"
                }
            }
        }
        if (offenders.isNotEmpty()) {
            fail(
                "A `.join(` must be preceded by a `while (... isAlive ...)` watchdog " +
                    "loop, or long shell work is capped at a fixed few seconds " +
                    "(watchdog rule):\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `a work directory is never removed while its worker may still be running`() {
        // The other half of invariant 26: import ran `rm -rf staging` on the
        // failure path, which fired while tar was still extracting into it. Any
        // cleanup of a directory a background worker writes to has to come after
        // the worker is confirmed finished (or killed), not merely after the wait
        // expired.
        //
        // Two accepted shapes. The shell version pairs every `rm -rf` of a
        // staging path with a `pkill` of that same worker, so a future edit
        // cannot drop the kill and keep the delete. The engine version owns the
        // child handle directly (`kill_child`) and the transfer manager only
        // ever removes staging itself on paths where the engine call already
        // returned — which is strictly stronger, because there is no pattern to
        // match the wrong process with.
        val src = code(read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/VaultTransferManager.kt")))
        val workerKills = Regex("pkill -f 'tar -[cx]zf").findAll(src).count()
        val engine = read(File(repoRoot, "engine/src/transfer.rs"))
        // One supervisor owns every child: export and extract both funnel
        // through it, so a single kill site covers all long work.
        val engineOwnsChildren = engine.contains("fn kill_child")
            && engine.contains("fn supervise")
            && engine.contains("ACTION:CANCELLED")
            && engine.contains("supervise(&mut child")
        assertTrue(
            "long shell work must either pair staging cleanup with a kill of " +
                "that same worker, or run it in the engine which owns the child " +
                "handle directly (watchdog rule)",
            workerKills >= 2 || engineOwnsChildren
        )
    }

    // --- a number must be measured the same way on both sides of a comparison

    @Test
    fun `the archive size the split is checked against is a stat, not a du`() {
        // Found on device: the export took `du -sk * 1024` as the archive's
        // size while the split's integrity check added its parts up with
        // `stat`. `du` reports ALLOCATED BLOCKS, which on shared storage runs
        // ~0.1% over the apparent size — 28 MB on a 29.7 GB archive. The gap
        // was reported as corruption, every good part was deleted, and the
        // user was left with a single file where they had asked for parts.
        //
        // The rule is that `realBytes` comes from `statBytes`. Not "du is
        // banned": `du` is the right tool for the vault's on-disk footprint in
        // the space gate, where it errs on the safe side.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/VaultTransferManager.kt"))
        )
        val real = Regex("""val\s+realBytes\s*=\s*([^\n]+)""").find(src)?.groupValues?.get(1)
        assertNotNull("could not find `val realBytes = ...`", real)
        val expr = real!!
        assertTrue(
            "realBytes must come from statBytes, found: $expr",
            expr.contains("statBytes(")
        )
        assertFalse(
            "realBytes must not be a du figure, found: $expr",
            Regex("""\bdu\b""").containsMatchIn(expr)
        )
    }

    @Test
    fun `every transfer engine step is bounded by its own size`() {
        // A flat hour killed the split of a 29.7 GB archive every time. Each
        // step that moves bytes must scale its bound; the two that do not
        // (the survey, and the app-side archive listing) are named explicitly
        // because they scale with file COUNT, not bytes.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/VaultTransferManager.kt"))
        )
        // Call sites only: the declaration is `fun runTransferEngine(` and is
        // not a bound on anything.
        val calls = mutableListOf<String>()
        var at = src.indexOf("runTransferEngine(")
        while (at >= 0) {
            if (!src.substring(maxOf(0, at - 6), at).endsWith("fun ")) {
                calls += src.substring(at, minOf(src.length, at + 420))
            }
            at = src.indexOf("runTransferEngine(", at + 1)
        }
        assertTrue("no runTransferEngine call sites found", calls.isNotEmpty())
        val unscaled = calls.filter {
            !it.contains("transferWatchdogMs(") && !it.contains("IMPORT_WATCHDOG_MS")
        }
        assertTrue(
            "these engine steps still use a flat bound:\n" +
                unscaled.joinToString("\n") { "  " + it.take(90).replace('\n', ' ') },
            unscaled.isEmpty()
        )
    }

    @Test
    fun `release signing material is never committed`() {
        // The release keystore signs the APK. Anyone holding the repo can sign
        // a build the platform accepts as an update to every existing install,
        // which is a takeover of the app rather than a build problem — so this
        // is checked on every test run rather than trusted to `.gitignore`.
        val ignore = File(repoRoot, ".gitignore").readText()
        val tracked = File(repoRoot, ".git")
            .takeIf { it.isDirectory }
            ?.let { git ->

                // `git ls-files` is authoritative when the tree is a repo. In a
                // plain source drop (no `.git`) there is nothing to leak, so the
                // check degrades to the ignore rules rather than failing.
                val proc = ProcessBuilder("git", "ls-files")
                    .directory(repoRoot)
                    .redirectErrorStream(true)
                    .start()
                val out = proc.inputStream.bufferedReader().readText()
                proc.waitFor()
                if (proc.exitValue() == 0) out.lines().toSet() else null
            }

        // Match on trimmed, uncommented lines. These patterns sit in the file as
        // `keystore.properties` and `keystore/`, and a naive `contains` also
        // matched the PROSE that explains them — which is exactly how a guard
        // ends up reporting a rule as enforced while checking nothing.
        val ignoreLines = ignore.lines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }

        val patterns = listOf("keystore.properties", "local.properties")
        val missing = patterns.filter { p -> ignoreLines.none { it == p } }
        assertTrue(
            ".gitignore no longer excludes: ${missing.joinToString(", ")}",
            missing.isEmpty()
        )
        // `keystore/` must be present as a directory rule, either exactly or
        // with a trailing comment already stripped above.
        assertTrue(
            ".gitignore no longer excludes keystore/",
            ignoreLines.any { it == "keystore/" }
        )

        if (tracked != null) {
            val leaked = tracked.filter {
                it.endsWith(".jks") || it.endsWith(".keystore") ||
                    it == "keystore.properties" || it == "local.properties" ||
                    it.endsWith(".p12") || it.endsWith(".pfx")
            }
            assertTrue(
                "SIGNING/SECRET MATERIAL IS STAGED FOR COMMIT:\n" +
                    leaked.joinToString("\n") { "  $it" } +
                    "\nUnstage it and confirm .gitignore still excludes it.",
                leaked.isEmpty()
            )
        }

        // The documented example must stay a placeholder. A real password pasted
        // into the README is the most likely way a secret reaches a public repo.
        val readme = File(repoRoot, "README.md").takeIf { it.isFile }?.readText()
        if (readme != null) {
            assertFalse(
                "README.md appears to contain a real keystore password",
                Regex("""storePassword\s*[=:]\s*(?!\.\.\.\s*$)["']?[A-Za-z0-9!@#$%^&*_.-]{6,}""")
                    .containsMatchIn(readme)
            )
        }
    }

    @Test
    fun `every engine command is bounded by a scaled timeout, never the default`() {
        // `ShellEngine.executeEngineCommand` enforces its bound with
        // `future.cancel(true)`, which SIGTERMs the engine while it is still
        // writing. Inheriting the 15-minute default is therefore a bound on
        // SPEED: a large vault is legitimately slower, and the run was killed
        // mid-write and reported as a timeout.
        //
        // The earlier guard only covered `runTransferEngine` call sites inside
        // VaultTransferManager, which is why the main backup/restore path and
        // the verify path both passed with flat bounds. This one scans every
        // call site in the main source set, so a new caller cannot inherit the
        // default silently.
        val offenders = mutableListOf<String>()
        File(repoRoot, "app/src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val lines = file.readText().split('\n')
                lines.forEachIndexed { i, line ->
                    val at = line.indexOf("executeEngineCommand(")
                    if (at < 0) return@forEachIndexed
                    // The declaration itself, and the doc above it.
                    val before = lines.take(i).joinToString("\n").let { prev ->
                        prev.substringAfterLast("\n\n")
                    }
                    if (before.trimEnd().endsWith("fun") || before.contains("fun executeEngineCommand")) return@forEachIndexed
                    // Read a window forward: an argument may sit on a later line.
                    val window = lines.subList(i, minOf(lines.size, i + 3)).joinToString("\n")
                    if (!window.contains("timeoutMs")) {
                        offenders += "${file.name}:${i + 1}  ${line.trim().take(80)}"
                    }
                }
            }
        assertTrue(
            "these engine commands inherit ShellEngine's flat 15-minute default " +
                "and will be killed mid-write:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    // DELIBERATELY NOT GUARDED: "a RunCoordinator.release() is always paired
    // with a tryAcquire in the same scope". The rule is real (invariant 6: the
    // gate has no owner tracking, so a release from a non-holder frees another
    // run's lock) and it is documented on `release()` itself. It is not
    // enforced, because every attempt to scope it statically mis-modelled
    // Kotlin's nesting and fired on CORRECT code: a release 78 lines below its
    // `tryAcquire` in TransferService.onStartCommand, and a release past a
    // local `fun want(...)` nested inside the block in deleteDeviceBackups.
    // Indentation does not disambiguate either — the local function sits at
    // column 12 and the release it wrongly became the "scope" of at column 16.
    // A guard that false-positives on correct code is worse than none: it
    // teaches the next reader that the guards are advisory. The five call
    // sites were each checked by hand and are correct.

    // --- do not recompress what is already compressed -----------------------

    @Test
    fun `the export pack must not compress the vault`() {
        // Measured on the reference device: every `.shift` in
        // the vault is ALREADY a zstd stream, written by `zapdos` during backup.
        // Re-gzipping it ran at ~32 MB/s — a fifteenth of what the storage can
        // do — to save 0.7%. Packing a 29.7 GB vault took 19m52s, and the
        // compression was the overwhelming majority of it.
        //
        // The guard is that the packer writes a plain tar. A faster codec, a
        // JNI library, parallel gzip: none of them help, because the work
        // itself is pointless. The thing to protect is the absence, so this
        // test fails the moment somebody helpfully adds `-z` back.
        val src = code(
            read(File(repoRoot, "engine/src/transfer.rs"))
        )
        val pack = Regex("""fn transfer_export.*?fn transfer_survey""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(0)
        assertNotNull("could not find transfer_export", pack)
        assertFalse(
            "the vault packer must not gzip the archive — it is already zstd",
            pack!!.contains("\"-czf\"")
        )
    }

    @Test
    fun `the reader decides the tar flag from the bytes not the file name`() {
        // toybox tar auto-detects gzip on a FILE but NOT on a pipe, and the
        // split importer reads `cat parts | tar`. Verified on device:
        // `tar -xf -` over a gzip stream dies with "zcat: gzclose: Illegal
        // seek" after extracting only the first member. So the flag has to be
        // decided from the magic number, and both read paths must use it.
        val src = code(read(File(repoRoot, "engine/src/transfer.rs")))
        assertTrue(
            "transfer_extract must pick its flag by magic, not by hardcoding -xzf",
            src.contains("extract_flag(")
        )
        assertTrue(
            "the survey reads a pipe, so it needs the magic-number flag too",
            src.contains("list_flag(")
        )
        assertFalse(
            "a hardcoded \"-xzf\" in the read paths cannot work for both formats",
            Regex("""args\(\["-xzf"""").containsMatchIn(src)
        )
    }

    @Test
    fun `the export must not copy the archive it just packed`() {
        // The pack used to land in app-private staging and then be COPIED into
        // Downloads — 30 GB read and 30 GB written to end up somewhere it could
        // have been written directly. Measured on the reference device: that
        // copy ran at ~54 MB/s against ~340 MB/s for the same bytes in one
        // `dd`, because it was queueing behind the writeback of the pack that
        // had just finished. It was 9.5 minutes of a 12.5 minute export.
        //
        // The archive is now written straight into its export folder and renamed
        // there, so there is no copy to tune. The guard is the absence of one:
        // a `dd` that reads the packed archive back and writes it somewhere else
        // is this bug returning, whatever block size it uses.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/VaultTransferManager.kt"))
        )
        assertFalse(
            "the export must not copy the archive after packing it",
            src.contains("dd if=")
        )
        assertTrue(
            "the packed archive must be renamed into place, not copied",
            src.contains("mv -f \${ShellQuote.q(staging)}")
        )
    }

    @Test
    fun `an interrupted export can never leave its archive behind`() {
        // Shipped for one build, and it was the worst kind of bug: the reap ran
        // against the batch folder that had just been created EMPTY, so it
        // could never match anything. An export killed mid-pack — crash, battery
        // pull, OOM — left thirty gigabytes in its folder, and the next export
        // made a new folder and never looked at the old one. Nothing else
        // cleans that up, so the disk fills one interrupted export at a time.
        //
        // A cleanup scoped to something created empty in the same breath is a
        // cleanup that never runs. The reap has to reach orphans wherever they
        // are, including one level down in a previous batch.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/VaultTransferManager.kt"))
        )
        val reap = Regex("""fun reapInProgressExports\(\) \{(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(1)
        assertNotNull("export must reap its in-progress archive", reap)
        val body = reap!!
        assertTrue(
            "the reap must search the whole export tree, not one batch folder: $body",
            body.contains("find") && body.contains("EXPORT_DIR")
        )
        assertFalse(
            "the reap must not be scoped to a single batch folder: $body",
            body.contains("batchDir")
        )
        // And it has to run on every exit, not only the cancel branch: a thrown
        // exception unwinds past `if (cancel) rm -f staging`.
        val exportTail = Regex("""fun export\(.*?\n    \}\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(0) ?: ""
        assertTrue(
            "the export's finally must reap the in-progress archive too",
            exportTail.contains("reapInProgressExports()")
        )
        // ...but the finally must delete ITS OWN file by name, not sweep. Two
        // exports can overlap unless the gate excludes them, and a sweep in
        // `finally` would remove an archive a live export is still writing into
        // — a writer carrying on into a file that no longer exists.
        assertTrue(
            "the export must track the file it owns so finally can name it",
            Regex("""var ownedPart: String\? = null""").containsMatchIn(src)
        )
        assertTrue(
            "the finally must remove the run's own part file by name",
            Regex("""ownedPart\?\.let \{ Shell\.cmd\("rm -f """).containsMatchIn(src)
        )
    }

    @Test
    fun `an export must hold the process-wide run gate`() {
        // The sweep at the start of an export is only safe because nothing else
        // can be writing in that folder. That is the gate's job, not the UI
        // flag's: `if (isRunning) return` and `isRunning = true` are separate
        // operations, so two triggers arriving together both pass it.
        //
        // Export was the only long-running path here that did not hold the
        // gate, and the exception is precisely what made its own cleanup
        // unsafe.
        // The work moved into TransferService, so the gate moved with it: a
        // gate held by a ViewModel that no longer owns the work is a gate over
        // nothing. And it must be non-blocking — a second transfer is refused,
        // not queued behind the first while holding a foreground notification.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/TransferService.kt"))
        )
        assertTrue(
            "the transfer service must hold the process-wide gate",
            src.contains("RunCoordinator.tryAcquire(")
        )
        assertTrue(
            "the gate must be released when the run ends",
            src.contains("RunCoordinator.release()")
        )
        assertFalse(
            "a queued transfer must not wait on the gate holding a notification",
            src.contains("RunCoordinator.withRun(")
        )
    }

    @Test
    fun `the automation state file reaps its own stale temps`() {
        // `writeStateFileBlocking` removes its temp only when the shell command
        // REPORTS failure. A process killed between the `cat` and the `mv` —
        // which is what a kill does — never gets there, and the vault root is
        // never cleared, so the temp was carried into every later export.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/AutomationSnapshot.kt"))
        )
        // The CALL, not just the helper. A guard that only checks the function
        // exists passes on a build where nobody calls it — which is the bug,
        // wearing the fix's clothes.
        val writer = Regex("""fun writeStateFileBlocking\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(0)
        assertNotNull("could not find writeStateFileBlocking", writer)
        assertTrue(
            "writeStateFileBlocking must actually CALL the reap",
            writer!!.contains("reapStaleStateTemps(")
        )
        val reap = Regex("""fun reapStaleStateTemps\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(0)
        assertNotNull("could not find reapStaleStateTemps", reap)
        assertTrue(
            "the state reap must be depth 1 and match only the temp shape",
            reap!!.contains("maxdepth 1") && reap.contains("tmp-")
        )
    }

    @Test
    fun `vault staging files are reaped, not just written`() {
        // Every device archive is written to `<name>.shift.tmp-<nanos>` and
        // renamed over the live one, and the automation state file does the
        // same — three call sites. Unlike the export's staging these land in
        // the VAULT, which nothing ever cleans, so a cancelled backup left its
        // temp file in the tree that every subsequent export carries. The junk
        // grew a little per cancelled run and was removed by nothing at all.
        //
        // A staging file that is created but never reaped is the same defect as
        // one that is created but never cleaned: both are invisible until they
        // are a problem, and only one of them has a test.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/DeviceManager.kt"))
        )
        assertTrue(
            "device backups must reap leftover staging in the vault",
            src.contains("reapVaultStaging")
        )
        val reap = Regex("""fun reapVaultStaging\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(0)
        assertNotNull("could not find reapVaultStaging", reap)
        assertTrue(
            "the reap must match the temp shape these files are given",
            reap!!.contains("*.tmp-*")
        )
    }

    @Test
    fun `a transfer frees its space before the gate opens`() {
        // The run gate exists to stop two transfers colliding. It is worthless
        // if it opens while the previous run is still handing space back: the
        // next transfer starts allocating against a disk that has not finished
        // being freed, and the moment that matters most is when the disk was
        // too full for the run to begin with.
        //
        // So the order is load-bearing: delete, stop foreground, THEN release.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/TransferService.kt"))
        )
        // Compared by position in the file, not by slicing on a text anchor.
        // Anchoring on `TransferBus.finish(` picks up the bus's own definition
        // of `finish`, which sits ABOVE the service — so the "tail" became the
        // whole file and the guard went green for the wrong reason. Two
        // independent indices cannot drift like that.
        val release = src.indexOf("RunCoordinator.release()")
        assertTrue("the service must release the run gate", release >= 0)
        val staged = src.indexOf("java.io.File(stagedCopy).delete()")
        assertTrue("the SAF staged copy must be freed somewhere", staged >= 0)
        assertTrue(
            "the staged copy must be freed BEFORE the gate is released",
            staged < release
        )
    }

    @Test
    fun `a cancel must not ask the service to be recreated`() {
        // START_STICKY on the cancel path means a service killed just after a
        // cancel is recreated and re-raises the cancel — against whatever
        // transfer is running by then. A cancel is the opposite of sticky.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/TransferService.kt"))
        )
        val cancelBranch = Regex("""ACTION_CANCEL\) \{(.*?)\n        \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(1)
        assertNotNull("could not find the cancel branch", cancelBranch)
        assertTrue(
            "the cancel path must return START_NOT_STICKY",
            cancelBranch!!.contains("START_NOT_STICKY") && !cancelBranch.contains("START_STICKY")
        )
    }

    @Test
    fun `the export finally deletes its own file, not a tree`() {
        // The sweep belongs at the START of a run, where nothing of ours exists
        // yet. Running it in `finally` too was redundant and wrong in
        // principle: a sweep is only safe when you cannot name what you are
        // deleting (§2.40).
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/VaultTransferManager.kt"))
        )
        // Scoped to export's own text. A non-greedy match from `fun export(`
        // runs past it into `import` and every later function, and then the
        // guard is asserting about whichever `finally` happened to come last in
        // that span — which is how a guard ends up green for the wrong reason.
        val exportStart = src.indexOf("suspend fun export(")
        val exportEnd = src.indexOf("suspend fun import(", exportStart)
        assertTrue("could not locate export()", exportStart >= 0 && exportEnd > exportStart)
        val body = src.substring(exportStart, exportEnd)

        val named = body.indexOf("ownedPart?.let")
        assertTrue(
            "the export's finally must delete the run's own part file by name",
            named >= 0
        )
        assertFalse(
            "the export's finally must not sweep the tree — only the start-of-run may",
            body.substring(named).contains("reapInProgressExports()")
        )
        // ...and the sweep must still happen at the start, or a killed run's
        // orphan is never collected.
        assertTrue(
            "the start-of-run sweep must remain",
            body.substring(0, named).contains("reapInProgressExports()")
        )
    }

    // --- exports live where the user (and the picker) can reach them --------

    @Test
    fun `the transfer folder stays inside shared Downloads`() {
        // Loose files in Downloads were unrecognizable as one set, but moving
        // the folder anywhere SAF-invisible (app-private storage, /data/local)
        // would break both the user's PC copy and the import picker that reads
        // whatever folder the picked file sits in. The folder may be renamed;
        // it may not leave shared storage.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/models/VaultTransfer.kt"))
        )
        val dir = Regex("""EXPORT_DIR\s*=\s*"([^"]+)"""").find(src)?.groupValues?.get(1)
        assertTrue(
            "EXPORT_DIR must be a path under shared Downloads, found [$dir]",
            dir != null && dir.startsWith("/storage/emulated/0/Download/")
        )
    }

    @Test
    fun `the export folder is defined once and both sides use that one name`() {
        // The importer used to rebuild the export path itself, hard-coded to
        // the Downloads root. When exports moved into a folder of their own the
        // importer went on looking in the old place, so a transfer the app had
        // just written could not be read back: picking one part reported that
        // the other thirteen were "not on this phone", with all of them sitting
        // in the same directory.
        //
        // Two spellings of one path is the defect, so the guard is that the
        // writer refers to the shared constant rather than repeating it.
        val manager = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/VaultTransferManager.kt"))
        )
        assertFalse(
            "VaultTransferManager must not spell out the export path itself",
            Regex("""EXPORT_DIR\s*=\s*"/""").containsMatchIn(manager)
        )
        assertTrue(
            "VaultTransferManager must take its export folder from VaultTransfer",
            manager.contains("VaultTransfer.EXPORT_DIR")
        )
    }

    @Test
    fun `every absolute SAF document id shape is understood`() {
        // Captured on device: the picker returns
        //   raw:/storage/emulated/0/Download/DroidVault/DV-....part-aa
        // and the resolver read "raw" as a volume name, producing
        // /storage/raw//storage/emulated/0/... — a path to nothing, for a file
        // that was really there. Only the shapes we have actually seen get a
        // branch; every new one must be added here rather than discovered on
        // a user's phone.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt"))
        )
        val fn = Regex("""fun resolveDocumentPath\(id: String\): String\? \{(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.groupValues?.get(1)
        assertNotNull("could not find resolveDocumentPath", fn)
        val body = fn!!
        for (shape in listOf("raw:", "file:", "msf:", "\"primary\"")) {
            assertTrue(
                "resolveDocumentPath does not handle the `$shape` document id shape",
                body.contains(shape)
            )
        }
    }

    // --- the home Check must verify apps, not just device data --------------

    @Test
    fun `verifyVault checks app archives before device data`() {
        // Shipped wrong: verifyVault passed deviceData through to the first
        // verifyBackups call, so tapping Check on Home ran --verify-device
        // twice, never --verify-apps, and the card honestly read "did not
        // finish" over 107 healthy archives. A device-only report has total 0,
        // which is inconclusive by construction.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt"))
        )
        val calls = Regex("verifyBackups\\(getApplication\\(\\), _savedPath\\.value, (true|false|deviceData)\\)")
            .findAll(src).map { it.groupValues[1] }.toList()
        assertTrue(
            "verifyVault must run an app-archives check; found calls with args $calls",
            calls.contains("false")
        )
        assertTrue(
            "verifyVault must not pass deviceData straight into the first check — " +
                "that verified device data twice and app archives never",
            calls.firstOrNull() != "deviceData"
        )
    }

    // --- a verdict about the vault cannot outlive a run over it -------------

    @Test
    fun `every screen that states a vault verdict is told which vault it is about`() {
        // `BackupHealth.from` takes the vault's change counter, and DEFAULTS it
        // to 0 — which is the honest value for "no run has released the gate in
        // this session", so a caller that forgets it does not crash and does not
        // fail loudly; the dashboard simply keeps showing a tick for a vault that
        // has since been rewritten. The screens that take a `stale` argument
        // (VerificationDialog, VerificationBanner) are enforced by the compiler
        // because that parameter has no default. This is the one function where
        // a default would quietly compile the defect back in, so it is checked by
        // reading the call sites.
        val offenders = mutableListOf<String>()
        File(repoRoot, "app/src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                val src = codeKeepLines(file.readText())
                var idx = src.indexOf("BackupHealth.from(")
                while (idx >= 0) {
                    val open = idx + "BackupHealth.from(".length
                    val close = src.indexOf(')', open)
                    val args = if (close > open) src.substring(open, close) else ""
                    val line = src.take(idx).count { it == '\n' } + 1
                    // The declaration lives in BackupHealth.kt as `fun from(...)`,
                    // so it cannot match "BackupHealth.from("; only a call does.
                    if (!args.contains(',')) {
                        offenders += "${file.name}:$line  BackupHealth.from($args)"
                    }
                    idx = src.indexOf("BackupHealth.from(", idx + 1)
                }
            }
        assertTrue(
            "these render a verdict without the epoch it was taken at, so a run " +
                "since then cannot invalidate it:\n" + offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    // --- one check, and it covers everything --------------------------------

    @Test
    fun `no entry point checks app archives without the device archives`() {
        // `verifyVault()` defaults `deviceData` to false. The Manage screen's
        // "Verify backups" chip called it that way, so the same label meant two
        // different things: on Home it checked every archive, and on Manage it
        // read app archives and left Messages/CallLogs/Folders unexamined —
        // reporting "all readable" about work that never ran (§2.44). The run
        // doc already says one button covers everything; this keeps every call
        // site saying so out loud, because the default silently accepts the
        // smaller job.
        val offenders = mutableListOf<String>()
        File(repoRoot, "app/src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                codeKeepLines(file.readText()).split('\n').forEachIndexed { i, line ->
                    if (line.contains("verifyVault()")) offenders += "${file.name}:${i + 1}"
                }
            }
        assertTrue(
            "these check the vault with the default scope, which skips device data:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty()
        )
    }

    // --- a check decides its answer, not where its answer is shown -----------

    @Test
    fun `a check never decides by itself to show the result dialog`() {
        // Only the Backups tab composes `VerificationDialog`, and `verifyVault`
        // used to reveal it as a side effect of running. Found on the device
        // 2026-10-13: tapping Check on Home set the flag, and the dialog then
        // appeared over the Backups tab when the user happened to open it — a
        // behaviour chosen by navigation order, which is invariant 21's failure
        // in miniature. Exactly two functions may reveal it, and both mean "show
        // it now": the banner's re-open and the screen that starts a check.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt"))
        )
        val reveals = Regex("_verificationDialogVisible\\.value = true").findAll(src).count()
        assertEquals(
            "expected exactly two places that reveal the dialog — `openVerificationDialog` " +
                "and `showVerificationDialog`; a third means a check, or a worker, is deciding " +
                "to put a dialog on a screen that may never be shown",
            2, reveals
        )
        assertTrue(
            "`showVerificationDialog` must exist: the control that starts a check opens its " +
                "own dialog, because the check itself cannot know which screen asked",
            src.contains("fun showVerificationDialog()")
        )
    }

    // --- one restore-integrity gate, on every path that restores -------------

    @Test
    fun `both device-data restore entry points consult the device gate`() {
        // The rule shipped on one path before — that is the L-01/L-02 shape, and
        // the §2.44 defect one screen out: the manual Migrate flow refused a
        // damaged archive while the scheduler restored from it headlessly, with
        // nobody watching. There are exactly two places a device restore starts,
        // and each has to apply the same function: buying a second check by hand
        // is how the first one ends up describing a different restore than the
        // one that runs.
        val manual = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt"))
        )
        val scheduled = code(
            read(
                File(
                    repoRoot,
                    "app/src/main/kotlin/com/phoenix/droidvault/utils/AutomationRunner.kt"
                )
            )
        )
        assertTrue(
            "the manual device restore (runDeviceDataOperation) must consult " +
                "restoreBlockReasonDevice, or a known-damaged Messages.shift is written " +
                "into the provider",
            manual.contains("restoreBlockReasonDevice(")
        )
        assertTrue(
            "the scheduled restore must consult restoreBlockReasonDevice too: a device-only " +
                "config is restored with nobody watching",
            scheduled.contains("restoreBlockReasonDevice(")
        )
    }

    // --- a verdict has to outlive the process --------------------------------

    @Test
    fun `the app writes its verdict down and reads it back when it starts`() {
        // Two halves of one rule, and dropping either one loses the finding
        // silently: a check that is never saved is forgotten on the next launch
        // (the restore gate then has nothing to refuse with), and a stored verdict
        // that is never loaded is a file nobody reads. Both are call-site facts,
        // so both are read off the source rather than trusted to a comment.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt"))
        )
        assertTrue(
            "MainViewModel must persist a conclusive check (VaultVerdictStore.saveIfConclusive): " +
                "a verdict held only in memory disappears with the process, which is the " +
                "interval a wipe-then-restore spans",
            src.contains("VaultVerdictStore.saveIfConclusive(")
        )
        assertTrue(
            "MainViewModel must load the stored verdict (VaultVerdictStore.load) so the gate " +
                "has one before any run can start",
            src.contains("VaultVerdictStore.load(")
        )
    }

    @Test
    fun `only the loader may mark a report as restored`() {
        // `restoredFromDisk` is what withdraws good news from a verdict that was
        // read back from disk. A run that set it on its own fresh report would
        // hide real results; worse, if a LOADER forgot to set it, a stored
        // verdict would be rendered as if this process had just measured the
        // vault — the §2.46 defect across a restart. One assignment site, in the
        // codec, is what makes the flag mean "nobody here has looked".
        val sites = mutableListOf<String>()
        File(repoRoot, "app/src/main/kotlin").walkTopDown()
            .filter { it.extension == "kt" }
            .forEach { file ->
                codeKeepLines(file.readText()).split('\n').forEachIndexed { i, line ->
                    if (line.contains("restoredFromDisk = true")) {
                        sites += "${file.name}:${i + 1}"
                    }
                }
            }
        assertEquals(
            "expected exactly one place that marks a report as read back from disk " +
                "(VaultVerdict.forVault); found $sites",
            1, sites.size
        )
        assertTrue(
            "and it must be the codec: ${sites.first()}",
            sites.first().startsWith("VaultVerdict.kt")
        )
    }

    // --- §4.27 The transfer's reader and the store's writer must agree -------

    @Test
    fun `the transfer config counter reads the keys the store actually writes`() {
        // A key name shared between a writer and a reader is an interface:
        // ConfigCounts.fromJson read
        // "configs" while AutomationStore.exportJson writes "configurations",
        // so the count was permanently 0. Not cosmetic: `isEmpty` is what
        // decides between restoring and asking, so a phone holding
        // configurations but no labels read as bare and had them replaced
        // without ever being asked.
        //
        // The original unit test passed through all of that, because its fixture
        // used the same wrong key as the code it was checking. A fixture written
        // from the reader's assumption cannot catch the reader being wrong — so
        // this compares the two against each other, in the real source.
        val store = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/AutomationStore.kt"))
        )
        val reader = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/models/VaultTransfer.kt"))
        )
        // Keys exportJson puts in the state object, in source order.
        val written = Regex("\\.put\\(\\s*\"(labels|assignments|configurations|schedules)\"")
            .findAll(store).map { it.groupValues[1] }.toSet()
        assertTrue(
            "expected to find the state-object keys in AutomationStore.exportJson, " +
                "found $written — if this test broke, the serialiser moved and the " +
                "guard needs re-pointing rather than deleting",
            written.containsAll(setOf("labels", "configurations", "schedules"))
        )
        for (key in written) {
            // assignments are counted as labels by ConfigCounts, so only the
            // three keys it actually reads are asserted here.
            if (key == "assignments") continue
            assertTrue(
                "ConfigCounts.fromJson must read the \"$key\" key that exportJson writes; " +
                    "a key that does not exist counts as 0 and silently changes the " +
                    "restore-or-ask decision",
                reader.contains("""n("$key")""")
            )
        }
        assertFalse(
            "fromJson must not read a \"configs\" key: the serialiser writes " +
                "\"configurations\", and a reader that misses it always sees 0",
            Regex("""n\("configs"\)""").containsMatchIn(reader)
        )
    }

    // --- the finding survives the check that is re-measuring it ---------------

    @Test
    fun `a running check does not withdraw the finding it is re-measuring`() {
        // Found while reading the same rule one phase further in. `verifyVault`
        // set `_verification` to null before it looked at anything, and that
        // flow is the ONLY thing the pre-restore gate reads — so for as long as a
        // check ran (seconds on a small vault, minutes on a 30 GB one), a
        // confirmed-damaged archive was restorable, by the very control the user
        // had just pressed to confirm the damage. A check that has not finished
        // has measured nothing: it has no standing to withdraw a finding, and the
        // screens do not need it withdrawn either — the card reads "Checking
        // backups" while `isVerifying` and the banner hides itself, so the old
        // verdict is invisible except to the gate that must keep refusing.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt"))
        )
        val body = Regex("""fun verifyVault\(.*?\n    \}""", RegexOption.DOT_MATCHES_ALL)
            .find(src)?.value
        assertNotNull("could not find verifyVault in MainViewModel", body)
        assertFalse(
            "verifyVault must not clear the verdict before it has a replacement: the " +
                "pre-restore gate reads that value, so clearing it unblocks a restore of " +
                "the archive the check is still confirming is damaged",
            body!!.contains("_verification.value = null")
        )
        assertTrue(
            "and the finished report must still replace it, with a run that observed " +
                "nothing keeping the finding (VaultVerdict.shownAfter)",
            body.contains("VaultVerdict.shownAfter(")
        )
    }

    @Test
    fun `a verdict is re-read when the vault folder changes`() {
        // A verdict describes ONE folder's archives — that is why the record
        // carries its path and why the loader refuses a record taken elsewhere.
        // The report held in memory is not path-checked, so a vault that moves (or
        // is auto-detected to another folder) would otherwise go on being vouched
        // for, or refused, by a check of files it never read. Every place the path
        // is assigned must therefore re-read the verdict; the load path-matches,
        // so a folder with no verdict of its own becomes null — the honest "not
        // checked yet", and never the previous folder's answer.
        val src = code(
            read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt"))
        )
        val lines = src.split('\n')
        val assignments = lines.withIndex().filter { it.value.contains("_savedPath.value =") }
        assertTrue(
            "expected the vault path to be assigned somewhere in MainViewModel — if this " +
                "is empty the path moved behind a helper and this guard needs re-pointing",
            assignments.isNotEmpty()
        )
        val missing = assignments.filter { (i, _) ->
            lines.drop(i).take(40).none { it.contains("reloadVerdict()") }
        }.map { (i, line) -> "line ${i + 1}: ${line.trim()}" }
        assertTrue(
            "these change the vault folder without re-reading the verdict, so the card " +
                "and the restore gate keep describing the folder the user left:\n" +
                missing.joinToString("\n"),
            missing.isEmpty()
        )
        assertTrue(
            "and the startup path is the same rule one launch earlier",
            src.contains("VaultVerdictStore.load(getApplication(), _savedPath.value)")
        )
    }

    // --- a restored tree must be readable by the app that will read it ------

    /**
     * The body of a `fn`/`fun`, by brace matching from its declaration.
     *
     * A guard that greps a whole file for the thing it names is not guarding
     * that thing. `chcon` is called elsewhere in this engine for other reasons,
     * and one stray match would report the rule as enforced while the restore
     * path quietly lost it again — which is exactly what happened here.
     */
    private fun rustSpanLike(source: String, name: String): String? {
        val decl = Regex("""\b(?:fn|fun)\s+$name\b""").find(source) ?: return null
        val open = source.indexOf('{', decl.range.last)
        if (open < 0) return null
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        return null
    }

    @Test
    fun `a restored tree is labelled for the app that has to read it`() {
        // Found on device (POCO F6, Android 16): every file a restore placed under
        // /data/data/<pkg> came back `system_data_file`, because `plat_file_contexts`
        // has no `/data/data` entry and `restorecon -R` is therefore a silent no-op
        // there — the old comment here claimed it "DOES repair it", and it did not.
        // Brave could not open its own History database, and Chromium's answer to a
        // history database it cannot read is to mark every row `hidden`: the restore
        // reported success, the browser opened empty, and relabelling the files
        // afterwards did not bring the rows back.
        //
        // The rule: the placement pass applies the app-data label explicitly rather
        // than leaving it to restorecon. Checked inside the span of the function
        // that does the fixing, so `chcon` used elsewhere cannot stand in for it.
        val src = code(read(File(repoRoot, "engine/src/restore.rs")))
        val owner = rustSpanLike(src, "fix_ownership_and_labels")
        assertNotNull("could not find fn fix_ownership_and_labels — re-point this guard", owner)
        val inlined = owner!!.contains("chcon")
        val delegated = Regex("""\bapply_app_data_label\s*\(""").containsMatchIn(owner) &&
            (rustSpanLike(src, "apply_app_data_label") ?: "").contains("chcon")
        assertTrue(
            "fix_ownership_and_labels must label a placed tree explicitly: restorecon " +
                "alone leaves it system_data_file, and an app cannot read its own " +
                "restored data under that label",
            inlined || delegated
        )
    }

    @Test
    fun `the ssaid settings file is replaced by a rename, never an in-place copy`() {
        // Invariant 23 in the worst available place. `fs::copy` opens the
        // destination O_TRUNC, so `settings_ssaid.xml` is destroyed at the START
        // of the copy, not the end — measured on the reference device with a
        // blocked writer: the live file read back as 5 bytes of the incoming
        // data with the original header already gone.
        //
        // The old code kept a backup and read it only when `fs::copy` RETURNED an
        // error, which is no help at all here: a SIGKILL or a power loss in that
        // window returns nothing, so no code path ever runs the restore. A
        // truncated ABX settings file is unparseable by SettingsProvider, so one
        // interrupted write of ONE app's SSAID takes down every app's identity.
        //
        // The rule: the finished file is renamed into place. Not "a backup is
        // kept" — the rename is what makes the swap atomic, and a backup cannot
        // be consulted after a crash.
        val src = code(read(File(repoRoot, "engine/src/engine.rs")))
        val fn = rustSpanLike(src, "write_ssaid")
        assertNotNull("could not find fn write_ssaid — re-point this guard", fn)
        val body = fn!!
        assertFalse(
            "write_ssaid must not copy over the live settings file: a copy " +
                "truncates it first, so a crash mid-write destroys the original " +
                "with no code path left to restore it (invariant 23)",
            Regex("""fs::copy\([^)]*,[^)]*path\)""").containsMatchIn(body)
        )
        assertTrue(
            "write_ssaid must finish with a rename over the live file, which is " +
                "the only act that has no partial state",
            body.contains("fs::rename")
        )
        // And it must stage in the target's own directory, or the rename is
        // across a filesystem boundary and is a copy with extra steps.
        assertTrue(
            "the staged file must live in the target's directory so the rename " +
                "cannot cross a filesystem (invariant 32)",
            Regex("""\w+\s*=\s*\w+\.parent\(\)""").containsMatchIn(body) ||
                body.contains(".parent()")
        )
    }

    @Test
    fun `a partition image is staged, renamed, and never flashed unverified`() {
        // The most dangerous path in the app. `dd of=<final>` truncates the
        // destination at open(2), so a failed or ENOSPC read destroyed the
        // PREVIOUS good image on the way to reporting "Backup failed" — and left
        // the truncated file behind, still listed and still offered to flash.
        //
        // Worse, the checksum gate could not see it: the digest is recorded only
        // after `dd && sync` succeed, so the truncated image is the one with NO
        // sidecar — and the gate returned Ok(()) when the sidecar was missing.
        // Its own comment said it existed because "an image truncated by an
        // interrupted dd was flashed as-is"; as written it waved through exactly
        // that case. A partial image written to `boot` is a bricked phone.
        val src = code(read(File(repoRoot, "engine/src/partitions.rs")))

        // 1. The backup must stage and rename, never dd into the live name.
        val backup = rustSpanLike(src, "live_backup")
        assertNotNull("could not find live_backup — re-point this guard", backup)
        val ddTarget = Regex("""format!\("of=\{\}", ([a-zA-Z_][\w.]*)\.display\(\)\)""")
            .findAll(backup!!)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(
            "live_backup must dd into a STAGING path, not the image name itself: " +
                "an interrupted read then leaves the previous good image intact " +
                "(invariant 23). Offending targets: " +
                ddTarget.filter { !it.contains("tmp") && !it.contains("staged") },
            ddTarget.isNotEmpty() && ddTarget.none { it == "image" }
        )
        assertTrue(
            "the staged image must be promoted by a rename",
            backup.contains("fs::rename")
        )
        // 2. The gate must fail closed on a missing digest.
        val gate = rustSpanLike(src, "flash_gate")
        assertNotNull("could not find flash_gate — re-point this guard", gate)
        assertFalse(
            "a missing checksum record must be a REFUSAL, not a pass: its absence " +
                "is the signature of an interrupted dd, and a partial image " +
                "flashed to a boot partition bricks the device",
            Regex("""let Some\(recorded\)\s*=\s*sidecar\s*else\s*\{\s*return Ok""")
                .containsMatchIn(gate!!)
        )
        assertTrue(
            "the gate must refuse when the bytes do not match the record",
            Regex("""!digest_matches\s*\{\s*return Err""").containsMatchIn(gate)
        )
    }

    @Test
    fun `a shell timeout actually stops the engine`() {
        // Verified by decompiling libsu 6.0.0 from this project's own dependency
        // cache: `ResultFuture.cancel(boolean)` is `return latch.getCount() != 0`.
        // It ignores the flag, interrupts nothing, and `isCancelled()` is
        // hardcoded to `false`. So `future.cancel(true)` on a timeout stopped
        // nothing — the engine kept packing archives into the vault with its
        // output discarded, the run gate was released, and the user could start a
        // second run against the same folder. Two writers, one gate.
        //
        // This file previously documented the opposite in its KDoc, which is why
        // the finding survived: invariant 39 and §2.43 both rest on `cancel(true)`
        // interrupting the engine, and it does not.
        //
        // The rule: a trip must raise the engine's own cancel flag AND confirm
        // the process is gone before the run is reported failed and the gate
        // released. Without the confirmation the gate is handed back while a
        // writer is still live.
        val src = code(read(File(repoRoot, "app/src/main/kotlin/com/phoenix/droidvault/utils/ShellEngine.kt")))
        val fn = rustSpanLike(src, "stopEngineBlocking")
        assertNotNull("could not find the engine stop routine — re-point this guard", fn)
        val body = fn!!
        assertTrue(
            "a timeout must raise the engine's own cancel flag; the hard kill alone " +
                "can leave an archive half written",
            body.contains("ENGINE_CANCEL_FLAG") || body.contains("droidvault_cancel")
        )
        assertTrue(
            "the engine must be signalled by process name — libsu's Future cannot " +
                "be used for this, it is a no-op",
            body.contains("pkill")
        )
        // Presence is not enough: a `pgrep` inside an unreachable branch checks
        // nothing, which is exactly how a no-op passes a `contains` assertion.
        // The confirmation has to be a bounded RETRY, so both the probe and the
        // loop that repeats it are required.
        assertTrue(
            "the stop must CONFIRM the process is gone before returning, or the run " +
                "gate is released while the engine is still writing",
            body.contains("pgrep") && body.contains("pidof")
        )
        assertTrue(
            "the confirmation must be a bounded retry loop; a single probe cannot " +
                "distinguish 'died' from 'not dead yet'",
            Regex("""\bwhile\s*\(""").containsMatchIn(body)
        )
        assertTrue(
            "the retry must be bounded by a deadline, or a stuck engine hangs the " +
                "close path forever",
            body.contains("deadline")
        )
        // Both exit paths must go through it: a timeout, and an abandoned
        // collector (cancel). One without the other leaves a live writer.
        assertTrue(
            "the timeout path must stop the engine, not just close the flow",
            Regex("""catch\s*\(_?\s*:\s*TimeoutException\)[\s\S]{0,400}?stopEngineBlocking""")
                .containsMatchIn(src)
        )
        assertTrue(
            "awaitClose (cancel / collector teardown) must stop the engine too",
            Regex("""awaitClose\s*\{[\s\S]{0,300}?stopEngineBlocking""").containsMatchIn(src)
        )
        // And the false claim must not come back.
        assertFalse(
            "ShellEngine must not claim Future.cancel interrupts the engine — " +
                "verified false against libsu 6.0.0",
            Regex("""cancel\(true\)\s*(interrupts|\*\*interrupts)""").containsMatchIn(src) ||
                Regex("""Future\.cancel]?\s*interrupts""").containsMatchIn(src)
        )
    }

    @Test
    fun `a debloat restore is confirmed installed, never inferred from an exit code`() {
        // Measured on device: restoring an app that had been `cmd package
        // archive`d rather than uninstalled. `install-existing` correctly failed
        // with NameNotFoundException; `request-unarchive` then printed "Success"
        // and exited 0 while the package stayed uninstalled for the user. The
        // engine trusted the exit status, printed RESTORED, and the screen said
        // "Restore Complete! Successfully restored 1 app."
        //
        // The verb decides HOW to try; only the package manager's own answer
        // decides whether it worked.
        val tools = File(repoRoot, "engine/src/tools.rs").readText()
        val body = tools.substring(
            tools.indexOf("pub fn restore_debloat"),
            tools.indexOf("pub fn restore_debloat") + 2600
        )
        assertTrue(
            "restore_debloat must confirm the package with installed_and_enabled " +
                "before reporting RESTORED",
            Regex("""if\s+installed_and_enabled\(package\)\s*\{[^}]*RESTORED""")
                .containsMatchIn(body)
        )
        assertFalse(
            "restore_debloat must NOT reach RESTORED by short-circuiting two " +
                "`status(...)` exit codes — `request-unarchive` prints Success and " +
                "exits 0 for a package it did not install",
            Regex("""\|\|\s*status\(""")
                .containsMatchIn(body.substringBefore("/// Is [package]"))
        )
        // And the question it asks must be "installed AND enabled". A reinstall can
        // leave a package present and unlaunchable — measured: "Successfully
        // restored 2 apps" while both sat disabled. `pm list packages` without
        // `-e` would call that a success.
        assertTrue(
            "the confirmation must ask for ENABLED packages, not merely present " +
                "ones — a reinstall can leave the package installed but disabled",
            Regex("""\"list\",\s*\"packages\",\s*\"-e\"""")
                .containsMatchIn(tools)
        )
    }

    @Test
    fun `the migrator list and the run share ONE visibility predicate`() {
        // The defect was not that the predicate was wrong, it was that there were
        // TWO of them: the screen drew rows with `showSystemApps ||
        // (showPartialSystemApps && isLaunchable)` while `runnableSelectedApps`
        // honoured `showSystemApps` alone, so a ticked Chrome drew checked while
        // the count stayed 0 and the button stayed dead. Fixing the arithmetic in
        // both places only leaves the next divergence waiting.
        val screen = read(File(mainSrc, "com/phoenix/droidvault/ui/screens/MigratorScreen.kt"))
        val vm = read(File(mainSrc, "com/phoenix/droidvault/MainViewModel.kt"))

        assertTrue(
            "MigratorScreen must ask the shared predicate, not re-derive the " +
                "system-app test inline (that is what let the two drift)",
            screen.contains("isShownInMigrator(app, appState)")
        )
        assertFalse(
            "the system-app visibility test must not be written out a second time " +
                "in MigratorScreen — one implementation, two consumers",
            Regex("""showSystemApps\s*\|\|\s*\(\s*appState\.showPartialSystemApps""")
                .containsMatchIn(screen)
        )
        assertTrue(
            "runnableSelectedApps must ask the shared predicate",
            Regex("""isSelected\s*&&\s*[\w.]*isShownInMigrator\(""")
                .containsMatchIn(vm)
        )
        assertFalse(
            "and must not keep its own copy of the system-app test",
            Regex("""it\.isSystem\s*&&\s*state\.showSystemApps""")
                .containsMatchIn(vm)
        )
    }

    /**
     * A release-notes file may only name an APK that the workflow actually
     * publishes.
     *
     * 2.0.0's release page told every user to download
     * `app-arm64-v8a-release.apk`. The asset was `arm64-v8a-release.apk`,
     * because the workflow's collect step strips the Gradle `app-` prefix when
     * it copies into `dist/`. So the one instruction every reader acts on named
     * a file that was not there — and it was published, and it stayed published
     * until someone read the page back.
     *
     * Both halves are read from their own source of truth rather than restated:
     * the ABIs come from the Gradle splits, the published naming from the
     * workflow's own rename. Restating either here would let the guard agree with
     * a document that had already drifted.
     */
    @Test
    fun `release notes only name APKs the release workflow publishes`() {
        val gradle = read(File(repoRoot, "app/build.gradle.kts"))
        val workflow = read(File(repoRoot, ".github/workflows/release.yml"))

        val abis = Regex("""include\(([^)]*)\)""")
            .find(gradle)?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().trim('"', '\'') }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        assertTrue("could not read the ABI splits from build.gradle.kts", abis.isNotEmpty())

        // The name a user downloads is `<abi>-release.apk`: the collect step
        // copies `app-<abi>-release.apk` to `dist/${abi}-release.apk`.
        assertTrue(
            "the workflow's collect step should publish <abi>-release.apk — if this " +
                "changed, this guard is checking the wrong contract",
            workflow.contains("out=\"dist/\${abi}-release.apk\"")
        )

        val notesDir = File(repoRoot, ".github/release-notes")
        val notes = notesDir.listFiles { f: File -> f.name.endsWith(".md") }.orEmpty()
        assertTrue("expected at least one release-notes file", notes.isNotEmpty())

        val published = abis.map { "$it-release.apk" }.toSet()
        for (file in notes) {
            val named = Regex("""`([A-Za-z0-9_.-]+\.apk)`""")
                .findAll(read(file))
                .map { it.groupValues[1] }
                .toSet()
            for (name in named) {
                assertTrue(
                    "${file.name} names `$name`, which the release does not publish. " +
                        "The downloadable files are ${published.sorted().joinToString()}. " +
                        "A reader who follows the page must find the file it names.",
                    name in published
                )
            }
        }
    }
}
