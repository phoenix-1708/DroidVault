package com.phoenix.droidvault.utils

import android.annotation.SuppressLint
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.os.storage.StorageManager.UUID_DEFAULT
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.createBitmap
import com.phoenix.droidvault.BuildConfig
import com.phoenix.droidvault.models.AppInfo
import com.phoenix.droidvault.models.AppSeed
import com.phoenix.droidvault.models.AppState
import com.phoenix.droidvault.models.LiveOp
import com.phoenix.droidvault.models.MigratorMode
import com.phoenix.droidvault.models.EngineEvent
import com.phoenix.droidvault.models.VerificationParser
import com.phoenix.droidvault.models.Trash
import com.phoenix.droidvault.models.VaultTransfer
import com.phoenix.droidvault.models.VerificationReport
import com.phoenix.droidvault.models.summaryLine
import com.phoenix.droidvault.models.Parts
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

object MigratorManager {

    // @Volatile: filled from IO coroutines on different threads; a torn/stale
    // read is only a duplicated fetch, but visibility should be guaranteed.
    @Volatile private var userAppsCache: List<AppInfo>? = null
    @Volatile private var sysAppsCache: List<AppInfo>? = null
    @Volatile private var uninstalledCache: List<AppInfo>? = null
    @Volatile private var allInstalledCache: List<AppInfo>? = null
    /** Disk-view cache (AllBackups). Every mutation path clears via [clearCache]. */
    @Volatile private var restoreAppsCache: List<AppInfo>? = null

    // The caches were keyed on `type` alone, so a request for a DIFFERENT vault
    // path — or for a mode that changes what the numbers mean — was served the
    // previous answer. Two concrete bugs this caused: after moving the vault,
    // refreshPathBoundState() re-read everything while these still described
    // the old folder; and a non-manage restore list could be handed the MANAGE
    // list, whose sizes come from a different measurement entirely. The key now
    // covers every input that changes the result.
    @Volatile private var cachedKey: String? = null

    private fun cacheKeyOf(
        currentPath: String, type: String, isManage: Boolean,
        includeOverhead: Boolean, onlyAppSize: Boolean
    ) = "$currentPath|$type|manage=$isManage|ovh=$includeOverhead|only=$onlyAppSize"

    // ---------- Trash (quarantine instead of irreversible delete) ----------

    private fun trashDir(vaultPath: String): String = "$vaultPath/${Trash.DIR_NAME}"
    private fun trashManifest(vaultPath: String): String = "${trashDir(vaultPath)}/index"

    /**
     * The manifest is read and written THROUGH THE ROOT SHELL, staged via the
     * app cache.
     *
     * Direct file I/O into the vault returns EPERM here even when the directory
     * is owned by the app uid: once a root `mv` has touched a directory on
     * emulated storage, the FUSE layer stops the app writing into it. Found on
     * device — the archive moved correctly but the index was never written, so
     * the trash was invisible and unrecoverable. The move already needs root,
     * so the index follows the same path rather than fighting the layer.
     */
    /**
     * Serialises every read-modify-write of the trash index.
     *
     * The index is one file, and all three mutators (`moveToTrash`,
     * `restoreTrashBatch`, `purgeTrashBatch`) read it, change it and write it
     * back. Two of them running at once — two taps on two rows in the trash
     * dialog, or a delete landing while an undo is running — each read a version
     * the other had not yet written, and one batch was saved to disk with no
     * manifest entry: invisible, never purged, and unrecoverable.
     *
     * `deleteDeviceBackups` fixed this by acquiring the RUN gate. That is the
     * wrong level: the trash can be touched with no run in flight at all, and
     * the gate is far coarser than the thing being protected. The lock is here,
     * next to the index, so the rule travels with the code that breaks it and
     * covers all three paths at once.
     */
    private val trashIndexLock = java.util.concurrent.locks.ReentrantLock()


    fun loadTrash(context: Context, vaultPath: String): List<Trash.Batch> {
        ShellQuote.requireAbsPath(vaultPath)
        val out = runCatching {
            Shell.cmd("cat -- ${ShellQuote.q(trashManifest(vaultPath))} 2>/dev/null").exec().out
                .joinToString("\n")
        }.getOrDefault("")
        return Trash.decode(out)
    }

    private fun saveTrash(context: Context, vaultPath: String, batches: List<Trash.Batch>): Boolean {
        val staged = File(context.cacheDir, "droidvault_trash_index")
        return try {
            staged.writeText(Trash.encode(batches))
            Shell.cmd("mkdir -p ${ShellQuote.q(trashDir(vaultPath))}").exec()
            val res = Shell.cmd(
                "cp -- ${ShellQuote.q(staged.absolutePath)} " +
                        "${ShellQuote.q(trashManifest(vaultPath))} && " +
                        "chmod 600 ${ShellQuote.q(trashManifest(vaultPath))}"
            ).exec()
            staged.delete()
            if (!res.isSuccess) {
                android.util.Log.w("MigratorManager", "Trash manifest write failed: exit ${res.code}")
            }
            res.isSuccess
        } catch (e: Exception) {
            android.util.Log.w("MigratorManager", "Trash manifest write failed: ${e.message}")
            false
        }
    }

    /**
     * Move paths into the quarantine instead of erasing them.
     *
     * A rename within one filesystem is instant and frees nothing, so "delete"
     * here is reversible at no cost. Returns the entries that could NOT be
     * moved; those are still on disk, which the caller must report rather than
     * claim as deleted.
     */
    fun moveToTrash(context: Context, vaultPath: String, paths: List<String>, kind: String): Trash.Batch {
        trashIndexLock.lock()
        try {
            ShellQuote.requireAbsPath(vaultPath)
            val now = System.currentTimeMillis()
            val id = "b$now"
            val base = "${trashDir(vaultPath)}/$id"
            val moved = mutableListOf<Trash.Entry>()
            val failed = mutableListOf<String>()

            for (path in paths) {
                // Files too, not just app directories: the Device/ archives
                // (Messages.shift, CallLogs.shift, Folders.shift) are single
                // files, and a quarantiner that silently skipped them would leave
                // the only unrecoverable deletion in the app un-quarantined.
                if (!File(path).exists() && !Shell.cmd("[ -e ${ShellQuote.q(path)} ]").exec().isSuccess) {
                    continue
                }
                try {
                    Trash.requireEncodable(path)
                    // Nested under the PARENT's name so two batches-worth of
                    // entries sharing a basename cannot clobber each other —
                    // Apps/User/1DM+ and Device/1DM+ would otherwise land on the
                    // same destination and one would silently overwrite the other.
                    val src = File(path)
                    val dest = "$base/${src.parentFile?.name ?: "_"}/${src.name}"
                    val parent = File(dest).parentFile
                    parent?.mkdirs()
                    // Root `du`, NOT java.io. A walk of the vault from the app
                    // process sees no files at all (scoped storage — the same blind
                    // spot a device audit recorded), so this silently reported every
                    // deleted backup as 0 KB and the trash looked empty of data.
                    // Found on device.
                    // Works for a file as well as a directory.
                    val sizeKb = Shell.cmd("du -sk ${ShellQuote.q(path)} 2>/dev/null").exec().out
                        .sumOf { it.trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L }
                    val res = Shell.cmd("mv -- ${ShellQuote.q(path)} ${ShellQuote.q(dest)}").exec()
                    if (res.isSuccess) {
                        moved += Trash.Entry(path, dest, src.parentFile?.name?.let { "$it/${src.name}" } ?: src.name, sizeKb)
                    } else {
                        failed += path
                    }
                } catch (e: Exception) {
                    failed += path
                }
            }

            val batch = Trash.Batch(id, now, moved, kind)
            if (moved.isNotEmpty()) {
                val kept = loadTrash(context, vaultPath) + batch
                // saveTrash returns Boolean precisely so a failed index write can be
                // detected. It was discarded, so a root revoked mid-operation, ENOSPC,
                // or an unwritable .trash produced archives sitting under .trash/<id>
                // with NO index entry — invisible, unrecoverable through the UI, and
                // never purged, because purge is index-driven. Meanwhile the caller
                // reported "recoverable for 7 days".
                val recorded = saveTrash(context, vaultPath, kept)
                // Also record the batch id so a user can at least find it by hand.
                if (!recorded) {
                    android.util.Log.e(
                        "MigratorManager",
                        "Trash: index write FAILED for batch $id — $id/moved items are in " +
                            "$base but have no manifest entry and cannot be restored from the app"
                    )
                }
                Trash.expiredBatchIds(kept, System.currentTimeMillis()).forEach { stale ->
                    purgeTrashBatch(context, vaultPath, stale)
                }
            }
            if (failed.isNotEmpty()) {
                android.util.Log.w(
                    "MigratorManager",
                    "Trash: ${failed.size} item(s) could not be moved and remain on disk: $failed"
                )
            }
            return batch
        } finally {
            trashIndexLock.unlock()
        }
    }

    /** Put a quarantined batch back where it came from. */
    fun restoreTrashBatch(context: Context, vaultPath: String, batchId: String): Result<Int> {
        trashIndexLock.lock()
        try {
            ShellQuote.requireAbsPath(vaultPath)
            return runCatching {
                val batches = loadTrash(context, vaultPath)
                val batch = batches.firstOrNull { it.id == batchId }
                    ?: return@runCatching 0
                var restored = 0
                for (e in batch.entries) {
                    File(e.originalPath).parentFile?.mkdirs()
                    val res = Shell.cmd("mv -- ${ShellQuote.q(e.trashPath)} ${ShellQuote.q(e.originalPath)}")
                        .exec()
                    if (res.isSuccess) restored++
                }
                saveTrash(context, vaultPath, batches.filterNot { it.id == batchId })
                // Drop the now-empty directory tree. Leaving it behind accumulates
                // one tree per undo, and the manifest no longer references them, so
                // nothing would ever reclaim them.
                //
                // Two earlier attempts were wrong and the device caught both:
                //  - `rmdir <batch>` refuses outright when `<batch>/Device/` still
                //    exists, because rmdir does not recurse.
                //  - `rmdir -p <batch>` fails for the SAME reason: it removes the
                //    target first, cannot, and so never walks up. Verified — the
                //    orphan survived, while `rmdir -p <batch>/Device` run by hand
                //    removed both.
                // `-depth` visits children before parents, so every directory that
                // is now empty goes, and one that is not (a partially-restored
                // batch) is simply skipped rather than aborting the walk.
                //
                // The exit status is deliberately NOT checked: `find -exec rmdir`
                // is non-zero if any rmdir refused, which is the normal case for a
                // batch that still holds something. The check that matters is
                // whether the batch directory is gone.
                Shell.cmd(
                    "find ${ShellQuote.q("${trashDir(vaultPath)}/$batchId")} -depth -type d " +
                        "-exec rmdir -- {} + 2>/dev/null"
                ).exec()
                restored
            }
        } finally {
            trashIndexLock.unlock()
        }
    }

    /** Irreversibly erase one quarantined batch. */
    fun purgeTrashBatch(context: Context, vaultPath: String, batchId: String): Boolean {
        trashIndexLock.lock()
        try {
            ShellQuote.requireAbsPath(vaultPath)
            val batches = loadTrash(context, vaultPath)
            val batch = batches.firstOrNull { it.id == batchId } ?: return false
            batch.entries.forEach {
                Shell.cmd("rm -rf -- ${ShellQuote.q(it.trashPath)}").exec()
            }
            Shell.cmd("rm -rf -- ${ShellQuote.q("${trashDir(vaultPath)}/$batchId")}").exec()
            saveTrash(context, vaultPath, batches.filterNot { it.id == batchId })
            return true
        } finally {
            trashIndexLock.unlock()
        }
    }

    fun trashHeldKb(context: Context, vaultPath: String): Long =
        Trash.heldKb(loadTrash(context, vaultPath))

    /**
     * Verify every archive in the vault by asking the engine to read it back.
     *
     * Exposed because a corrupt archive is otherwise discovered at restore time,
     * which is the worst possible moment: the device may already be wiped. The
     * engine returns one line per app plus a summary; parsing and the
     * restore-gate decision live in [com.phoenix.droidvault.models.VerificationParser]
     * so they stay testable without root.
     *
     * Fails closed on an unavailable engine rather than reporting "no backups",
     * because an empty report and a vault with nothing in it look identical and
     * only one of them is true.
     */
    suspend fun verifyBackups(
        context: Context,
        vaultPath: String,
        deviceData: Boolean = false
    ): VerificationReport = withContext(Dispatchers.IO) {
        ShellQuote.requireAbsPath(vaultPath)
        if (!BackendInstaller.isEngineUpToDate(context) && !BackendInstaller.installEngine(context)) {
            return@withContext VerificationReport(
                error = "Backup engine unavailable — cannot verify archives"
            )
        }
        val verb = if (deviceData) "--verify-device" else "--verify-apps"
        val env = InstallerPolicy.shellEnv(context)
        val command = "$env sh /data/adb/DroidVault/DroidVault.sh $verb ${ShellQuote.q(vaultPath)}"
        var report = VerificationReport()
        // Verification reads every archive back through zapdos + tar, so its
        // cost scales with the bytes in the vault. The flat half hour this used
        // to carry is a bound on speed rather than on liveness: a vault several
        // times this device's would be SIGTERMed mid-read and reported as an
        // error while every archive was in fact intact.
        val vaultKb = Shell.cmd("du -sk ${ShellQuote.q(vaultPath)} 2>/dev/null").exec().out.sumOf {
            it.trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
        }
        val verifyWatchdogMs = VaultTransfer.transferWatchdogMs(
            vaultKb * 1024L, VaultTransfer.RUN_WATCHDOG_MS
        )
        ShellEngine.executeEngineCommand(command, timeoutMs = verifyWatchdogMs)
            .collect { event ->
                when (event) {
                    is EngineEvent.RawLog -> report = VerificationParser.parse(event.line, report)
                    is EngineEvent.Error -> report = report.copy(error = event.msg)
                    is EngineEvent.Cancelled -> report = report.copy(cancelled = true)
                    else -> Unit
                }
            }
        DroidVaultLogger.i("Verify", report.summaryLine())
        report
    }

    fun clearCache() {
        userAppsCache = null
        sysAppsCache = null
        uninstalledCache = null
        allInstalledCache = null
        restoreAppsCache = null
        cachedKey = null
    }

    /** True when the file exists per the root-shell [vaultFileIndex]. */
    private fun indexed(vault: Map<String, Long>, path: String): Boolean =
        vault.containsKey(path)

    /**
     * Absolute path -> mtime (ms) for every artifact under the vault's Apps
     * tree, gathered in ONE root `find` pass (audit A-09).
     *
     * Why root: the vault lives on /sdcard and the app declares no all-files
     * access. Under enforced scoped storage `java.io`'s File.exists() and
     * lastModified() return false/0 for the app uid — every app silently
     * showed "No backup on device", and the automation restore planner reads
     * the same flags, so scheduled RESTOREs would skip every app.
     */
    private fun vaultFileIndex(currentPath: String): Map<String, Long> {
        // find -printf gathers mtimes with ZERO forks. The previous
        // `-exec stat {} ';'` forked once PER FILE — 2.7s for ~300 files on
        // the reference device, paid on EVERY list fetch in every tab (the
        // "Automation/Migrate load slowly" field report). Batched `-exec +`
        // stays as the fallback for find builds without -printf.
        val fastCmd = "find ${ShellQuote.q("$currentPath/Apps")} -maxdepth 3 -type f " +
            "-printf '%T@|%p\\n' 2>/dev/null"
        var out: List<String> = emptyList()
        try {
            val res = Shell.cmd(fastCmd).exec()
            if (res.isSuccess && res.out.isNotEmpty()) {
                out = res.out
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) {
            try {
                out = Shell.cmd(
                    "find ${ShellQuote.q("$currentPath/Apps")} -maxdepth 3 -type f " +
                        "-exec stat -c '%Y|%n' {} + 2>/dev/null"
                ).exec().out
            } catch (_: Exception) {
            }
        }
        val map = HashMap<String, Long>(out.size * 2)
        for (line in out) {
            val sep = line.indexOf('|')
            if (sep <= 0) continue
            // %T@ is seconds.nanoseconds — keep whole seconds only.
            val secs = line.substring(0, sep).trim().substringBefore('.').toLongOrNull() ?: continue
            map[line.substring(sep + 1).trim()] = secs * 1000L
        }
        return map
    }

    @SuppressLint("SimpleDateFormat")
    suspend fun fetchAppsList(
        context: Context, currentPath: String, type: String,
        append: Boolean = false, currentList: List<AppInfo> = emptyList(),
        isManage: Boolean = false, includeOverhead: Boolean = true,
        onlyAppSize: Boolean = false
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val fetchStart = System.currentTimeMillis()
        val cacheKey = cacheKeyOf(currentPath, type, isManage, includeOverhead, onlyAppSize)
        // A cache entry is only valid for the exact inputs that produced it.
        val keyMatches = cachedKey == cacheKey
        if (!append && keyMatches) {
            when (type) {
                "User" -> userAppsCache?.let { return@withContext it }
                "System" -> sysAppsCache?.let { return@withContext it }
                "Uninstalled" -> uninstalledCache?.let { return@withContext it }
                // Entering Migrate repeatedly re-ran the full expensive fetch
                // (shell + stats per app). Same invalidation as the rest.
                "AllInstalled" -> allInstalledCache?.let { return@withContext it }
                // The restore disk-view was NEVER cached: every entry into a
                // restore screen/preview re-ran the Meta grep + icon copy +
                // per-app stats (the "Restore pages load slowly" report).
                // Mutation paths (run completion, manual ops, manage deletes)
                // all clear via [clearCache], so the list stays honest.
                "AllBackups" -> restoreAppsCache?.let {
                    DroidVaultLogger.d("Migrate", "fetch AllBackups: ${it.size} app(s) (cached)")
                    return@withContext it
                }
            }
        }

        val pm = context.packageManager
        val apps = if (append) currentList.toMutableList() else mutableListOf()
        val sdf = java.text.SimpleDateFormat("hh:mm a • dd MMM, yyyy", Locale.getDefault())
        // One root pass for all vault-side existence/mtime probes (audit A-09).
        val vaultFiles = vaultFileIndex(currentPath)

        when (type) {
            "User", "System", "AllInstalled", "Uninstalled" -> {
                val sysModCmd =
                    "ls -1 /data/adb/modules/DroidVault/system/product/priv-app /data/adb/modules_update/DroidVault/system/product/priv-app 2>/dev/null"
                val systemizedLabels = Shell.cmd(sysModCmd).exec().out.toSet()

                val iconCacheDir = File(context.cacheDir, "droidvault_icons").apply { mkdirs() }

                val (mediaSizes, obbSizes) = fetchMediaAndObbSizes()

                // ONE grep for the whole vault instead of one per backed-up app
                // (63 root round-trips per fetch → 1; a measurable slice of the
                // list latency on label-keyed vaults). Returned paths are the
                // Meta.txt files that carry an SSAID line.
                val ssaidMetaFiles: Set<String> = try {
                    // The vault folder is the user's choice, so it is quoted rather
                    // than hand-wrapped in double quotes: a folder named with a `$
                    // or a backtick is a command otherwise, and the globs after it
                    // must stay outside the quotes to expand.
                    Shell.cmd(
                        "grep -l '^SSAID=' ${ShellQuote.q("$currentPath/Apps")}/*/*/Meta.txt 2>/dev/null"
                    )
                        .exec().out.map { it.trim() }.toSet()
                } catch (_: Exception) {
                    emptySet()
                }

                // ONE grep for every recorded size, batched like the SSAID pass.
                // The engine compares these against the live size to decide
                // whether a part is re-written, so reading them here is what
                // lets the estimate and the "changed only" filter agree with
                // what a run will actually do instead of the full app size.
                val recordedSizes: Map<String, Triple<Long, Long, Long>> = try {
                    val acc = mutableMapOf<String, Triple<Long, Long, Long>>()
                    Shell.cmd(
                        "grep -H -e '^AppSize=' -e '^DataExtSize=' -e '^MediaOBBSize=' " +
                                "\"$currentPath\"/Apps/*/*/Meta.txt 2>/dev/null"
                    ).exec().out.forEach { line ->
                        val sep = line.indexOf("/Meta.txt:")
                        if (sep == -1) return@forEach
                        val dir = line.substring(0, sep)
                        val kv = line.substring(sep + 10).split("=", limit = 2)
                        if (kv.size != 2) return@forEach
                        val prev = acc[dir] ?: Triple(-1L, -1L, -1L)
                        val value = kv[1].trim().toLongOrNull() ?: -1L
                        acc[dir] = when (kv[0]) {
                            "AppSize" -> prev.copy(first = value)
                            "DataExtSize" -> prev.copy(second = value)
                            "MediaOBBSize" -> prev.copy(third = value)
                            else -> prev
                        }
                    }
                    acc
                } catch (_: Exception) {
                    emptyMap()
                }

                val installedApps = if (type == "Uninstalled") {
                    pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
                } else {
                    pm.getInstalledApplications(0)
                }
                val fetchedApps = installedApps.chunked(16).flatMap { chunk ->
                    chunk.map { app ->
                        async(Dispatchers.IO) {
                        val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val isInst = (app.flags and ApplicationInfo.FLAG_INSTALLED) != 0

                        val shouldInclude = when (type) {
                            "AllInstalled" -> isInst
                            "System" -> isInst && isSys
                            "User" -> isInst && !isSys
                            "Uninstalled" -> !isInst
                            else -> false
                        }

                        if (shouldInclude) {
                            val label = app.loadLabel(pm).toString().replace("|", "").replace("\n", "").trim()
                            val pkg = app.packageName.replace("|", "").replace("\n", "").trim()
                            var version = ""
                            var vCode = 0L
                            val aPath = app.sourceDir

                            try {
                                val pi = pm.getPackageInfo(app.packageName, 0)
                                version = pi.versionName ?: ""
                                vCode = PackageInfoCompat.getLongVersionCode(pi)
                                } catch (e: Exception) {
                                    android.util.Log.d("MigratorManager", "Icon cache write skipped")
                                }

                            val iconFile = File(iconCacheDir, "${pkg}_icon.png")
                            if (!iconFile.exists()) {
                                try {
                                    val rawIcon = app.loadIcon(pm)
                                    val bitmap = getTinyBitmap(rawIcon)
                                    if (bitmap != null) {
                                        FileOutputStream(iconFile).use { out ->
                                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                                        }
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            val iconPath = if (iconFile.exists()) iconFile.absolutePath else null

                            // Label dirs are created from the SANITIZED label
                            // (ShellQuote.safeLabel) — probe with the same value.
                            val appDir =
                                "$currentPath/Apps/${if (isSys) "System" else "User"}/${ShellQuote.safeLabel(label)}"
                            val metaMtime = vaultFiles["$appDir/Meta.txt"] ?: 0L
                            val bTime = if (metaMtime > 0L) {
                                sdf.format(java.util.Date(metaMtime))
                            } else "No backup on device"

                            val availInBackup = mutableSetOf<Int>()
                            if (metaMtime > 0L) {
                                if (indexed(vaultFiles, "$appDir/App.shift")) availInBackup.add(1)
                                if (indexed(vaultFiles, "$appDir/Data.shift") ||
                                    indexed(vaultFiles, "$appDir/ExtData.shift") ||
                                    indexed(vaultFiles, "$appDir/UserDe.shift")
                                ) availInBackup.add(2)
                                if (indexed(vaultFiles, "$appDir/Permissions.txt")) availInBackup.add(3)
                                if (indexed(vaultFiles, "$appDir/Media.shift") ||
                                    indexed(vaultFiles, "$appDir/Obb.shift")
                                ) availInBackup.add(4)

                                if (ssaidMetaFiles.contains("$appDir/Meta.txt")) availInBackup.add(5)
                            }

                            val stats = getDetailedPackageSizes(context, pkg)
                            val duMedia = mediaSizes[pkg] ?: 0L
                            val duObb = obbSizes[pkg] ?: 0L

                            val appSizeKb = (stats.first - duObb).coerceAtLeast(0L)
                            val dataSizeKb = (stats.second - duMedia).coerceAtLeast(0L)
                            val mediaSizeKb = duMedia + duObb

                            val displaySizeKb = if (onlyAppSize) {
                                appSizeKb
                            } else {
                                appSizeKb + dataSizeKb + mediaSizeKb + if (includeOverhead) 31 else 0
                            }

                            val safeLabel = label.replace(Regex("[^a-zA-Z0-9_]"), "")
                            val isSystemizedApp = systemizedLabels.contains(safeLabel)
                            val isLaunchable = pm.getLaunchIntentForPackage(pkg) != null

                            AppInfo(
                                label = label,
                                packageName = pkg,
                                version = version,
                                isSystem = isSys,
                                iconPath = iconPath,
                                backupTime = bTime,
                                size = formatSize(displaySizeKb.toString()),
                                isSystemized = isSystemizedApp,
                                appSizeKb = appSizeKb,
                                dataSizeKb = dataSizeKb,
                                mediaSizeKb = mediaSizeKb,
                                isInstalled = isInst,
                                versionCode = vCode,
                                apkPath = aPath,
                                isLaunchable = isLaunchable,
                                availableInBackup = availInBackup,
                                // Mirrors the engine's own change test so the
                                // estimate and the "changed only" filter agree
                                // with what a run will actually re-write.
                                backedUpAppKb = recordedSizes[appDir]?.first ?: -1L,
                                backedUpDataKb = recordedSizes[appDir]?.second ?: -1L,
                                backedUpMediaKb = recordedSizes[appDir]?.third ?: -1L
                            )
                        } else {
                            null
                        }
                        }
                    }
                }.awaitAll().filterNotNull()
                apps.addAll(fetchedApps)
            }
            "RestoreUser", "RestoreSystem", "AllBackups" -> {
                val pathType = when (type) { "RestoreUser" -> "User"; "RestoreSystem" -> "System"; else -> "*" }

                // `UserDeSize` joins the list the engine writes: it records
                // `/data/user_de/0` on its own, alongside `DataSize` for
                // `/data/data`. The restore space gate reads these per-archive
                // figures, so a grep that left one out would silently drop a root
                // the gate is asked about.
                val command =
                    "grep -H -e \"^Name=\" -e \"^Package=\" -e \"^Version=\" -e \"^VersionCode=\" -e \"^AppSize=\" -e \"^DataExtSize=\" -e \"^MediaOBBSize=\" -e \"^DataSize=\" -e \"^UserDeSize=\" -e \"^ExtDataSize=\" -e \"^MediaSize=\" -e \"^ObbSize=\" -e \"^SSAID=\" \"$currentPath\"/Apps/$pathType/*/Meta.txt 2>/dev/null"
                val result = Shell.cmd(command).exec()
                val iconCacheDir = File(context.cacheDir, "droidvault_icons").apply { mkdirs() }

                val appMap = mutableMapOf<String, MutableMap<String, String>>()
                result.out.forEach { line ->
                    val delimiterIdx = line.indexOf("/Meta.txt:")
                    if (delimiterIdx != -1) {
                        val filePath = line.substring(0, delimiterIdx)
                        val kv = line.substring(delimiterIdx + 10)
                        val splitKv = kv.split("=", limit = 2)
                        if (splitKv.size == 2) {
                            appMap.getOrPut(filePath) { mutableMapOf() }[splitKv[0].trim()] = splitKv[1].trim()
                        }
                    }
                }

                val diskSizes = mutableMapOf<String, Long>()
                if (isManage && appMap.isNotEmpty()) {
                    appMap.keys.chunked(20).forEach { batch ->
                        batch.forEach { p ->
                            Shell.cmd("du -sk ${ShellQuote.q(p)} 2>/dev/null")
                                .exec().out.forEach { line ->
                                    val parts = line.trim().split(Regex("\\s+"), 2)
                                    if (parts.size == 2) {
                                        diskSizes[parts[1]] = parts[0].toLongOrNull() ?: 0L
                                    }
                                }
                        }
                    }
                }

                val iconScript = StringBuilder()
                appMap.forEach { (basePath, data) ->
                    val pkg = data["Package"] ?: return@forEach
                    if (!pkg.matches(Regex("^[A-Za-z][A-Za-z0-9_.]*$"))) return@forEach
                    val cacheFile = File(iconCacheDir, "${pkg}_icon.png")
                    if (!cacheFile.exists()) {
                        iconScript.append("cp ${ShellQuote.q("$basePath/Icon.png")} ${ShellQuote.q(cacheFile.absolutePath)} && chmod 644 ${ShellQuote.q(cacheFile.absolutePath)}\n")
                    }
                }
                if (iconScript.isNotEmpty()) {
                    Shell.cmd(iconScript.toString()).exec()
                }

                val deferredBackups = appMap.map { (basePath, data) ->
                    async(Dispatchers.IO) {
                        val sysType = basePath.split("/").lastOrNull { it == "System" || it == "User" } ?: "User"
                        val isSys = sysType == "System"
                        val label = data["Name"] ?: ""
                        val pkg = data["Package"] ?: ""
                        val version = data["Version"] ?: ""
                        val vCode = data["VersionCode"]?.toLongOrNull() ?: 0L

                        val appSizeKb: Long
                        val dataSizeKb: Long
                        val mediaSizeKb: Long

                        // What Meta.txt RECORDED, kept separate from the live
                        // sizes below: in Manage mode appSizeKb is the on-disk
                        // size, not the recorded one, and conflating them would
                        // make every app look "unchanged".
                        var recordedAppKb = -1L
                        var recordedDataKb = -1L
                        var recordedMediaKb = -1L

                        if (isManage) {
                            appSizeKb = diskSizes[basePath] ?: 0L
                            dataSizeKb = 0
                            mediaSizeKb = 0
                        } else {
                            val aSize = data["AppSize"]?.toLongOrNull() ?: 0L

                            val deSize = data["DataExtSize"]?.toLongOrNull()
                                ?: ((data["DataSize"]?.toLongOrNull()
                                    ?: 0L) + (data["ExtDataSize"]?.toLongOrNull() ?: 0L))

                            val moSize = data["MediaOBBSize"]?.toLongOrNull()
                                ?: ((data["MediaSize"]?.toLongOrNull()
                                    ?: 0L) + (data["ObbSize"]?.toLongOrNull() ?: 0L))

                            appSizeKb = aSize
                            dataSizeKb = deSize
                            mediaSizeKb = moSize
                            recordedAppKb = aSize
                            recordedDataKb = deSize
                            recordedMediaKb = moSize
                        }

                        val displaySizeKb = if (onlyAppSize) {
                            appSizeKb
                        } else {
                            appSizeKb + dataSizeKb + mediaSizeKb + if (includeOverhead) 31 else 0
                        }

                        if (label.isNotBlank() && pkg.isNotBlank()) {
                            val cacheFile = File(iconCacheDir, "${pkg}_icon.png")
                            val iconPath =
                                if (cacheFile.exists() && cacheFile.length() > 0) cacheFile.absolutePath else null

                            val appDir = basePath
                            val metaMtime = vaultFiles["$appDir/Meta.txt"] ?: 0L
                            val bTime = if (metaMtime > 0L) {
                                sdf.format(java.util.Date(metaMtime))
                            } else "No backup on device"

                            val isInst = try {
                                pm.getPackageInfo(pkg, 0); true
                            } catch (_: Exception) {
                                false
                            }

                            val availInBackup = mutableSetOf<Int>()
                            if (indexed(vaultFiles, "$appDir/App.shift")) availInBackup.add(1)
                            if (indexed(vaultFiles, "$appDir/Data.shift") ||
                                indexed(vaultFiles, "$appDir/ExtData.shift") ||
                                indexed(vaultFiles, "$appDir/UserDe.shift")
                            ) availInBackup.add(2)
                            if (indexed(vaultFiles, "$appDir/Permissions.txt")) availInBackup.add(3)
                            if (indexed(vaultFiles, "$appDir/Media.shift") ||
                                indexed(vaultFiles, "$appDir/Obb.shift")
                            ) availInBackup.add(4)
                            if (data.containsKey("SSAID")) availInBackup.add(5)

                            AppInfo(
                                label = label,
                                packageName = pkg,
                                version = version,
                                isSystem = isSys,
                                iconPath = iconPath,
                                backupTime = bTime,
                                size = formatSize(displaySizeKb.toString()),
                                isInstalled = isInst,
                                appSizeKb = appSizeKb,
                                dataSizeKb = dataSizeKb,
                                mediaSizeKb = mediaSizeKb,
                                versionCode = vCode,
                                availableInBackup = availInBackup,
                                // Mirrors what the engine compares the live size
                                // against, so the Kotlin-side estimate and the
                                // "changed only" filter agree with what a run
                                // will actually re-write.
                                backedUpAppKb = recordedAppKb,
                                backedUpDataKb = recordedDataKb,
                                backedUpMediaKb = recordedMediaKb
                            )
                        } else null
                    }
                }
                apps.addAll(deferredBackups.awaitAll().filterNotNull())
            }
            else -> {}
        }

        val finalApps = apps.filter { it.packageName != BuildConfig.APPLICATION_ID }
            .distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.ROOT) }

        if (!append) {
            when (type) {
                "User" -> userAppsCache = finalApps
                "System" -> sysAppsCache = finalApps
                "Uninstalled" -> uninstalledCache = finalApps
                "AllInstalled" -> allInstalledCache = finalApps
                "AllBackups" -> restoreAppsCache = finalApps
            }
            cachedKey = cacheKey
        }

        DroidVaultLogger.d(
            "Migrate",
            "fetch $type: ${finalApps.size} app(s) in ${System.currentTimeMillis() - fetchStart}ms" +
                (if (append) " (append)" else "")
        )
        return@withContext finalApps
    }

    @SuppressLint("SdCardPath")
    suspend fun runDynamicOperation(
        context: Context, state: AppState, selectedApps: List<AppInfo>, currentPath: String,
        updateProgress: (String, String, Int) -> Unit, onComplete: (String, String) -> Unit,
        trackProgress: Boolean = false,
        /** False for background/scheduled runs: tracked, but never auto-pops UI. */
        interactiveRun: Boolean = true,
        /**
         * True when the CALLER already holds [RunCoordinator] (the automation
         * worker): this call must not try to take the same non-reentrant gate.
         */
        gateHeld: Boolean = false
    ) = withContext(Dispatchers.IO) {
        var wakeLock: PowerManager.WakeLock? = null
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidVault::OperationWakelock")
            wakeLock.acquire(15 * 60 * 1000L)
        } catch (e: SecurityException) {
            android.util.Log.w("MigratorManager", "WakeLock denied, continuing without it")
        }

        // Live progress: when [trackProgress] the run feeds ProgressTracker so the
        // Processing screen can render per-app/per-part status. Owner id = hash of
        // this invocation (endRun only releases if still owned).
        //
        // SEED ORDER MATTERS: do_backup/do_restore sort targets by size
        // descending (DroidVault.sh: sort -t'|' -k1 -n -r) before processing.
        // Mirroring that order here is what keeps the hero app, the list
        // highlight, and "App N of M" on the SAME app.
        val isRestore = state.migratorMode.name.contains("RESTORE")
        DroidVaultLogger.i(
            "Migrate",
            "${if (isRestore) "RESTORE" else "BACKUP"} run: ${selectedApps.size} app(s) mode=${state.migratorMode}"
        )
        // The CLI comps flag filters Perm/AndID by consent (same rule as the
        // engine's global gate). Per-app comps OVERRIDE the flag entirely, so
        // restore per-app comps must apply the consent filter themselves or an
        // app-level override would bypass the user's consent choice.
        val consentComps = state.globalComponents.filter {
            when (it) {
                3 -> state.allowPermRestore
                5 -> state.allowSsaidRestore
                else -> true
            }
        }.toSet().ifEmpty { setOf(1, 2, 4) }
        val trackOwner = if (trackProgress) "mig-${System.nanoTime()}" else null
        if (trackOwner != null) {
            val op = if (isRestore) LiveOp.RESTORE else LiveOp.BACKUP
            val iconCacheDir = File(context.cacheDir, "droidvault_icons")
            val seeds = selectedApps.map { app ->
                val compsRaw = app.activeComponents ?: state.globalComponents
                // RESTORE seeds reflect what the backup actually contains, not
                // just the selection: the engine require_part-skips missing
                // artifacts at runtime, so seeding them produced "5/5 parts"
                // with three grayed "no X in backup" chips on APK-only
                // restores (user report 2026-10-05). AllBackups fetches carry
                // per-app availability — intersect. Empty availability (list
                // not fetched from AllBackups) keeps the raw selection. The
                // consent filter is folded in (see consentComps above).
                val comps = if (isRestore) {
                    com.phoenix.droidvault.models.SeedPolicy.restoreComponents(
                        compsRaw intersect consentComps, app.availableInBackup
                    )
                } else compsRaw
                DroidVaultLogger.v(
                    "Seed",
                    "${app.label}: comps=$compsRaw avail=${app.availableInBackup} -> ${comps.sorted()}"
                )
                val sysType = if (app.isSystem) "System" else "User"
                // Label dirs are created from the SANITIZED label — probe the
                // same value so the byte watch tracks the real artifacts.
                val appDir = "$currentPath/Apps/$sysType/${ShellQuote.safeLabel(app.label)}"
                // Byte watch per part. Backup polls the growing .shift outputs;
                // RESTORE polls the extraction TARGET dirs (root-only — the
                // tracker measures them with one batched root `du` per tick).
                // Without it a 15 GB extraction sat on a motionless spinner
                // while backup showed the same fill treatment (parity gap).
                val safePkg = try {
                    ShellQuote.requirePackage(app.packageName)
                } catch (_: Exception) {
                    ""
                }
                val watch = when {
                    !isRestore -> buildMap {
                        if (comps.contains(1)) put(Parts.APP, listOf("$appDir/App.shift"))
                        if (comps.contains(2)) put(
                            Parts.DATA,
                            listOf("$appDir/Data.shift", "$appDir/UserDe.shift", "$appDir/ExtData.shift")
                        )
                        if (comps.contains(4)) put(
                            Parts.MEDIA,
                            listOf("$appDir/Media.shift", "$appDir/Obb.shift")
                        )
                    }
                    safePkg.isNotEmpty() -> buildMap {
                        if (comps.contains(2)) put(
                            Parts.DATA,
                            listOf(
                                "/data/data/$safePkg",
                                "/data/user_de/0/$safePkg",
                                "/data/media/0/Android/data/$safePkg"
                            )
                        )
                        if (comps.contains(4)) put(
                            Parts.MEDIA,
                            listOf(
                                "/data/media/0/Android/media/$safePkg",
                                "/data/media/0/Android/obb/$safePkg"
                            )
                        )
                    }
                    else -> emptyMap()
                }
                val expected = buildMap {
                    if (comps.contains(1)) put(Parts.APP, app.appSizeKb)
                    if (comps.contains(2)) put(Parts.DATA, app.dataSizeKb)
                    if (comps.contains(4)) put(Parts.MEDIA, app.mediaSizeKb)
                }.filterValues { it > 0 }
                AppSeed(
                    packageName = app.packageName,
                    label = app.label,
                    isSystem = app.isSystem,
                    // Resolve icon from the app's own cache entry when present;
                    // missing icons stay letter avatars — never an app mismatch.
                    iconPath = app.iconPath ?: File(iconCacheDir, "${app.packageName}_icon.png")
                        .takeIf { it.exists() && it.length() > 0 }?.absolutePath,
                    parts = buildList {
                        if (comps.contains(1)) add(Parts.APP)
                        if (comps.contains(2)) add(Parts.DATA)
                        if (comps.contains(3)) add(Parts.PERM)
                        if (comps.contains(5)) add(Parts.SSAID)
                        if (comps.contains(4)) add(Parts.MEDIA)
                    },
                    sizeKb = app.appSizeKb + app.dataSizeKb + app.mediaSizeKb,
                    watchFiles = if (isRestore) emptyMap() else watch,
                    watchDirs = if (isRestore) watch else emptyMap(),
                    partExpectedKb = expected,
                )
            }.sortedByDescending { it.sizeKb }
            val started = ProgressTracker.startRun(
                ownerId = trackOwner,
                op = op,
                seeds = seeds,
                title = if (isRestore) "Restoring" else "Backing up",
                interactive = interactiveRun,
            )
            // A refused start means another live run owns the tracker. Running
            // anyway put two engines under one UI (audit A-08): abort with an
            // honest error instead of corrupting both runs' progress.
            if (!started) {
                android.util.Log.w(
                    "MigratorManager",
                    "ProgressTracker refused startRun — another tracked run is active"
                )
                DroidVaultLogger.w("Migrate", "Tracker refused startRun — another run is active")
                updateProgress(
                    "Another operation is in progress",
                    "Wait for it to finish, then try again.",
                    -1
                )
                onComplete(
                    "Operation Failed!",
                    "Another operation is already being tracked. Please wait for it to finish."
                )
                wakeLock?.let { if (it.isHeld) it.release() }
                return@withContext
            }
        }

        // Fail-closed engine-freshness gate: a backup/restore must never run
        // on a stale deployed script (an outdated engine reported completions
        // before the real work — the "10/10 apps done while 15 GB still
        // compressing" bug class). Deploy is normally done at app launch, but
        // that path can silently fail (e.g. pending root grant), so verify
        // deployed == bundled right here; if stale, attempt one repair; if
        // that fails, abort with a clear error instead of running old logic.
        if (state.migratorMode != MigratorMode.MANAGE && !BackendInstaller.isEngineUpToDate(context)) {
            android.util.Log.w("MigratorManager", "Engine stale — attempting repair before operation")
            DroidVaultLogger.w("Migrate", "Engine stale — attempting repair before operation")
            val repaired = BackendInstaller.installEngine(context)
            if (!repaired || !BackendInstaller.isEngineUpToDate(context)) {
                val msg = "Engine update required but could not be applied. " +
                    "Grant root access to Droid Vault and try again."
                android.util.Log.e("MigratorManager", "Aborting operation: $msg")
                DroidVaultLogger.e("Migrate", "Aborting operation: $msg")
                updateProgress("Operation Failed!", msg, -1)
                if (trackOwner != null) {
                    ProgressTracker.fail("Operation Failed!", msg)
                    // Same ownership leak as the gate refusal below: this
                    // return is also ahead of the try/finally that ends the
                    // run, so the tracker must be released explicitly or the
                    // next manual backup/restore is refused forever.
                    ProgressTracker.endRun(trackOwner)
                }
                onComplete("Operation Failed!", msg)
                return@withContext
            }
        }

        // Process-wide run gate (audit A-05): a manual run must never overlap a
        // scheduled or another manual run — a double restore duplicates
        // messages/call logs and two SMS-role handoffs would fight. Refuse
        // clearly instead of waiting (a run can take 30 minutes).
        val acquiredHere =
            if (gateHeld) false else RunCoordinator.tryAcquire("manual:${state.migratorMode.name}")
        if (!gateHeld && !acquiredHere) {
            val holder = RunCoordinator.currentOwner ?: "another operation"
            android.util.Log.w("MigratorManager", "run refused: gate held by $holder")
            updateProgress("Another operation is in progress", "$holder is still running.", -1)
            // The tracker was claimed above, BEFORE the gate, and this return
            // happens before the try/finally that releases it. Without an
            // explicit release the tracker stays Running with this owner for
            // the life of the process: beginRun then refuses every later manual
            // backup/restore (Migrate tab permanently dead), the BACKUP byte
            // poller started by startRun is never cancelled, and the live
            // overlay shows a phantom run that can never advance.
            if (trackOwner != null) {
                ProgressTracker.fail("Operation not started", "$holder is still running.")
                ProgressTracker.endRun(trackOwner)
            }
            onComplete(
                "Another operation is in progress",
                "$holder is still running. Please wait for it to finish and try again."
            )
            wakeLock?.let { if (it.isHeld) it.release() }
            return@withContext
        }

        try {
            val actText = if (isRestore) "Restoring Apps" else "Backing up Apps"

            // Free-space pre-flight for MANUAL runs too (audit A-21): the
            // automation path had this; manual backups could start with
            // near-zero space and fail per-file with cryptic ENOSPC. -1 =
            // df unavailable → never block on a failed probe.
            if (!isRestore && state.migratorMode != MigratorMode.MANAGE) {
                val freeKb = AutomationSafety.freeSpaceKb(currentPath)
                if (freeKb in 0 until AutomationSafety.MIN_FREE_KB) {
                    val msg = "Not enough free space at $currentPath (${freeKb / 1024} MB free, " +
                        "${AutomationSafety.MIN_FREE_KB / 1024} MB required)"
                    updateProgress("Operation Failed!", msg, -1)
                    if (trackOwner != null) ProgressTracker.fail("Operation Failed!", msg)
                    onComplete("Operation Failed!", msg)
                    return@withContext
                }
            }

            if (state.migratorMode == MigratorMode.MANAGE) {
                val baseDir = File(currentPath)
                if (!baseDir.exists()) {
                    ShellQuote.requireAbsPath(currentPath)
                    Shell.cmd("mkdir -p ${ShellQuote.q("$currentPath/Apps")} ${ShellQuote.q("$currentPath/Partitions")} && touch ${ShellQuote.q("$currentPath/.droidvault_dir")}")
                        .exec()
                }
                val appDirs = selectedApps.map {
                    val sys = if (it.isSystem) "System" else "User"
                    val label = ShellQuote.safeLabel(it.label)
                    "$currentPath/Apps/$sys/$label"
                }
                // Quote each path individually to avoid word-split/glob.
                //
                // These are MOVED to the quarantine, not erased. A backup archive
                // is the one thing here that cannot be re-derived from the
                // device — deleting it destroys the only copy until the next full
                // backup of every app. A rename frees the same space instantly
                // and can be undone, so the irreversible step is an explicit
                // "empty trash" the user has to ask for.
                var totalFreedKb = 0L
                appDirs.forEach { dir ->
                    val sizeOut = Shell.cmd("du -sk ${ShellQuote.q(dir)} 2>/dev/null").exec().out
                    totalFreedKb += sizeOut.sumOf {
                        it.trim().split("\\s+".toRegex()).firstOrNull()?.toLongOrNull() ?: 0L
                    }
                }
                val batch = moveToTrash(context, currentPath, appDirs, "apps")
                if (batch.entries.isEmpty()) {
                    // Nothing moved. Report the failure rather than claiming a
                    // deletion that did not happen.
                    val msg = "Could not remove the selected backups. Nothing was deleted."
                    updateProgress("Deletion Failed", msg, -1)
                    if (trackOwner != null) ProgressTracker.fail("Deletion Failed!", msg)
                    onComplete("Deletion Failed!", msg)
                    return@withContext
                }
                val notMoved = batch.entries.size
                val formattedSize = formatSize(totalFreedKb.toString())
                updateProgress("Backups Deleted", "Moved to trash", 100)
                onComplete(
                    "Deletion Complete!",
                    // A rename within one filesystem frees NOTHING. The archives
                    // are still on the phone, in .trash, and the free-space figure
                    // is unchanged. Saying "$formattedSize freed" is the most direct
                    // way to make a user skip the cleanup they still need — and it
                    // contradicted TrashUi, which correctly says the space is only
                    // reclaimed when the trash is emptied.
                    if (notMoved == selectedApps.size) {
                        "$notMoved backup(s) moved to Recently deleted, $formattedSize held. " +
                            "Nothing is erased and no space is freed until you empty the trash."
                    } else {
                        "$notMoved of ${selectedApps.size} moved to Recently deleted, $formattedSize held. " +
                            "No space is freed until you empty the trash."
                    }
                )
                return@withContext
            }

            val appPartsMap = mutableMapOf<String, String>()

            if (isRestore) {
                // Root-shell vault index: the java.io probes here were blind
                // under scoped storage (audit A-09). SafeLabel matches the
                // on-disk directory names the engine creates.
                val vaultFiles = vaultFileIndex(currentPath)
                selectedApps.forEachIndexed { index, app ->
                    updateProgress(
                        actText,
                        "Analyzing backups (${index + 1}/${selectedApps.size})...",
                        -1
                    )
                    val sysType = if (app.isSystem) "System" else "User"
                    val basePath = "$currentPath/Apps/$sysType/${ShellQuote.safeLabel(app.label)}"
                    val parts = mutableListOf<String>()

                    val activeComps = app.activeComponents ?: state.globalComponents
                    val curApp = activeComps.contains(1)
                    val curData = activeComps.contains(2)
                    val curPerm = activeComps.contains(3)
                    val curMedia = activeComps.contains(4)
                    val curId = activeComps.contains(5)

                    if (curApp && indexed(vaultFiles, "$basePath/App.shift")) parts.add("App")
                    if (curData && (indexed(vaultFiles, "$basePath/Data.shift") ||
                            indexed(vaultFiles, "$basePath/UserDe.shift") ||
                            indexed(vaultFiles, "$basePath/ExtData.shift"))
                    ) parts.add("Data")
                    if (curPerm && indexed(vaultFiles, "$basePath/Permissions.txt")) parts.add("Perm")
                    if (curMedia && (indexed(vaultFiles, "$basePath/Media.shift") ||
                            indexed(vaultFiles, "$basePath/Obb.shift"))
                    ) parts.add("Media")
                    if (curId) {
                        try {
                            val metaPath = "$currentPath/Apps/$sysType/${ShellQuote.safeLabel(app.label)}/Meta.txt"
                            val hasAndId = Shell.cmd("grep -q '^SSAID=' ${ShellQuote.q(metaPath)} && echo YES")
                                .exec().out.joinToString("").trim() == "YES"
                            if (hasAndId) parts.add("AndID")
                        } catch (e: Exception) {
                            android.util.Log.d("MigratorManager", "AndID check skipped: ${e.message}")
                        }
                    }
                    appPartsMap[app.packageName] = parts.joinToString(" • ")
                }
            } else {
                updateProgress(actText, "Checking app components...", -1)
                val d = "/data"
                val dlr = "$"
                val script = buildString {
                    selectedApps.forEach { app ->
                        // $pkg is interpolated straight into a generated shell
                        // script, so it MUST be validated — every sibling call
                        // site already does this. Without it a package name
                        // carrying a quote or `$()` would become code inside
                        // check_comps.sh, which then runs as root.
                        val pkg = ShellQuote.requirePackage(app.packageName)
                        val activeComps = app.activeComponents ?: state.globalComponents
                        val curApp = activeComps.contains(1)
                        val curData = activeComps.contains(2)
                        val curPerm = activeComps.contains(3)
                        val curMedia = activeComps.contains(4)
                        val curId = activeComps.contains(5)

                        appendLine("res=\"\"")
                        if (curApp) appendLine("res=\"${dlr}res|App\"")
                        if (curData) {
                            appendLine("res=\"${dlr}res|Data\"")
                            appendLine("if [ -d \"$d/media/0/Android/data/$pkg\" ] && [ \"${dlr}(ls -A $d/media/0/Android/data/$pkg 2>/dev/null)\" ]; then res=\"${dlr}res|ExtData\"; fi")
                        }
                        if (curPerm) appendLine("res=\"${dlr}res|Perm\"")
                        if (curMedia) {
                            appendLine("if [ -d \"$d/media/0/Android/media/$pkg\" ] && [ \"${dlr}(ls -A $d/media/0/Android/media/$pkg 2>/dev/null)\" ]; then res=\"${dlr}res|Media\"; fi")
                            appendLine("if [ -d \"$d/media/0/Android/obb/$pkg\" ] && [ \"${dlr}(ls -A $d/media/0/Android/obb/$pkg 2>/dev/null)\" ]; then res=\"${dlr}res|Obb\"; fi")
                        }
                        if (curId) appendLine("if grep -q 'package=\"$pkg\"' $d/system/users/0/settings_ssaid.xml 2>/dev/null; then res=\"${dlr}res|AndID\"; fi")
                        appendLine("echo \"$pkg==${dlr}res\"")
                    }
                }

                val scriptFile = File(context.cacheDir, "check_comps.sh")
                scriptFile.writeText(script)
                val out = Shell.cmd("sh ${ShellQuote.q(scriptFile.absolutePath)}").exec().out
                scriptFile.delete()

                out.forEach { line ->
                    val split = line.split("==")
                    if (split.size == 2) {
                        val comps = split[1].split("|").filter { it.isNotBlank() }.joinToString(" • ")
                        appPartsMap[split[0]] = comps
                    }
                }
            }

            val targetData = selectedApps.joinToString("\n") { app ->
                val sysType = if (app.isSystem) "System" else "User"
                // Per-app comps are what the engine CHK-gates per app. For
                // RESTORE they must carry selection ∩ availability ∩ consent:
                // empty comps fall back to the (unfiltered) global flag inside
                // the engine, which re-introduced the "5/5 parts with grayed
                // no-X-in-backup chips" bug on APK-only restores and could
                // restore Perm/AndID against the user's consent choice.
                val comps = if (isRestore) {
                    val raw = app.activeComponents ?: state.globalComponents
                    com.phoenix.droidvault.models.SeedPolicy.restoreComponents(
                        raw intersect consentComps, app.availableInBackup
                    ).sorted().joinToString(" ")
                } else {
                    app.activeComponents?.sorted()?.joinToString(" ") ?: ""
                }
                // Label is sanitized (ShellQuote.safeLabel): it becomes a
                // directory name AND a pipe-delimited field — a raw label with
                // '|' would shift the engine's field parse and corrupt paths.
                // For RESTORE the package name is read back out of Meta.txt in
                // the archive, i.e. it is attacker-supplied, so validate it and
                // reject any field that could break the `|`-delimited protocol
                // (a newline would inject an entirely new install line).
                val pkg = try {
                    ShellQuote.requirePackage(app.packageName)
                } catch (_: IllegalArgumentException) {
                    android.util.Log.w("MigratorManager", "skipping target with invalid package id")
                    return@joinToString ""
                }
                val apk = app.apkPath ?: ""
                if ('\n' in apk || '\r' in apk || '|' in apk) {
                    android.util.Log.w("MigratorManager", "skipping target with unsafe apk path")
                    return@joinToString ""
                }
                "$pkg|${ShellQuote.safeLabel(app.label)}|${ShellQuote.safeLabel(app.version)}|${app.versionCode}|$sysType|$apk|${app.appSizeKb}|${app.dataSizeKb}|${app.mediaSizeKb}|$comps"
            } + "\n"

            val targetFile = File(context.cacheDir, "droidvault_targets.txt")
            targetFile.writeText(targetData)
            // Private staging: keep targets in app-private cache (0600) and stream via stdin-safe copy
            targetFile.setReadable(false, false)
            targetFile.setReadable(true, true)
            targetFile.setWritable(true, true)
            // Write through a fresh temp and rename into place. The old
            // `cat src > /data/local/tmp/droidvault_targets.txt` followed a
            // pre-planted symlink (that directory is shell-writable and had no
            // sticky bit), so root's redirect truncated whatever it pointed at.
            // A root-owned temp plus a same-directory rename removes the
            // symlink window entirely.
            val targetsTmp = "/data/local/tmp/.dv_targets_$$"
            Shell.cmd(
                "rm -f ${ShellQuote.q(targetsTmp)} /data/local/tmp/droidvault_targets.txt" +
                    " && cat ${ShellQuote.q(targetFile.absolutePath)} > ${ShellQuote.q(targetsTmp)}" +
                    " && chmod 600 ${ShellQuote.q(targetsTmp)}" +
                    " && mv -f ${ShellQuote.q(targetsTmp)} /data/local/tmp/droidvault_targets.txt" +
                    " && chmod 600 /data/local/tmp/droidvault_targets.txt" +
                    " && rm -f ${ShellQuote.q(targetsTmp)}"
            )
                .exec()
            targetFile.delete()
            val operation = if (isRestore) "--restore" else "--backup"
            // Consent enforcement: strip Perm(3)/AndID(5) unless explicitly allowed
            val effectiveComps = state.globalComponents.filter {
                when (it) {
                    3 -> state.allowPermRestore
                    5 -> state.allowSsaidRestore
                    else -> true
                }
            }.sorted().ifEmpty { listOf(1, 2, 4) }
            val compsString = ShellQuote.requireComps(effectiveComps.joinToString(" "))
            val env = InstallerPolicy.shellEnv(context)

            val command =
                "$env sh /data/adb/DroidVault/DroidVault.sh $operation ${ShellQuote.q(compsString)} ${ShellQuote.q(currentPath)}"
            if (state.migratorMode == MigratorMode.BACKUP_APPS) {
                val iconScript = java.lang.StringBuilder()
                val iconCacheDir = File(context.cacheDir, "droidvault_icons")

                selectedApps.forEachIndexed { index, app ->
                    updateProgress(
                        actText,
                        "Preparing icons (${index + 1}/${selectedApps.size})...",
                        -1
                    )
                    val sysType = if (app.isSystem) "System" else "User"
                    val destDir = "$currentPath/Apps/$sysType/${ShellQuote.safeLabel(app.label)}"
                    val destIcon = File(destDir, "Icon.png")

                    if (destIcon.exists() && destIcon.length() > 0) return@forEachIndexed

                    try {
                        val cacheFile = File(iconCacheDir, "${app.packageName}_icon.png")

                        if (cacheFile.exists() && cacheFile.length() > 0) {
                            iconScript.append("mkdir -p ${ShellQuote.q(destDir)}\n")
                            iconScript.append("cp ${ShellQuote.q(cacheFile.absolutePath)} ${ShellQuote.q(destIcon.absolutePath)}\n")
                            iconScript.append("chmod 644 ${ShellQuote.q(destIcon.absolutePath)}\n")
                        } else {
                            val rawIcon = context.packageManager.getApplicationIcon(app.packageName)
                            val bitmap = getTinyBitmap(rawIcon)

                            if (bitmap != null) {
                                val tempFile = File(context.cacheDir, "icon_${app.packageName}.png")
                                FileOutputStream(tempFile).use { out ->
                                    bitmap.compress(
                                        Bitmap.CompressFormat.PNG,
                                        100,
                                        out
                                    )
                                }

                                try {
                                    FileOutputStream(cacheFile).use { out ->
                                        bitmap.compress(
                                            Bitmap.CompressFormat.PNG,
                                            100,
                                            out
                                        )
                                    }
                                } catch (_: Exception) {
                                }

                                iconScript.append("mkdir -p ${ShellQuote.q(destDir)}\n")
                                iconScript.append("cp ${ShellQuote.q(tempFile.absolutePath)} ${ShellQuote.q(destIcon.absolutePath)}\n")
                                iconScript.append("chmod 644 ${ShellQuote.q(destIcon.absolutePath)}\n")
                                iconScript.append("rm -f ${ShellQuote.q(tempFile.absolutePath)}\n")
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MigratorManager", "Icon prepare failed: ${e.message}")
                    }
                }
                if (iconScript.isNotEmpty()) {
                    val scriptFile = File(context.cacheDir, "copy_icons.sh")
                    scriptFile.writeText(iconScript.toString())
                    Shell.cmd("sh ${ShellQuote.q(scriptFile.absolutePath)}").exec()
                    scriptFile.delete()
                }
            }
            // Overall guard: the UI must never stick forever even if the backend
            // exits without GLOBAL_DONE (lost final line, killed shell, etc.).
            //
            // Both bounds below scale with the bytes this run can write. They
            // used to be flat — 15 minutes inside ShellEngine (inherited from
            // its default) and 30 here — which is a bound on speed rather than
            // on liveness, and the inner one SIGTERMs the engine when it
            // fires. A large library is legitimately slower than either, so a
            // full backup died mid-write and the user was told it timed out.
            // The engine's stall detector only watches the TRANSFER steps, so
            // nothing else covered this path.
            //
            // Both are derived from one value on purpose: scaling only the inner
            // would leave this outer `withTimeoutOrNull` as the tighter and
            // therefore effective ceiling, which cancels the coroutine while
            // the engine keeps running unobserved.
            val runWatchdogMs = VaultTransfer.runWatchdogMs(selectedApps)
            val failedInstalls = mutableMapOf<String, String>()
            val finished = kotlinx.coroutines.withTimeoutOrNull(runWatchdogMs) {
                // Exactly-once completion: the backend exit is ALSO reported via
                // BackendExited (final stdout lines can be lost in async dispatch),
                // so guard against double onComplete.
                var completed = false
                fun completeOnce(action: String, step: String) {
                    if (!completed) {
                        completed = true
                        onComplete(action, step)
                    }
                }
                ShellEngine.executeEngineCommand(command, timeoutMs = runWatchdogMs).collect { event ->
                    if (trackOwner != null) ProgressTracker.onEngineEvent(event, if (isRestore) LiveOp.RESTORE else LiveOp.BACKUP)
                    when (event) {
                    is EngineEvent.BackupProgress -> {
                        val app = selectedApps.find { it.label == event.label }
                        val activeParts = appPartsMap[app?.packageName] ?: ""
                        val partsString = if (activeParts.isNotEmpty()) "\n• $activeParts" else ""

                        val rawKb = event.size.replace(Regex("[^0-9]"), "").toLongOrNull() ?: 0L
                        val formattedSize = if (rawKb > 0) formatSize(rawKb.toString()) else ""
                        val sizeInfo = if (formattedSize.isNotEmpty()) " [Size: $formattedSize]" else ""

                        val title =
                            if (event.jobs > 0) "$actText (+${event.jobs} more)" else actText
                        updateProgress(
                            title,
                            "${event.label}$sizeInfo (${event.current}/${event.total})$partsString",
                            event.percent
                        )
                    }

                    is EngineEvent.InfoStep -> {
                        val title =
                            if (event.jobs > 0) "$actText (+${event.jobs} more)" else actText
                        updateProgress(title, event.msg, -1)
                    }

                    is EngineEvent.Error -> {
                        completeOnce("Operation Failed!", event.msg)
                    }

                    is EngineEvent.Cancelled -> {
                        completeOnce("Operation Cancelled!", "Stopped. Finished apps are kept.")
                        if (trackOwner != null) ProgressTracker.complete("Operation Cancelled", "Stopped. Finished apps are kept.")
                    }

                    is EngineEvent.BackendExited -> {
                        // Backend is gone but no terminal line arrived (lost in
                        // dispatch). Complete from the exit code instead of
                        // hanging until the 30-min guard.
                        if (event.success) {
                            val done = if (isRestore) "Restore Complete!" else "Backup Complete!"
                            completeOnce(done, "Finished (exit confirmed)")
                        } else {
                            completeOnce("Operation Failed!", "backend exited abnormally")
                        }
                    }

                    is EngineEvent.GlobalDone -> {
                        val smartSize = formatSize(event.totalKb)
                        val actDone = if (isRestore) "Restore Complete!" else "Backup Complete!"
                        if (trackOwner != null) ProgressTracker.complete(actDone, "Total Size: $smartSize | Time: ${event.timeSec}s")

                        val timeSecInt = event.timeSec.toIntOrNull() ?: 0
                        val timeStr = if (timeSecInt >= 60) "${timeSecInt / 60}m ${timeSecInt % 60}s" else "${timeSecInt}s"

                        if (failedInstalls.isNotEmpty()) {
                            val names = failedInstalls.keys.take(3).joinToString(", ")
                            val more = if (failedInstalls.size > 3) " +${failedInstalls.size - 3} more" else ""
                            completeOnce("Restore Partial!", "Failed: $names$more — others restored")
                        } else {
                            completeOnce(actDone, "Total Size: $smartSize | Time: $timeStr")
                        }
                    }
                    is EngineEvent.InstallDone -> {
                        failedInstalls.remove(event.pkg)
                    }
                    is EngineEvent.InstallError -> {
                        failedInstalls[event.pkg] = event.msg
                        updateProgress(actText, "Install failed: ${event.pkg}", -1)
                    }
                    else -> {}
                    }
                }
            }
            if (finished == null) {
                // State the bound that ACTUALLY applied. It used to say "30 min"
                // as a literal, which became a lie the moment the bound scaled:
                // a five-hour ceiling reporting "did not finish in 30 min" would
                // have the user hunting for a deadline that never existed.
                val limit = when {
                    runWatchdogMs % 3_600_000L == 0L -> "${runWatchdogMs / 3_600_000L} h"
                    runWatchdogMs % 60_000L == 0L -> "${runWatchdogMs / 60_000L} min"
                    else -> "${runWatchdogMs / 1000L} s"
                }
                val detail = "Backend did not finish in $limit. Your files on disk are safe — retry or check the backup folder."
                onComplete("Operation timed out!", detail)
                if (trackOwner != null) ProgressTracker.fail("Operation timed out", detail)
            }
        } catch (e: Exception) {
            if (trackOwner != null && e !is kotlinx.coroutines.CancellationException) {
                ProgressTracker.fail("Operation Failed", e.message ?: "unknown error")
            }
            throw e
        } finally {
            if (trackOwner != null) ProgressTracker.endRun(trackOwner)
            wakeLock?.let { if (it.isHeld) it.release() }
            if (acquiredHere) RunCoordinator.release()
        }
    }

    private fun getTinyBitmap(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        return try {
            val size = 120
            val bitmap = createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    fun getAvailableSpaceKb(path: String): Long {
        return try {
            var file: File? = File(path)
            while (file != null && !file.exists()) {
                file = file.parentFile
            }
            val stat = android.os.StatFs(file?.absolutePath ?: "/")
            (stat.availableBlocksLong * stat.blockSizeLong) / 1024
        } catch (_: Exception) {
            0L
        }
    }

    fun getTotalSpaceKb(path: String): Long {
        return try {
            var file: File? = File(path)
            while (file != null && !file.exists()) {
                file = file.parentFile
            }
            val stat = android.os.StatFs(file?.absolutePath ?: "/")
            (stat.blockCountLong * stat.blockSizeLong) / 1024
        } catch (_: Exception) {
            0L
        }
    }

    private fun getDetailedPackageSizes(context: Context, packageName: String): Pair<Long, Long> {
        try {
            val storageStatsManager =
                context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val stats = storageStatsManager.queryStatsForPackage(
                UUID_DEFAULT,
                packageName,
                android.os.Process.myUserHandle()
            )

            val appSize = stats.appBytes / 1024
            val dataSize = (stats.dataBytes - stats.cacheBytes) / 1024

            return appSize to dataSize
        } catch (_: Exception) {
            try {
                val pathCmd = "pm path $packageName"
                val apkPath = Shell.cmd(pathCmd).exec().out.firstOrNull()?.removePrefix("package:")
                if (!apkPath.isNullOrBlank() && (apkPath.startsWith("/data/app") || apkPath.startsWith(
                        "/data/priv-app"
                    ))
                ) {
                    val sizeCmd = "du -sk $(dirname \"$apkPath\") 2>/dev/null | awk '{print $1}'"
                    val sizeKb = Shell.cmd(sizeCmd).exec().out.firstOrNull()?.toLongOrNull() ?: 0L
                    return sizeKb to 0L
                }
            } catch (_: Exception) {
            }
            return 0L to 0L
        }
    }

    private fun fetchMediaAndObbSizes(): Pair<Map<String, Long>, Map<String, Long>> {
        val mediaSizes = mutableMapOf<String, Long>()
        val obbSizes = mutableMapOf<String, Long>()

        Shell.cmd("du -sk /data/media/0/Android/media/* 2>/dev/null")
            .exec().out.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), 2)
            if (parts.size == 2) {
                val size = parts[0].toLongOrNull() ?: 0L
                val pkg = parts[1].split("/").lastOrNull() ?: ""
                if (pkg.isNotEmpty()) mediaSizes[pkg] = size
            }
        }

        Shell.cmd("du -sk /data/media/0/Android/obb/* 2>/dev/null")
            .exec().out.forEach { line ->
            val parts = line.trim().split(Regex("\\s+"), 2)
            if (parts.size == 2) {
                val size = parts[0].toLongOrNull() ?: 0L
                val pkg = parts[1].split("/").lastOrNull() ?: ""
                if (pkg.isNotEmpty()) obbSizes[pkg] = size
            }
        }

        return mediaSizes to obbSizes
    }

    /** KB-string -> human size. Delegates to the shared [Formatters] (P6 dedupe). */
    fun formatSize(kbString: String): String {
        val kb = kbString.trim().toLongOrNull() ?: return "$kbString KB"
        return Formatters.formatKb(kb)
    }
}