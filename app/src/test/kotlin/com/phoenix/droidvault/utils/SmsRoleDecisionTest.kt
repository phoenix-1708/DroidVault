package com.phoenix.droidvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression tests for the SMS-role decision core shared by the automation
 * runner and the Migrate tab, driven by a real field finding: a de-Googled
 * device whose `sms_default_application` setting names an UNINSTALLED
 * package. Handing the default-SMS role to such a ghost package would strand
 * SMS with nobody able to receive it.
 */
class SmsRoleDecisionTest {

    private val installed = setOf("com.android.messaging", "com.phoenix.droidvault")
    private val isInstalled: (String) -> Boolean = { it in installed }

    @Test
    fun `ghost captured default is rejected`() {
        // Exactly the stale value observed in the field: named by the
        // setting but not installed.
        assertNull(
            AutomationRunner.sanitizeCapturedDefault(
                "com.google.android.apps.messaging",
                "com.phoenix.droidvault",
                isInstalled
            )
        )
    }

    @Test
    fun `real previous default passes through`() {
        assertEquals(
            "com.android.messaging",
            AutomationRunner.sanitizeCapturedDefault(
                "com.android.messaging",
                "com.phoenix.droidvault",
                isInstalled
            )
        )
    }

    @Test
    fun `self blank and null captures are rejected`() {
        assertNull(
            AutomationRunner.sanitizeCapturedDefault(
                "com.phoenix.droidvault", "com.phoenix.droidvault", isInstalled
            )
        )
        assertNull(AutomationRunner.sanitizeCapturedDefault("", "com.phoenix.droidvault", isInstalled))
        assertNull(AutomationRunner.sanitizeCapturedDefault(null, "com.phoenix.droidvault", isInstalled))
    }

    @Test
    fun `fallback picks first installed candidate`() {
        val candidates = listOf(
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.android.mms"
        )
        // De-Googled device: Google Messages absent -> AOSP Messaging wins.
        assertEquals(
            "com.android.messaging",
            AutomationRunner.pickSmsFallback(candidates, isInstalled)
        )
    }

    @Test
    fun `fallback tail is a real package not a ghost`() {
        // Nothing installed at all: the tail must be the always-present AOSP
        // app, never the Google package this class of device lacks.
        assertEquals(
            "com.android.messaging",
            AutomationRunner.pickSmsFallback(emptyList()) { false }
        )
    }

    @Test
    fun `fallback prefers google messages when present`() {
        val candidates = listOf(
            "com.google.android.apps.messaging",
            "com.android.messaging",
            "com.android.mms"
        )
        assertEquals(
            "com.google.android.apps.messaging",
            AutomationRunner.pickSmsFallback(candidates) { it == "com.google.android.apps.messaging" }
        )
    }
}
