package com.phoenix.droidvault

import com.phoenix.droidvault.DroidVaultApp
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bound on the root shell, and the startup race around it.
 *
 * Both are the same shape as §2.50 and §2.51: a safety net whose behaviour was
 * assumed. Here it is a TIMEOUT — and a timeout is a statement about speed, not
 * about whether the thing you are waiting for ever arrives.
 */
class RootAccessStartupTest {

    @Test
    fun `the_root_shell_bound_is_long_enough_for_a_human_to_answer_a_dialog`() {
        // At 10s — half of libsu's own 20s default — the sequence was a trap.
        // Verified by decompiling libsu 6.0.0: `BuilderImpl.start()` tries
        // `su --mount-master`, then `su`, then falls back to `sh`, each bounded
        // by this number. A user slower than 10s had that dialog DISCARDED, was
        // shown a second, and if slow again was told "Droid Vault needs root"
        // while Magisk was holding the grant.
        assertTrue(
            "the bound is on human reaction time, not on anything technical: " +
                "${DroidVaultApp.ROOT_SHELL_TIMEOUT_SECONDS}s must exceed the time to " +
                "read a Magisk permission dialog and decide",
            DroidVaultApp.ROOT_SHELL_TIMEOUT_SECONDS >= 45
        )
    }

    @Test
    fun `a_longer_bound_cannot_slow_down_the_no_root_case`() {
        // The reason a generous bound is safe: the timeout only bites when `su`
        // EXISTS and is waiting for a human. When Magisk is absent the attempt
        // fails immediately, so "no root" is still reported at once. This is why
        // the fix does not trade one bad case for another.
        val src = File("src/main/kotlin/com/phoenix/droidvault/DroidVaultApp.kt")
            .readText()
        assertTrue(
            "sanity: the builder must not be configured to block before trying `su`",
            !src.contains("setInitializers") || src.contains("Shell.Builder.create()")
        )
    }

    @Test
    fun `the_shell_is_not_destroyed_while_another_startup_path_uses_it`() {
        // `DroidVaultApp.onCreate` starts `LegacyMigration.complete` on IO, which
        // issues a root command within milliseconds of process start. The
        // ViewModel's probe used to `close()` the process-wide cached shell
        // unconditionally, so on a fresh install the two raced: the migration's
        // job ran on a shell that was closed out from under it, and its verdict
        // — including whether its one-time DONE marker gets written — came from
        // a destroyed shell.
        val src = File("src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt")
            .readText()
        val probe = requireNotNull(Regex("""\bfun\s+checkRootAccess\b""").find(src)?.range?.first) {
            "checkRootAccess not found"
        }
        val body = src.substring(probe, probe + 1500)
        assertTrue(
            "the teardown must be reference-counted, not unconditional",
            body.contains("shellUsers") || body.contains("shellTeardownLock")
        )
        assertTrue(
            "sanity: the count must actually be taken and released around the probe",
            body.contains("getAndIncrement") && body.contains("decrementAndGet")
        )
    }

    @Test
    fun `the_engine_deploy_is_judged_on_the_bytes_that_landed`() {
        // The chain's exit status is the LAST command's — `chown -R`, `2>/dev/null`
        // 'd, near-always green. A failed `cp` (reaped cache file, ENOSPC on /data,
        // read-only or SELinux-blocked /data/adb) therefore read as success, six
        // call sites recorded the engine as deployed, and the first symptom was a
        // backup refusing much later with nothing pointing back here.
        //
        // Recorded here because this fix was WRITTEN UP BEFORE IT WAS MADE — the
        // register said fixed while the file was untouched in the tree. A register
        // is not evidence; the file is.
        val src = File("src/main/kotlin/com/phoenix/droidvault/utils/BackendInstaller.kt")
            .readText()
        val fn = requireNotNull(Regex("""\bfun\s+installEngine\b""").find(src)?.range?.first) {
            "installEngine not found"
        }
        // Bound by the NEXT top-level declaration, not a character count: the
        // first version used a 5000-char window and the checks sit ~90 lines
        // in, so every probe fell outside it and the test was red at baseline.
        // A red test makes every mutation look like it was caught.
        val after = src.substring(fn)
        val next = after.indexOf("\n    fun ").let { if (it < 0) after.length else it }
        val body = after.substring(0, next)
        assertTrue(
            "installEngine must verify the deployed files exist and are non-empty, " +
                "not trust the shell's exit status",
            body.contains("length() > 0L")
        )
        assertTrue(
            "the success branch must require the landed check, not just isSuccess",
            Regex("""isSuccess\s*&&\s*landed""").containsMatchIn(body)
        )
        // Pinned to the MESSAGE, not to the identifier: `body.contains("missing")`
        // is satisfied by the `val missing = ...` list on its own, so a mutation
        // that replaced the log text with anything at all still passed.
        assertTrue(
            "a missing file must be named in the log — a field report says which " +
                "file is absent, not just that something went wrong",
            body.contains("missing or empty")
        )
    }

    @Test
    fun `the_shell_lock_is_not_a_constructor_ordered_property`() {
        // Caught on device TWICE, on the release build, on every launch.
        //
        // As a plain member declared below `init`: `NullPointerException: Null
        // reference used for synchronization (monitor-enter)` — Kotlin assigns
        // properties in declaration order and `init { checkRootAccess() }` launches
        // on IO, so it read the field first.
        //
        // Switched to `by lazy`, which is the wrong fix and still crashed:
        // `Attempt to invoke virtual method 'Object lh1.getValue()' on a null object
        // reference`. `lazy` defers the VALUE; the delegate FIELD is still assigned
        // in declaration order, so the coroutine still beat it.
        //
        // Only file scope is safe: the holder is created when the class is loaded,
        // which is before any instance can exist. No source-text assertion can
        // prove thread interleaving, so the rule is asserted as the scope.
        val src = File("src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt")
            .readText()
        val fileLevel = Regex("""(?m)^private\s+val\s+shellTeardownLock\b""")
        assertTrue(
            "shellTeardownLock must be FILE-level: as a member it is assigned in " +
                "declaration order, after the init block that reaches it",
            fileLevel.containsMatchIn(src)
        )
        assertTrue(
            "shellUsers must be file-level for the same reason",
            Regex("""(?m)^private\s+val\s+shellUsers\b""").containsMatchIn(src)
        )
        assertTrue(
            "neither may be a member property again — that is the crash",
            !Regex("""(?m)^\s{4}private\s+val\s+shell(TeardownLock|Users)\b""")
                .containsMatchIn(src)
        )
        assertTrue(
            "and `by lazy` is NOT the answer: it defers the value, not the field's " +
                "own assignment, which is why the second attempt still crashed",
            !Regex("""val\s+shell(TeardownLock|Users)[^=]*by\s+lazy""").containsMatchIn(src)
        )
    }

    @Test
    fun `the_migration_and_the_probe_still_both_reach_root_somewhere`() {
        // Guards against "fix the race" being implemented by dropping one of the
        // two callers: the migration is a real one-time heal and the probe is
        // what populates hasRoot.
        val app = File("src/main/kotlin/com/phoenix/droidvault/DroidVaultApp.kt").readText()
        val vm = File("src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt").readText()
        assertTrue("the migration must still run at startup", app.contains("LegacyMigration.complete"))
        assertTrue("the root probe must still run", vm.contains("Shell.getShell().isRoot"))
    }
}