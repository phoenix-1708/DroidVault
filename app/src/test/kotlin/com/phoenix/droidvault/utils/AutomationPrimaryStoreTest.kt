package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.AppLabel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Primary-storage contract (automation-state.json in the Droid Vault folder):
 * the document shape every writer must produce and every reader accepts.
 * Pure parsing only — no Android context / shell needed.
 */
class AutomationPrimaryStoreTest {

    private fun validDoc(): JSONObject {
        val label = AutomationCodec.labelTo(AppLabel("l1", "FULL", "everything", 10L, 20L))
        return JSONObject()
            .put(AutomationStore.KEY_SCHEMA_VERSION, AutomationStore.SCHEMA_VERSION)
            .put(AutomationStore.KEY_STORE_REV, 7L)
            .put(AutomationStore.KEY_LABELS, JSONArray().put(label))
            .put(AutomationStore.KEY_ASSIGNMENTS, JSONArray())
            .put(AutomationStore.KEY_CONFIGS, JSONArray())
            .put(AutomationStore.KEY_SCHEDULES, JSONArray())
            .put(AutomationStore.KEY_MASTER, false)
    }

    @Test fun parseAcceptsValidDocument() {
        val parsed = AutomationStore.parseStateFile(validDoc().toString())
        assertNotNull(parsed)
        parsed!!
        assertEquals(7L, parsed.rev)
        assertEquals(1, parsed.labels?.size)
        assertEquals("l1", parsed.labels!![0].optString("id"))
        // Empty arrays surface as null (key-absent semantics for fallback).
        assertNull(parsed.assignments)
        assertNull(parsed.configs)
        assertNull(parsed.schedules)
        assertEquals(false, parsed.masterEnabled)
    }

    @Test fun parseRejectsGarbageAndWrongSchema() {
        assertNull(AutomationStore.parseStateFile(null))
        assertNull(AutomationStore.parseStateFile(""))
        assertNull(AutomationStore.parseStateFile("not json"))
        assertNull(AutomationStore.parseStateFile("[]"))
        assertNull(AutomationStore.parseStateFile(JSONObject().put("schemaVersion", 99).toString()))
        assertNull(AutomationStore.parseStateFile(JSONObject().toString()))
    }

    @Test fun parseToleratesCorruptRows() {
        val doc = validDoc()
        doc.put(
            AutomationStore.KEY_LABELS,
            JSONArray().put("garbage").put(JSONObject().put("id", "l2").put("name", "X"))
        )
        val parsed = AutomationStore.parseStateFile(doc.toString())!!
        assertEquals(1, parsed.labels?.size)
        assertEquals("l2", parsed.labels!![0].optString("id"))
    }

    @Test fun labelRowsRoundTripThroughCodec() {
        val l = AppLabel("id1", "FULL", "everything", 1L, 2L)
        val row = AutomationCodec.labelTo(l)
        val parsed = AutomationStore.parseStateFile(
            validDoc().put(AutomationStore.KEY_LABELS, JSONArray().put(row)).toString()
        )!!
        val back = AutomationCodec.labelFrom(parsed.labels!!.first())
        assertEquals(l, back)
    }

    @Test fun keyNamesMatchExportShape() {
        // The primary file shares the export schema — the ROM-survival
        // snapshot and the live store must stay interchangeable. Empty
        // sections surface as null (key-absent → prefs fallback semantics).
        val exported = JSONObject(
            "{schemaVersion:2,labels:[],assignments:[],configurations:[],schedules:[],masterEnabled:true}"
        )
        val parsed = AutomationStore.parseStateFile(exported.toString())
        assertNotNull(parsed)
        assertEquals(true, parsed!!.masterEnabled)
        assertNull(parsed.configs)
    }

    @Test fun prefArrayParserIsTolerant() {
        assertTrue(AutomationStore.parsePrefArray(null).isEmpty())
        assertTrue(AutomationStore.parsePrefArray("").isEmpty())
        assertTrue(AutomationStore.parsePrefArray("{broken").isEmpty())
        assertEquals(2, AutomationStore.parsePrefArray("""[{"a":1},"x",{"b":2}]""").size)
    }

    // ---- write-side merge contract (fresh-install anti-clobber) ----

    private fun row(id: String): JSONObject = JSONObject().put("id", id)

    @Test fun neverInitializedCacheAdoptsFileRows() {
        // Fresh install / app-data clear: prefs cache absent, file has rows.
        // A mutation must carry the file's untouched sections through instead
        // of clobbering them to empty (the field-observed label wipe).
        val merged = AutomationStore.resolveSectionForWrite(null, listOf(row("l1"), row("l2")))
        assertEquals(2, merged.size)
        assertEquals("l1", merged[0].optString("id"))
    }

    @Test fun initializedCacheWinsIncludingDeliberateEmpty() {
        // writeArray always leaves a string, even "[]" — an initialized cache
        // reflects user intent (e.g. the last label was deleted) and must win
        // over stale file rows, or deleted data would resurrect.
        val merged = AutomationStore.resolveSectionForWrite("[]", listOf(row("l1")))
        assertTrue(merged.isEmpty())
        val merged2 = AutomationStore.resolveSectionForWrite("""[{"id":"l9"}]""", listOf(row("l1")))
        assertEquals("l9", merged2.single().optString("id"))
    }

    @Test fun absentEverywhereYieldsEmptySection() {
        assertTrue(AutomationStore.resolveSectionForWrite(null, null).isEmpty())
        // Corrupt cache JSON degrades to file rows rather than throwing.
        val merged = AutomationStore.resolveSectionForWrite("{broken", listOf(row("l1")))
        assertTrue(merged.isEmpty())
    }

    @Test fun mergedDocumentKeepsUnwrittenSections() {
        // buildStateJson's merge: prefs null for labels + file rows in the
        // parsed document → the serialized document must keep the labels.
        val labels = listOf(AutomationCodec.labelTo(AppLabel("l1", "FULL", "", 1L, 2L)))
        val merged = AutomationStore.resolveSectionForWrite(null, labels)
        assertEquals(1, merged.size)
        assertEquals("FULL", AutomationCodec.labelFrom(merged[0]).name)
    }

    @Test fun stateFileNameIsStable() {
        // Primary location contract: <Droid Vault folder>/automation-state.json.
        // (The folder constant itself lives in SettingsManager, which touches
        // android.os.Environment — not unit-testable on the JVM.)
        assertEquals("automation-state.json", AutomationSnapshot.STATE_FILE)
    }

    // ---- reinstall self-heal: exact by-name rule relink ----

    private fun rule(id: String, labelId: String, name: String = "") =
        com.phoenix.droidvault.models.ConfigRule(id, labelId, 1, emptySet(), labelName = name)

    @Test fun relinkRemapsDanglingRulesByRecordedName() {
        // The field bug: a reinstall re-issues label UUIDs while rules carry
        // the previous install's ids. Repair = exact remap by recorded name.
        val labels = listOf(
            AppLabel("new-1", "Games", "", 1L, 1L),
            AppLabel("new-2", "Banking", "", 1L, 1L)
        )
        val rules = listOf(
            rule("r1", "stale-a", name = "Games"),  // dangling id, known name → remap
            rule("r2", "new-2", name = "Banking"),  // already resolves → untouched
            rule("r3", "stale-b", name = "Gone"),   // unknown name → left alone
            rule("r4", "stale-c")                   // no name hint → left alone
        )
        val out = AutomationStore.relinkRulesToLabels(rules, labels)
        assertEquals("new-1", out[0].labelId)
        assertEquals("new-2", out[1].labelId)
        assertEquals("stale-b", out[2].labelId)
        assertEquals("stale-c", out[3].labelId)
        // Nothing else about a relinked rule may change.
        assertEquals("r1", out[0].id)
    }

    @Test fun relinkIsCaseInsensitiveAndFirstMatchWins() {
        val labels = listOf(
            AppLabel("id-a", "Daily Backup", "", 1L, 1L),
            AppLabel("id-b", "daily backup", "", 1L, 1L)
        )
        val out = AutomationStore.relinkRulesToLabels(
            listOf(rule("r1", "stale", name = "DAILY BACKUP")), labels
        )
        assertEquals("id-a", out.single().labelId)
    }

    @Test fun relinkNoOpsOnHealthyStoresAndDegenerateInputs() {
        val labels = listOf(AppLabel("l1", "FULL", "", 1L, 1L))
        val healthy = listOf(rule("r1", "l1", name = "FULL"))
        assertTrue(AutomationStore.relinkRulesToLabels(healthy, labels) === healthy)
        val empty = emptyList<com.phoenix.droidvault.models.ConfigRule>()
        assertTrue(AutomationStore.relinkRulesToLabels(empty, labels) === empty)
        assertTrue(AutomationStore.relinkRulesToLabels(healthy, emptyList()) === healthy)
    }
}
