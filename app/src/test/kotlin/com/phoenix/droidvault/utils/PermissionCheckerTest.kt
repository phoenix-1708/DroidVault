package com.phoenix.droidvault.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure decision cores behind the Settings "Permissions" self-check card.
 * Live probes (root shell / package manager) are exercised on device; these
 * pin the parser contracts against real dumpsys/appops output shapes.
 */
class PermissionCheckerTest {

    // ---------- appopsAllows (GET_USAGE_STATS) ----------

    @Test fun appopsAllowLineIsAccepted() {
        // Real on-device shape (<device-serial>): "GET_USAGE_STATS: allow; time=..."
        assertTrue(
            PermissionChecker.appopsAllows(
                "Uid mode: foreground\nGET_USAGE_STATS: allow; time=+19m50s640ms ago\nWRITE_SMS: allow"
            )
        )
    }

    @Test fun appopsBareAllowIsAccepted() {
        assertTrue(PermissionChecker.appopsAllows("GET_USAGE_STATS: allow"))
    }

    @Test fun appopsDeniedModesAreRejected() {
        assertFalse(PermissionChecker.appopsAllows("GET_USAGE_STATS: ignore"))
        assertFalse(PermissionChecker.appopsAllows("GET_USAGE_STATS: deny"))
        assertFalse(PermissionChecker.appopsAllows("GET_USAGE_STATS: default"))
        // "foreground" alone is not the standing allow
        assertFalse(PermissionChecker.appopsAllows("GET_USAGE_STATS: foreground"))
    }

    @Test fun appopsMissingLineIsRejected() {
        assertFalse(PermissionChecker.appopsAllows(""))
        assertFalse(PermissionChecker.appopsAllows(null))
        assertFalse(PermissionChecker.appopsAllows("WRITE_SMS: allow\nVIBRATE: allow"))
    }

    @Test fun appopsExactOpNameOnly() {
        // A hypothetical look-alike op must not satisfy the real one.
        assertFalse(PermissionChecker.appopsAllows("GET_USAGE_STATS_V2: allow"))
        assertFalse(PermissionChecker.appopsAllows("XGET_USAGE_STATS: allow"))
    }

    @Test fun appopsToleratesIndentationAndPrefix() {
        // dumpsys sometimes indents or prefixes lines; the op token after
        // optional leading noise before the colon decides.
        assertTrue(PermissionChecker.appopsAllows("  GET_USAGE_STATS: allow; time=+1s ago"))
        // Leading package echo line must not break the scan.
        assertTrue(PermissionChecker.appopsAllows("Package com.example:\nGET_USAGE_STATS: allow"))
    }

    // ---------- isWhitelisted (dumpsys deviceidle) ----------

    @Test fun whitelistMatchIsAccepted() {
        // Real shape: the package appears among the whitelisted users.
        assertTrue(
            PermissionChecker.isWhitelisted(
                "service=system_battery_controller\n...\nwhitelist=user:0:[com.android.systemui,com.phoenix.droidvault]\n",
                "com.phoenix.droidvault"
            )
        )
    }

    @Test fun whitelistAbsentIsRejected() {
        assertFalse(
            PermissionChecker.isWhitelisted(
                "whitelist=user:0:[com.android.systemui]", "com.phoenix.droidvault"
            )
        )
    }

    @Test fun whitelistNullAndEmptyAreRejected() {
        assertFalse(PermissionChecker.isWhitelisted(null, "com.phoenix.droidvault"))
        assertFalse(PermissionChecker.isWhitelisted("", "com.phoenix.droidvault"))
    }

    @Test fun whitelistRequiresWholeWordMatch() {
        // A sibling package sharing a prefix must not satisfy the check.
        val out = "whitelist=user:0:[com.phoenix.droidvaultsibling]"
        assertFalse(PermissionChecker.isWhitelisted(out, "com.phoenix.droidvault"))
        // Boundary punctuation still counts as a word boundary.
        assertTrue(
            PermissionChecker.isWhitelisted(
                "whitelist=user:0:[systemui,com.phoenix.droidvault]", "com.phoenix.droidvault"
            )
        )
    }

    // ---------- restrictedSettingsAllowed (ACCESS_RESTRICTED_SETTINGS) ----------

    @Test fun restrictedAllowLiftsTheGate() {
        // `cmd appops get <pkg> ACCESS_RESTRICTED_SETTINGS` after the fix.
        assertTrue(
            PermissionChecker.restrictedSettingsAllowed(
                "ACCESS_RESTRICTED_SETTINGS: allow; time=+2m ago"
            )
        )
    }

    @Test fun restrictedIgnoreAndDefaultStillGate() {
        // The clean-install state observed on device (probe: `ignore`).
        assertFalse(PermissionChecker.restrictedSettingsAllowed("ACCESS_RESTRICTED_SETTINGS: ignore"))
        assertFalse(PermissionChecker.restrictedSettingsAllowed("ACCESS_RESTRICTED_SETTINGS: default"))
        assertFalse(PermissionChecker.restrictedSettingsAllowed("ACCESS_RESTRICTED_SETTINGS: deny"))
    }

    @Test fun restrictedMissingLineReadsAllowed() {
        // Pre-Android-13 ROMs have no such op: nothing to gate on.
        assertTrue(PermissionChecker.restrictedSettingsAllowed(null))
        assertTrue(PermissionChecker.restrictedSettingsAllowed(""))
        assertTrue(PermissionChecker.restrictedSettingsAllowed("WRITE_SMS: allow"))
    }

    @Test fun restrictedExactOpNameOnly() {
        // A look-alike op must not be mistaken for the real gate. With the
        // real op line absent the pre-Android-13 default applies (allowed) —
        // the gate is only ever read from the exact op token.
        assertTrue(PermissionChecker.restrictedSettingsAllowed("ACCESS_RESTRICTED_SETTINGS_V2: ignore"))
    }

    @Test fun restrictedToleratesIndentationAndPackageEcho() {
        assertTrue(
            PermissionChecker.restrictedSettingsAllowed(
                "Package com.phoenix.droidvault:\n  ACCESS_RESTRICTED_SETTINGS: allow"
            )
        )
    }
}
