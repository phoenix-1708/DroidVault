package com.phoenix.droidvault

import com.phoenix.droidvault.models.VaultTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-device transfer: the merge decision and the file naming.
 *
 * The dangerous direction is an import over a destination that already holds
 * backups. `KEEP_DESTINATION` is the default precisely because the moment an
 * import can destroy the newer copy is the moment this feature becomes a data-
 * loss feature — and it would do so quietly, on the phone the user just set up.
 */
class VaultTransferTest {

    // --- merge policy -------------------------------------------------------

    @Test
    fun `new directories are added`() {
        val r = VaultTransfer.planImport(
            incoming = listOf("1DM+", "AB DM"),
            existingOnDestination = emptySet(),
            merge = VaultTransfer.Merge.KEEP_DESTINATION
        )
        assertEquals(listOf("1DM+", "AB DM"), r.added)
        assertTrue(r.keptExisting.isEmpty())
        assertFalse(r.isEmpty)
    }

    @Test
    fun `an existing archive is kept by default, never clobbered`() {
        val r = VaultTransfer.planImport(
            incoming = listOf("1DM+"),
            existingOnDestination = setOf("1DM+"),
            merge = VaultTransfer.Merge.KEEP_DESTINATION
        )
        assertTrue("must not add over an existing archive", r.added.isEmpty())
        assertEquals(listOf("1DM+"), r.keptExisting)
    }

    @Test
    fun `replace is available but must be asked for`() {
        val r = VaultTransfer.planImport(
            incoming = listOf("1DM+"),
            existingOnDestination = setOf("1DM+"),
            merge = VaultTransfer.Merge.REPLACE
        )
        assertEquals(listOf("1DM+"), r.replaced)
        assertTrue(r.added.isEmpty())
    }

    @Test
    fun `a mixed import adds what is new and keeps what is not`() {
        val r = VaultTransfer.planImport(
            incoming = listOf("1DM+", "AB DM", "Airtel"),
            existingOnDestination = setOf("AB DM"),
            merge = VaultTransfer.Merge.KEEP_DESTINATION
        )
        assertEquals(listOf("1DM+", "Airtel"), r.added)
        assertEquals(listOf("AB DM"), r.keptExisting)
    }

    @Test
    fun `the plan is deterministic regardless of input order`() {
        val a = VaultTransfer.planImport(
            listOf("z", "a", "m"), emptySet(), VaultTransfer.Merge.KEEP_DESTINATION
        )
        val b = VaultTransfer.planImport(
            listOf("m", "z", "a"), emptySet(), VaultTransfer.Merge.KEEP_DESTINATION
        )
        assertEquals(a.added, b.added)
    }

    @Test
    fun `an empty transfer does nothing and says so`() {
        val r = VaultTransfer.planImport(emptyList(), setOf("x"), VaultTransfer.Merge.REPLACE)
        assertTrue(r.isEmpty)
        assertEquals(0, r.addedCount)
    }

    @Test
    fun `carried trees are the data, not the local mirrors`() {
        // Mirrors (DroidVault.apk, engine, state file) are re-created on the
        // destination from its own install — carrying the source device's APK
        // mirror would restore a build the destination did not ask for.
        assertEquals(listOf("Apps", "Device"), VaultTransfer.CARRIED_TREES)
        assertFalse(VaultTransfer.CARRIED_TREES.contains("DroidVault.apk"))
    }

    // --- export file naming -------------------------------------------------

    // --- short, recognizable transfer names --------------------------------
    // `DV-20261011-1430.dvbackup.tar.gz`: date + time is what a person
    // recognizes across a ROM flash. The old `<vault>-<epoch millis>` scheme
    // was unique but unreadable. Uniqueness within the minute is the caller's
    // dedupe loop, not this function's job.

    @Test
    fun `the name carries date and time, not epoch millis`() {
        // 1791448609947 ms = 2026-10-08 08:36:49 UTC.
        assertEquals("DV-20261008-0836.${VaultTransfer.EXTENSION}",
            VaultTransfer.exportFileName(1791448609947L, java.time.ZoneOffset.UTC))
    }

    @Test
    fun `the name is short, ASCII, and MTP-safe`() {
        val name = VaultTransfer.exportFileName(1791448609947L, java.time.ZoneOffset.UTC)
        assertTrue("got $name", name.length < 40)
        assertTrue(name.all { it.code < 128 })
        assertFalse(name.contains(' '))
        assertFalse(name.contains(':'))
        assertFalse(name.contains('/'))
        assertTrue(name.endsWith(".${VaultTransfer.EXTENSION}"))
    }

    @Test
    fun `the same minute names the same file`() {
        // Deterministic: the caller dedupes collisions, so this function must
        // not smuggle uniqueness in through millis.
        assertEquals(
            VaultTransfer.exportFileName(1791448609900L, java.time.ZoneOffset.UTC),
            VaultTransfer.exportFileName(1791448609999L, java.time.ZoneOffset.UTC)
        )
    }

    // --- accepting a file from the user's Downloads ------------------------

    @Test
    fun `only our own extension is accepted`() {
        assertTrue(VaultTransfer.looksLikeTransferFile("DroidVault-123.${VaultTransfer.EXTENSION}"))
        assertTrue(VaultTransfer.looksLikeTransferFile("x.${VaultTransfer.EXTENSION.uppercase()}"))
        assertFalse(VaultTransfer.looksLikeTransferFile("payload.tar.gz"))
        assertFalse(VaultTransfer.looksLikeTransferFile("evil.sh"))
        assertFalse(VaultTransfer.looksLikeTransferFile("noextension"))
    }

    @Test
    fun `a crafted filename with a newline is rejected`() {
        assertFalse(VaultTransfer.looksLikeTransferFile("a.${VaultTransfer.EXTENSION}\nrm -rf /"))
    }
}