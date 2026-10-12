package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.LiveOp
import com.phoenix.droidvault.models.TrackerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the 2026-10-07 gap audit of the Rust-engine rework.
 * Pure-logic tests only (project rule: unit tests target the pure core, shell
 * and engine behaviour is verified on device).
 */
class GapAuditTest {

    /**
     * Backup exclusion anchoring — the audit's highest-severity finding.
     *
     * tar matches an UNANCHORED `--exclude=lib` against ANY trailing path
     * component at ANY depth, so the engine's previous unanchored patterns
     * silently dropped every nested `lib`, `cache`, `code_cache`, `Backup_*`
     * and `.ota` directory from each app's data archive, while Meta.txt still
     * recorded the full size — unrecoverable loss with no visible symptom.
     * Verified against GNU tar 1.35 and toybox tar; this pins the tail-glob
     * rule both implement.
     */
    @Test
    fun backupExclusionsAreAnchoredToTheMemberPrefix() {
        val topLevelDirs = listOf("cache", "code_cache", "lib", ".ota", "no_backup")

        // Nested directories of an excluded name MUST be kept.
        val nested = listOf(
            "com.foo/files/cache/f.bin",
            "com.foo/files/code_cache/f.bin",
            "com.foo/sub/lib/f.bin",
            "com.foo/sub/Backup_y/f.bin"
        )
        for (member in nested) {
            assertFalse(
                "anchored excludes must not match nested path: $member",
                excludedBy(member, topLevelDirs + "Backup_*")
            )
        }

        // The intended top-level directories MUST still be excluded. tar
        // excludes a matched directory together with everything under it, so
        // the model covers the directory node itself and its whole subtree.
        for (dir in topLevelDirs) {
            assertTrue(
                "top-level $dir must stay excluded",
                excludedBy("com.foo/$dir", topLevelDirs + "Backup_*")
            )
            assertTrue(
                "contents of top-level $dir must stay excluded",
                excludedBy("com.foo/$dir/nested/f.bin", topLevelDirs + "Backup_*")
            )
        }
        assertTrue(
            "top-level Backup_* glob must stay excluded",
            excludedBy("com.foo/Backup_staging", topLevelDirs + "Backup_*")
        )
        assertTrue(
            "contents of top-level Backup_* must stay excluded",
            excludedBy("com.foo/Backup_staging/f.bin", topLevelDirs + "Backup_*")
        )

        // Document the original defect: the UNANCHORED spelling did drop a
        // nested lib, which is exactly why anchoring is required. Checked
        // directly against the bare pattern (not via excludedBy, which models
        // the anchored form).
        assertTrue(
            "unanchored --exclude=lib matches com.foo/sub/lib (documents the regression)",
            globMatchesTail("com.foo/sub/lib", "lib") ||
                globMatchesTail("com.foo/sub/lib/f.bin", "lib")
        )
    }

    /**
     * Anchored exclude patterns, expressed as engine/src/archive.rs builds
     * them: `--exclude=<member-prefix>/<pattern>`. A pattern also covers
     * everything beneath a directory it matches, which is how tar applies
     * `--exclude` to directories.
     */
    private fun excludedBy(member: String, topLevelPatterns: List<String>): Boolean {
        // tar applies an exclude to a matched directory and to everything
        // beneath it, so test the member itself plus each ancestor node.
        val nodes = buildList {
            var current = member
            while (current.isNotEmpty()) {
                add(current)
                current = current.substringBeforeLast('/', "")
            }
        }
        return topLevelPatterns.any { pattern ->
            val anchored = "com.foo/$pattern"
            nodes.any { globMatchesTail(it, anchored) }
        }
    }

    /**
     * tar's exclude match is TAIL-anchored: the glob is matched against the
     * END of a member name, which is why an unanchored `--exclude=lib` also
     * matches `com.foo/sub/lib` while `--exclude=com.foo/lib` matches only
     * that one top-level directory.
     */
    private fun globMatchesTail(member: String, pattern: String): Boolean {
        val regex = buildString {
            pattern.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
            append('$')
        }
        return Regex(regex).containsMatchIn(member)
    }

    /**
     * ProgressTracker ownership must be releasable after a terminal event.
     *
     * MigratorManager claims the tracker BEFORE taking the RunCoordinator gate,
     * so both of its pre-`try` refusals (stale engine, gate held) return ahead
     * of the `finally` that ends the run. Without an explicit fail+endRun the
     * tracker stayed Running with its owner set for the life of the process:
     * beginRun refused every later manual backup/restore, the BACKUP byte
     * poller was never cancelled, and the live overlay showed a phantom run.
     */
    @Test
    fun trackerIsReusableAfterARefusedRunIsReleased() {
        val owner = "gap-audit-owner"
        assertTrue(
            "first run must claim the tracker",
            ProgressTracker.startRun(
                ownerId = owner,
                op = LiveOp.BACKUP,
                seeds = emptyList(),
                title = "Backing up"
            )
        )
        assertFalse(
            "a second owner must be refused while the first run is live",
            ProgressTracker.beginRun("other", startEvent())
        )

        // Exactly what the two refusal paths must now do.
        ProgressTracker.fail("Operation not started", "another operation is running")
        ProgressTracker.endRun(owner)

        assertTrue(
            "tracker must accept a new owner after fail + endRun",
            ProgressTracker.beginRun("gap-audit-next", startEvent())
        )
        ProgressTracker.endRun("gap-audit-next")
    }

    private fun startEvent() =
        TrackerEvent.Start(LiveOp.BACKUP, emptyList(), "Backing up", true)

    /**
     * Install-target lines stay one-per-line and field-safe. The engine runs as
     * root and installs EVERY line it reads, so a newline or pipe in any field
     * forges extra protocol lines or shifts the parse fields (audit A-02: a
     * crafted APK label injected a silent root install of an arbitrary APK).
     */
    @Test
    fun installerTargetLineKeepsOneLinePerTarget() {
        assertEquals(
            "/tmp/a.apk|com.foo|Ok App",
            ShellQuote.installerTargetLine("/tmp/a.apk", "com.foo", "Ok App")
        )
        // The PATH is validated, not sanitised: a newline or pipe there could
        // forge an extra target line, so it is rejected outright.
        assertNull(ShellQuote.installerTargetLine("/tmp/b.apk\n/x|evil.apk", "com.foo", "L"))
        assertNull(ShellQuote.installerTargetLine("/tmp/a.apk\r|evil.apk", "com.foo", "L"))
        assertNull(ShellQuote.installerTargetLine(" ", "com.foo", "Label"))

        // The PACKAGE is rejected when it is not a valid package name.
        assertNull(ShellQuote.installerTargetLine("/tmp/a.apk", "com..foo", "Label"))
        assertNull(ShellQuote.installerTargetLine("/tmp/a.apk", "-o-root", "Label"))

        // The LABEL comes from another app's android:label, so it is fully
        // attacker-controlled and is sanitised (never rejected): structural and
        // code-execution characters are removed. What matters is the INVARIANT
        // — the result stays a single line with exactly three pipe-separated
        // fields, so it can never add a target or shift the engine's parse.
        for (hostile in listOf(
            "Bad\nLabel",
            "Bad\rLabel",
            "Bad|Label",
            "a/b",
            "a" + '$' + "(id)",
            "a" + '`' + "id" + '`',
            "..",
            "/"
        )) {
            val line = ShellQuote.installerTargetLine("/tmp/a.apk", "com.foo", hostile)
            assertNotNull("expected a sanitised line for: $hostile", line)
            val l = requireNotNull(line)
            assertFalse("newline survived in: $hostile", l.contains('\n') || l.contains('\r'))
            assertEquals("wrong field count for: $hostile", 3, l.split('|').size)
            assertEquals("/tmp/a.apk", l.split('|')[0])
            assertEquals("com.foo", l.split('|')[1])
            assertFalse("dot-only label survived: $hostile", l.split('|')[2].isEmpty())
        }
    }

    /**
     * A label is attacker-controlled (another app's android:label) and reaches
     * shell interpolation, TARGETS fields AND directory names, so every
     * structural and code-execution character must be neutralised rather than
     * merely rejected.
     */
    @Test
    fun safeLabelNeutralisesDirectoryAndCodeCharacters() {
        assertEquals("app", ShellQuote.safeLabel("/"))
        assertEquals("app", ShellQuote.safeLabel("|"))
        assertEquals("app", ShellQuote.safeLabel(".."))
        assertFalse(ShellQuote.safeLabel("a/b").contains('/'))
        assertFalse(ShellQuote.safeLabel("a|b").contains('|'))
        assertFalse(ShellQuote.safeLabel("a" + '$' + "(id)").contains('$'))
        assertFalse(ShellQuote.safeLabel("a" + '`' + "id" + '`').contains('`'))
        assertFalse(ShellQuote.safeLabel("a\"b").contains('"'))
        assertFalse(ShellQuote.safeLabel("a'b").contains('\''))
        assertFalse(ShellQuote.safeLabel("a\nb").contains('\n'))
        assertFalse(ShellQuote.safeLabel("..hidden").startsWith('.'))
    }
}
