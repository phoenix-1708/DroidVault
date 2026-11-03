package com.phoenix.droidvault.utils

import android.content.Context
import android.content.pm.PackageManager
import com.phoenix.droidvault.models.VaultArchive
import com.phoenix.droidvault.models.AppInfo
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Immutable
data class BackupPartInfo(
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val present: Boolean
)

@Immutable
data class AppDetail(
    val app: AppInfo,
    val parts: List<BackupPartInfo>,
    val meta: Map<String, String>,
    /** Granted runtime permissions + allowed appops, from Permissions.txt. */
    val granted: List<String>,
    /** Requested but not granted (live PackageManager, no root needed). */
    val denied: List<String>,
    val frozen: Boolean,
    val backupDirExists: Boolean
)

/**
 * Reads everything the detail screen needs from existing on-disk state:
 * backup parts + Meta.txt + Permissions.txt under savedPath, live denied
 * permissions from PackageManager. No format changes, no root required
 * except the frozen flag.
 */
object AppDetailLoader {

    /**
     * The parts and the archive each one is in, by BASE name.
     *
     * The base, not the file name: an archive written before the extension
     * moved is the same archive under the other name, and the detail screen
     * has to show whichever is actually on disk.
     */
    private val PART_FILES = listOf(
        "APK" to "App",
        "Data" to "Data",
        "UserDe" to "UserDe",
        "ExtData" to "ExtData",
        "Media" to "Media",
        "Obb" to "Obb",
        "Permissions" to "Permissions.txt"
    )

    suspend fun load(context: Context, app: AppInfo, savedPath: String): AppDetail =
        withContext(Dispatchers.IO) {
            val sysType = if (app.isSystem) "System" else "User"
            // The folder is named by the SANITISED label — the same function
            // every writer and every reader resolves the vault with. A raw
            // label read the wrong folder for any app whose name needed
            // sanitising (`Tachiyomi: Mangakakalot`), so its parts page showed
            // "nothing backed up" for an archive that was on the phone.
            val dir = File("$savedPath/Apps/$sysType/${ShellQuote.safeLabel(app.label)}")
            val parts = PART_FILES.map { (name, base) ->
                // The name that is actually there, under either extension.
                val file = if (base.endsWith(".txt")) {
                    base
                } else {
                    VaultArchive.names(base).firstOrNull { File(dir, it).exists() }
                        ?: VaultArchive.name(base)
                }
                val f = File(dir, file)
                BackupPartInfo(name, file, if (f.exists()) f.length() else 0L, f.exists())
            }
            val meta = try {
                File(dir, "Meta.txt").takeIf { it.exists() }
                    ?.readLines()
                    ?.mapNotNull { line ->
                        val idx = line.indexOf('=')
                        if (idx > 0) line.substring(0, idx) to line.substring(idx + 1) else null
                    }?.toMap() ?: emptyMap()
            } catch (_: Exception) {
                emptyMap()
            }
            val granted = try {
                File(dir, "Permissions.txt").takeIf { it.exists() }
                    ?.readLines()
                    ?.mapNotNull { line ->
                        when {
                            line.startsWith("PERM:") -> line.removePrefix("PERM:").substringBefore('=')
                            line.startsWith("APPOP:") -> "appop:" + line.removePrefix("APPOP:").substringBefore('=')
                            else -> null
                        }
                    }?.distinct()?.sorted() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            val denied = try {
                val pi = context.packageManager.getPackageInfo(
                    app.packageName,
                    PackageManager.GET_PERMISSIONS
                )
                val requested = pi.requestedPermissions ?: emptyArray()
                val flags = try {
                    pi.requestedPermissionsFlags ?: IntArray(0)
                } catch (_: Exception) {
                    IntArray(0)
                }
                requested.filterIndexed { i, _ ->
                    ((flags.getOrNull(i) ?: 0) and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0
                }.sorted()
            } catch (_: Exception) {
                emptyList()
            }
            val frozen = ToolsManager.isFrozen(context, app.packageName)
            AppDetail(
                app = app,
                parts = parts,
                meta = meta,
                granted = granted,
                denied = denied,
                frozen = frozen,
                backupDirExists = dir.exists()
            )
        }

    /** Delegates to the shared [Formatters]; blank placeholder kept for the UI. */
    fun formatBytes(bytes: Long): String =
        if (bytes <= 0) "—" else Formatters.formatBytes(bytes)
}
