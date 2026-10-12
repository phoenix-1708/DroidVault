package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppLabel
import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.BackupConfig
import com.phoenix.droidvault.models.CONFIG_LIST_ORDER_DEFAULT
import com.phoenix.droidvault.models.ConfigOperation
import com.phoenix.droidvault.models.ConfigRule
import com.phoenix.droidvault.models.FallbackPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codec round-trips + schema tolerance (spec §31, §44). */
class AutomationCodecTest {

    @Test fun labelRoundTrip() {
        val l = AppLabel("id1", "FULL", "everything", 1L, 2L)
        val back = AutomationCodec.labelFrom(AutomationCodec.labelTo(l))
        assertEquals(l, back)
    }

    @Test fun configRoundTrip() {
        val c = BackupConfig(
            id = "c1", name = "Daily", description = "d", enabled = true,
            rules = listOf(
                ConfigRule("r1", "L1", 1, setOf(BackupComponent.APK, BackupComponent.PRIVATE_DATA)),
                ConfigRule("r2", "L2", 2, emptySet(), exclude = true)
            ),
            fallback = FallbackPolicy.USE_DEFAULT,
            defaultComponents = setOf(BackupComponent.APK),
            destinationId = AutomationStore.DEST_DEFAULT
        )
        val back = AutomationCodec.configFrom(AutomationCodec.configTo(c))
        assertEquals(c, back)
    }

    @Test fun configFoldersRoundTrip() {
        val c = BackupConfig(
            id = "cf", name = "Folders",
            deviceData = setOf(com.phoenix.droidvault.models.DeviceDataItem.FOLDERS),
            folderPaths = listOf("/storage/emulated/0/Magisk", "/storage/emulated/0/Android/data/com.whatsapp")
        )
        val back = AutomationCodec.configFrom(AutomationCodec.configTo(c))
        assertEquals(c, back)
    }

    @Test fun configListOrderRoundTrip() {
        val c = BackupConfig(id = "c", name = "n", listOrder = 3L)
        val back = AutomationCodec.configFrom(AutomationCodec.configTo(c))
        assertEquals(3L, back.listOrder)
    }

    @Test fun configListOrderDefaultsWhenAbsent() {
        val o = JSONObject().put("id", "c").put("name", "n")
        assertEquals(CONFIG_LIST_ORDER_DEFAULT, AutomationCodec.configFrom(o).listOrder)
    }

    @Test fun reorderSwapsWithinOperationOnly() {
        val b1 = BackupConfig(id = "b1", name = "Alpha", operation = ConfigOperation.BACKUP)
        val b2 = BackupConfig(id = "b2", name = "Beta", operation = ConfigOperation.BACKUP)
        val r1 = BackupConfig(id = "r1", name = "Restore A", operation = ConfigOperation.RESTORE)
        val r2 = BackupConfig(id = "r2", name = "Restore B", operation = ConfigOperation.RESTORE)

        // Move b2 up: only the BACKUP group is renumbered; restores untouched.
        val out = reorderedConfigs(listOf(b1, b2, r1, r2), "b2", up = true)!!
        val backupIds = out.filter { it.operation == ConfigOperation.BACKUP }
            .sortedBy { it.listOrder }.map { it.id }
        assertEquals(listOf("b2", "b1"), backupIds)
        assertEquals(CONFIG_LIST_ORDER_DEFAULT, out.first { it.id == "r1" }.listOrder)
        assertEquals(CONFIG_LIST_ORDER_DEFAULT, out.first { it.id == "r2" }.listOrder)
    }

    @Test fun reorderAtEdgesIsNoop() {
        val b1 = BackupConfig(id = "b1", name = "Alpha", operation = ConfigOperation.BACKUP)
        val b2 = BackupConfig(id = "b2", name = "Beta", operation = ConfigOperation.BACKUP)
        // Already first: moving up changes nothing.
        assertEquals(listOf("b1", "b2"), reorderedConfigs(listOf(b1, b2), "b1", up = true)!!.map { it.id })
        // Already last: moving down changes nothing.
        assertEquals(listOf("b1", "b2"), reorderedConfigs(listOf(b1, b2), "b2", up = false)!!.map { it.id })
    }

    @Test fun reorderUnknownConfigReturnsNull() {
        val b1 = BackupConfig(id = "b1", name = "Alpha")
        assertEquals(null, reorderedConfigs(listOf(b1), "nope", up = true))
    }

    @Test fun reorderThenListOrderSurvivesSequenceOfMoves() {
        val a = BackupConfig(id = "a", name = "a", listOrder = 1L)
        val b = BackupConfig(id = "b", name = "b", listOrder = 2L)
        val c = BackupConfig(id = "c", name = "c", listOrder = 3L)
        var list = listOf(a, b, c)
        // One-slot swaps against the visible order: b up → [b, a, c],
        // then c up → [b, c, a].
        list = reorderedConfigs(list, "b", up = true)!!
        list = reorderedConfigs(list, "c", up = true)!!
        assertEquals(listOf("b", "c", "a"), list.sortedBy { it.listOrder }.map { it.id })
        // Positions are always dense 1..n after each move.
        assertEquals(listOf(1L, 2L, 3L), list.sortedBy { it.listOrder }.map { it.listOrder })
    }

    @Test fun reorderUsesDisplayNameOrderForTiedEntries() {
        // All DEFAULT: visible order is alphabetical (b, c, a) via the name
        // tie-break — swapping must follow that, not raw list order.
        val a = BackupConfig(id = "a", name = "c")
        val b = BackupConfig(id = "b", name = "b")
        val c = BackupConfig(id = "c", name = "b2")
        val out = reorderedConfigs(listOf(a, b, c), "a", up = true)!!
        // Visible [b, c, a] → moving a up one slot gives [b, a, c].
        assertEquals(listOf("b", "a", "c"), out.sortedBy { it.listOrder }.map { it.id })
    }

    @Test fun invalidFolderPathsDroppedOnImport() {
        val o = AutomationCodec.configTo(
            BackupConfig(id = "c", name = "n", folderPaths = listOf("/storage/emulated/0/ok"))
        )
        // Inject paths that must not survive an import.
        o.put("folderPaths", org.json.JSONArray(listOf(
            "relative/path", "", "/data/adb", "/storage/emulated/0/ok", "/tmp/x;rm -rf /"
        )))
        val c = AutomationCodec.configFrom(o)
        assertEquals(listOf("/storage/emulated/0/ok"), c.folderPaths)
    }

    @Test fun folderManifestRoundTripAndSanitize() {
        val folders = listOf("/storage/emulated/0/A", "/storage/emulated/0/Android/obb")
        assertEquals(folders, BackupConfigFolders.manifestFolders(BackupConfigFolders.manifestJson(folders)))
        assertEquals(emptyList<String>(), BackupConfigFolders.manifestFolders("{not json"))
        assertEquals(
            listOf("/storage/emulated/0/A", "/storage/emulated/0/B"),
            BackupConfigFolders.sanitize(listOf("/storage/emulated/0/A/", "x", "/storage/emulated/0/A", "/storage/emulated/0/B"))
        )
    }

    @Test fun unknownComponentIgnoredForForwardTolerance() {
        val o = JSONObject().put("id", "r").put("labelId", "L").put("priority", 1)
            .put("components", org.json.JSONArray(listOf("APK", "FUTURE_THING")))
        val r = AutomationCodec.ruleFrom(o)
        assertEquals(setOf(BackupComponent.APK), r.components)
    }

    @Test fun unknownFallbackDefaultsToSkip() {
        val o = JSONObject().put("id", "c").put("name", "n")
            .put("rules", org.json.JSONArray())
        val c = AutomationCodec.configFrom(o)
        assertEquals(FallbackPolicy.SKIP, c.fallback)
        assertTrue(c.rules.isEmpty())
    }

    @Test fun exportEnvelopeHasSchemaVersion() {
        val o = JSONObject().put("schemaVersion", AutomationStore.SCHEMA_VERSION)
        assertEquals(AutomationStore.SCHEMA_VERSION, o.getInt("schemaVersion"))
    }

    @Test fun assignmentRemapPrefersNameThenIdThenDrops() {
        val byName = mapOf("full" to "new-id")
        val ids = setOf("new-id", "old-id")
        // Name match wins even when the id is stale.
        val a = AutomationCodec.resolveAssignment(
            JSONObject().put("packageName", "com.x").put("labelId", "stale-id").put("labelName", "FULL"),
            byName, ids
        )
        assertEquals(null, a.droppedReason)
        assertEquals("new-id", a.labelId)
        // No name: id fallback works on same-device re-import.
        val b = AutomationCodec.resolveAssignment(
            JSONObject().put("packageName", "com.x").put("labelId", "old-id"),
            byName, ids
        )
        assertEquals(null, b.droppedReason)
        assertEquals("old-id", b.labelId)
        // Neither: dropped with reason instead of silent loss.
        val c = AutomationCodec.resolveAssignment(
            JSONObject().put("packageName", "com.x").put("labelId", "ghost").put("labelName", "Ghost"),
            byName, ids
        )
        assertTrue(c.droppedReason!!.contains("Ghost"))
    }

    @Test fun ruleRemapKeepsResolvableDropsUnknown() {
        val rules = listOf(
            com.phoenix.droidvault.models.ConfigRule("r1", "old-full", 1, setOf(BackupComponent.APK)),
            com.phoenix.droidvault.models.ConfigRule("r2", "ghost-id", 2, setOf(BackupComponent.APK))
        )
        val incomingNames = mapOf("old-full" to "FULL")
        val res = AutomationCodec.resolveRules(
            rules, incomingNames, mapOf("full" to "new-id"), setOf("new-id")
        )
        assertEquals(1, res.rules.size)
        assertEquals("new-id", res.rules.single().labelId)
        assertEquals(listOf("ghost-id"), res.missingLabels)
    }

    @Test fun assignmentCarriesLabelName() {
        val a = com.phoenix.droidvault.models.LabelAssignment("com.x", "id-1")
        val o = AutomationCodec.assignmentTo(a, "FULL")
        assertEquals("FULL", o.getString("labelName"))
        assertEquals("id-1", o.getString("labelId"))
    }

    @Test fun assignmentRoundTripsLabelNameHint() {
        // The name hint must SURVIVE a persist→read cycle: it is the relink
        // key after a reinstall re-issues label UUIDs.
        val row = AutomationCodec.assignmentTo(
            com.phoenix.droidvault.models.LabelAssignment("com.x", "old-uuid"), "FULL"
        )
        val back = AutomationCodec.assignmentFrom(row)
        assertEquals("FULL", back.labelName)
        assertEquals("old-uuid", back.labelId)
        // Rows from before the field existed parse with an empty hint.
        assertEquals("", AutomationCodec.assignmentFrom(
            org.json.JSONObject().put("packageName", "com.x").put("labelId", "x")
        ).labelName)
    }

    @Test fun assignmentRelinkResolvesBySurvivingNameHint() {
        val labels = listOf(
            com.phoenix.droidvault.models.AppLabel("new-uuid", "FULL", ""),
            com.phoenix.droidvault.models.AppLabel("other-uuid", "Media", "")
        )
        val assignments = listOf(
            com.phoenix.droidvault.models.LabelAssignment("com.a", "dead-uuid", labelName = "full"),
            com.phoenix.droidvault.models.LabelAssignment("com.b", "other-uuid", labelName = ""),
            com.phoenix.droidvault.models.LabelAssignment("com.c", "no-hint-uuid", labelName = "")
        )
        val relinked = com.phoenix.droidvault.utils.AutomationStore.relinkAssignmentsToLabels(assignments, labels)
        assertEquals("new-uuid", relinked[0].labelId)
        assertEquals("other-uuid", relinked[1].labelId)
        assertEquals("no-hint-uuid", relinked[2].labelId) // no hint → untouched
    }

    @Test fun assignmentEqualsIgnoresNameHint() {
        val a = com.phoenix.droidvault.models.LabelAssignment("com.x", "id-1", labelName = "FULL")
        val b = com.phoenix.droidvault.models.LabelAssignment("com.x", "id-1", labelName = "")
        assertEquals(a, b)
    }

    @Test fun ruleRowCarriesLabelNameForReinstallRelink() {
        // Every persisted rule row embeds its label's name: a reinstall
        // re-issues label UUIDs, and the name is the exact relink key.
        val r = com.phoenix.droidvault.models.ConfigRule(
            "r1", "old-id", 1, setOf(BackupComponent.APK), labelName = "FULL"
        )
        val back = AutomationCodec.ruleFrom(AutomationCodec.ruleTo(r))
        assertEquals(r, back)
        // Export shape overrides with the live name (unchanged behavior).
        assertEquals("LIVE", AutomationCodec.ruleTo(r, "LIVE").getString("labelName"))
        // Rows from before the field existed parse with an empty hint.
        assertEquals("", AutomationCodec.ruleFrom(JSONObject().put("labelId", "x")).labelName)
    }

    // ---------- full-config export/import round trip (all fields) ----------

    @Test
    fun fullConfigRoundTripKeepsEveryField() {
        val c = BackupConfig(
            id = "full-1", name = "Nightly", description = "all fields",
            enabled = false,
            rules = listOf(
                com.phoenix.droidvault.models.ConfigRule(
                    "r1", "L1", 1,
                    setOf(
                        BackupComponent.APK, BackupComponent.PRIVATE_DATA,
                        BackupComponent.PERMISSIONS, BackupComponent.MEDIA,
                        BackupComponent.ANDROID_ID
                    ),
                    exclude = false
                ),
                com.phoenix.droidvault.models.ConfigRule("r2", "L2", 2, emptySet(), exclude = true)
            ),
            fallback = FallbackPolicy.USE_DEFAULT,
            defaultComponents = setOf(BackupComponent.APK, BackupComponent.MEDIA),
            restoreGraceful = false,
            deviceData = com.phoenix.droidvault.models.DeviceDataItem.entries.toSet(),
            folderPaths = listOf("/storage/emulated/0/Android/media/com.whatsapp")
        )
        val back = AutomationCodec.configFrom(AutomationCodec.configTo(c))
        assertEquals(c, back)
    }

    @Test
    fun universalFolderSelectionResolvesAgainstStore() {
        val store = listOf(
            "/storage/emulated/0/Android/media/com.whatsapp",
            "/storage/emulated/0/Magisk"
        )
        // No explicit selection → the whole store (a folder added in Settings
        // is picked up by every FOLDERS-enabled config immediately).
        assertEquals(
            store,
            BackupConfigFolders.resolveAgainstStore(emptyList(), store)
        )
        // Explicit selection → pruned to what still exists in the store (a
        // folder removed in Settings never runs again — no stale cache).
        assertEquals(
            listOf("/storage/emulated/0/Magisk"),
            BackupConfigFolders.resolveAgainstStore(
                listOf("/storage/emulated/0/Magisk", "/storage/emulated/0/Removed"),
                store
            )
        )
        // Selection narrowed to nothing (all removed) → honest empty, which the
        // runner records as SKIPPED instead of a phantom backup.
        assertEquals(
            emptyList<String>(),
            BackupConfigFolders.resolveAgainstStore(
                listOf("/storage/emulated/0/Removed"), store
            )
        )
    }

    @Test
    fun shortDisplayNameShowsRealFolderNeverGeneric() {
        // Final segment only — the app shows folder NAMES, not full paths.
        assertEquals(
            "Magisk",
            BackupConfigFolders.shortDisplayName("/storage/emulated/0/Magisk")
        )
        val long = "/storage/emulated/0/Android/media/com.whatsapp/Backups"
        val shown = BackupConfigFolders.shortDisplayName(long)
        assertEquals("Backups", shown)
        assertTrue(!shown.contains("Folder"))
    }

    @Test
    fun shortDisplayNameDisambiguatesSameNamedFolders() {
        BackupConfigFolders.setDisplayContext(
            listOf("/storage/emulated/0/App/A", "/storage/emulated/1/App/A")
        )
        assertEquals("App/A", BackupConfigFolders.shortDisplayName("/storage/emulated/0/App/A"))
        BackupConfigFolders.setDisplayContext(emptyList())
        assertEquals("A", BackupConfigFolders.shortDisplayName("/storage/emulated/0/App/A"))
    }
}
