package com.phoenix.droidvault.models

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Restore seed policy (S17 fix): per-app restore parts = selection ∩ what
 * the backup actually contains, so an APK-only restore no longer shows
 * "5/5 parts" with grayed "no X in backup" chips.
 */
class SeedPolicyTest {

    @Test
    fun `apk-only backup yields only the app part`() {
        assertEquals(
            setOf(1),
            SeedPolicy.restoreComponents(setOf(1, 2, 3, 4, 5), setOf(1))
        )
    }

    @Test
    fun `full backup keeps every available part`() {
        assertEquals(
            setOf(1, 2, 3, 5),
            SeedPolicy.restoreComponents(setOf(1, 2, 3, 4, 5), setOf(1, 2, 3, 5))
        )
    }

    @Test
    fun `selection narrower than backup stays narrow`() {
        assertEquals(
            setOf(1),
            SeedPolicy.restoreComponents(setOf(1), setOf(1, 2, 3))
        )
    }

    @Test
    fun `no availability info falls back to the raw selection`() {
        assertEquals(
            setOf(1, 2, 3, 4, 5),
            SeedPolicy.restoreComponents(setOf(1, 2, 3, 4, 5), emptySet())
        )
    }
}
