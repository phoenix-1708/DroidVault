package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.isShownInMigrator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One predicate, two consumers: the Migrate list draws rows it returns, and
 * `runnableSelectedApps` runs the rows it returns. They were separate
 * predicates and drifted.
 */
class MigratorVisibilityTest {

    private fun system(launchable: Boolean) = AppInfo(
        label = "Chrome",
        packageName = "com.android.chrome",
        isSystem = true,
        isLaunchable = launchable
    )

    private fun user() = AppInfo(label = "Brave", packageName = "com.brave.browser")

    @Test
    fun `a launchable system app shown by the partial filter is runnable`() {
        // The reported bug: "Partial system apps" on, Chrome visible and ticked,
        // and the row drew checked while the count stayed 0 and the button was
        // dead. Partial-only must mean partial apps count.
        val state = AppState(
            showSystemApps = false,
            showPartialSystemApps = true,
            showUserApps = true
        )
        assertTrue(
            "a launchable system app the screen is showing must be runnable",
            isShownInMigrator(system(launchable = true), state)
        )
    }

    @Test
    fun `a non-launchable system app stays hidden while both filters are off`() {
        val state = AppState(
            showSystemApps = false,
            showPartialSystemApps = true,
            showUserApps = true
        )
        assertFalse(
            "the partial filter is for LAUNCHABLE system apps; it must not admit " +
                "every system app on the device",
            isShownInMigrator(system(launchable = false), state)
        )
    }

    @Test
    fun `the system-apps filter alone still admits non-launchable system apps`() {
        val state = AppState(showSystemApps = true, showPartialSystemApps = false, showUserApps = true)
        assertTrue(isShownInMigrator(system(launchable = false), state))
        assertTrue(isShownInMigrator(system(launchable = true), state))
    }

    @Test
    fun `user apps follow the user-apps filter`() {
        assertTrue(
            "a user app must be runnable when user apps are shown",
            isShownInMigrator(user(), AppState(showUserApps = true, showSystemApps = false))
        )
        assertFalse(
            "a user app must be excluded when user apps are hidden",
            isShownInMigrator(user(), AppState(showUserApps = false, showSystemApps = true))
        )
    }

    @Test
    fun `the restore half shows everything regardless of the app filters`() {
        // actionFilterState == 2 is Debloat's restore list: the question is what
        // can be put back, not what is installed, so no filter may hide a row.
        for (showSystem in listOf(true, false)) {
            for (showUser in listOf(true, false)) {
                for (partial in listOf(true, false)) {
                    val state = AppState(
                        actionFilterState = 2,
                        showSystemApps = showSystem,
                        showUserApps = showUser,
                        showPartialSystemApps = partial
                    )
                    assertTrue(
                        "restore must show every app (system=$showSystem " +
                            "user=$showUser partial=$partial)",
                        isShownInMigrator(system(launchable = false), state) &&
                            isShownInMigrator(user(), state)
                    )
                }
            }
        }
    }
}
