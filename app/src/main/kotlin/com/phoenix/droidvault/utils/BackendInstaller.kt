package com.phoenix.droidvault.utils

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.phoenix.droidvault.BuildConfig
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

object BackendInstaller {

    // Shared hashing (P6 dedupe) — identical digest pipeline.
    private fun java.io.InputStream.sha256(): String? = Hashing.sha256Hex(this)

    private fun getAssetSha256(context: Context, assetName: String): String? {
        return try {
            context.assets.open(assetName).use { it.sha256() }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun installEngine(context: Context): Boolean = withContext(Dispatchers.IO) {
        val targetDir = "/data/adb/DroidVault"
        val launcherName = "DroidVault.sh"
        val engineName = "droidvault-engine"
        val zapdosName = "zapdos"
        val cacheDir = context.cacheDir

        // zapdos ships as jniLibs. With useLegacyPackaging=false it is NOT
        // extracted to nativeLibraryDir (the dir is empty) — it only exists
        // inside the APK. Fall back to extracting it from the APK.
        val deviceLibFile = File(context.applicationInfo.nativeLibraryDir, "libzapdos.so")
            .takeIf { it.exists() && it.length() > 0 }
            ?: extractZapdosFromApk(context, cacheDir)
        if (deviceLibFile == null) {
            android.util.Log.e(TAG, "installEngine aborted: libzapdos.so neither extracted nor bundled in APK")
            return@withContext false
        }
        val deviceEngineFile = File(context.applicationInfo.nativeLibraryDir, "libdroidvault_engine.so")
            .takeIf { it.exists() && it.length() > 0 }
            ?: extractBundledNative(context, cacheDir, "libdroidvault_engine.so", engineName)
        if (deviceEngineFile == null) {
            android.util.Log.e(TAG, "installEngine aborted: Rust engine binary is not bundled")
            return@withContext false
        }

        try {
            val currentVersionCode = BuildConfig.VERSION_CODE.toLong()
            val prefs = context.getSharedPreferences("droidvault_backend_prefs", Context.MODE_PRIVATE)
            val savedVersionCode = prefs.getLong("installed_version", -1L)

            val launcherFile = File(targetDir, launcherName)
            val engineFile = File(targetDir, engineName)
            val zapdosFile = File(targetDir, zapdosName)

            val filesExist = Shell.cmd(
                    "[ -x ${ShellQuote.q(launcherFile.absolutePath)} ] && " +
                    "[ -x ${ShellQuote.q(engineFile.absolutePath)} ] && " +
                    "[ -x ${ShellQuote.q(zapdosFile.absolutePath)} ]"
            ).exec().isSuccess

            // /data/adb is root-only: Java File I/O cannot see these files, so
            // ALL deployed-state checks go through the (root) shell.
            val assetHash = getAssetSha256(context, launcherName)
            val diskHash = deployedScriptSha256()
            val scriptIntact = assetHash != null && assetHash == diskHash
            val bundledEngineSha = Hashing.sha256Hex(deviceEngineFile)
            val engineIntact = bundledEngineSha != null && deployedEngineSha256() == bundledEngineSha

            // zapdos: byte-identical to the bundled native lib and plausibly
            // sized. Size-only was weak (audit A-17): a corrupted or stale
            // binary passed the check and restores could silently misbehave.
            val bundledZapdosSha = Hashing.sha256Hex(deviceLibFile)
            val zapdosIntact = bundledZapdosSha != null &&
                deployedZapdosSha256() == bundledZapdosSha &&
                deviceLibFile.length() > 100_000L

            if (filesExist && scriptIntact && engineIntact && zapdosIntact && savedVersionCode == currentVersionCode) {
                android.util.Log.i(TAG, "Rust engine and launcher verified (launcher sha256=${assetHash.orEmpty().take(12)}…)")
                DroidVaultLogger.i("Engine", "Rust engine and launcher verified — up to date")
                return@withContext true
            }

            val outLauncher = File(cacheDir, launcherName)
            context.assets.open(launcherName).use { input ->
                outLauncher.outputStream().use { output -> input.copyTo(output) }
            }

            val commands = arrayOf(
                "mkdir -p ${ShellQuote.q(targetDir)}",
                "cp ${ShellQuote.q(outLauncher.absolutePath)} ${ShellQuote.q(launcherFile.absolutePath)}",
                "cp ${ShellQuote.q(deviceEngineFile.absolutePath)} ${ShellQuote.q(engineFile.absolutePath)}",
                "cp ${ShellQuote.q(deviceLibFile.absolutePath)} ${ShellQuote.q(zapdosFile.absolutePath)}",
                "chmod 755 ${ShellQuote.q(targetDir)}",
                "chmod 755 ${ShellQuote.q(launcherFile.absolutePath)}",
                "chmod 755 ${ShellQuote.q(engineFile.absolutePath)}",
                "chmod 755 ${ShellQuote.q(zapdosFile.absolutePath)}",
                "chown -R root:root ${ShellQuote.q(targetDir)} 2>/dev/null"
            )

            val result = Shell.cmd(*commands).exec()

            if (result.isSuccess) {
                prefs.edit { putLong("installed_version", currentVersionCode) }
                android.util.Log.i(TAG, "Engine updated to bundled version (sha256=${assetHash?.take(12)}…)")
                DroidVaultLogger.i("Engine", "Engine deployed/updated to bundled build")
                return@withContext true
            } else {
                android.util.Log.e(
                    "BackendInstaller",
                    "installEngine shell failed: code=${result.code} out=${result.out.take(3)} err=${result.err.take(3)}"
                )
                DroidVaultLogger.w("Engine", "Engine deploy failed (shell code=${result.code})")
            }
        } catch (e: Exception) {
            android.util.Log.e("BackendInstaller", "installEngine failed: ${e.message}")
        } finally {
            File(cacheDir, launcherName).delete()
            File(cacheDir, zapdosName).delete()
            File(cacheDir, engineName).delete()
        }
        return@withContext false
    }

    /**
     * Extract libzapdos.so from the installed APK (needed when native libs
     * are not extracted to disk, i.e. useLegacyPackaging=false).
     */
    private fun extractZapdosFromApk(context: Context, cacheDir: File): File? = try {
        extractBundledNative(context, cacheDir, "libzapdos.so", "zapdos")
    } catch (e: Exception) {
        android.util.Log.e(TAG, "extractZapdosFromApk failed: ${e.message}")
        null
    }

    private fun extractBundledNative(context: Context, cacheDir: File, libraryName: String, outputName: String): File? = try {
        val out = File(cacheDir, outputName)
        var extracted = false
        ZipFile(File(context.applicationInfo.sourceDir)).use { zip ->
            for (abi in Build.SUPPORTED_ABIS) {
                val entry = zip.getEntry("lib/$abi/$libraryName") ?: continue
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setExecutable(true)
                extracted = true
                break
            }
        }
        if (extracted) out else null
    } catch (e: Exception) {
        android.util.Log.e(TAG, "extractBundledNative($libraryName) failed: ${e.message}")
        null
    }

    private const val TAG = "BackendInstaller"

    /** SHA-256 of the compatibility launcher bundled in this APK build. */
    fun assetScriptSha256(context: Context): String? = getAssetSha256(context, "DroidVault.sh")

    /**
     * SHA-256 of the compatibility launcher deployed at /data/adb/DroidVault,
     * read via the root shell (that directory is root-only; Java File I/O
     * cannot see it, which is why [installEngine]'s intact-check re-copies
     * on every launch).
     */
    fun deployedScriptSha256(): String? = try {
        val result = Shell.cmd("sha256sum /data/adb/DroidVault/DroidVault.sh 2>/dev/null").exec()
        result.out.firstOrNull()?.substringBefore(' ')?.takeIf { it.length == 64 }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "deployedScriptSha256 failed: ${e.message}")
        null
    }

    /** SHA-256 of the root-deployed Rust operation engine. */
    fun deployedEngineSha256(): String? = try {
        val result = Shell.cmd("sha256sum /data/adb/DroidVault/droidvault-engine 2>/dev/null").exec()
        result.out.firstOrNull()?.substringBefore(' ')?.takeIf { it.length == 64 }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "deployedEngineSha256 failed: ${e.message}")
        null
    }

    /** SHA-256 of the deployed zapdos binary, read via the root shell. */
    fun deployedZapdosSha256(): String? = try {
        val result = Shell.cmd("sha256sum /data/adb/DroidVault/zapdos 2>/dev/null").exec()
        result.out.firstOrNull()?.substringBefore(' ')?.takeIf { it.length == 64 }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "deployedZapdosSha256 failed: ${e.message}")
        null
    }

    /** SHA-256 of the zapdos binary bundled in this APK (native lib or APK). */
    private fun bundledZapdosSha256(context: Context): String? = try {
        val file = File(context.applicationInfo.nativeLibraryDir, "libzapdos.so")
            .takeIf { it.exists() && it.length() > 0 }
            ?: extractZapdosFromApk(context, context.cacheDir)
        file?.let { Hashing.sha256Hex(it) }
    } catch (_: Exception) {
        null
    }

    private fun bundledEngineSha256(context: Context): String? = try {
        val file = File(context.applicationInfo.nativeLibraryDir, "libdroidvault_engine.so")
            .takeIf { it.exists() && it.length() > 0 }
            ?: extractBundledNative(context, context.cacheDir, "libdroidvault_engine.so", "droidvault-engine")
        file?.let { Hashing.sha256Hex(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * True when the deployed engine is byte-identical to the bundled one —
     * compatibility launcher, Rust operation binary, AND zapdos. This is the fail-closed freshness gate for every
     * backup/restore run (audit A-17: a stale native binary must not pass).
     */
    suspend fun isEngineUpToDate(context: Context): Boolean = withContext(Dispatchers.IO) {
        val bundled = assetScriptSha256(context) ?: return@withContext false
        if (deployedScriptSha256() != bundled) return@withContext false
        val bundledEngine = bundledEngineSha256(context) ?: return@withContext false
        if (deployedEngineSha256() != bundledEngine) return@withContext false
        val bundledZapdos = bundledZapdosSha256(context) ?: return@withContext false
        deployedZapdosSha256() == bundledZapdos
    }
}
