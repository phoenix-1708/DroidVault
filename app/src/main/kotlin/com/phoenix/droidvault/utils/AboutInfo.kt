package com.phoenix.droidvault.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.phoenix.droidvault.BuildConfig

/**
 * Pure, testable helpers behind the About screen's dynamic facts.
 *
 * Everything here is READ FROM THE INSTALLED PACKAGE at runtime — nothing is
 * hard-coded and nothing is copied from documentation. If a value cannot be
 * obtained from the platform or the build config, the caller shows nothing
 * rather than a fallback guess.
 */
object AboutInfo {

    /** Android marketing name for an API level, e.g. 29 -> "Android 10". */
    fun androidNameForApi(api: Int): String? = when (api) {
        29 -> "Android 10"
        30 -> "Android 11"
        31 -> "Android 12"
        32 -> "Android 12L"
        33 -> "Android 13"
        34 -> "Android 14"
        35 -> "Android 15"
        36 -> "Android 16"
        37 -> "Android 17"
        else -> null
    }

    /** "Android 15 (API 35)", or the bare API number on an unknown future level. */
    fun platformLabel(api: Int = Build.VERSION.SDK_INT, release: String? = Build.VERSION.RELEASE): String {
        val name = androidNameForApi(api)
        val release = release?.trim().orEmpty()
        // Many builds report RELEASE as just the version number ("16"), which
        // would render as the stutter "Android 16 (16, API 36)". Drop the
        // parenthetical when it only repeats the name.
        val releaseNumber = release.substringBefore('(').trim()
        val repeatsName = name != null && releaseNumber.toIntOrNull() != null && name.endsWith(releaseNumber)
        return when {
            name != null && release.isNotEmpty() && !repeatsName -> "$name ($release, API $api)"
            name != null -> "$name (API $api)"
            release.isNotEmpty() -> "Android $release (API $api)"
            else -> "API $api"
        }
    }

    /** The primary ABI, e.g. "arm64-v8a". Empty when the platform reports none. */
    fun primaryAbi(abis: Array<String> = Build.SUPPORTED_ABIS): String =
        abis.firstOrNull { it.isNotBlank() }.orEmpty()

    /**
     * The text placed on the clipboard by "Copy build info". Deliberately
     * factual: it is the same set of facts shown on screen, no extras.
     *
     * [platform] and [abi] default to the live device values but are injectable
     * because `Build.SUPPORTED_ABIS` / `Build.VERSION` are unmocked framework
     * getters in a plain JVM unit test (they return null there, so a test that
     * called the defaults would NPE instead of asserting the formatting).
     */
    fun buildReport(
        versionName: String = BuildConfig.VERSION_NAME,
        versionCode: Int = BuildConfig.VERSION_CODE,
        packageName: String = BuildConfig.APPLICATION_ID,
        minSdk: Int,
        targetSdk: Int,
        rootGranted: Boolean,
        engineCurrent: Boolean?,
        platform: String = platformLabel(),
        abi: String = primaryAbi()
    ): String = buildString {
        appendLine("Droid Vault $versionName (build $versionCode)")
        appendLine("Package: $packageName")
        appendLine("Device: $platform")
        appendLine("ABI: $abi")
        appendLine("Requires: ${androidNameForApi(minSdk) ?: "API $minSdk"} (API $minSdk)")
        appendLine("Targets: ${androidNameForApi(targetSdk) ?: "API $targetSdk"} (API $targetSdk)")
        appendLine("Root: ${if (rootGranted) "granted" else "not granted"}")
        appendLine("Backup engine: ${when (engineCurrent) {
            true -> "matches this build"
            false -> "differs from this build"
            null -> "status unknown"
        }}")
    }.trimEnd()

    /** minSdk/targetSdk of the INSTALLED build; 0 when the platform omits them. */
    fun sdkBounds(context: Context): Pair<Int, Int> {
        val appInfo: ApplicationInfo = context.applicationInfo
        val min = appInfo.minSdkVersion
        val target = appInfo.targetSdkVersion
        return min to target
    }
}
