package com.phoenix.droidvault

import com.phoenix.droidvault.utils.DeviceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * The rule that stops a device backup replacing the user's only copy of their
 * networks, wallpaper or paired devices with an archive holding nothing.
 *
 * Found on device (POCO F6): `mkdir -p <cache>/Wi-Fi` succeeds even when the
 * engine behind it then fails or finds no Wi-Fi source on that ROM, and `tar`
 * packs an empty directory perfectly happily. Measured here: zapdos exits 0 on
 * empty input and produces a 22-byte archive, so the pipeline's `[ -s tmp ]`
 * guard passes and `mv` promotes it over the live archive. The run reports
 * success throughout.
 *
 * Calls and Contacts already refused to pack in this situation. These three
 * gathered into a directory rather than a file and had no equivalent check, so
 * the rule was applied to two of five items and not to three.
 */
class DeviceItemEmptinessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(name: String, build: (File) -> Unit): File {
        val d = tmp.newFolder(name)
        build(d)
        return d
    }

    private fun File.put(name: String, bytes: ByteArray) {
        File(this, name).writeBytes(bytes)
    }

    @Test
    fun `an empty directory is not content`() {
        // Exactly the state `mkdir -p` leaves behind when the engine then fails.
        assertFalse(DeviceManager.hasContent(dir("wifi-empty") {}))
    }

    @Test
    fun `a directory of empty directories is still nothing to pack`() {
        // tar emits a header for each, so the archive is non-empty and would
        // pass `[ -s ]` — while containing no data at all.
        assertFalse(
            DeviceManager.hasContent(dir("wifi-hollow") { root ->
                File(root, "WifiConfigStore").mkdirs()
                File(root, "softap.conf").mkdirs()
            })
        )
    }

    @Test
    fun `a zero byte file is not content`() {
        // A truncated or failed copy leaves exactly this.
        assertFalse(
            DeviceManager.hasContent(dir("bt-zero") { root ->
                root.put("bt_config.conf", ByteArray(0))
            })
        )
    }

    @Test
    fun `a real file is content`() {
        assertTrue(
            DeviceManager.hasContent(dir("wifi-real") { root ->
                root.put("WifiConfigStore.xml", "<Network/>".toByteArray())
            })
        )
    }

    @Test
    fun `one real file among empty siblings is enough`() {
        // The real shape: the engine wrote the config and left a stray empty dir.
        assertTrue(
            DeviceManager.hasContent(dir("bt-real") { root ->
                root.put("bt_config.conf", "key".toByteArray())
                File(root, "empty").mkdirs()
                root.put("stray", ByteArray(0))
            })
        )
    }

    @Test
    fun `content nested below an empty top level still counts`() {
        assertTrue(
            DeviceManager.hasContent(dir("wp-nested") { root ->
                File(root, "0").mkdirs()
                File(root, "0").put("wallpaper.png", byteArrayOf(1, 2, 3))
            })
        )
    }

    @Test
    fun `a path that is not a directory at all is not content`() {
        // `packArchive` is handed a name; if the stage were a plain file, or
        // vanished, the answer must still be "nothing to pack" rather than a
        // crash inside the backup.
        val f = tmp.newFile("plain")
        assertFalse(DeviceManager.hasContent(f))
        assertFalse(DeviceManager.hasContent(tmp.root))
        assertFalse(DeviceManager.hasContent(File(tmp.root, "does-not-exist")))
    }

    @Test
    fun `every device item that gathers into a directory is guarded`() {
        // The defect was a rule applied to some call sites and not others, so
        // the rule is asserted over the call sites themselves: the three items
        // that stage a DIRECTORY must each be inside a `hasContent` guard,
        // whatever the local variable or branch is later renamed to.
        val src = File("src/main/kotlin/com/phoenix/droidvault/utils/DeviceManager.kt")
            .readText()
            .let { raw ->
                // Strip comments so a guard is matched on behaviour, not on the
                // sentence explaining it.
                Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
                    .replace(raw, " ")
                    .replace(Regex("//[^\n]*"), " ")
            }
        for (item in listOf("Wi-Fi", "Wallpaper", "Bluetooth")) {
            // Anchored on the call plus the DESTINATION it writes, not the item
            // label: the label also appears on the restore side, where no
            // emptiness guard belongs and one would be meaningless.
            val itemIdx = Regex("""packArchive\([^)]*?/""" + Regex.escape(item) + """\.shift""")
                .find(src)
                ?: error("packArchive call for $item not found — re-point this test")
            // The guard must appear BEFORE the pack, not after it: a check that
            // runs afterwards has already let the good archive be replaced.
            val before = src.substring(0, itemIdx.range.first)
            assertTrue(
                "$item.shift is packed with no emptiness guard before it: an empty " +
                    "staged directory packs to a valid archive and replaces the only " +
                    "good copy",
                before.contains("hasContent")
            )
        }
    }
}