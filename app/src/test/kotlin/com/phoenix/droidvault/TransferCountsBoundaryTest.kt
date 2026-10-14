package com.phoenix.droidvault

import com.phoenix.droidvault.models.VaultTransfer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The count that decides whether to ask before replacing configurations has to
 * survive the hop from the Activity to the transfer service.
 *
 * Found in review: `MainViewModel.currentConfigCounts()` computed four counts —
 * labels, configs, schedules and **folders** — and `TransferService` serialised
 * three of them. On the other side it rebuilt `ConfigCounts` with three
 * POSITIONAL arguments, which silently resolved to the 3-arg shape and defaulted
 * `folders` to 0.
 *
 * That matters because `isEmpty` is the conjunction of all four. So a phone
 * holding only a backup-folder registry — an ordinary user who never wrote an
 * automation — counted as "not configured", and the incoming configurations
 * replaced its state file with no confirmation. Then
 * `BackupFolderStore.restoreFromJson` rewrote its folder list from the other
 * device's. The prompt that is supposed to prevent exactly that was decided by a
 * count that had been thrown away one call earlier.
 *
 * This is invariant 29's shape: a key shared between a writer and a reader, where
 * the reader guessed. The existing guard pinned `ConfigCounts.fromJson` to the
 * serialiser; neither side of the service boundary was covered.
 */
class TransferCountsBoundaryTest {

    /** The writer's JSON, as `TransferService.startImport` builds it. */
    private fun serialise(c: VaultTransfer.ConfigCounts): String =
        JSONObject()
            .put("labels", c.labels)
            .put("configs", c.configs)
            .put("schedules", c.schedules)
            .put("folders", c.folders)
            .toString()

    /** The reader, as the fixed `TransferService` rebuilds it — named args. */
    private fun deserialise(json: String?): VaultTransfer.ConfigCounts? =
        json?.let {
            runCatching {
                val o = JSONObject(it)
                VaultTransfer.ConfigCounts(
                    labels = o.optInt("labels"),
                    configs = o.optInt("configs"),
                    schedules = o.optInt("schedules"),
                    folders = o.optInt("folders")
                )
            }.getOrNull()
        }

    @Test
    fun `a folder-only phone is configured, and the crossing must say so`() {
        // The case that was broken. Every other count is zero.
        val folderOnly = VaultTransfer.ConfigCounts(folders = 3)
        assertFalse(
            "a phone holding three backup folders IS configured — this is the " +
                "premise of the whole test",
            folderOnly.isEmpty
        )
        val across = deserialise(serialise(folderOnly))
        assertEquals(folderOnly, across)
        assertFalse(
            "after crossing the service boundary the phone must still read as " +
                "configured, or its configurations are replaced unasked",
            across!!.isEmpty
        )
        assertEquals(
            "a configured destination must be ASKED before its configurations are " +
                "replaced — ASK is the whole point of the prompt",
            VaultTransfer.ConfigsAction.ASK,
            VaultTransfer.planConfigs(VaultTransfer.ConfigCounts(labels = 2), folderOnly)
        )
    }

    @Test
    fun `every count survives the crossing`() {
        val full = VaultTransfer.ConfigCounts(labels = 1, configs = 2, schedules = 3, folders = 4)
        assertEquals(full, deserialise(serialise(full)))
    }

    @Test
    fun `a genuinely empty phone stays empty`() {
        // The guard must not turn the other way and make every import prompt.
        val bare = VaultTransfer.ConfigCounts()
        assertTrue(bare.isEmpty)
        assertEquals(bare, deserialise(serialise(bare)))
        assertTrue(bare.isEmpty)
    }

    @Test
    fun `a missing folders key is tolerated as unknown, not as a crash`() {
        // An extra transfer file from an older build. Absent key must not throw.
        val legacy = deserialise("""{"labels":1,"configs":0,"schedules":0}""")
        assertEquals(
            VaultTransfer.ConfigCounts(labels = 1, configs = 0, schedules = 0, folders = 0),
            legacy
        )
        // And garbage stays null so the caller can fall back deliberately.
        assertEquals(null, deserialise("{not json"))
        assertEquals(null, deserialise(null))
    }

    @Test
    fun `the_writer_and_reader_of_the_service_boundary_agree_on_every_key`() {
        // The rule, over the source: a positional rebuild of a four-count value is
        // exactly what silently dropped one, so the reader must name every field
        // (which makes a dropped field a compile error) and the writer must
        // serialise every field (which makes it a failing test).
        val src = File("src/main/kotlin/com/phoenix/droidvault/utils/TransferService.kt")
            .readText()
            .let { raw ->
                Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
                    .replace(raw, " ")
                    .replace(Regex("//[^\n]*"), " ")
            }
        // Writer side: all four keys must be serialised.
        for (key in listOf("labels", "configs", "schedules", "folders")) {
            assertTrue(
                "TransferService must serialise `$key` into EXTRA_COUNTS; dropping " +
                    "one here is how a configured phone reads as bare on the far side",
                src.contains("""put("$key", counts.$key)""")
            )
        }
        // Reader side: every field named, never positional.
        val rebuild = Regex("""VaultTransfer\.ConfigCounts\(([^)]*)\)""")
            .findAll(src)
            .map { it.groupValues[1] }
            .toList()
        assertTrue("expected a ConfigCounts rebuild in the service", rebuild.isNotEmpty())
        val positional = rebuild.filter { body ->
            body.isNotBlank() && !body.contains("=") && body.trim().startsWith("o.")
        }
        assertTrue(
            "ConfigCounts must be rebuilt with NAMED arguments on the service path; " +
                "positional arguments resolve to the defaulted fields silently:\n" +
                positional.joinToString("\n"),
            positional.isEmpty()
        )
        assertTrue(
            "the fallback must be the zero of every field",
            src.contains("VaultTransfer.ConfigCounts(0, 0, 0, 0)")
        )
    }
}