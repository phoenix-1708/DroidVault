package com.phoenix.droidvault.utils

import android.content.Context
import android.content.pm.PackageManager
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

    private val PART_FILES = listOf(
        "APK" to "App.shift",
        "Data" to "Data.shift",
        "UserDe" to "UserDe.shift",
        "ExtData" to "ExtData.shift",
        "Media" to "Media.shift",
        "Obb" to "Obb.shift",
        "Permissions" to "Permissions.txt"
    )

    suspend fun load(context: Context, app: AppInfo, savedPath: String): AppDetail =
        withContext(Dispatchers.IO) {
            val sysType = if (app.isSystem) "System" else "User"
            val dir = File("$savedPath/Apps/$sysType/${app.label}")
            val parts = PART_FILES.map { (name, file) ->
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
