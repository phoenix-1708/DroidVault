package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppParts
import com.phoenix.droidvault.models.SeedPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rules for choosing what part of an app to back up or restore.
 *
 * These guard the per-app component page added in 2.2.2: it is the first place a
 * user can see, per app, what a run will and will not do. Every rule here used to
 * be duplicated across the chip row, the size estimate and the run seeds.
 */
class AppPartsTest {

    private fun app(
        pkg: String = "com.example",
        available: Set<Int> = emptySet(),
        appKb: Long = 1000,
        dataKb: Long = 2000,
        mediaKb: Long = 4000
    ) = AppInfo(
        label = "Example",
        packageName = pkg,
        appSizeKb = appKb,
        dataSizeKb = dataKb,
        mediaSizeKb = mediaKb,
        availableInBackup = available
    )

    // --- selectableForRestore ------------------------------------------------

    @Test
    fun `restore offers everything the archive holds when consent is given`() {
        val avail = setOf(AppParts.APP, AppParts.DATA)
        assertEquals(
            avail,
            AppParts.selectableForRestore(
                availableInBackup = avail,
                allowPermissions = true,
                allowSsaid = true
            )
        )
    }

    @Test
    fun `restore hides parts that were never backed up`() {
        val avail = setOf(AppParts.APP)
        val selectable = AppParts.selectableForRestore(
            availableInBackup = avail,
            allowPermissions = true,
            allowSsaid = true
        )
        assertEquals(setOf(AppParts.APP), selectable)
        assertFalse(AppParts.MEDIA in selectable)
    }

    @Test
    fun `restore withholds the consent gated parts when consent is off`() {
        // The point of the gate: offering a part the run strips anyway means the
        // screen promises something the engine silently drops.
        val selectable = AppParts.selectableForRestore(
            availableInBackup = AppParts.ALL,
            allowPermissions = false,
            allowSsaid = true
        )
        assertFalse(AppParts.PERMISSIONS in selectable)
        assertTrue(AppParts.SSAID in selectable)

        val other = AppParts.selectableForRestore(
            availableInBackup = AppParts.ALL,
            allowPermissions = true,
            allowSsaid = false
        )
        assertTrue(AppParts.PERMISSIONS in other)
        assertFalse(AppParts.SSAID in other)
    }

    @Test
    fun `unknown availability does not filter anything`() {
        // The list can be fetched without a vault index (no root, or a cache
        // miss), in which case availability is empty but NOT "nothing is
        // available". Filtering on it would leave a restore page with no parts.
        val selectable = AppParts.selectableForRestore(
            availableInBackup = emptySet(),
            allowPermissions = true,
            allowSsaid = true
        )
        assertEquals(AppParts.ALL, selectable)
    }

    // --- defaultSelection ----------------------------------------------------

    @Test
    fun `a newly selected backup app defaults to every part`() {
        val selected = AppParts.defaultSelection(
            isRestore = false,
            availableInBackup = emptySet(),
            allowPermissions = false,
            allowSsaid = false
        )
        assertEquals(AppParts.ALL, selected)
        // Backup does not consume restore consent: nothing is written by
        // restoring, so the gate must not empty a backup's selection.
        assertTrue(AppParts.PERMISSIONS in selected)
        assertTrue(AppParts.SSAID in selected)
    }

    @Test
    fun `a newly selected restore app defaults to what it can actually seed`() {
        val selected = AppParts.defaultSelection(
            isRestore = true,
            availableInBackup = setOf(AppParts.APP, AppParts.DATA),
            allowPermissions = true,
            allowSsaid = true
        )
        assertEquals(setOf(AppParts.APP, AppParts.DATA), selected)
    }

    @Test
    fun `a restore whose only parts are consent gated selects nothing`() {
        // The archive holds Permissions and Android ID and the user withheld
        // consent for both. There is genuinely nothing to restore, so the honest
        // answer is an empty selection — filling it from anywhere else would
        // restore parts that were never backed up. The page must explain that
        // rather than show five dead rows.
        val selected = AppParts.defaultSelection(
            isRestore = true,
            availableInBackup = setOf(AppParts.PERMISSIONS, AppParts.SSAID),
            allowPermissions = false,
            allowSsaid = false
        )
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `the same archive selects both parts once consent is granted`() {
        val selected = AppParts.defaultSelection(
            isRestore = true,
            availableInBackup = setOf(AppParts.PERMISSIONS, AppParts.SSAID),
            allowPermissions = true,
            allowSsaid = true
        )
        assertEquals(setOf(AppParts.PERMISSIONS, AppParts.SSAID), selected)
    }

    // --- pageSelection -------------------------------------------------------

    @Test
    fun `an unselected app shows no parts checked`() {
        // It has no set of its own, so it falls back to the default — but it is
        // not going to be backed up at all. Showing the default here made the
        // page claim "5 of 5 parts" for an app the run would skip.
        assertEquals(
            emptySet<Int>(),
            AppParts.pageSelection(
                isSelected = false,
                activeComponents = null,
                default = AppParts.ALL
            )
        )
    }

    @Test
    fun `an unselected app with an explicit empty set shows nothing`() {
        assertEquals(
            emptySet<Int>(),
            AppParts.pageSelection(
                isSelected = false,
                activeComponents = emptySet(),
                default = AppParts.ALL
            )
        )
    }

    @Test
    fun `a selected app with no explicit set follows the default`() {
        assertEquals(
            AppParts.ALL,
            AppParts.pageSelection(
                isSelected = true,
                activeComponents = null,
                default = AppParts.ALL
            )
        )
    }

    @Test
    fun `a selected app with its own set ignores the default`() {
        assertEquals(
            setOf(AppParts.APP),
            AppParts.pageSelection(
                isSelected = true,
                activeComponents = setOf(AppParts.APP),
                default = AppParts.ALL
            )
        )
    }

    @Test
    fun `toggling a part on an unselected app selects only that part`() {
        // Found on device: the page showed nothing checked, but the toggle began
        // from the default set, so one tap on "App" reported "4 of 5 parts". The
        // shown set and the toggled set have to be the same value.
        val shown = AppParts.pageSelection(
            isSelected = false,
            activeComponents = null,
            default = AppParts.ALL
        )
        val afterToggle = AppParts.toggle(shown, AppParts.APP, AppParts.ALL)
        assertEquals(setOf(AppParts.APP), afterToggle)
    }

    @Test
    fun `toggling the last part off leaves nothing selected`() {
        val shown = AppParts.pageSelection(
            isSelected = true,
            activeComponents = setOf(AppParts.APP),
            default = AppParts.ALL
        )
        assertEquals(emptySet<Int>(), AppParts.toggle(shown, AppParts.APP, AppParts.ALL))
    }

    @Test
    fun `applying a set to all can never grant an unavailable part`() {
        // "Apply to all" must not become a way around the availability and
        // consent gates: the user's own default is intersected with what each
        // app can actually seed.
        val selectable = AppParts.selectableForRestore(
            availableInBackup = setOf(AppParts.APP, AppParts.DATA),
            allowPermissions = false,
            allowSsaid = true
        )
        val applied = AppParts.ALL intersect selectable
        assertEquals(setOf(AppParts.APP, AppParts.DATA), applied)
        assertFalse(AppParts.PERMISSIONS in applied)
        assertFalse(AppParts.MEDIA in applied)
    }

    // --- toggle --------------------------------------------------------------

    @Test
    fun `toggle adds and removes, and stays inside the selectable set`() {
        val selectable = setOf(AppParts.APP, AppParts.DATA)
        val on = AppParts.toggle(emptySet(), AppParts.APP, selectable)
        assertEquals(setOf(AppParts.APP), on)
        val off = AppParts.toggle(on, AppParts.APP, selectable)
        assertEquals(emptySet<Int>(), off)
    }

    @Test
    fun `toggle refuses an unavailable part instead of granting it`() {
        val selectable = setOf(AppParts.APP)
        val result = AppParts.toggle(
            setOf(AppParts.APP),
            AppParts.MEDIA,
            selectable
        )
        assertEquals(setOf(AppParts.APP), result)
    }

    // --- estimate ------------------------------------------------------------

    @Test
    fun `estimate counts the real part sizes plus the fixed overhead`() {
        val a = app()
        val estimate = AppParts.estimateKb(
            setOf(AppParts.APP, AppParts.DATA, AppParts.MEDIA),
            a
        )
        assertEquals(
            AppParts.PER_APP_OVERHEAD_KB + 1000 + 2000 + 4000,
            estimate
        )
    }

    @Test
    fun `estimate charges the metadata parts their fixed cost`() {
        val estimate = AppParts.estimateKb(
            setOf(AppParts.PERMISSIONS, AppParts.SSAID),
            app()
        )
        assertEquals(
            AppParts.PER_APP_OVERHEAD_KB +
                    AppParts.PERMISSIONS_KB +
                    AppParts.SSAID_KB,
            estimate
        )
    }

    @Test
    fun `estimate falls as parts are cleared`() {
        val a = app()
        val full = AppParts.estimateKb(AppParts.ALL, a)
        val appOnly = AppParts.estimateKb(setOf(AppParts.APP), a)
        assertTrue(appOnly < full)
    }

    @Test
    fun `an empty selection still carries the per-app overhead`() {
        // A run with an app selected and no parts writes the app directory and
        // its Meta.txt regardless, so the estimate must not read as zero.
        assertEquals(
            AppParts.PER_APP_OVERHEAD_KB,
            AppParts.estimateKb(emptySet(), app())
        )
    }

    @Test
    fun `per-app selection estimates are priced individually`() {
        // The "apply to all" shortcut assumes one shared set; the page does not.
        // Swapping app a from APP (1000 KB) to DATA (2000 KB) must move the total
        // by exactly that app's data size, not by the shared set's average.
        val a = app(pkg = "a")
        val b = app(pkg = "b")
        val shared = AppParts.estimateKb(setOf(AppParts.APP), listOf(a, b))
        val perApp = AppParts.estimateKb(
            listOf(a to setOf(AppParts.DATA), b to setOf(AppParts.APP))
        )
        assertEquals(1000L, perApp - shared)
    }

    // --- agreement with the run-time seed policy -----------------------------

    @Test
    fun `the offered selection and the run-time seed agree`() {
        // The picker says these parts will be restored; SeedPolicy decides what
        // the engine is actually handed. A mismatch here is the "5/5 parts with
        // three greyed chips" bug from the field report.
        val available = setOf(AppParts.APP, AppParts.MEDIA)
        val offered = AppParts.selectableForRestore(
            availableInBackup = available,
            allowPermissions = true,
            allowSsaid = true
        )
        val seeded = SeedPolicy.restoreComponents(offered, available)
        assertEquals(offered, seeded)
        assertTrue(seeded.all { it in available })
    }

    @Test
    fun `a count of one is singular`() {
        // Found on device twice: the health card read "All 1 backups readable"
        // and the export toast read "Exported 1 backup(s)". Both sentences were
        // written by hand, and both shipped the wrong plural.
        assertEquals("1 app", AppParts.count(1, "app"))
        assertEquals("1 backup", AppParts.count(1, "backup"))
        assertEquals("1 part", AppParts.count(1, "part"))
        assertEquals("1 configuration", AppParts.count(1, "configuration"))
        assertEquals("1 schedule", AppParts.count(1, "schedule"))
    }

    @Test
    fun `the progress row subtitle reads as a count and a part count`() {
        // Reported off a finished run's screen: the subtitle read "1/1 part}"
        // and "5/5 parts}". The row was a string template written inline in the
        // Composable with one `}` too many, and a `}` outside a `${…}` is an
        // ordinary character — so the compiler agreed, and nothing rendered the
        // row except a completed run.
        assertEquals("1/1 part", AppParts.progressMeta(1, 1))
        assertEquals("5/5 parts", AppParts.progressMeta(5, 5))
        assertEquals("3/6 parts", AppParts.progressMeta(3, 6))
        assertEquals("0/5 parts", AppParts.progressMeta(0, 5))
    }

    @Test
    fun `the progress row subtitle never carries a stray brace`() {
        // Asserted separately from the expected strings so a regression names
        // the defect rather than just failing to match.
        for (done in 0..6) {
            for (parts in 1..6) {
                val meta = AppParts.progressMeta(done, parts)
                assertFalse(
                    "a user-facing subtitle must not contain a brace: \"$meta\"",
                    meta.any { it == '{' || it == '}' }
                )
            }
        }
    }

    @Test
    fun `an app with no parts contributes no subtitle`() {
        // The caller adds a size instead; an empty string would render as a
        // leading " • " on the joined row.
        assertEquals("", AppParts.progressMeta(0, 0))
        assertEquals("", AppParts.progressMeta(3, -1))
    }

    @Test
    fun `every other count is plural`() {
        // Zero is plural, which is why "No apps" style copy must not route
        // through this helper — it describes absence, not a count.
        assertEquals("0 apps", AppParts.count(0, "app"))
        assertEquals("2 apps", AppParts.count(2, "app"))
        assertEquals("107 backups", AppParts.count(107, "backup"))
        assertEquals("5 parts", AppParts.count(5, "part"))
    }

    @Test
    fun `an irregular plural can be supplied`() {
        assertEquals("2 media", AppParts.count(2, "medium", "media"))
    }
}