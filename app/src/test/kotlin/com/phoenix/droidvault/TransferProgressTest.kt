package com.phoenix.droidvault

import com.phoenix.droidvault.models.TransferProgress
import com.phoenix.droidvault.models.VaultTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that stop a transfer bar from lying.
 *
 * Each of these was a way the straightforward implementation reports something
 * false: a bar that reads 100% for a run that failed, one that jumps backwards
 * mid-copy, or one that shows "done" because a size probe returned nothing.
 */
class TransferProgressTest {

    @Test
    fun `an unknown total never reads as complete`() {
        assertEquals(0, TransferProgress.percent(500, 0, finished = false))
        assertEquals(0, TransferProgress.percent(0, 1000, finished = false))
        // The killer case: total could not be measured, work is streaming.
        assertTrue(TransferProgress.percent(9_000_000, 0, finished = false) < 100)
    }

    @Test
    fun `reaching the total is not completion`() {
        // Bytes written is not a finished backup. Only the exit status is.
        assertEquals(99, TransferProgress.percent(1000, 1000, finished = false))
        assertEquals(100, TransferProgress.percent(1000, 1000, finished = true))
    }

    @Test
    fun `output larger than input is clamped`() {
        // tar writes a 512-byte header per file, so output can exceed input.
        assertEquals(99, TransferProgress.percent(5000, 1000, finished = false))
    }

    @Test
    fun `progress never goes backwards`() {
        // The byte count can dip when tar rewrites a header mid-file.
        var last = 0
        listOf(100L, 250L, 400L, 380L, 500L, 420L, 900L).forEach { done ->
            last = TransferProgress.percent(done, 1000, finished = false, last = last)
        }
        assertEquals(90, last)
    }

    @Test
    fun `a shrinking total pushes the bar up, never down`() {
        // A re-measured (smaller) total means the work genuinely appears to be
        // further along than before — 800 of 400 is 200%. Clamping to 99 is the
        // honest reading; rewinding to the old 80 would understate real progress.
        var last = TransferProgress.percent(800, 1000, finished = false)
        assertEquals(80, last)
        last = TransferProgress.percent(800, 400, finished = false, last = last)
        assertEquals(99, last)
    }

    @Test
    fun `weighted copy reflects sizes not item counts`() {
        // One 900 MB app and one 100 MB app: after the small one the bar must have
        // moved 10%, not 50%.
        val w = TransferProgress.Weighted(mapOf("big" to 900_000_000L, "small" to 100_000_000L))
        assertEquals(10, w.complete("small"))
        assertEquals(100, w.complete("big").let { w.finish() })
    }

    @Test
    fun `an unknown item does not invent progress`() {
        val w = TransferProgress.Weighted(mapOf("a" to 100L))
        assertEquals(0, w.complete("something-else"))
        // Copying the last item fills the byte total but is still not "finished":
        // 100 is reserved for finish(), which runs only after staging is cleaned
        // up. Reaching 99 here means the last copy landed without claiming the
        // whole transfer succeeded before it had.
        assertEquals(99, w.complete("a"))
        assertEquals(100, w.finish())
    }

    @Test
    fun `size labels use real figures`() {
        assertEquals("0 B of 1.0 GB", TransferProgress.sizeLabel(0L, 1_000_000_000L))
        assertEquals("500 MB so far", TransferProgress.sizeLabel(500_000_000L, 0L))
        assertEquals("1.5 GB", TransferProgress.humanBytes(1_500_000_000L))
        // Negative probes must not render as "-1 B".
        assertEquals("0 B", TransferProgress.humanBytes(-5L))
    }

    @Test
    fun `an empty weight list is not a crash or a lie`() {
        val w = TransferProgress.Weighted(emptyMap())
        assertTrue(w.isEmpty)
        assertEquals(0, w.complete("anything"))
        assertEquals(100, w.finish())
    }
}

/**
 * A transfer file that carries only `Apps` and `Device` hands a clean install
 * the archives and none of the configuration: no labels, no assignments, no
 * configs, no schedules. The folder registry went with them, because it is
 * prefs-only and only heals FROM the state file. These pin the rule that decides
 * when a carried configuration is applied and when it is merely offered.
 */
class TransferConfigsTest {

    private val empty = VaultTransfer.ConfigCounts()
    private val some = VaultTransfer.ConfigCounts(labels = 2, configs = 3, schedules = 1, folders = 4)

    @Test
    fun `an empty phone takes the carried configuration without asking`() {
        assertEquals(
            VaultTransfer.ConfigsAction.RESTORE,
            VaultTransfer.planConfigs(some, empty)
        )
    }

    @Test
    fun `a configured phone is asked, never overwritten silently`() {
        // This is the whole point. Importing backups onto the phone you are
        // holding must not reconfigure it as a side effect.
        assertEquals(
            VaultTransfer.ConfigsAction.ASK,
            VaultTransfer.planConfigs(some, VaultTransfer.ConfigCounts(configs = 1))
        )
    }

    @Test
    fun `a transfer with no configuration changes nothing`() {
        assertEquals(VaultTransfer.ConfigsAction.NONE, VaultTransfer.planConfigs(null, some))
        assertEquals(VaultTransfer.ConfigsAction.NONE, VaultTransfer.planConfigs(empty, empty))
    }

    @Test
    fun `schedules alone still count as configured`() {
        // A phone with only a schedule is configured. Treating "no configs" as
        // "empty" would replace a live schedule with a stale one.
        assertEquals(
            VaultTransfer.ConfigsAction.ASK,
            VaultTransfer.planConfigs(some, VaultTransfer.ConfigCounts(schedules = 1))
        )
    }

    @Test
    fun `folders alone still count as configured`() {
        assertEquals(
            VaultTransfer.ConfigsAction.ASK,
            VaultTransfer.planConfigs(some, VaultTransfer.ConfigCounts(folders = 1))
        )
    }

    @Test
    fun `counts read out of a real state file`() {
        // Key names taken from AutomationStore.exportJson, NOT from what this
        // class happens to read. The first version of this test used "configs"
        // to match the (wrong) reader, so it passed while the reader was wrong.
        val json = """{"schemaVersion":2,"labels":[{"id":"a"},{"id":"b"}],
            "assignments":[{"packageName":"x"}],"configurations":[{"id":"c"}],
            "schedules":[{"id":"s"},{"id":"t"},{"id":"u"}],"masterEnabled":true}"""
        val c = VaultTransfer.ConfigCounts.fromJson(json)
        assertEquals(2, c.labels)
        assertEquals(1, c.configs)
        assertEquals(3, c.schedules)
        // folders live in their own sidecar file, not in the state object.
        assertEquals(0, c.folders)
        assertFalse(c.isEmpty)
    }

    @Test
    fun `a truncated state file counts as empty rather than crashing`() {
        // Mid-import a partial read must degrade to "nothing", never take the
        // import down with it.
        assertEquals(VaultTransfer.ConfigCounts(), VaultTransfer.ConfigCounts.fromJson("{\"labels\":["))
        assertEquals(VaultTransfer.ConfigCounts(), VaultTransfer.ConfigCounts.fromJson(""))
        assertEquals(VaultTransfer.ConfigCounts(), VaultTransfer.ConfigCounts.fromJson("not json"))
    }

    @Test
    fun `describe names what is there and omits what is not`() {
        assertEquals("2 labels, 3 configurations, 1 schedule, 4 folders", some.describe())
        assertEquals("1 label, 1 configuration", VaultTransfer.ConfigCounts(labels = 1, configs = 1).describe())
        assertEquals("nothing", empty.describe())
    }

    @Test
    fun `the transfer carries the reinstall kit but not the engine`() {
        // The engine and launcher are the freshness gate's business (invariant
        // 5) and are redeployed from the bundled assets; shipping a source
        // device's copy would hand the new phone a build it did not ask for.
        assertTrue(VaultTransfer.CARRIED_FILES.contains("DroidVault.apk"))
        assertFalse(VaultTransfer.CARRIED_FILES.contains("DroidVault.engine"))
        assertFalse(VaultTransfer.CARRIED_FILES.contains("DroidVault.sh"))
    }

    @Test
    fun `the meta staging directory is hidden from the vault scans`() {
        // It is created inside the vault for the duration of an export, because
        // toybox tar cannot combine two roots. A leading dot keeps it out of the
        // Apps/Device walks, alongside .trash and .droidvault_dir.
        assertTrue(VaultTransfer.META_DIR.startsWith("."))
    }
}
