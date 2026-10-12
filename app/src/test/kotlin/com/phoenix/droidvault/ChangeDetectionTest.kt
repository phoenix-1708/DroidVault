package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.ChangeDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Incremental backup: which parts a run would actually re-write.
 *
 * This mirrors a test the Rust engine already performs in `backup_app`, where a
 * part whose recorded size still matches the live size is skipped with
 * "already backed up". Mirroring it in Kotlin is what lets the estimate be
 * honest and "changed only" select the right apps.
 *
 * The dangerous direction is claiming a part is UNCHANGED when it is not: that
 * silently does not back data up. Every "we cannot tell" case here resolves to
 * changed.
 */
class ChangeDetectionTest {

    private fun app(
        pkg: String = "com.x",
        appKb: Long = 100,
        dataKb: Long = 200,
        mediaKb: Long = 300,
        backedAppKb: Long = 100,
        backedDataKb: Long = 200,
        backedMediaKb: Long = 300,
        archived: Set<Int> = setOf(1, 2, 4)
    ) = AppInfo(
        label = "X",
        packageName = pkg,
        appSizeKb = appKb,
        dataSizeKb = dataKb,
        mediaSizeKb = mediaKb,
        availableInBackup = archived,
        backedUpAppKb = backedAppKb,
        backedUpDataKb = backedDataKb,
        backedUpMediaKb = backedMediaKb
    )

    private val bulk = setOf(AppParts.APP, AppParts.DATA, AppParts.MEDIA)

    // --- the happy path -----------------------------------------------------

    @Test
    fun `an unchanged app writes nothing bulk`() {
        val a = app()
        assertFalse(ChangeDetection.needsWrite(a, AppParts.APP))
        assertFalse(ChangeDetection.needsWrite(a, AppParts.DATA))
        assertFalse(ChangeDetection.needsWrite(a, AppParts.MEDIA))
        assertTrue(ChangeDetection.isUpToDate(a, bulk))
        assertEquals(0L, ChangeDetection.writeEstimateKb(bulk, a))
    }

    @Test
    fun `a grown data dir is rewritten and costs only that part`() {
        val a = app(dataKb = 200)
        val changed = a.copy(dataSizeKb = 900)
        assertTrue(ChangeDetection.needsWrite(changed, AppParts.DATA))
        assertFalse(ChangeDetection.needsWrite(changed, AppParts.APP))

        val estimate = ChangeDetection.writeEstimateKb(bulk, changed)
        // overhead + the changed part only, NOT the unchanged App and Media.
        assertEquals(AppParts.PER_APP_OVERHEAD_KB + 900, estimate)
    }

    @Test
    fun `a shrunk part is also rewritten`() {
        // Only equality skips. A smaller dir is different content.
        val changed = app(dataKb = 50)
        assertTrue(ChangeDetection.needsWrite(changed, AppParts.DATA))
    }

    // --- unknown is always changed -----------------------------------------

    @Test
    fun `an unrecorded size is never treated as unchanged`() {
        val a = app(backedAppKb = -1, backedDataKb = -1, backedMediaKb = -1)
        assertTrue(ChangeDetection.needsWrite(a, AppParts.APP))
        assertTrue(ChangeDetection.needsWrite(a, AppParts.DATA))
        assertTrue(ChangeDetection.needsWrite(a, AppParts.MEDIA))
    }

    @Test
    fun `a part with no archive is rewritten`() {
        val a = app(archived = emptySet())
        assertTrue(ChangeDetection.needsWrite(a, AppParts.APP))
        assertFalse(ChangeDetection.isUpToDate(a, bulk))
    }

    @Test
    fun `an app never backed up needs everything`() {
        val a = AppInfo(label = "X", packageName = "com.y", appSizeKb = 50, dataSizeKb = 60)
        assertTrue(ChangeDetection.needsWrite(a, AppParts.APP))
        assertTrue(ChangeDetection.needsWrite(a, AppParts.DATA))
    }

    // --- permissions and android id are always fresh ------------------------

    @Test
    fun `permissions and android id are always rewritten`() {
        // Both are single small files read fresh from the system. There is
        // nothing to skip, and skipping would risk restoring a stale snapshot.
        val a = app(archived = setOf(1, 2, 3, 4, 5))
        assertTrue(ChangeDetection.needsWrite(a, AppParts.PERMISSIONS))
        assertTrue(ChangeDetection.needsWrite(a, AppParts.SSAID))
        assertFalse(
            "bulk parts may still all be unchanged",
            AppParts.APP in ChangeDetection.partsToWrite(a, AppParts.ALL)
        )
    }

    @Test
    fun `a selection of only bulk parts can be fully up to date`() {
        val a = app()
        assertTrue(ChangeDetection.isUpToDate(a, bulk))
        // Adding the always-rewritten parts makes it not up to date, which is
        // correct: the run will do something.
        assertFalse(ChangeDetection.isUpToDate(a, AppParts.ALL))
    }

    // --- the estimate -------------------------------------------------------

    @Test
    fun `estimate of a first backup is the full selection`() {
        val a = AppInfo(
            label = "X", packageName = "com.z",
            appSizeKb = 1000, dataSizeKb = 2000, mediaSizeKb = 4000
        )
        val estimate = ChangeDetection.writeEstimateKb(AppParts.ALL, a)
        assertEquals(
            AppParts.PER_APP_OVERHEAD_KB + 1000 + 2000 + 4000 +
                    AppParts.PERMISSIONS_KB + AppParts.SSAID_KB,
            estimate
        )
    }

    @Test
    fun `estimate of an unchanged app is only the metadata it rewrites`() {
        // The defect this exists for: a nightly re-backup claimed the full size
        // of every app while writing almost nothing. Permissions and Android ID
        // are always rewritten, so a fully-selected unchanged app costs the
        // overhead plus those two — NOT its APK, data and media.
        val a = app()
        assertEquals(0L, ChangeDetection.writeEstimateKb(bulk, a))
        assertEquals(
            AppParts.PER_APP_OVERHEAD_KB + AppParts.PERMISSIONS_KB + AppParts.SSAID_KB,
            ChangeDetection.writeEstimateKb(AppParts.ALL, a)
        )
    }

    @Test
    fun `estimate never exceeds a full first backup`() {
        val fresh = AppInfo(
            label = "X", packageName = "com.z",
            appSizeKb = 1000, dataSizeKb = 2000, mediaSizeKb = 4000
        )
        val partial = fresh.copy(
            availableInBackup = setOf(AppParts.APP, AppParts.DATA, AppParts.MEDIA),
            backedUpAppKb = 1000, backedUpDataKb = 9999, backedUpMediaKb = 4000
        )
        assertTrue(
            ChangeDetection.writeEstimateKb(AppParts.ALL, partial) <
                    ChangeDetection.writeEstimateKb(AppParts.ALL, fresh)
        )
    }
}