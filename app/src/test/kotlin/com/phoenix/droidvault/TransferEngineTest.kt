package com.phoenix.droidvault

import com.phoenix.droidvault.models.EngineTransferEvent
import com.phoenix.droidvault.models.TransferEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app and the Rust transfer engine agree on these lines, and on nothing
 * else. A field the engine stops sending must read as null — never move the UI
 * and never crash the collector mid-run.
 */
class TransferEngineTest {

    @Test
    fun `export progress parses`() {
        val e = TransferEngine.parse("ACTION:TRANSFER|OP:EXPORT|DONE:1234|TOTAL:9999|PCT:12")
        assertTrue(e is EngineTransferEvent.Progress)
        e as EngineTransferEvent.Progress
        assertEquals("EXPORT", e.op)
        assertEquals(1234L, e.done)
        assertEquals(9999L, e.total)
        assertEquals(12, e.pct)
    }

    @Test
    fun `extract progress parses`() {
        val e = TransferEngine.parse("ACTION:TRANSFER|OP:EXTRACT|DONE:50|TOTAL:100|PCT:50")
        assertTrue(e is EngineTransferEvent.Progress)
    }

    @Test
    fun `a placed file parses`() {
        val e = TransferEngine.parse("ACTION:TRANSFER_FILE|OP:PLACE|NAME:Demo|STATE:added")
        assertEquals(EngineTransferEvent.FilePlaced("Demo", "added"), e)
    }

    @Test
    fun `export completion carries the real byte count`() {
        val e = TransferEngine.parse("ACTION:TRANSFER_DONE|OP:EXPORT|PATH:/a/b.tgz|BYTES:77")
        assertEquals(EngineTransferEvent.ExportDone("/a/b.tgz", 77L), e)
    }

    @Test
    fun `place summary carries the counts`() {
        val e = TransferEngine.parse("ACTION:TRANSFER_DONE|OP:PLACE|ADDED:3|KEPT:1|FAILED:0")
        assertEquals(EngineTransferEvent.PlaceDone(3, 1, 0), e)
    }

    @Test
    fun `anything else is ignored, never fatal`() {
        assertNull(TransferEngine.parse("ACTION:GLOBAL_DONE|TOTAL:1|TIME:2"))
        assertNull(TransferEngine.parse("INFO:STEP|MSG:hi"))
        assertNull(TransferEngine.parse("garbage"))
        assertNull(TransferEngine.parse(""))
        // A progress line missing its denominator is not a progress line.
        assertNull(TransferEngine.parse("ACTION:TRANSFER|OP:EXPORT|DONE:5|PCT:9"))
    }
}

/**
 * Split transfers speak two more verbs: survey results and split results.
 * Same contract — a missing field reads as null, never as progress.
 */
class TransferSplitLinesTest {

    @Test
    fun `split completion carries count and bytes`() {
        val e = TransferEngine.parse("ACTION:TRANSFER_DONE|OP:SPLIT|PARTS:9|BYTES:77")
        assertEquals(EngineTransferEvent.SplitDone(9, 77), e)
    }

    @Test
    fun `survey completion carries the uncompressed total`() {
        val e = TransferEngine.parse("ACTION:TRANSFER_DONE|OP:SURVEY|BYTES:12345")
        assertEquals(EngineTransferEvent.SurveyDone(12345), e)
    }

    @Test
    fun `split progress parses like any other phase`() {
        val e = TransferEngine.parse("ACTION:TRANSFER|OP:SPLIT|DONE:5|TOTAL:9|PCT:55")
        assertTrue(e is EngineTransferEvent.Progress)
        assertEquals(55, (e as EngineTransferEvent.Progress).pct)
    }

    @Test
    fun `a split line missing its count is not a result`() {
        assertNull(TransferEngine.parse("ACTION:TRANSFER_DONE|OP:SPLIT|BYTES:77"))
    }
}
