package com.phoenix.droidvault

import com.phoenix.droidvault.utils.AutomationStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Automation data restored" notice must not depend on who finishes first.
 *
 * Measured on device (POCO F6, debug build): the store finished recovering and
 * wrote the hint at T+1.2s after launch, while MainScreen composed at T+0.4s
 * and read `null` from a one-shot `LaunchedEffect(Unit)`. The read was never
 * repeated, so the dialog never appeared — and whether it appeared came down to
 * a race between two coroutines, which is exactly why it was reported as a
 * popup that shows up "randomly".
 *
 * The store now publishes the hint on a flow that MainScreen collects. These
 * tests pin the decision half of that: the notice must be produced whenever a
 * recovery genuinely happened, which is the same `shouldRecoverFromFile` gate
 * X-07 narrowed so it can no longer fire on deliberately emptied data.
 */
class AutomationRestoreNoticeTest {

    @Test
    fun `a genuine fresh-install recovery produces a notice`() {
        // The case the feature is for: prefs never written, file populated.
        assertTrue(AutomationStore.shouldRecoverFromFile(prefInitialized = false, fileData = true))
    }

    @Test
    fun `deliberately emptied data produces no notice`() {
        // X-07: this used to fire, restoring what the user deleted and then
        // announcing it. A notice that lies about a recovery is worse than none.
        assertFalse(AutomationStore.shouldRecoverFromFile(prefInitialized = true, fileData = true))
    }

    @Test
    fun `no file means no notice`() {
        assertFalse(AutomationStore.shouldRecoverFromFile(prefInitialized = false, fileData = false))
    }

    @Test
    fun `an already-initialised cache needs no notice`() {
        assertFalse(AutomationStore.shouldRecoverFromFile(prefInitialized = true, fileData = false))
    }

    @Test
    fun `a notice that was never produced stays absent from the flow`() {
        // The flow must not start out holding a stale notice from a previous
        // process, or the dialog would reappear after every cold start.
        assertTrue(
            "restoreHint is process-scoped state and must not be pre-seeded",
            !runCatching { AutomationStore.restoreHint.value != null }.getOrDefault(false)
        )
    }
}