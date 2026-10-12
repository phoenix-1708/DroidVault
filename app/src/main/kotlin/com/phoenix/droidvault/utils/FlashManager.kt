package com.phoenix.droidvault.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.phoenix.droidvault.models.FlashAction
import com.phoenix.droidvault.models.FlashZip
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

object FlashManager {

    /**
     * Partition-image file name: anchored, so no shell metacharacter and no
     * path separator can survive. `ls`-derived names reach this validator, so
     * anything outside this alphabet is rejected rather than sanitised.
     */
    private val IMG_NAME = Regex("^[A-Za-z0-9._-]{1,120}\\.img$")

    /**
     * Conservative validation for a path that may end up in an
     * OpenRecoveryScript `install` line (audit A-12) or in a root `unzip -l`.
     *
     * docIds are attacker-influenceable (any app can register a
     * DocumentsProvider); ORS is line-based and executed by recovery as root,
     * so a CR/LF smuggled through a "path" would append arbitrary commands
     * (e.g. `format data`). Quotes/backslashes are rejected because the ORS
     * tokenizer's quoting rules vary across recoveries, and `..` for
     * traversal. Unicode names stay allowed — none of those bytes are special
     * to a line/token parser.
     *
     * `$`, backtick and the command separators are rejected because these
     * values were historically interpolated inside a `su -c "…"` double-quoted
     * string, where they are live syntax: `$(id)` and `;rm -rf …` both executed
     * as root (verified on-device). The interpolation sites are now unquoted
     * single-quoted `q()` arguments, so this is defence in depth — but
     * `isSafeOrsPath` is the documented gate for ORS lines, which recovery
     * executes with its own parser, so it must stay strict.
     */
    fun isSafeOrsPath(path: String?): Boolean {
        if (path.isNullOrEmpty() || !path.startsWith("/") || path.length >= 512) return false
        if (path.contains('\n') || path.contains('\r')) return false
        if (path.contains('\'') || path.contains('"') || path.contains('`') || path.contains('\\')) return false
        if (path.contains('$')) return false
        if (path.contains(';') || path.contains('|') || path.contains('&')) return false
        if (path.contains('<') || path.contains('>')) return false
        if (path.contains("..")) return false
        return true
    }

    @SuppressLint("SdCardPath")
    fun getPathFromUri(context: Context, uri: Uri): String? {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            // getDocumentId returns null for a document URI with fewer than two
            // path segments (e.g. the media documents provider) while isDocumentUri
            // still returns true, and throws for a non-document URI. Both are
            // reachable from ExternalInstallerActivity, which is exported with no
            // permission, and getPathFromUri is called on the MAIN thread.
            val docId = try { DocumentsContract.getDocumentId(uri) } catch (_: Exception) { null } ?: return null
            val split = docId.split(":")
            val type = split[0]
            val path = split.getOrNull(1) ?: ""

            if ("primary".equals(type, ignoreCase = true)) {
                val ext = Environment.getExternalStorageDirectory()?.absolutePath ?: return null
                val full = "$ext/$path"
                return full.takeIf { isSafeOrsPath(it) }
            }

            if ("raw".equals(type, ignoreCase = true)) {
                // Provider-controlled: accept only a conservative absolute path.
                return path.takeIf { isSafeOrsPath(it) }
            }

            val externalPath = "/storage/$type/$path"
            if (!isSafeOrsPath(externalPath)) return null
            val exists = Shell.cmd("[ -e ${ShellQuote.q(externalPath)} ]").exec().isSuccess
            if (exists) return externalPath
        }

        try {
            val path = uri.path
            if (path != null && (path.startsWith("/storage/") || path.startsWith("/sdcard"))) {
                return path.takeIf { isSafeOrsPath(it) }
            }
        } catch (_: Exception) {
        }

        return null
    }

    suspend fun processZips(
        uris: List<Uri>,
        currentZips: List<FlashZip>,
        append: Boolean,
        context: Context
    ): List<FlashZip> = coroutineScope {
        val zips = if (append) currentZips.toMutableList() else mutableListOf()

        // Bound root `unzip -l` probes to 4 at a time (was unbounded)
        val results = mutableListOf<FlashZip>()
        uris.chunked(4).forEach { chunk ->
            val deferredZips = chunk.map { uri ->
                async(Dispatchers.IO) {
                val path = getPathFromUri(context, uri) ?: return@async null
                if (zips.any { it.path == path }) return@async null

                val name = path.substringAfterLast("/")
                val nameLower = name.lowercase(Locale.ROOT)
                val contents =
                    Shell.cmd("unzip -l ${ShellQuote.q(path)}").exec().out.joinToString("\n")

                val hasModuleProp = contents.contains("module.prop", ignoreCase = true)
                val hasUpdateBinary =
                    contents.contains(
                        Regex(
                            "META-INF/.*update-binary|META-INF/.*updater-script",
                            RegexOption.IGNORE_CASE
                        )
                    )
                var isGapps = false
                var isAddon = false

                if (nameLower.contains(Regex("gapps|nikgapps|bitgapps|mindthegapps"))) {
                    isGapps = true; if (nameLower.contains("addon")) isAddon = true
                } else if (contents.contains(Regex("bitgapps|nikgapps|mindthegapps|busybox-arm|util_functions\\.sh"))) {
                    isGapps = true; isAddon = true
                }

                val category: String? = when {
                    isGapps -> if (isAddon) "Addon" else "GApps"
                    hasModuleProp -> null
                    contents.contains(Regex("firmware-update", RegexOption.IGNORE_CASE)) ||
                            contents.contains(
                                Regex(
                                    "abl\\.elf|xbl\\.elf|tz\\.mbn|devcfg\\.mbn",
                                    RegexOption.IGNORE_CASE
                                )
                            ) -> "Firmware"

                    contents.contains(
                        Regex(
                            "payload\\.bin|system\\.new\\.dat|system\\.transfer\\.list|apex_info\\.pb|care_map\\.pb|payload_properties\\.txt|META-INF/com/android/metadata",
                            RegexOption.IGNORE_CASE
                        )
                    ) -> "ROM"
                    contents.contains(
                        Regex(
                            "anykernel|zimage|image\\.gz",
                            RegexOption.IGNORE_CASE
                        )
                    ) ||
                            nameLower.contains(Regex("kernel|perf|stormbreaker|eas")) -> "Kernel"

                    contents.contains(
                        Regex(
                            "recovery\\.img|ramdisk-recovery\\.img|ramdisk-recovery\\.cpio|magiskboot|PBRP|orangefox|twrp",
                            RegexOption.IGNORE_CASE
                        )
                    ) -> "Recovery"
                    hasUpdateBinary -> "Other"
                    else -> null
                }

                if (category != null) FlashZip(name, path, category) else null
                }
            }

            results.addAll(deferredZips.awaitAll().filterNotNull())
        }

        zips.addAll(results)

        val order = listOf("Firmware", "Recovery", "ROM", "GApps", "Addon", "Kernel", "Other")
        zips.sortedWith(compareBy<FlashZip> {
            val index = order.indexOf(it.category); if (index == -1) 99 else index
        }.thenBy { it.name })
    }

    fun checkLockscreen(): Boolean = !Shell.cmd("locksettings verify").exec().isSuccess

    @SuppressLint("SdCardPath")
    fun generateOrsAndProceed(actions: List<FlashAction>, rebootOption: String) {
        val script = StringBuilder()

        actions.forEach { action ->
            when (action) {
                is FlashAction.Wipe -> {
                    action.partitions.forEach { script.append("wipe $it\n") }
                }

                is FlashAction.InstallZip -> {
                    var path = action.zip.path
                    if (path.startsWith("/storage/emulated/0")) {
                        path = path.replace("/storage/emulated/0", "/sdcard")
                    }
                    // Last line of defense before recovery executes this
                    // (audit A-12): never write an `install` line for a path
                    // that fails the decode-time validation.
                    if (!isSafeOrsPath(path)) {
                        android.util.Log.w(
                            "FlashManager",
                            "skipping unsafe ORS path (name=${action.zip.name.take(64)})"
                        )
                        return@forEach
                    }
                    script.append("install $path\n")
                }

                is FlashAction.FormatData -> {
                    script.append("format data\n")
                }
            }
        }

        // `isSafeOrsPath` already rejects every quote/backslash byte, so the
        // script can never contain a `'`. The old manual `'\''` pre-escape was
        // therefore dead code that would have double-escaped and produced an
        // unterminated-quote syntax error the moment that validation relaxed.
        val scriptContent = script.toString()
        val safeReboot = when (rebootOption) { "system", "recovery", "bootloader", "none" -> rebootOption else -> "none" }
        Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --ors ${ShellQuote.q(scriptContent)} ${ShellQuote.q(safeReboot)}")
            .exec()
    }

    fun restartFlashWizard() {
        val locations = listOf("/cache/recovery", "/data/cache/recovery", "/metadata/recovery")
        val shellCommand =
            "mount -o rw,remount /cache 2>/dev/null; mount -o rw,remount /metadata 2>/dev/null; rm -f ${
                locations.joinToString(
                    " "
                ) { "$it/openrecoveryscript" }
            } 2>/dev/null"
        Shell.cmd(shellCommand).exec()
    }

    fun executeFlashNow() {
        Shell.cmd("sync; reboot recovery").exec()
    }

    fun getAllPartitions(): List<String> {
        return Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --get-partitions")
            .exec().out
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .sorted()
    }

    fun getBackedUpImages(savedPath: String): List<String> {
        ShellQuote.requireAbsPath(savedPath)
        return Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --get-images ${ShellQuote.q(savedPath)}")
            .exec().out
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun deleteLivePartitionImage(savedPath: String, imgName: String) {
        ShellQuote.requireAbsPath(savedPath)
        // Anchor the whole name: a bare `endsWith(".img")` still let `$`, a
        // backtick or a `;` through into a root shell.
        require(IMG_NAME.matches(imgName)) { "Invalid image: $imgName" }
        Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --delete-image ${ShellQuote.q(savedPath)} ${ShellQuote.q(imgName)}")
            .exec()
    }

    /**
     * Live backup/restore of one partition. Returns true only when the engine
     * emitted its success token (audit A-11: the UI previously reported
     * success unconditionally — a failed partition flash looked successful).
     */
    fun runLiveOperation(action: String, partition: String, customPath: String?, savedPath: String): Boolean {
        require(action == "--live-backup" || action == "--live-restore") { "Invalid action" }
        ShellQuote.requirePartition(partition)
        ShellQuote.requireAbsPath(savedPath)
        val out = if (action == "--live-backup") {
            Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh $action ${ShellQuote.q(partition)} ${ShellQuote.q(savedPath)}")
                .exec().out.joinToString("\n")
        } else {
            val imgPath = customPath ?: "$savedPath/Partitions/${partition}_backup.img"
            ShellQuote.requireAbsPath(imgPath)
            require(imgPath.endsWith(".img")) { "Only .img allowed" }
            Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh $action ${ShellQuote.q(partition)} ${ShellQuote.q(imgPath)}")
                .exec().out.joinToString("\n")
        }
        val token = if (action == "--live-backup") "LIVE_BACKUP_OK|PART:$partition" else "LIVE_RESTORE_OK|PART:$partition"
        return out.contains(token)
    }
}