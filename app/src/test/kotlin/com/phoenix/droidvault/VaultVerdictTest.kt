package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppVerification
import com.phoenix.droidvault.models.DeviceDataItem
import com.phoenix.droidvault.models.DeviceVerification
import com.phoenix.droidvault.models.VaultVerdict
import com.phoenix.droidvault.models.VerifyScope
import com.phoenix.droidvault.models.VerificationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict that survives a restart.
 *
 * Two things are being pinned here, and both have already cost this project
 * time: the FORMAT, because records written by an older build are on users'
 * devices and a renamed key reads back as a default (the transfer counter read
 * `configs` while the export wrote `configurations` and sat at zero forever,
 * §2.24/T-16); and PROVENANCE, because a restored verdict asserting good news is
 * the exact defect §2.46 fixed inside a session, one process further out.
 */
class VaultVerdictTest {

    private val vault = "/storage/emulated/0/DroidVault"

    /** Nothing default: a dropped field then fails instead of reading as 0/false/empty. */
    private val rich = VerificationReport(
        apps = listOf(
            AppVerification(
                label = "Drive",
                packageName = "com.google.android.apps.docs",
                partsChecked = 3,
                corruptParts = listOf("Data.shift", "Media.shift"),
                bytesReadable = 62_339_780L,
                hashMismatchedParts = listOf("Permissions.txt"),
                firstSeenMs = 1_699_000_000_000L
            ),
            AppVerification(
                label = "",
                packageName = "com.weird.pkg",
                partsChecked = 5,
                corruptParts = emptyList(),
                bytesReadable = 1L
            )
        ),
        total = 108,
        okCount = 106,
        corruptCount = 2,
        bytesReadable = 30_000_000_000L,
        cancelled = false,
        error = "",
        scope = VerifyScope.BOTH,
        deviceChanged = 1,
        // A device row with every field non-default, so a dropped field in the
        // codec fails here instead of reading back as "no finding".
        deviceItems = listOf(
            DeviceVerification(
                item = DeviceDataItem.MESSAGES,
                archiveName = "Messages.shift",
                ok = false,
                corruptParts = listOf("Messages.shift"),
                bytesReadable = 0L,
                hashMismatchedParts = listOf("Messages.shift"),
                firstSeenMs = 1_699_500_000_000L
            ),
            DeviceVerification(
                item = DeviceDataItem.CALLS,
                archiveName = "CallLogs.shift",
                ok = true,
                bytesReadable = 4_096L,
                hashMismatchedParts = listOf("CallLogs.shift")
            ),
            // An archive this build has no item for: still a finding, and the
            // item must come back null rather than be guessed at.
            DeviceVerification(
                item = null,
                archiveName = "Something.shift",
                ok = true,
                bytesReadable = 7L
            )
        ),
        readParts = 9,
        skippedParts = 4,
        takenAtEpoch = 7L,
        // Non-default so a dropped key in the codec fails this fixture instead
        // of reading back as "no age" and "no repair".
        takenAtMs = 1_700_000_000_000L,
        reconciledRecords = 2
    )

    @Test
    fun `every field survives the round trip`() {
        val record = VaultVerdict.fromJson(VaultVerdict.toJson(vault, rich))
        assertNotNull("a record this build wrote must read back", record)
        assertEquals("the record itself is not marked as restored", rich, record!!.report)
        // What the loader hands on is marked: the flag is applied on read, so a
        // run's own report can never be confused with one that came off disk.
        assertEquals(rich.copy(restoredFromDisk = true), VaultVerdict.forVault(record, vault))
    }

    @Test
    fun `a stored device finding keeps the archive it is about`() {
        // The finding has to survive as a NAMED archive, not as a count: the gate
        // is asked about a specific item ("is the messages archive damaged?"), and
        // a restored report that kept only `corruptCount` could not answer it — so
        // a damaged Messages.shift would become restorable again by restarting the
        // app, which is the interval a wipe-then-restore spans.
        val back = VaultVerdict.fromJson(VaultVerdict.toJson(vault, rich))!!.report
        assertEquals(rich.deviceItems, back.deviceItems)
        assertEquals(listOf("Messages.shift"), back.deviceDamagedArchives())
        assertEquals(listOf("Messages.shift", "CallLogs.shift"), back.deviceChangedArchivesNamed())
        assertTrue(back.hasDamagedDeviceArchives())
        assertTrue(back.hasAnyDamagedArchives())
    }

    @Test
    fun `an empty verdict round-trips too`() {
        // A check that found nothing is still a result: total 0 is what makes the
        // next screen say "no backups" instead of "not checked".
        val empty = VerificationReport()
        val back = VaultVerdict.fromJson(VaultVerdict.toJson(vault, empty))
        assertEquals(empty, back!!.report)
    }

    @Test
    fun `the keys a writer writes are the keys a reader reads`() {
        // Literal, deliberately: this is the on-disk format, and records written
        // by the build that shipped are read by the build that ships next. If a
        // constant is ever renamed, this fails while the round-trip test above
        // still passes — which is the whole reason both exist.
        val json = VaultVerdict.toJson(vault, rich)
        for (key in listOf(
            "\"version\"", "\"vault\"", "\"report\"", "\"takenAtEpoch\"", "\"total\"",
            "\"okCount\"", "\"corruptCount\"", "\"bytesReadable\"", "\"cancelled\"",
            "\"error\"", "\"scope\"", "\"deviceChanged\"", "\"readParts\"",
            "\"skippedParts\"", "\"apps\"", "\"label\"", "\"packageName\"",
            "\"partsChecked\"", "\"corruptParts\"", "\"hashMismatchedParts\"",
            "\"deviceItems\"", "\"archiveName\"", "\"ok\"",
            // How long a finding has stood, and what the check repaired: both are
            // facts about the finding, so a restart that dropped them would report
            // an old problem as new.
            "\"takenAtMs\"", "\"firstSeenMs\"", "\"reconciledRecords\""
        )) {
            assertTrue("the record must carry $key: $json", json.contains(key))
        }
        assertTrue("the scope is stored by name", json.contains("\"BOTH\""))
    }

    @Test
    fun `provenance is not stored, it is applied on read`() {
        // `restoredFromDisk` describes the reader, not the measurement. Writing it
        // would let a record claim a freshness it cannot have, and reading it back
        // would let a run's own report look like it came off disk.
        val json = VaultVerdict.toJson(vault, rich.copy(restoredFromDisk = true))
        assertFalse(
            "the record must not carry provenance: $json",
            json.contains("restoredFromDisk")
        )
        val back = VaultVerdict.fromJson(json)!!.report
        assertFalse("a stored report is never stored as restored", back.restoredFromDisk)
    }

    @Test
    fun `only the vault it was taken in is described`() {
        val record = VaultVerdict.fromJson(VaultVerdict.toJson(vault, rich))
        assertNull(
            "a verdict about another folder is not a verdict about this one",
            VaultVerdict.forVault(record, "/storage/emulated/0/OtherVault")
        )
        val here = VaultVerdict.forVault(record, vault)
        assertNotNull(here)
        assertTrue(
            "what the loader returns was not observed in this launch",
            here!!.restoredFromDisk
        )
    }

    @Test
    fun `a failed check does not erase the finding it could not replace`() {
        // The gate reads whatever the screens are showing, so "the run did not
        // finish" must not take a known-damaged archive's refusal away. The store
        // already keeps the record; this is the same decision on the display side.
        val stored = VerificationReport(
            apps = listOf(
                AppVerification(
                    label = "Broken",
                    packageName = "com.b",
                    partsChecked = 3,
                    corruptParts = listOf("Data.shift"),
                    bytesReadable = 0L
                )
            ),
            total = 1,
            okCount = 0,
            corruptCount = 1
        )
        val cancelled = VerificationReport(total = 0, cancelled = true)
        val errored = VerificationReport(error = "engine unavailable")
        assertEquals(stored, VaultVerdict.shownAfter(cancelled, stored))
        assertEquals(stored, VaultVerdict.shownAfter(errored, stored))

        // A conclusive run replaces it, in both directions: damage repaired is
        // gone, and damage found is shown.
        val healthy = VerificationReport(total = 1, okCount = 1)
        assertEquals(healthy, VaultVerdict.shownAfter(healthy, stored))
        assertEquals(stored, VaultVerdict.shownAfter(stored, healthy))

        // Nothing stored: the run's own honest state, not a passed check nobody ran.
        assertEquals(cancelled, VaultVerdict.shownAfter(cancelled, null))
    }

    @Test
    fun `nothing about a broken record throws`() {
        // A prefs blob is plain text any of a dozen failures can corrupt. Reading
        // it happens during construction of the ViewModel, so a throw here is a
        // crash on launch, not a missing verdict.
        for (bad in listOf(
            null, "", "   ", "not json", "{}", "[]", "[1,2,3]",
            """{"version":1}""",
            """{"version":1,"vault":"$vault"}""",
            """{"version":1,"vault":"","report":{}}""",
            VaultVerdict.toJson(vault, rich).substring(0, 40)
        )) {
            assertNull("must refuse: $bad", VaultVerdict.fromJson(bad))
        }
    }

    @Test
    fun `a record from a newer build is refused rather than approximated`() {
        // A downgrade has no way to know what the extra fields mean, and guessing
        // would put a verdict on screen that nothing measured.
        val json = VaultVerdict.toJson(vault, rich)
            .replace("\"version\":${VaultVerdict.VERSION}", "\"version\":${VaultVerdict.VERSION + 1}")
        assertNull(VaultVerdict.fromJson(json))
    }

    @Test
    fun `an unknown scope is refused`() {
        // The scope decides the wording of the summary, so an unreadable one must
        // not decay into the app wording — that is §2.44, the device check that
        // reported itself as an app check.
        val json = VaultVerdict.toJson(vault, rich).replace("\"BOTH\"", "\"SOMETHING\"")
        assertNull(VaultVerdict.fromJson(json))
    }

    @Test
    fun `an empty part name is not a part`() {
        // The engine writes an empty field when nothing is corrupt; a part called
        // "" is a finding nobody can act on, and the dialog would show a blank.
        val report = VerificationReport(
            apps = listOf(
                AppVerification(
                    label = "A",
                    packageName = "com.a",
                    partsChecked = 1,
                    corruptParts = listOf(""),
                    bytesReadable = 1L
                )
            ),
            total = 1,
            okCount = 0,
            corruptCount = 1
        )
        val back = VaultVerdict.fromJson(VaultVerdict.toJson(vault, report))!!.report
        assertTrue(back.apps[0].corruptParts.isEmpty())
        assertTrue(back.apps[0].ok)
    }
}
