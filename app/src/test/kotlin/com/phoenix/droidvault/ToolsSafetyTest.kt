package com.phoenix.droidvault

import com.phoenix.droidvault.utils.InstallerPolicy
import com.phoenix.droidvault.utils.ToolsManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The two ways this review found a phone left unusable, and the one way it told
 * a user the truth about their data.
 *
 * Both are "the tool guessed, and the guess was not reversible":
 * freezing Droid Vault is a self-lockout that takes ADB to undo, and a recovery
 * script holding `format data` survives the user walking away from the screen
 * that armed it.
 */
class ToolsSafetyTest {

    private val tools = ToolsManager

    @Test
    fun `droid vault cannot freeze itself`() {
        // The self-lockout. Disabling it means the app cannot be opened to undo
        // the disabling, so the user has no way back except ADB — and they are
        // asking for help BECAUSE something is broken.
        assertNotNull(
            "freezing Droid Vault must be refused",
            tools.freezeRefusal(ToolsManager.OWN_PACKAGE, freezing = true)
        )
        // And unfreezing it is never refused: the escape hatch must always work.
        assertNull(
            "unfreezing must never be refused — that is how a user recovers",
            tools.freezeRefusal(ToolsManager.OWN_PACKAGE, freezing = false)
        )
    }

    @Test
    fun `nothing the phone needs to work can be frozen`() {
        // SystemUI disabled is a soft brick with no launcher to navigate from.
        // Play Services disabled breaks the account, notifications and the store.
        for (pkg in listOf("com.android.systemui", "com.google.android.gms", "android")) {
            assertNotNull(
                "$pkg must not be freezable — doing so leaves the phone unusable",
                tools.freezeRefusal(pkg, freezing = true)
            )
        }
    }

    @Test
    fun `the home screen app cannot be frozen`() {
        // Dynamic, because the launcher is whatever the ROM shipped rather than a
        // fixed name. Freezing it removes the only way to reach anything.
        val launchers = setOf("com.miui.home", "com.android.launcher3")
        for (pkg in launchers) {
            assertNotNull(
                "the active launcher must not be freezable",
                tools.freezeRefusal(pkg, freezing = true, launchers = launchers)
            )
        }
        // And it is only excluded while it IS the launcher.
        assertNull(
            "the same package is fair game when it is not a launcher",
            tools.freezeRefusal("com.miui.home", freezing = true, launchers = emptySet())
        )
    }

    @Test
    fun `an ordinary app is still freezeable`() {
        // The rule must not have quietly disabled the feature.
        for (pkg in listOf("com.example.game", "com.brave.browser", "com.spotify.music")) {
            assertNull(
                "$pkg is an ordinary app and must remain freezeable",
                tools.freezeRefusal(pkg, freezing = true)
            )
        }
    }

    @Test
    fun `every refusal_tells_the_user_why`() {
        for (pkg in listOf(ToolsManager.OWN_PACKAGE, "com.android.systemui", "android")) {
            val why = tools.freezeRefusal(pkg, freezing = true)
            assertTrue(
                "a refusal with no reason leaves the user thinking it failed: $why",
                !why.isNullOrBlank()
            )
        }
    }

    @Test
    fun `the_refusal_list_is_checked_in_the_engine_too_not_only_the_screen`() {
        // A screen is not a gate: the exclusion has to be enforced where the
        // `pm disable` is actually issued, or any other caller bypasses it.
        val src = File("src/main/kotlin/com/phoenix/droidvault/utils/ToolsManager.kt")
            .readText()
            .let { raw ->
                Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
                    .replace(raw, " ")
                    .replace(Regex("//[^\n]*"), " ")
            }
        val fn = Regex("""\bfun\s+setFrozen\b""").find(src)?.range?.first
            ?: error("setFrozen not found")
        val body = src.substring(fn)
        assertTrue(
            "setFrozen must consult freezeRefusal before issuing `pm disable`; a UI " +
                "only check is bypassed by every other caller",
            body.contains("freezeRefusal")
        )
    }

    @Test
    fun `the_about_page_no_longer_claims_the_restore_consents_default_off`() {
        // Both RESTORE-side consents default ON (InstallerPolicy), and the About
        // page is where a user goes to find out what this app does unasked. It
        // asserted the opposite — on the one screen whose job is disclosure.
        assertTrue(
            "sanity: the consents really do default on, or this test is wrong",
            InstallerPolicy.DEFAULT_SSAID_RESTORE && InstallerPolicy.DEFAULT_PERM_RESTORE
        )
        val src = File("src/main/kotlin/com/phoenix/droidvault/ui/screens/AboutScreen.kt")
            .readText()
        assertFalse(
            "About must not say a restore consent is \"off unless you enable it\" " +
                "while the code has it on by default",
            Regex("""Restoring (this|it) is optional and off""").containsMatchIn(src)
        )
        assertTrue(
            "About should state the default plainly instead",
            src.contains("ON by default")
        )
    }

    @Test
    fun `deleting_a_partition_image_confirms_first`() {
        // Nothing quarantines it (invariant 24), the image is often the only copy,
        // and the button sat within a finger's width of the row's own select.
        val src = File("src/main/kotlin/com/phoenix/droidvault/ui/screens/FlashScreen.kt")
            .readText()
        val call = src.indexOf("viewModel.deleteLivePartitionImage(")
        assertTrue("the delete call must exist", call > 0)
        // The first occurrence must be inside the dialog's confirm handler, which
        // is inside an AlertDialog — not a bare click handler.
        val dialog = src.lastIndexOf("AlertDialog(", call)
        val confirm = src.lastIndexOf("confirmButton", call)
        assertTrue(
            "deleteLivePartitionImage must only be called from a confirmation's " +
                "confirmButton, never straight from a click handler",
            dialog in 0..call && confirm in 0..call && dialog > 0 && confirm > 0
        )
    }

    @Test
    fun `walking_back_from_the_flash_wizard_disarms_the_recovery_script`() {
        // The script can carry `format data`, `wipe data`, `install` and
        // `reboot`. It survived leaving the tab, backing out, and process death,
        // and fired on the NEXT recovery boot for any reason at all.
        val vm = File("src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt").readText()
        val engine = File("../engine/src/engine.rs").readText()

        assertTrue(
            "the engine must have a way to REMOVE a recovery script; writing one " +
                "without a way to clear it is the bug",
            engine.contains("clear_ors")
        )
        assertTrue(
            "every wizard exit must clear it, not just the explicit button",
            Regex("""fun\s+closeFlashWizard[\s\S]{0,400}?disarmRecoveryScript""")
                .containsMatchIn(vm)
        )
        assertTrue(
            "stepping BACK out of the armed step must disarm it too",
            Regex("""fun\s+flashWizardStepBack[\s\S]{0,700}?disarmRecoveryScript""")
                .containsMatchIn(vm)
        )
        // And writing it must be able to fail loudly, not `let _ =` three times.
        // Both the call AND its guard condition are required: a `protocol::error`
        // inside an unreachable `if false` is still visible to a text scan, and
        // that mutation survived this assertion until the condition was pinned.
        assertTrue(
            "write_ors must report that nothing was written — it used to exit 0 " +
                "and the wizard advanced to \"Everything is prepared\"",
            Regex("""fn\s+write_ors[\s\S]{0,2000}?protocol::error""").containsMatchIn(engine)
        )
        assertTrue(
            "the error must be reachable: it is guarded on having written NOTHING, " +
                "and a disabled condition would silence it while leaving the call " +
                "in place where a text scan still sees it",
            engine.contains("if !wrote_any {") && engine.contains("wrote_any = true")
        )
    }

    @Test
    fun `every_trash_index_mutation_is_serialised`() {
        // The index is one file and three mutators read-modify-write it. Two at
        // once means one batch saved with no manifest entry: invisible and
        // unrecoverable. The run gate fixed ONE caller; the rule belongs next to
        // the index.
        val src = File("src/main/kotlin/com/phoenix/droidvault/utils/MigratorManager.kt")
            .readText()
            .let { raw ->
                Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
                    .replace(raw, " ")
                    .replace(Regex("//[^\n]*"), " ")
            }
        assertTrue(
            "the trash index must have a lock next to it",
            src.contains("trashIndexLock")
        )
        for (fn in listOf("moveToTrash", "restoreTrashBatch", "purgeTrashBatch")) {
            val start = requireNotNull(Regex("""\bfun\s+$fn\b""").find(src)?.range?.first) {
                "$fn not found"
            }
            val span = src.substring(start, start + 4000)
            val end = span.indexOf("\n    fun ") .let { if (it < 0) span.length else it }
            assertTrue(
                "$fn read-modify-writes the shared trash index and must hold the " +
                    "lock; two of them at once lose a batch permanently",
                span.substring(0, end).contains("trashIndexLock.lock()")
            )
        }
    }

    @Test
    fun `the_home_card_counts_device_archives_as_backups`() {
        // "No backups yet" in the largest font, to a user whose messages and call
        // logs are the only backup they have — on the same screen that lists
        // their device archives and offers to Check them.
        val src = File("src/main/kotlin/com/phoenix/droidvault/ui/screens/DashboardScreen.kt")
            .readText()
        assertTrue(
            "the empty headline must account for device archives",
            Regex("""count\s*==\s*0\s*&&\s*hasDeviceArchives""").containsMatchIn(src)
        )
    }

    @Test
    fun `debloat_and_systemize_take_the_run_gate`() {
        // They drive the same engine as a backup. Without the gate two
        // `droidvault-engine` processes share the box, and a backup hitting its
        // timeout pkills straight through the middle of a debloat.
        val vm = File("src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt").readText()
        val start = Regex("""\bfun\s+runDynamicOperation\b""").find(vm)?.range?.first
        requireNotNull(start) { "runDynamicOperation not found" }
        // Read the function's own span, to its matching closing brace — NOT a fixed
        // window of characters. This guard used to slice 3000 chars from `start`,
        // which reads as "the gate is inside the function" while actually meaning
        // "the gate is within the first 3000 chars of it". Adding the permanent-
        // debloat confirmation above the gate pushed the acquisition past that
        // window and the guard failed on working code — the failure mode §2.43
        // warns about, where a guard reports a rule as enforced while inspecting
        // only part of what it names.
        val body = vm.substring(start).let { rest ->
            var depth = 0
            var i = 0
            while (i < rest.length) {
                when (rest[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return@let rest.substring(0, i + 1)
                    }
                }
                i++
            }
            rest
        }
        assertTrue(
            "runDynamicOperation must acquire the run gate before dispatching a " +
                "debloat/systemize",
            body.contains("RunCoordinator.tryAcquire")
        )
        // And the gate has to be taken BEFORE the run dispatches, not merely
        // somewhere in the same function.
        assertTrue(
            "the gate must be acquired before runDynamicOperation dispatches the run",
            body.indexOf("RunCoordinator.tryAcquire") <
                body.indexOf("ToolsManager.runDebloatOperation")
        )
        assertTrue(
            "and must release it, or the app refuses every later run for the life " +
                "of the process",
            Regex("""acquiredHere\s*\)\s*RunCoordinator\.release""").containsMatchIn(vm)
        )
    }

    /** The body of `fn <name>` in [source], by brace matching. */
    private fun functionBody(source: String, name: String): String {
        val start = Regex("""fun\s+$name\s*\(""").find(source)?.range?.first
            ?: throw AssertionError("`fun $name(` not found")
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return source.substring(start)
    }

    /** The `{...}` block that begins at [at], matched by counting braces. */
    private fun blockStartingAt(source: String, at: Regex): String {
        val start = at.find(source)?.range?.first
            ?: throw AssertionError("no block matching ${at.pattern}")
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return source.substring(start)
    }

    @Test
    fun `partition imaging takes the run gate, like every other engine operation`() {
        // `--live-backup` / `--live-restore` drive the SAME droidvault-engine
        // binary as every backup, and this was the one engine-driving operation
        // that took no gate. So a `dd` could run beside a backup — and any of
        // them hitting its watchdog pkills by binary name, straight through the
        // middle of it. Two engines, one name, one kill.
        val vm = File("src/main/kotlin/com/phoenix/droidvault/MainViewModel.kt").readText()
        val body = functionBody(vm, "runLiveOperation")
        assertTrue(
            "runLiveOperation must acquire the run gate before dispatching a " +
                "partition backup or flash",
            body.contains("RunCoordinator.tryAcquire")
        )
        assertTrue(
            "and must release it, or every later run is refused for the life of " +
                "the process",
            body.contains("RunCoordinator.release")
        )
        val acquire = body.indexOf("RunCoordinator.tryAcquire")
        val dispatch = body.indexOf("FlashManager.runLiveOperation")
        assertTrue(
            "the gate must be taken BEFORE the engine is dispatched",
            acquire in 0 until dispatch
        )
    }

    @Test
    fun `a completed engine command is not stopped, and a stop clears its own flag`() {
        // Found on device as an import that extracted all 482,592,475 bytes and
        // then reported "Import cancelled". `awaitClose` runs on every exit from
        // the flow — including an ordinary completion — and it called
        // `stopEngineBlocking()` unconditionally, which `touch`es the engine's
        // cancel flag. The NEXT engine command in the same run then started,
        // found the flag, and reported ACTION:CANCELLED for work it never began.
        //
        // `awaitClose` exists to stop an engine still writing into the vault
        // behind an abandoned collector (invariant 26). A command that exited on
        // its own has no such writer, so stopping it is not merely wasteful — it
        // poisons the run that comes next.
        val engine = File("src/main/kotlin/com/phoenix/droidvault/utils/ShellEngine.kt")
            .readText()
        val run = functionBody(engine, "executeEngineCommand")
        val block = blockStartingAt(run, Regex("""awaitClose\s*\{"""))
        assertTrue(
            "awaitClose must decide whether the engine actually needs stopping; " +
                "calling stopEngineBlocking() unconditionally raises the cancel " +
                "flag after a command that already succeeded, and the next " +
                "command in the run then reports ACTION:CANCELLED",
            Regex("""if\s*\(\s*!?\s*finishedNormally\s*\)""").containsMatchIn(block)
        )
        assertTrue(
            "and that branch must be the one holding the stop",
            Regex("""if\s*\(\s*!?\s*finishedNormally\s*\)\s*\{[^}]*stopEngineBlocking""")
                .containsMatchIn(block)
        )

        // The stop must not outlive itself: the flag only ever means "this
        // engine", and a stop that leaves it behind is a live cancel order for
        // the next run.
        val stop = functionBody(engine, "stopEngineBlocking")
        assertTrue(
            "stopEngineBlocking must confirm the engine is gone",
            stop.contains("if (!alive)")
        )
        assertTrue(
            "once the engine is confirmed gone, stopEngineBlocking must remove " +
                "the cancel flag it raised — otherwise the next run starts " +
                "already cancelled",
            Regex("""rm\s+-f\s+[^)]*ENGINE_CANCEL_FLAG""").containsMatchIn(stop)
        )
    }

}
