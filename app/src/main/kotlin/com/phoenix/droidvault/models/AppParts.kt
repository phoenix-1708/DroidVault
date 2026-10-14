package com.phoenix.droidvault.models

/**
 * The five backable parts of an app, and every pure rule about choosing them.
 *
 * This is where the component logic lives so it can be unit-tested without an
 * Android runtime. The engine consumes the same integers (`--comps` flag and
 * the per-app targets file), so these ids are a wire format, not a UI detail —
 * changing one changes what a restore actually does.
 */
object AppParts {
    const val APP = 1
    const val DATA = 2
    const val PERMISSIONS = 3
    const val MEDIA = 4
    const val SSAID = 5

    /** Canonical display order. Not numerically sorted: Permissions and Android
     *  ID are metadata, so they belong after the bulk data they describe. */
    val ORDER = listOf(APP, DATA, MEDIA, PERMISSIONS, SSAID)

    val ALL = ORDER.toSet()

    /** Fixed per-app bookkeeping the engine adds regardless of components. */
    const val PER_APP_OVERHEAD_KB = 25L

    /** Size charged for the two metadata parts. Mirrors what the engine writes. */
    const val PERMISSIONS_KB = 5L
    const val SSAID_KB = 1L

    fun label(id: Int): String = when (id) {
        APP -> "App"
        DATA -> "Data & External"
        PERMISSIONS -> "Permissions"
        MEDIA -> "Media & OBB"
        SSAID -> "Android ID"
        else -> "Component $id"
    }

    /** One-word label for the compact summary line on a list row. */
    fun shortLabel(id: Int): String = when (id) {
        APP -> "App"
        DATA -> "Data"
        PERMISSIONS -> "Perms"
        MEDIA -> "Media"
        SSAID -> "AndID"
        else -> "?$id"
    }

    /** What this part actually is, for the detail page. */
    fun description(id: Int): String = when (id) {
        APP -> "The APK itself, plus split APKs and native libraries."
        DATA -> "Internal app data, device-protected storage and the app's own external files."
        MEDIA -> "Shared media in Android/media and the app's OBB expansion files."
        PERMISSIONS -> "Granted permissions and app-ops. Restoring these can grant access the app did not have."
        SSAID -> "The Android ID this app was backed up with, so it keeps the same identity after restore."
        else -> ""
    }

    /** True for the two parts gated by an explicit restore consent. */
    fun isConsentGated(id: Int): Boolean = id == PERMISSIONS || id == SSAID

    /**
     * What the component page should show checked for this app.
     *
     * An app that is NOT selected shows nothing, even though it has no explicit
     * set of its own yet. Falling back to [default] there made the page claim
     * "5 of 5 parts" for an app that was not going to be backed up at all — the
     * page and the run would have disagreed about whether the app was included.
     */
    fun pageSelection(isSelected: Boolean, activeComponents: Set<Int>?, default: Set<Int>): Set<Int> =
        if (isSelected) (activeComponents ?: default) else emptySet()

    /**
     * The parts a restore may actually seed, and therefore the parts the detail
     * page may offer.
     *
     * Two independent gates apply, and BOTH belong here so the picker cannot
     * offer something the run would silently drop:
     *  - consent: the run strips Permissions/Android ID unless the user allowed
     *    it ([InstallerPolicy]). Offering them anyway produces a selection that
     *    quietly restores less than the screen claims.
     *  - availability: only what the archive holds can be restored. Empty
     *    availability means the list was not fetched from the vault, so nothing
     *    is filtered — see [SeedPolicy] for the matching run-time rule.
     */
    fun selectableForRestore(
        availableInBackup: Set<Int>,
        allowPermissions: Boolean,
        allowSsaid: Boolean
    ): Set<Int> {
        val consented = ALL.filterTo(mutableSetOf()) { id ->
            when (id) {
                PERMISSIONS -> allowPermissions
                SSAID -> allowSsaid
                else -> true
            }
        }
        return if (availableInBackup.isEmpty()) consented
        else consented intersect availableInBackup
    }

    /**
     * What a newly selected app should start with.
     *
     * A restore seeds exactly what it has and is allowed to seed; a backup seeds
     * everything, because the engine creates what it is asked to. Selecting an
     * app therefore materialises an explicit set instead of leaving it null —
     * a null means "fall back to the global default" downstream, which is what
     * let a row claim parts the run would never write.
     */
    fun defaultSelection(
        isRestore: Boolean,
        availableInBackup: Set<Int>,
        allowPermissions: Boolean,
        allowSsaid: Boolean
    ): Set<Int> =
        if (isRestore) selectableForRestore(availableInBackup, allowPermissions, allowSsaid)
        else ALL

    /**
     * Toggle one part, clamped to [selectable].
     *
     * Returns the input unchanged when [id] is not selectable, so a stale tap
     * cannot smuggle an unavailable part back into the set.
     */
    fun toggle(current: Set<Int>, id: Int, selectable: Set<Int>): Set<Int> {
        if (id !in selectable) return current
        return if (id in current) current - id else current + id
    }

    /** Bytes this component contributes for one app, in KB. */
    fun sizeKb(id: Int, app: AppInfo): Long = when (id) {
        APP -> app.appSizeKb
        DATA -> app.dataSizeKb
        MEDIA -> app.mediaSizeKb
        PERMISSIONS -> PERMISSIONS_KB
        SSAID -> SSAID_KB
        else -> 0L
    }

    /**
     * Total vault cost of backing up [components] for [app].
     *
     * The single source of truth for the estimate the detail page shows and the
     * "Need:" figure the run button guards on. They used to be computed
     * independently, so the sheet could promise a size the button disagreed
     * with.
     */
    fun estimateKb(components: Set<Int>, app: AppInfo): Long =
        PER_APP_OVERHEAD_KB + components.sumOf { sizeKb(it, app) }

    /**
     * Estimate for a whole selection.
     *
     * Takes each app WITH its own component set rather than one shared set:
     * per-app choices are the point of the new picker, so assuming a single set
     * would misprice any selection the user tailored.
     */
    fun estimateKb(selection: Iterable<Pair<AppInfo, Set<Int>>>): Long =
        selection.sumOf { (app, components) -> estimateKb(components, app) }

    /**
     * The estimate when every app shares one component set — the "apply to all"
     * path, and the only case where a single set is honest.
     */
    fun estimateKb(components: Set<Int>, apps: Iterable<AppInfo>): Long =
        apps.sumOf { estimateKb(components, it) }

    /**
     * `"1 app"` / `"2 apps"` — count plus noun, pluralised on the count alone.
     *
     * This exists because the same sentence was hand-written in six places and
     * each shipped a `1 apps` at some point: the Home health card, the export
     * toast, the config card, the verification dialog, the import summary. A
     * plural rule re-implemented six times is re-implemented wrongly six times,
     * and the failure is invisible until a user happens to have exactly one of
     * something.
     *
     * [pluralNoun] defaults to [noun] plus `s`, which is correct for every noun
     * this project counts (app, backup, part, label, config, schedule,
     * assignment, folder, archive). Irregulars pass both.
     *
     * Zero is deliberately plural: this describes a count, not an absence.
     * Copy that means "none" ("No apps") must not route through here.
     */
    fun count(count: Int, noun: String, pluralNoun: String = noun + "s"): String =
        "$count ${if (count == 1) noun else pluralNoun}"
}
// ---------- Incremental backup ----------

/**
 * Which parts of [app] a backup run would actually re-write.
 *
 * The engine ALREADY skips a part whose recorded size still matches the live
 * size (`backup_app` compares `Meta::app_size` / `data_size` / `media_size` and
 * emits `already backed up`). That is invisible from the UI, and — worse — the
 * Kotlin-side estimate added every selected app's FULL size, so a nightly
 * re-backup of 63 unchanged apps claimed "Need: 38 GB" and then wrote almost
 * nothing.
 *
 * Mirroring the engine's test here is what makes the estimate honest and lets
 * the user select only what changed.
 *
 * Permissions and Android ID are always rewritten: both are single small text
 * files read fresh from the system, so there is nothing to skip and skipping
 * would risk restoring a stale snapshot.
 *
 * An UNCHANGED result is only claimed when an archive exists AND its recorded
 * size is known AND it matches. Anything unknown is treated as changed, because
 * wrongly skipping a part means silently not backing data up.
 */
object ChangeDetection {

    /** Recorded size for a component, or -1 when nothing is recorded. */
    fun recordedKb(app: AppInfo, component: Int): Long = when (component) {
        AppParts.APP -> app.backedUpAppKb
        AppParts.DATA -> app.backedUpDataKb
        AppParts.MEDIA -> app.backedUpMediaKb
        AppParts.PERMISSIONS -> -1L
        AppParts.SSAID -> -1L
        else -> -1L
    }

    /** Does an archive for this part exist in the vault? */
    fun archived(app: AppInfo, component: Int): Boolean =
        component in app.availableInBackup

    /**
     * True when the engine would re-pack this part.
     *
     * Mirrors two engine conditions: the recorded size differs, OR the source
     * directory exists while the archive does not (`missing_source_archive`).
     * The second is approximated by "the part is not archived": without a source
     * probe per app this is the conservative direction — it can only cause an
     * extra re-pack, never a missed one.
     */
    fun needsWrite(app: AppInfo, component: Int): Boolean {
        // Always rewritten, so always "needs write".
        if (component == AppParts.PERMISSIONS || component == AppParts.SSAID) return true
        if (!archived(app, component)) return true
        val recorded = recordedKb(app, component)
        // Nothing recorded: we cannot claim it is unchanged.
        if (recorded < 0) return true
        return recorded != liveKb(app, component)
    }

    fun liveKb(app: AppInfo, component: Int): Long = when (component) {
        AppParts.APP -> app.appSizeKb
        AppParts.DATA -> app.dataSizeKb
        AppParts.MEDIA -> app.mediaSizeKb
        else -> 0L
    }

    /** Parts of [selected] that a run would re-write. */
    fun partsToWrite(app: AppInfo, selected: Set<Int>): Set<Int> =
        selected.filterTo(mutableSetOf()) { needsWrite(app, it) }

    /** True when nothing selected would be re-written: the run is a no-op. */
    fun isUpToDate(app: AppInfo, selected: Set<Int>): Boolean =
        partsToWrite(app, selected).isEmpty()

    /**
     * Vault cost of a run over [selected] for [app], counting only parts that
     * will actually be written. This is what "Need:" must show.
     */
    fun writeEstimateKb(selected: Set<Int>, app: AppInfo): Long {
        val toWrite = partsToWrite(app, selected)
        if (toWrite.isEmpty()) return 0L
        // Parenthesised: `a + if (x) y else z + if (w) v else u` parses as
        // `a + if (x) y else (z + if (w) v else u)`, which silently DROPPED the
        // Android ID term whenever permissions were also selected. Caught by a
        // unit test asserting a first backup's total.
        var total = AppParts.PER_APP_OVERHEAD_KB + toWrite.sumOf { liveKb(app, it) }
        if (AppParts.PERMISSIONS in toWrite) total += AppParts.PERMISSIONS_KB
        if (AppParts.SSAID in toWrite) total += AppParts.SSAID_KB
        return total
    }
}

/**
 * Would the Migrate list draw this row at all?
 *
 * The single answer to "does this filter include this app", so the screen and the
 * RUN cannot disagree about what a tick means. They did: the screen showed a
 * launchable system app whenever "Partial system apps" was on, while
 * `runnableSelectedApps` honoured "System Apps" alone — so ticking Chrome drew a
 * checked, highlighted row while the count stayed at 0 and the run button stayed
 * dead (reported on device).
 *
 * A filter exists to help you FIND things. Once a row is on screen and ticked, it
 * is in the run; a filter that silently un-selects what the user just selected is
 * not a filter, it is a trap.
 *
 * Debloat's restore half shows everything (`actionFilterState == 2`), because
 * there the question is what can be put back, not what is installed.
 */
fun isShownInMigrator(app: AppInfo, state: AppState): Boolean {
    if (state.actionFilterState == 2) return true
    return if (app.isSystem) {
        state.showSystemApps || (state.showPartialSystemApps && app.isLaunchable)
    } else {
        state.showUserApps
    }
}
