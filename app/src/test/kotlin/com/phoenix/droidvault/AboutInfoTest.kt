package com.phoenix.droidvault

import com.phoenix.droidvault.utils.AboutInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The About page must never display an inaccurate value for the installed
 * build, so its formatting helpers are pinned here rather than left to string
 * interpolation at the call site.
 */
class AboutInfoTest {

    @Test
    fun `android names cover every api level this app can run on`() {
        assertEquals("Android 10", AboutInfo.androidNameForApi(29))
        assertEquals("Android 11", AboutInfo.androidNameForApi(30))
        assertEquals("Android 12", AboutInfo.androidNameForApi(31))
        assertEquals("Android 12L", AboutInfo.androidNameForApi(32))
        assertEquals("Android 13", AboutInfo.androidNameForApi(33))
        assertEquals("Android 14", AboutInfo.androidNameForApi(34))
        assertEquals("Android 15", AboutInfo.androidNameForApi(35))
    }

    @Test
    fun `unknown future api levels have no invented marketing name`() {
        assertEquals(null, AboutInfo.androidNameForApi(99))
        assertEquals("API 99", AboutInfo.platformLabel(api = 99, release = ""))
    }

    @Test
    fun `platform label combines name release and api level`() {
        assertEquals(
            "Android 15 (VanillaIceCream, API 35)",
            AboutInfo.platformLabel(api = 35, release = "VanillaIceCream")
        )
        assertEquals("Android 14 (API 34)", AboutInfo.platformLabel(api = 34, release = ""))
    }

    @Test
    fun `numeric release that only repeats the name is not stuttered`() {
        // Shipped Android 16 devices report RELEASE as "16"; rendering that
        // verbatim gave "Android 16 (16, API 36)".
        assertEquals("Android 16 (API 36)", AboutInfo.platformLabel(api = 36, release = "16"))
        assertEquals("Android 15 (API 35)", AboutInfo.platformLabel(api = 35, release = "15"))
        // A release string that does NOT match the name is still informative.
        assertEquals("Android 16 (QPR3, API 36)", AboutInfo.platformLabel(api = 36, release = "QPR3"))
    }

    @Test
    fun `primary abi is the first non-blank entry`() {
        assertEquals("arm64-v8a", AboutInfo.primaryAbi(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals("armeabi-v7a", AboutInfo.primaryAbi(arrayOf(" ", "armeabi-v7a")))
        assertEquals("", AboutInfo.primaryAbi(emptyArray()))
    }

    @Test
    fun `build report states root and engine status honestly`() {
        val granted = AboutInfo.buildReport(
            versionName = "2.0.6-jolteon", versionCode = 200010,
            packageName = "com.phoenix.droidvault", minSdk = 29, targetSdk = 37,
            rootGranted = true, engineCurrent = true,
            platform = "Android 15 (VanillaIceCream, API 35)", abi = "arm64-v8a"
        )
        assertTrue(granted.startsWith("Droid Vault 2.0.6-jolteon (build 200010)"))
        assertTrue(granted.contains("Package: com.phoenix.droidvault"))
        assertTrue(granted.contains("Device: Android 15 (VanillaIceCream, API 35)"))
        assertTrue(granted.contains("ABI: arm64-v8a"))
        assertTrue(granted.contains("Requires: Android 10 (API 29)"))
        assertTrue(granted.contains("Targets: Android 17 (API 37)"))
        assertTrue(granted.contains("Root: granted"))
        assertTrue(granted.contains("Backup engine: matches this build"))

        // "unknown" must never be dressed up as a healthy engine.
        val unknown = AboutInfo.buildReport(
            minSdk = 29, targetSdk = 37, rootGranted = false, engineCurrent = null,
            platform = "Android 15 (API 35)", abi = "arm64-v8a"
        )
        assertTrue(unknown.contains("Root: not granted"))
        assertTrue(unknown.contains("Backup engine: status unknown"))
        assertTrue(!unknown.contains("matches this build"))
    }

    @Test
    fun `build report falls back to raw api numbers rather than guessing`() {
        val report = AboutInfo.buildReport(
            minSdk = 99, targetSdk = 120, rootGranted = false, engineCurrent = false,
            platform = "Android 15 (API 35)", abi = "arm64-v8a"
        )
        assertTrue(report.contains("Requires: API 99 (API 99)"))
        assertTrue(report.contains("Targets: API 120 (API 120)"))
        assertTrue(report.contains("Backup engine: differs from this build"))
    }
}
