package com.phoenix.droidvault

import com.phoenix.droidvault.models.Trash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The quarantine manifest, and which batches may be purged.
 *
 * Two failure modes are being prevented. A truncated index orphans every
 * quarantined archive forever (the user believes data is recoverable and it is
 * not). A purge that fires on a timer can remove a batch the user was about to
 * undo. So: encode/decode must round-trip paths with spaces, and expiry must be
 * a decision the caller reports rather than a silent deletion.
 */
class TrashTest {

    private val day = 24L * 60 * 60 * 1000

    private fun entry(path: String) = Trash.Entry(
        originalPath = path,
        trashPath = "/vault/.trash/b1/${path.substringAfterLast('/')}",
        label = path.substringAfterLast('/'),
        sizeKb = 1024
    )

    private fun batch(id: String, ageDays: Long, count: Int = 1) = Trash.Batch(
        id = id,
        // Anchored to now: a hardcoded epoch silently made every batch look
        // expired, which failed three unrelated tests before this was noticed.
        createdAt = System.currentTimeMillis() - ageDays * day,
        entries = (1..count).map { entry("/vault/Apps/User/App $it") },
        kind = "apps"
    )

    // --- round trip ---------------------------------------------------------

    @Test
    fun `a batch round trips`() {
        val batches = listOf(batch("b1", 0))
        val decoded = Trash.decode(Trash.encode(batches))
        assertEquals(1, decoded.size)
        assertEquals("b1", decoded[0].id)
        assertEquals(batches[0].createdAt, decoded[0].createdAt)
        assertEquals("apps", decoded[0].kind)
        assertEquals(batches[0].entries, decoded[0].entries)
    }

    @Test
    fun `paths with spaces survive the round trip`() {
        // A vault on an SD card is routinely "My Backup Drive". Spaces are the
        // common case, not the exotic one.
        val b = Trash.Batch(
            "b1", 1_700_000_000_000,
            listOf(entry("/storage/emulated/0/My Drive/Apps/User/Some App")),
            "apps"
        )
        val decoded = Trash.decode(Trash.encode(listOf(b)))
        assertEquals(
            "/storage/emulated/0/My Drive/Apps/User/Some App",
            decoded[0].entries[0].originalPath
        )
    }

    @Test
    fun `multiple batches round trip`() {
        val batches = listOf(batch("b1", 1), batch("b2", 0, 3), batch("b3", 2))
        val decoded = Trash.decode(Trash.encode(batches))
        assertEquals(listOf("b1", "b2", "b3"), decoded.map { it.id })
        assertEquals(3, decoded[1].entries.size)
    }

    @Test
    fun `an empty manifest round trips to empty`() {
        assertEquals(emptyList<Trash.Batch>(), Trash.decode(Trash.encode(emptyList())))
    }

    @Test
    fun `a truncated or foreign manifest reads as empty rather than throwing`() {
        // A half-written index must not crash the screen that renders it, and it
        // must not be mistaken for "there is nothing to recover".
        assertEquals(emptyList<Trash.Batch>(), Trash.decode(""))
        assertEquals(emptyList<Trash.Batch>(), Trash.decode("garbage\nmore garbage"))
        assertEquals(emptyList<Trash.Batch>(), Trash.decode("DVTRASH2\nB\tx"))
    }

    @Test
    fun `a malformed batch header is skipped without losing later batches`() {
        val text = "DVTRASH1\n" +
                "B\n" +
                "B\tb2\t1700000000000\tapps\t1\n" +
                "E\t/a\t/b\tA\t5\n"
        val decoded = Trash.decode(text)
        assertEquals(1, decoded.size)
        assertEquals("b2", decoded[0].id)
        assertEquals(5L, decoded[0].entries[0].sizeKb)
    }

    @Test
    fun `an entry line without a batch header is ignored`() {
        // Orphaned lines must not resurrect a batch that has no metadata.
        val text = "DVTRASH1\nE\t/a\t/b\tA\t5\n"
        assertEquals(emptyList<Trash.Batch>(), Trash.decode(text))
    }

    @Test
    fun `a tab in a path is refused rather than silently mangled`() {
        // Encoding it anyway would split the line into extra fields and move the
        // wrong file back on undo.
        val thrown = runCatching { Trash.requireEncodable("/a\tb") }
        assertTrue(thrown.isFailure)
        assertTrue(runCatching { Trash.requireEncodable("/a b/c") }.isSuccess)
    }

    // --- expiry -------------------------------------------------------------

    @Test
    fun `a recent batch is recoverable`() {
        val b = batch("b1", 1)
        assertTrue(b.isRecoverable(System.currentTimeMillis()))
        assertFalse(b.isRecoverable(System.currentTimeMillis() + Trash.KEEP_MILLIS + 1000))
    }

    @Test
    fun `expired batches are reported, not silently dropped`() {
        val now = System.currentTimeMillis()
        val batches = listOf(
            batch("old", 30),
            batch("recent", 1),
            batch("today", 0)
        )
        // Only the genuinely old one.
        assertEquals(listOf("old"), Trash.expiredBatchIds(batches, now))
    }

    @Test
    fun `a trash over the batch limit does NOT evict by count`() {
        // Explicit timestamps, newest last. Building them from batch() would
        // give each call its own currentTimeMillis, so "b0" would be the OLDEST
        // and the assertion below would be inverted.
        val now = System.currentTimeMillis()
        val total = Trash.MAX_BATCHES + 5
        val oldestFirst = (total - 1 downTo 0).map { i ->
            Trash.Batch("b$i", now - i * 1000L, listOf(entry("/v/A")), "apps")
        }
        val expired = Trash.expiredBatchIds(oldestFirst, now)
        // CHANGED CONTRACT. This used to expect the oldest five to be purged purely
        // for exceeding MAX_BATCHES. That made deleting an 11th app irreversibly
        // erase the first one, typically seconds after it was quarantined, while
        // every surface said "recoverable for 7 days". A cap is a rule for the
        // explicit "Empty trash"; expiry is a rule about AGE. Nothing is dropped
        // here — these batches are all seconds old and fully recoverable.
        assertEquals("nothing is evicted for being over the limit", 0, expired.size)
        assertFalse("newest batch must survive", expired.contains("b0"))
        assertFalse("the oldest batch must survive too", expired.contains("b14"))
    }

    @Test
    fun `batches past the limit still age out on their own`() {
        val now = System.currentTimeMillis()
        val long = Trash.KEEP_MILLIS + 60_000L
        val batches = (0 until Trash.MAX_BATCHES + 3).map { i ->
            Trash.Batch("b$i", now - long - i * 1000L, listOf(entry("/v/A")), "apps")
        }
        // Over the limit AND past the window: age is a sufficient reason.
        assertEquals(batches.size, Trash.expiredBatchIds(batches, now).size)
    }

    @Test
    fun `held space is reported for an honest warning`() {
        val batches = listOf(batch("b1", 0, 2), batch("b2", 0, 3))
        assertEquals(5L * 1024, Trash.heldKb(batches))
        assertEquals(2, batches[0].totalKb / 1024)
    }
}