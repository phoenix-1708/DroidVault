package com.phoenix.droidvault

import com.phoenix.droidvault.models.Trash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the trash tells the user about what it holds.
 *
 * Found on device: a card and dialog reading "1 app" told the user a count but
 * not WHICH app, and with two batches on screen the rows were indistinguishable.
 * The dialog then lingered after a purge with nothing in it.
 */
class TrashEntryNamesTest {

    private val now = 1_700_000_000_000L

    private fun entry(label: String, sizeKb: Long = 10) =
        Trash.Entry("/vault/$label", "/vault/.trash/b1/$label", label, sizeKb)

    private fun batch(kind: String, labels: List<String>) = Trash.Batch(
        id = "b1",
        createdAt = now,
        entries = labels.map { entry(it) },
        kind = kind
    )

    // --- names ---------------------------------------------------------------

    @Test
    fun `the app name is the last path segment`() {
        val b = batch("apps", listOf("User/1DM+"))
        assertEquals(listOf("1DM+"), Trash.entryNames(b))
    }

    @Test
    fun `archive suffixes are dropped for readability`() {
        val b = batch("device backups", listOf("Device/Messages.shift", "Device/Wi-Fi.shift"))
        assertEquals(listOf("Messages", "Wi-Fi"), Trash.entryNames(b))
    }

    @Test
    fun `a bare label with no directory still names the item`() {
        // Batches written before the parent-name change stored just the name.
        val b = batch("apps", listOf("1DM+"))
        assertEquals(listOf("1DM+"), Trash.entryNames(b))
    }

    @Test
    fun `a permissions file is named, not blanked`() {
        // Stripping the suffix must not reduce the name to nothing.
        val b = batch("apps", listOf("User/x/.shift"))
        assertTrue("must not be blank", Trash.entryNames(b).none { it.isBlank() })
    }

    @Test
    fun `names keep deletion order and drop repeats`() {
        val b = batch("apps", listOf("User/Beta", "User/Alpha", "User/Beta"))
        assertEquals(listOf("Beta", "Alpha"), Trash.entryNames(b))
    }

    // --- summaries -----------------------------------------------------------

    @Test
    fun `a short list is joined`() {
        val b = batch("apps", listOf("User/1DM+", "User/AB DM"))
        assertEquals("1DM+, AB DM", Trash.entrySummary(b))
    }

    @Test
    fun `a long list says how many more`() {
        val b = batch("apps", listOf("A", "B", "C", "D", "E"))
        assertEquals("A, B, C and 2 more", Trash.entrySummary(b))
    }

    @Test
    fun `an empty batch falls back to the count label`() {
        val b = Trash.Batch("b1", now, emptyList(), "apps")
        assertEquals("0 apps", Trash.entrySummary(b))
    }
}