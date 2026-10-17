package com.phoenix.droidvault

import com.phoenix.droidvault.models.DeviceArchiveHealth
import com.phoenix.droidvault.models.DeviceArchiveInfo
import com.phoenix.droidvault.models.DeviceArchiveRead
import com.phoenix.droidvault.models.DeviceArchiveReader
import com.phoenix.droidvault.models.DeviceArchiveRow
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.DeviceVerification
import com.phoenix.droidvault.models.VerificationReport
import com.phoenix.droidvault.models.deviceArchiveRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Manage screen's device list: the fact, the parse, and the health word.
 *
 * All pure. The shell read cannot run here, so it is [DeviceArchiveReader] —
 * the part that can be wrong — that is under test, and the rules that decide
 * what a row SAYS are tested directly, because "says Not checked" is the whole
 * safety property of this card and it cannot be observed without a device.
 */
class DeviceArchiveTest {

    private val deviceDir = "/storage/emulated/0/DroidVault/Device"

    private fun statLine(name: String, size: Long, mtime: Long) =
        "$deviceDir/$name $size $mtime"

    // --- the parse -------------------------------------------------------

    @Test
    fun `a present archive keeps its size and date`() {
        val rows = DeviceArchiveReader.parse(
            listOf(statLine("Messages.shift", 7_660L, 1_798_000_000L)),
            deviceDir
        )
        val messages = rows.first { it.item == DeviceDataItem.MESSAGES }
        assertTrue(messages.present)
        assertEquals(7_660L, messages.sizeBytes)
        // Seconds from `stat`, milliseconds for the UI.
        assertEquals(1_798_000_000_000L, messages.modifiedAt)
    }

    @Test
    fun `an archive that is not there is absent, not zero-sized`() {
        // `stat` is silent for a missing file, so absence is the absence of a
        // line. A row that came back present-with-size-0 would render as
        // "Backed up · 0 B", which is the one thing this card must never say.
        val rows = DeviceArchiveReader.parse(
            listOf(statLine("Messages.shift", 7_660L, 1_798_000_000L)),
            deviceDir
        )
        val contacts = rows.first { it.item == DeviceDataItem.CONTACTS }
        assertTrue(!contacts.present)
        assertEquals(0L, contacts.sizeBytes)
        assertEquals("Not backed up", contacts.presenceLabel)
    }

    @Test
    fun `a zero-length file counts as not backed up`() {
        // An interrupted write can leave a 0-byte archive. It is not a backup,
        // and the honest rendering of it is absence.
        val rows = DeviceArchiveReader.parse(
            listOf(statLine("CallLogs.shift", 0L, 1_798_000_000L)),
            deviceDir
        )
        assertTrue(!rows.first { it.item == DeviceDataItem.CALLS }.present)
    }

    @Test
    fun `every item is answered, always all seven`() {
        // A list of only what exists cannot answer "is Contacts backed up" —
        // absent and never-heard-of are the same row. The absent row is the
        // answer, so the count is fixed by the enum and not by the disk.
        val rows = DeviceArchiveReader.parse(listOf(statLine("Wi-Fi.shift", 2_510L, 1L)), deviceDir)
        assertEquals(DeviceDataItem.entries.size, rows.size)
        assertEquals(7, rows.size)
        assertEquals(
            DeviceDataItem.entries.toList(),
            rows.map { it.item }
        )
    }

    @Test
    fun `a path with a space in it still parses`() {
        // The vault folder is the user's to name, and a renamed folder is a
        // normal thing rather than an edge case. Size and mtime are the LAST two
        // fields; everything before them rejoins as the name.
        val spaced = "/storage/emulated/0/My Vaults/DroidVault/Device"
        val rows = DeviceArchiveReader.parse(
            listOf("$spaced/Messages.shift 7660 1798000000"),
            spaced
        )
        val messages = rows.first { it.item == DeviceDataItem.MESSAGES }
        assertTrue(messages.present)
        assertEquals(7_660L, messages.sizeBytes)
    }

    @Test
    fun `an unknown file in the folder is ignored, not guessed at`() {
        // Reporting an unknown archive as some known item puts a confident label
        // on the wrong file — the same rule `DeviceVerification` follows.
        val rows = DeviceArchiveReader.parse(
            listOf(
                statLine("Messages.shift", 7_660L, 1_798_000_000L),
                statLine("SomethingNew.shift", 5L, 1_798_000_000L)
            ),
            deviceDir
        )
        assertTrue(rows.none { it.item == DeviceDataItem.MESSAGES && it.sizeBytes == 5L })
        assertEquals(1, rows.count { it.present })
    }

    @Test
    fun `garbage lines are skipped rather than crashing the read`() {
        val rows = DeviceArchiveReader.parse(
            listOf("", "not a stat line", "a b", statLine("Wi-Fi.shift", 1L, 2L)),
            deviceDir
        )
        assertEquals(1, rows.count { it.present })
        assertEquals(7, rows.size)
    }

    @Test
    fun `nothing on disk is seven absent rows, not an error`() {
        val rows = DeviceArchiveReader.parse(emptyList(), deviceDir)
        assertEquals(7, rows.size)
        assertTrue(rows.none { it.present })
    }

    // --- the health word -------------------------------------------------

    private fun info(item: DeviceDataItem, present: Boolean = true) =
        DeviceArchiveInfo(item, present, if (present) 1_024L else 0L, 1_798_000_000_000L)

    private fun reportWith(vararg rows: DeviceVerification) =
        VerificationReport(deviceItems = rows.toList())

    private fun finding(
        item: DeviceDataItem?,
        ok: Boolean = true,
        mismatched: List<String> = emptyList()
    ) = DeviceVerification(
        item = item,
        archiveName = (item?.archiveFile ?: "Unknown.shift"),
        ok = ok,
        hashMismatchedParts = mismatched
    )

    @Test
    fun `no report means every row says not checked`() {
        // The property this card exists to hold. A tick for a check nobody ran
        // is the failure these rules exist to prevent.
        val rows = deviceArchiveRows(
            listOf(info(DeviceDataItem.MESSAGES), info(DeviceDataItem.WIFI)),
            verification = null
        )
        assertTrue(rows.all { it.health == DeviceArchiveHealth.NOT_CHECKED })
    }

    @Test
    fun `an item with no finding says not checked even beside one that has`() {
        val rows = deviceArchiveRows(
            listOf(
                info(DeviceDataItem.MESSAGES),
                info(DeviceDataItem.CALLS),
                info(DeviceDataItem.CONTACTS)
            ),
            reportWith(finding(DeviceDataItem.MESSAGES))
        )
        val by = rows.associateBy { it.item }
        assertEquals(DeviceArchiveHealth.READABLE, by[DeviceDataItem.MESSAGES]?.health)
        assertEquals(DeviceArchiveHealth.NOT_CHECKED, by[DeviceDataItem.CALLS]?.health)
        assertEquals(DeviceArchiveHealth.NOT_CHECKED, by[DeviceDataItem.CONTACTS]?.health)
    }

    @Test
    fun `the four health words are told apart`() {
        val rows = deviceArchiveRows(
            listOf(
                info(DeviceDataItem.MESSAGES),
                info(DeviceDataItem.CALLS),
                info(DeviceDataItem.CONTACTS),
                info(DeviceDataItem.WIFI)
            ),
            reportWith(
                finding(DeviceDataItem.MESSAGES, ok = false),
                finding(DeviceDataItem.CALLS, mismatched = listOf("CallLogs.shift")),
                finding(DeviceDataItem.CONTACTS),
                finding(DeviceDataItem.WIFI)
            )
        )
        val by = rows.associateBy { it.item }
        assertEquals(DeviceArchiveHealth.DAMAGED, by[DeviceDataItem.MESSAGES]?.health)
        assertEquals(DeviceArchiveHealth.CHANGED, by[DeviceDataItem.CALLS]?.health)
        assertEquals(DeviceArchiveHealth.READABLE, by[DeviceDataItem.CONTACTS]?.health)
        assertEquals(DeviceArchiveHealth.READABLE, by[DeviceDataItem.WIFI]?.health)
    }

    @Test
    fun `an archive the report covers but the folder does not have is not checked`() {
        // A report can describe a file deleted after the check ran. Presence is
        // read fresh and wins: the row must not describe bytes that are not on
        // the phone, whatever an older report says about them.
        val rows = deviceArchiveRows(
            listOf(info(DeviceDataItem.MESSAGES, present = false)),
            reportWith(finding(DeviceDataItem.MESSAGES, ok = false))
        )
        val messages = rows.first { it.item == DeviceDataItem.MESSAGES }
        assertEquals(DeviceArchiveHealth.NOT_CHECKED, messages.health)
        assertTrue(!messages.present)
    }

    @Test
    fun `a finding for an archive with no item does not attach to a row`() {
        // The engine can report a `.shift` this build has no item for. It is
        // still a finding for the verification dialog, which renders it by file
        // name — but it must not be pinned onto whichever row sorted first.
        val rows = deviceArchiveRows(
            listOf(info(DeviceDataItem.MESSAGES), info(DeviceDataItem.CALLS)),
            reportWith(finding(item = null, ok = false))
        )
        assertTrue(rows.all { it.health == DeviceArchiveHealth.NOT_CHECKED })
    }

    @Test
    fun `a row with no archive on disk is still rendered, not dropped`() {
        val rows = deviceArchiveRows(emptyList(), verification = null)
        assertEquals(7, rows.size)
        assertTrue(rows.all { !it.present })
        assertTrue(rows.all { it.subtitle == "Not backed up" })
    }

    // --- what the row says ----------------------------------------------

    @Test
    fun `a backed up row states its size and date`() {
        val row = DeviceArchiveRow(info(DeviceDataItem.MESSAGES), DeviceArchiveHealth.NOT_CHECKED)
        // humanBytes is decimal, so 1024 reads "1 KB" and not "1.0 KB".
        assertTrue(row.subtitle.startsWith("1 KB · "))
        // The date half is deliberately NOT asserted as text: it is formatted in
        // the device locale, so a test naming "Oct" passes in English CI and
        // fails in a locale where October is not "Oct". The composition is the
        // contract; the month name is the platform's.
        assertTrue(
            "expected a size and a date after the separator, got \"${row.subtitle}\"",
            row.subtitle.removePrefix("1 KB · ").isNotBlank()
        )
    }

    @Test
    fun `an absent row says only that it is not backed up`() {
        val row = DeviceArchiveRow(
            info(DeviceDataItem.CONTACTS, present = false),
            DeviceArchiveHealth.NOT_CHECKED
        )
        assertEquals("Not backed up", row.subtitle)
    }

    @Test
    fun `only a damaged archive is a problem`() {
        // "Changed" is readable and worth knowing about, but it is not the same
        // alarm as bytes that will not read, and colouring them the same would
        // teach the user to ignore the colour.
        assertTrue(DeviceArchiveHealth.DAMAGED.isProblem)
        assertTrue(!DeviceArchiveHealth.CHANGED.isProblem)
        assertTrue(!DeviceArchiveHealth.READABLE.isProblem)
        assertTrue(!DeviceArchiveHealth.NOT_CHECKED.isProblem)
    }

    @Test
    fun `the health words are sentences a user can read`() {
        for (h in DeviceArchiveHealth.entries) {
            assertTrue("\"${h.label}\" is empty", h.label.isNotBlank())
            assertTrue("\"${h.label}\" shows a brace", !h.label.contains('{') && !h.label.contains('}'))
        }
        assertEquals("Not checked", DeviceArchiveHealth.NOT_CHECKED.label)
    }

    // --- a read that failed is NOT an empty folder ------------------------

    @Test
    fun `an unreadable folder is not an empty one`() {
        // The bug this type exists for. `refreshDeviceBackups` was
        // `runCatching { … }.getOrDefault(emptyList())`, and the read it wraps
        // THROWS on a vault path that is not absolute — so a renamed folder
        // produced an empty list, and the Manage card rendered a confident
        // "0 of 7 backed up". The app was asserting as fact that a user has no
        // device backups because it could not open the directory. That is
        // invariant 16 (absence is not failure) in its worst form.
        val read: DeviceArchiveRead = DeviceArchiveRead.Unreadable("path must be absolute")
        assertTrue(!read.readable)
        // A PREDICATE may answer "no file here" — that is the safe direction for
        // the erase dialog, which is deciding whether to OFFER a row.
        assertTrue(read.archivesOrEmpty().isEmpty())
        // A REPORT may not, and the type is what stops it: `deviceArchiveRows`
        // takes the archives list, so a failure cannot reach it as "no rows".
        assertTrue(
            "an unreadable read must not be renderable as a list of absent items",
            read !is DeviceArchiveRead.Ok
        )
    }

    @Test
    fun `a folder nobody has read yet is unreadable, not empty`() {
        // The initial value. A fresh install has NOT established that the folder
        // is empty, and saying so before the first read is the same confident
        // error one screen-visit later.
        val initial = DeviceArchiveRead.Unreadable("Not read yet")
        assertTrue(!initial.readable)
        assertTrue(initial.archivesOrEmpty().isEmpty())
    }

    @Test
    fun `a real read is readable and keeps its seven answers`() {
        val read = DeviceArchiveRead.Ok(
            listOf(info(DeviceDataItem.MESSAGES), info(DeviceDataItem.CONTACTS, present = false))
        )
        assertTrue(read.readable)
        assertEquals(2, read.archivesOrEmpty().size)
    }
}
