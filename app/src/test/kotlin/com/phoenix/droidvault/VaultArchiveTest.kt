package com.phoenix.droidvault

import com.phoenix.droidvault.models.DeviceArchiveReader
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.VaultArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The vault's archive vocabulary.
 *
 * A vault archive used to be `*.shift`; it is now `*.vault`. The extension is
 * a WIRE FORMAT, not a label: every archive already on a user's card carries
 * the old name, and an app that stopped looking for those would report an
 * empty vault — the user's backups would be INVISIBLE rather than missing.
 *
 * So the name is RESOLVED, never assumed: a write uses the new name, and a
 * read takes the new one first and the old one second. These are the rules
 * that make the rename safe.
 */
class VaultArchiveTest {

    private val deviceDir = "/storage/emulated/0/DroidVault/Device"

    @Test
    fun `an archive is written with the new name`() {
        assertEquals("App.vault", VaultArchive.name("App"))
        assertEquals("Messages.vault", VaultArchive.deviceName(DeviceDataItem.MESSAGES))
        assertEquals("CallLogs.vault", VaultArchive.deviceName(DeviceDataItem.CALLS))
    }

    @Test
    fun `both names are archives`() {
        assertTrue(VaultArchive.isArchive("App.vault"))
        assertTrue(VaultArchive.isArchive("App.shift"))
        assertFalse(VaultArchive.isArchive("Meta.txt"))
        assertFalse(VaultArchive.isArchive("automation-state.json"))
    }

    @Test
    fun `the old name is still resolved`() {
        val index = mapOf("$deviceDir/Messages.shift" to 1L)
        assertEquals(
            "an archive written before the extension moved is still found",
            "Messages.shift",
            VaultArchive.existing(index, deviceDir, "Messages")
        )
        assertTrue(VaultArchive.present(index, deviceDir, "Messages"))
    }

    @Test
    fun `the new name wins when both are there`() {
        val index = mapOf(
            "$deviceDir/Messages.shift" to 1L,
            "$deviceDir/Messages.vault" to 2L
        )
        assertEquals(
            "Messages.vault",
            VaultArchive.existing(index, deviceDir, "Messages")
        )
    }

    @Test
    fun `an archive that is not there is not found`() {
        val index = mapOf("$deviceDir/Contacts.vault" to 1L)
        assertEquals(null, VaultArchive.existing(index, deviceDir, "Messages"))
        assertFalse(VaultArchive.present(index, deviceDir, "Messages"))
    }

    @Test
    fun `an archive file is matched to its item under either extension`() {
        assertEquals(
            DeviceDataItem.MESSAGES,
            VaultArchive.deviceItemFor("Messages.vault")
        )
        assertEquals(
            DeviceDataItem.MESSAGES,
            VaultArchive.deviceItemFor("Messages.shift")
        )
        assertEquals(
            DeviceDataItem.CALLS,
            VaultArchive.deviceItemFor("CallLogs.shift")
        )
        assertEquals(null, VaultArchive.deviceItemFor("SomethingNew.vault"))
    }

    @Test
    fun `a legacy-named device archive is still found and still named`() {
        // The read is the shell edge; the parse is the pure half. An archive
        // written before the extension moved must arrive as THIS item's
        // archive, under the name it actually has — a report that lost its
        // item would put a confident label on the wrong file.
        val rows = DeviceArchiveReader.parse(
            listOf("$deviceDir/Messages.shift 7660 1798000000"),
            deviceDir
        )
        val messages = rows.first { it.item == DeviceDataItem.MESSAGES }
        assertTrue(messages.present)
        assertEquals(7_660L, messages.sizeBytes)
        assertEquals(
            "the name on disk is the name the trash and the reader must use",
            "Messages.shift",
            messages.fileName
        )
    }

    @Test
    fun `a new-named device archive is found under its own name`() {
        val rows = DeviceArchiveReader.parse(
            listOf("$deviceDir/Messages.vault 7660 1798000000"),
            deviceDir
        )
        val messages = rows.first { it.item == DeviceDataItem.MESSAGES }
        assertTrue(messages.present)
        assertEquals("Messages.vault", messages.fileName)
    }

    @Test
    fun `when both spellings are on disk the new one wins`() {
        // A restore from the trash can put an old-named archive back beside a
        // new-named one. The live file is the new-named one, so that is the
        // one the row must report — a report that named the stale copy would
        // send the trash's move to the wrong file.
        val rows = DeviceArchiveReader.parse(
            listOf(
                "$deviceDir/Messages.vault 7660 1798000000",
                "$deviceDir/Messages.shift 100 1798000001"
            ),
            deviceDir
        )
        val messages = rows.first { it.item == DeviceDataItem.MESSAGES }
        assertEquals("Messages.vault", messages.fileName)
        assertEquals(7_660L, messages.sizeBytes)
    }
}
