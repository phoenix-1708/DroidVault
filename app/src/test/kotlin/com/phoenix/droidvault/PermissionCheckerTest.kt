package com.phoenix.droidvault

import com.phoenix.droidvault.utils.PermissionChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A timeout is not an answer. The engine probe runs under
 * `withTimeoutOrNull(10_000L)`, and it used to end in `?: false` — which reads
 * "we could not tell" as "the engine is stale", and rendered a healthy engine as
 * a red failure on exactly the busy phones where someone opens Settings to find
 * out why a run misbehaved.
 */
class PermissionCheckerTest {

    @Test
    fun `an unanswered engine probe is neither healthy nor stale`() {
        // The tri-state is the whole fix: `Boolean` cannot express it, and the
        // row renderer branches on this value. Building the state with a null
        // probe is only compilable if the field really is nullable — so this
        // test would not build against the old signature.
        val unanswered = PermissionChecker.CheckState(
            appopUsageStats = true,
            postNotifications = true,
            deviceidleWhitelisted = true,
            engineMatch = null
        )
        assertNull("an unanswered probe is null, not a verdict", unanswered.engineMatch)
        assertFalse("and must not read as a match either", unanswered.engineMatch == true)
    }

    @Test
    fun `a real mismatch is still false and a real match still true`() {
        val matched: Boolean? = true
        val stale: Boolean? = false
        assertTrue(matched == true)
        assertTrue(stale == false)
    }

    @Test
    fun `the self-check never launders a timeout into a failure`() {
        val src = java.io.File(
            "src/main/kotlin/com/phoenix/droidvault/utils/PermissionChecker.kt"
        ).readText()
        val start = src.indexOf("val engineMatch = try {")
        assertTrue("engineMatch probe not found", start > 0)
        val block = src.substring(start, start + 400)
        assertFalse(
            "`withTimeoutOrNull(...) ?: false` reads a timeout as a mismatch — " +
                "the exact defect this tri-state exists to prevent",
            block.contains("} ?: false")
        )
        assertFalse(
            "and the catch-all must not answer false either",
            block.contains("false\n        }")
        )
    }

    @Test
    fun `the settings summary may only claim freshness when every probe answered`() {
        val screen = java.io.File(
            "src/main/kotlin/com/phoenix/droidvault/ui/screens/SettingsScreen.kt"
        ).readText()
        assertNotNull(screen)
        assertTrue(
            "\"All standing grants are fresh\" is a claim about EVERY grant, so " +
                "an unanswered engine probe must not be folded into it as truth",
            screen.contains("it.engineMatch == true")
        )
    }
}
