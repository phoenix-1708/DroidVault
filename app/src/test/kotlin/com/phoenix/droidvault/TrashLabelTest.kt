package com.phoenix.droidvault

import com.phoenix.droidvault.models.Trash
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Batch labels in the trash dialog.
 *
 * Found on device: deriving the label from the stored kind alone produced
 * "1 device backups" the first time a single item was quarantined. Plural
 * agreement is user-visible text on a screen whose whole purpose is telling
 * someone exactly what a destructive action did, so it is pinned by tests
 * rather than left to a format string.
 */
class TrashLabelTest {

    @Test
    fun `device backups agree in number`() {
        assertEquals("1 device backup", Trash.batchLabel(1, "device backups"))
        assertEquals("2 device backups", Trash.batchLabel(2, "device backups"))
        assertEquals("3 device backups", Trash.batchLabel(3, "device backups"))
    }

    @Test
    fun `apps agree in number`() {
        assertEquals("1 app", Trash.batchLabel(1, "apps"))
        assertEquals("7 apps", Trash.batchLabel(7, "apps"))
    }

    @Test
    fun `exports agree in number`() {
        // The sentence a user reads after deleting an export — possibly the only
        // copy of an old phone's vault. "1 exports" was the fallback's answer,
        // and this is the one batch label where the singular is the normal case:
        // a split export moves as one folder, whatever its piece count.
        assertEquals("1 export", Trash.batchLabel(1, "exports"))
        assertEquals("2 exports", Trash.batchLabel(2, "exports"))
    }

    @Test
    fun `zero is plural`() {
        // "0 device backup" reads as a missing item rather than an empty count.
        assertEquals("0 device backups", Trash.batchLabel(0, "device backups"))
    }

    @Test
    fun `an unknown kind is shown as-is rather than swallowed`() {
        // A kind added later must be visible, not render as an empty string.
        assertEquals("2 widget bundles", Trash.batchLabel(2, "widget bundles"))
    }
}