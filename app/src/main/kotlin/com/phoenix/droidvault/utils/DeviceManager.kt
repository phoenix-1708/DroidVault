package com.phoenix.droidvault.utils

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.Log
import androidx.core.net.toUri
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object DeviceManager {
    private val threadIdCache = mutableMapOf<Set<String>, Long>()

    /** Content-provider column names only: `[a-z_][a-z0-9_]*`. */
    private val BIND_KEY = Regex("^[a-z_][a-z0-9_]*$")

    /**
     * Read-backs of the SMS store after a batch insert. A row written through
     * the provider is not always visible to a snapshot taken the same instant,
     * so a single look can invent failures; three with a short pause does not.
     */
    private const val VERIFY_ATTEMPTS = 3

    /** Guarded rm -rf: only under app cacheDir, quoted with -- guard. */
    private fun rmRfCacheSubdir(cacheDir: String, name: String) {
        require(name.matches(Regex("^[A-Za-z0-9._-]+$"))) { "Invalid name" }
        val target = "$cacheDir/$name"
        require(target.startsWith(cacheDir + "/")) { "Escape" }
        Shell.cmd("rm -rf -- ${ShellQuote.q(target)}").exec()
    }

    /**
     * Re-own and re-label a freshly restored staging tree to the app itself.
     *
     * Two independent defects are repaired here, both required for the app to
     * read what root just wrote:
     *  1. **Owner**: archives created before 2.0.1 (and every legacy backup
     *     taken on a prior install) store the *old* uid/gid. Root extraction
     *     faithfully reproduces it; a uid that no longer exists leaves the
     *     file unreadable to the current app. `--owner`/`--group` on the tar
     *     line fixes NEW extractions, this chown covers archives already on
     *     disk and the `cp`-created RCS part files.
     *  2. **SELinux label**: even correctly-owned files extracted by root get
     *     the *generic* `app_data_file` context with NO per-app categories
     *     (`s0` instead of `s0:c234,c256,c512,c768`). Verified on-device:
     *     `restorecon` does NOT repair this (the file is already a valid
     *     app_data_file), only `chcon` to the parent directory's exact context
     *     does. Copying the cache dir's own context is ROM-agnostic — no
     *     hardcoded category numbers.
     */
    private fun reclaimCacheDir(context: Context, vararg names: String) {
        val cacheDir = context.cacheDir.absolutePath
        val quoted = names.joinToString(" ") { name ->
            require(name.matches(Regex("^[A-Za-z0-9._-]+$"))) { "Invalid name: $name" }
            ShellQuote.q("$cacheDir/$name")
        }
        if (quoted.isEmpty()) return
        val dirLabel = Shell.cmd("ls -dZ ${ShellQuote.q(cacheDir)}")
            .exec().out.firstOrNull()?.trim()?.substringBefore(' ') ?: ""
        // chcon LAST: it is the authoritative label. `restorecon` cannot add
        // the per-app categories on its own (it sees a valid app_data_file and
        // leaves it be), but running it first fixes any OTHER label a ROM may
        // have applied without fighting our context copy.
        val chcon = if (dirLabel.startsWith("u:")) {
            "chcon -R ${ShellQuote.q(dirLabel)} $quoted 2>/dev/null"
        } else ""
        Shell.cmd(
            // Own + group taken from the cache dir itself, not from myUid():
            // Android gives cache files the `u<id>_a<n>_cache` group and this
            // keeps every staging file indistinguishable from one java.io
            // would have created.
            "O=\$(stat -c '%u:%g' ${ShellQuote.q(cacheDir)} 2>/dev/null); " +
                "[ -n \"\$O\" ] && chown -R \"\$O\" $quoted 2>/dev/null; " +
                "restorecon -R $quoted 2>/dev/null; " +
                chcon
        ).exec()
    }

    fun getAvailableBackups(savedPath: String): Set<String> {
        ShellQuote.requireAbsPath(savedPath)
        val deviceDir = "$savedPath/Device"
        return Shell.cmd("ls ${ShellQuote.q(deviceDir)}").exec().out.toSet()
    }

    private const val ZAPDOS_PATH = "/data/adb/DroidVault/zapdos"

    /**
     * Folders recorded in the current Folders.shift archive (manifest read
     * through the root shell — java.io cannot see the backup dir under
     * scoped storage). Empty when the archive is absent or unreadable.
     * Powers the Migrate tab's Restore Folders dialog: it shows exactly what
     * a restore would bring back before anything is touched.
     */
    fun listArchivedFolders(savedPath: String): List<String> {
        ShellQuote.requireAbsPath(savedPath)
        val src = "$savedPath/Device/${BackupConfigFolders.ARCHIVE_FILE}"
        if (Shell.cmd("[ -f ${ShellQuote.q(src)} ] && echo y").exec().out.firstOrNull()?.trim() != "y") {
            return emptyList()
        }
        val res = Shell.cmd(
            "${ShellQuote.q(ZAPDOS_PATH)} -d -q -c ${ShellQuote.q(src)} | tar -xO -f - ${ShellQuote.q(BackupConfigFolders.MANIFEST_NAME)} 2>/dev/null"
        ).exec()
        return BackupConfigFolders.manifestFolders(res.out.joinToString("\n"))
    }

    /**
     * Remove the given folders from the on-disk Folders.shift archive by
     * REBUILDING it without the selected entries. Each folder keeps its
     * F<index> prefix (positions in the ORIGINAL manifest), and the manifest
     * travelling inside the archive is rewritten without the removed folders
     * — the restore AND the Manage Backups list both read that manifest, so
     * a rebuilt archive must never name entries it no longer holds.
     *
     * Atomic-ish: a temp archive is written first and only swapped over the
     * original when the rebuild succeeded — a failed run can never truncate
     * an existing backup. All-or-nothing by design: partial selections of a
     * single folder's CONTENTS are not possible (an archive entry IS a whole
     * folder).
     *
     * Returns the folders that remain in the archive after the rebuild, or
     * null when the archive is missing/unreadable/failed to rebuild.
     */
    fun deleteArchivedFolders(
        context: Context,
        savedPath: String,
        removePaths: List<String>,
        cacheDir: String,
    ): List<String>? {
        ShellQuote.requireAbsPath(savedPath)
        val src = "$savedPath/Device/${BackupConfigFolders.ARCHIVE_FILE}"
        if (Shell.cmd("[ -f ${ShellQuote.q(src)} ] && echo y").exec().out.firstOrNull()?.trim() != "y") {
            return null
        }
        val removeSet = BackupConfigFolders.sanitize(removePaths).toSet()
        if (removeSet.isEmpty()) return null
        val manifest = listArchivedFolders(savedPath)
        if (manifest.isEmpty()) return null
        val keep = manifest.filter { it !in removeSet }
        if (keep.size == manifest.size) return manifest // nothing matched
        if (keep.isEmpty()) {
            // Nothing left to keep, so the whole archive would go. This used to be a
            // bare `rm -f`: the ONE irreversible delete in the app, on an archive
            // that is frequently the only copy of the user's photos and downloads,
            // reachable in two taps (Select all -> red bin) with no dialog. The SAME
            // archive is quarantined by deleteDeviceBackups, so it had one
            // recoverable delete and one unrecoverable one. Use the quarantine.
            val quarantined = com.phoenix.droidvault.utils.MigratorManager.moveToTrash(
                context, savedPath, listOf(src), "folder backup"
            )
            return if (quarantined.entries.isNotEmpty()) emptyList() else null
        }
        // Rewritten manifest (kept folders only), staged app-side like
        // backupFolders does — root shells can read it, java.io writes it.
        val manifestFile = java.io.File(cacheDir, BackupConfigFolders.MANIFEST_NAME)
        try {
            manifestFile.writeText(BackupConfigFolders.manifestJson(keep))
            Shell.cmd("chmod 644 ${ShellQuote.q(manifestFile.absolutePath)}").exec()
            // Pack the new manifest + every kept F<index> entry (original
            // positions) into a temp archive, then swap atomically. Unique
            // per-call suffix: two concurrent rebuilds must never race on one
            // temp path (the literal "$$" before was NOT shell-expanded).
            val tmp = "$src.tmp-${System.nanoTime()}"
            val keptEntries = keep.map { "F" + manifest.indexOf(it) }
            val tarArgs = StringBuilder(
                "tar -cf - --owner=0 --group=0 -C ${ShellQuote.q(cacheDir)} ${ShellQuote.q(BackupConfigFolders.MANIFEST_NAME)}"
            )
            keptEntries.forEach { entry ->
                tarArgs.append(" -C ${ShellQuote.q(src)} ${ShellQuote.q(entry)}")
            }
            val rebuilt = Shell.cmd(
                "$tarArgs | ${ShellQuote.q(ZAPDOS_PATH)} -1 -f -q -o ${ShellQuote.q(tmp)} && " +
                    "mv ${ShellQuote.q(tmp)} ${ShellQuote.q(src)}"
            ).exec()
            val ok = rebuilt.isSuccess &&
                Shell.cmd("[ -f ${ShellQuote.q(src)} ] && echo y").exec().out.firstOrNull()?.trim() == "y"
            if (!ok) {
                Shell.cmd("rm -f -- ${ShellQuote.q(tmp)}").exec()
                return null
            }
            // The archive was replaced, so its digest has to be replaced with it.
            // Leaving the old one would report every folder still in there as
            // changed since the backup — a true statement about the old file and
            // a useless one about this one.
            VaultDigest.record(src)
            return keep
        } catch (_: Exception) {
            return null
        } finally {
            try {
                manifestFile.delete()
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("SdCardPath")
    private fun ensureTelephonyDirs(context: Context) {
        val telephonyPkg = "com.android.providers.telephony"
        val pkg = context.packageName
        ShellQuote.requirePackage(pkg)

        listOf(
            "/data/user/0/$telephonyPkg/databases",
            "/data/user_de/0/$telephonyPkg/databases",
            "/data/user_de/0/$telephonyPkg/app_parts"
        ).forEach { dir ->
            Shell.cmd("mkdir -p ${ShellQuote.q(dir)}").exec()
            Shell.cmd("chown radio:radio ${ShellQuote.q(dir)} && chmod 771 ${ShellQuote.q(dir)}").exec()
            Shell.cmd("if [ \"\$(ls -A ${ShellQuote.q(dir)} 2>/dev/null)\" ]; then chown radio:radio ${ShellQuote.q(dir)}/* 2>/dev/null; chmod 660 ${ShellQuote.q(dir)}/* 2>/dev/null; fi")
                .exec()
        }
        Shell.cmd("am force-stop ${ShellQuote.q(telephonyPkg)}").exec()
        Thread.sleep(500)

        if (!Shell.cmd("[ -f /data/user/0/$telephonyPkg/databases/mmssms.db ]").exec().isSuccess) {
            val systemSmsApp = listOf(
                "com.google.android.apps.messaging",
                "com.android.messaging",
                "com.android.mms"
            ).firstOrNull { Shell.cmd("pm list packages ${ShellQuote.q(it)}").exec().out.isNotEmpty() }
            if (systemSmsApp != null) {
                setDefaultSmsAppRoot(systemSmsApp)
                Thread.sleep(1000)
                setDefaultSmsAppRoot(pkg)
                Thread.sleep(500)
            }
        }
        Shell.cmd("content query --uri content://sms --projection _id --where \"1=0\"").exec()
    }

    fun setDefaultSmsAppRoot(pkg: String): Boolean {
        // Validated: strict package pattern makes bare interpolation safe here.
        ShellQuote.requirePackage(pkg)
        val roleCmd = Shell.cmd("cmd role add-role-holder android.app.role.SMS $pkg").exec()

        Shell.cmd("settings put secure sms_default_application $pkg").exec()
        Shell.cmd("appops set $pkg WRITE_SMS allow").exec()
        Shell.cmd("appops set $pkg SEND_SMS allow").exec()
        Shell.cmd("appops set $pkg RECEIVE_SMS allow").exec()
        Shell.cmd("appops set $pkg RECEIVE_MMS allow").exec()

        Shell.cmd("content query --uri content://settings/secure --where \"name='sms_default_application'\"")
            .exec()

        return roleCmd.isSuccess
    }

    /**
     * Hand the SMS role to [pkg] in ONE root shell invocation. Releasing the role
     * revokes our role-granted permissions, which kills our process mid-call —
     * separate Shell.cmd calls after that point never run, leaving us stuck as
     * default. A single invocation completes in the su daemon even if we die.
     * NOTE: adding the new holder is NOT enough — we must also REMOVE ourselves,
     * otherwise the role system keeps us as default and reverts the setting.
     */
    fun releaseSmsRoleTo(pkg: String, ownPkg: String) {
        ShellQuote.requirePackage(pkg)
        ShellQuote.requirePackage(ownPkg)
        Shell.cmd(
            "cmd role add-role-holder android.app.role.SMS $pkg;" +
                "cmd role remove-role-holder android.app.role.SMS $ownPkg;" +
                "settings put secure sms_default_application $pkg;" +
                "appops set $pkg WRITE_SMS allow;" +
                "appops set $pkg SEND_SMS allow;" +
                "appops set $pkg RECEIVE_SMS allow;" +
                "appops set $pkg RECEIVE_MMS allow"
        ).exec()
    }

    private fun getValidColumns(context: Context, uri: Uri): Set<String> {
        val q = {
            context.contentResolver.query(uri, null, "1=0", null, null)
                ?.use { it.columnNames.toSet() }
        }
        return try {
            q() ?: emptySet()
        } catch (_: Exception) {
            if (uri.toString().contains("sms") || uri.toString().contains("mms")) {
                ensureTelephonyDirs(context)
                try {
                    q() ?: emptySet()
                } catch (_: Exception) {
                    emptySet()
                }
            } else emptySet()
        }
    }

    private fun decodeQuotedPrintable(input: String): String {
        return try {
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < input.length) {
                val c = input[i]
                if (c == '=') {
                    val hex = input.substring(i + 1, i + 3); out.write(hex.toInt(16)); i += 3
                } else {
                    out.write(c.code); i++
                }
            }
            out.toString("UTF-8")
        } catch (_: Exception) {
            input
        }
    }

    private fun importVcf(context: Context, vcfFile: File, updateProgress: (Int) -> Unit) {
        try {
            val ops = ArrayList<ContentProviderOperation>()
            val unfoldedLines = mutableListOf<String>()
            var cur: StringBuilder? = null
            vcfFile.forEachLine { line ->
                if (line.startsWith(" ") || line.startsWith("\t")) cur?.append(
                    line.substring(
                        1
                    )
                ) else {
                    cur?.let { unfoldedLines.add(it.toString()) }; cur = StringBuilder(line)
                }
            }
            cur?.let { unfoldedLines.add(it.toString()) }
            var name: String? = null
            val phones = mutableListOf<String>()
            var photo: ByteArray? = null
            val total = unfoldedLines.size
            unfoldedLines.forEachIndexed { i, line ->
                when {
                    line.startsWith("BEGIN:VCARD", true) -> {
                        name = null; phones.clear(); photo = null
                    }

                    line.startsWith("FN:", true) || line.startsWith("FN;", true) -> {
                        val v = line.substringAfter(":").trim(); name = if (line.contains(
                                "ENCODING=QUOTED-PRINTABLE",
                                true
                            )
                        ) decodeQuotedPrintable(v) else v
                    }

                    (line.startsWith("N:", true) || line.startsWith(
                        "N;",
                        true
                    )) && name == null -> {
                        val v = line.substringAfter(":").trim()
                        val d = if (line.contains(
                                "ENCODING=QUOTED-PRINTABLE",
                                true
                            )
                        ) decodeQuotedPrintable(v) else v; name =
                            d.split(";").filter { it.isNotBlank() }.joinToString(" ")
                    }

                    line.startsWith("TEL", true) -> {
                        val n = line.substringAfter(":").trim(); if (n.isNotEmpty()) phones.add(n)
                    }

                    line.startsWith("PHOTO", true) -> {
                        try {
                            photo = android.util.Base64.decode(
                                line.substringAfter(":").trim(),
                                android.util.Base64.DEFAULT
                            )
                        } catch (_: Exception) {
                        }
                    }

                    line.startsWith("END:VCARD", true) -> {
                        if (!name.isNullOrEmpty() || phones.isNotEmpty()) {
                            val rIdx = ops.size
                            ops.add(
                                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                                    .build()
                            )
                            ops.add(
                                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                    .withValueBackReference(
                                        ContactsContract.Data.RAW_CONTACT_ID,
                                        rIdx
                                    ).withValue(
                                        ContactsContract.Data.MIMETYPE,
                                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE
                                    ).withValue(
                                        ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                                        name ?: "Unknown"
                                    ).build()
                            )
                            phones.forEach {
                                ops.add(
                                    ContentProviderOperation.newInsert(
                                        ContactsContract.Data.CONTENT_URI
                                    ).withValueBackReference(
                                        ContactsContract.Data.RAW_CONTACT_ID,
                                        rIdx
                                    ).withValue(
                                        ContactsContract.Data.MIMETYPE,
                                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE
                                    ).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, it)
                                        .withValue(
                                            ContactsContract.CommonDataKinds.Phone.TYPE,
                                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
                                        ).build()
                                )
                            }
                            photo?.let {
                                ops.add(
                                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                                        .withValueBackReference(
                                            ContactsContract.Data.RAW_CONTACT_ID,
                                            rIdx
                                        ).withValue(
                                            ContactsContract.Data.MIMETYPE,
                                            ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
                                        )
                                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, it)
                                        .build()
                                )
                            }
                        }
                        if (ops.size > 100) {
                            context.contentResolver.applyBatch(
                                ContactsContract.AUTHORITY,
                                ops
                            ); ops.clear()
                        }
                    }
                }
                if (i % 50 == 0 && total > 0) updateProgress(i * 100 / total)
            }
            if (ops.isNotEmpty()) context.contentResolver.applyBatch(
                ContactsContract.AUTHORITY,
                ops
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Returns null on success, or the reason the Messages item could not be
     * backed up. The caller turns that into a per-item failure on BOTH paths
     * (Migrate tab and automation): writing no `sms` key at all used to be
     * silent — the run reported a Messages backup that backed up nothing.
     */
    private fun backupSms(context: Context, writer: JsonWriter): String? {
        // Read through a root-made snapshot of the SMS store instead of the app's
        // ContentResolver: the provider path needs the default-SMS role, and
        // taking that role is what killed the process at the end of every run.
        val db = SmsViaRoot.openSnapshot(context.filesDir)
        if (db == null) {
            // Writing an empty "sms" array would be indistinguishable from
            // "this device had no messages", so say nothing and let the run
            // report the item as failed instead.
            Log.e("DeviceManager", "Messages backup skipped: SMS store not readable")
            return "SMS store not readable (root snapshot failed)"
        }
        db.use {
            writer.name("sms").beginArray()
            for (row in SmsViaRoot.readAllRows(it)) {
                writer.beginObject()
                for ((name, value) in row) {
                    when (value) {
                        null -> writer.name(name).nullValue()
                        is String -> writer.name(name).value(value)
                        is Long -> writer.name(name).value(value)
                        is Double -> writer.name(name).value(value)
                        else -> writer.name(name).nullValue()
                    }
                }
                writer.endObject()
            }
            writer.endArray()
        }
        return null
    }

    /**
     * Consume a JSON array the callee will not read to its end.
     *
     * The restore helpers are called right after `reader.nextName()`, so the
     * reader is already parked ON the array value. Returning without reading it
     * makes the caller's next `nextName()` throw "Expected a name but was
     * BEGIN_ARRAY", which turns one honest per-item refusal into a whole-run
     * failure — the opposite of what refusing is for. Best effort: the original
     * error is the one worth reporting.
     */
    private fun drainArray(reader: JsonReader) {
        try {
            if (reader.peek() == JsonToken.BEGIN_ARRAY) reader.beginArray()
            while (reader.hasNext()) reader.skipValue()
            if (reader.peek() == JsonToken.END_ARRAY) reader.endArray()
        } catch (_: Exception) {
        }
    }

    /**
     * Content signature of an SMS row for idempotent restore: the same
     * (address, date, body) is the same message no matter which run inserted
     * it. Without this, every restore re-inserted the whole archive — each
     * restore→backup cycle doubled the data (field-verified: 11,341 rows
     * where ~300 belong, restore counter climbing into the thousands).
     */
    private fun smsSignature(address: Any?, date: Any?, body: Any?): String =
        "${address ?: ""}|${date ?: ""}|${body ?: ""}"

    /** Existing-signature set for the SMS store (one read, whole table). */
    private fun existingSmsSignatures(context: Context): MutableSet<String> {
        val db = SmsViaRoot.openSnapshot(context.filesDir)
        if (db == null) {
            Log.w("DeviceManager", "dedup pre-load failed (restoring without dedup): no snapshot")
            return HashSet()
        }
        db.use {
            val sigs = SmsViaRoot.readSignatures(it).toMutableSet()
            // Worth a line of its own: a restore that skips everything it was
            // asked to restore is indistinguishable from one that had nothing
            // to do, unless the size of the base it compared against is known.
            Log.d("DeviceManager", "SMS dedupe base: ${sigs.size} signatures in store")
            return sigs
        }
    }

    /**
     * Returns null when the Messages item completed, otherwise the reason it
     * did not — surfaced to BOTH callers so neither records a refused or
     * partial restore as a success.
     */
    private fun restoreSms(
        context: Context,
        reader: JsonReader,
        onProgress: (String) -> Unit
    ): String? {
        Log.d("DeviceManager", "Starting SMS restoration...")
        // Column set comes from the snapshot: the archive may have been written
        // on a ROM whose sms table had columns this one lacks, and passing those
        // to the provider is what the insert rejects.
        val snapshot = SmsViaRoot.openSnapshot(context.filesDir)
        if (snapshot == null) {
            // Refuse, do NOT "restore without dedupe". The dedupe set IS the
            // only thing standing between the archive and a duplicated inbox;
            // running blind once turned ~300 real messages into 11,341 rows
            // (field-verified in an earlier session). A skipped item the user
            // can retry is strictly better than a corrupted message store.
            Log.e("DeviceManager", "SMS restore refused: SMS store not readable for dedupe")
            onProgress("Error: cannot read existing Messages, refusing to restore (duplicates).")
            drainArray(reader)
            return "cannot read existing Messages, refusing to restore (duplicates)"
        }
        val validCols = SmsViaRoot.smsColumns(snapshot).toSet()
        // Conversations that still exist right now. An archived thread_id is
        // only restored when its `threads` row survived; deleting the last
        // message of a conversation destroys that row, and binding a stale id
        // would strand the message in a thread the provider does not know.
        // When it is stale we omit the binding and let the provider re-thread
        // by address, which lands the message in the right conversation.
        val liveThreads = SmsViaRoot.readThreadIds(snapshot)
        snapshot.close()
        var count = 0
        var dupes = 0
        var attempted = 0
        var processed = 0
        // Signatures of the rows this run decided to write, so the store can be
        // read back afterwards: the CLI's own success report is exactly the
        // thing that lied about the 18 colon-bearing messages.
        val staged = HashSet<String>()
        val seen = existingSmsSignatures(context)
        try {
            reader.beginArray()
            val batch = mutableListOf<ContentValues>()
            while (reader.hasNext()) {
                val values = ContentValues()
                // Captured out-of-band: thread_id must not go through the
                // generic column filter, because it is bound only when the
                // conversation still exists (see liveThreads above).
                var archivedThread: Long? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    val peek = reader.peek()
                    if (peek == JsonToken.NULL) {
                        reader.nextNull(); continue
                    }
                    if (name == "thread_id") {
                        if (peek == JsonToken.NUMBER) archivedThread = reader.nextLong()
                        else reader.skipValue()
                        continue
                    }
                    if (name == "_id" || name == "deleted" ||
                        (validCols.isNotEmpty() && !validCols.contains(
                            name
                        ))
                    ) {
                        reader.skipValue(); continue
                    }
                    when (peek) {
                        JsonToken.STRING -> values.put(name, reader.nextString())
                        JsonToken.NUMBER -> values.put(name, reader.nextLong())
                        JsonToken.BOOLEAN -> values.put(name, reader.nextBoolean())
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                // Idempotency: skip messages the provider already holds —
                // covers repeated restores AND archives polluted by earlier
                // duplicate-happy runs. Register the signature immediately so
                // intra-archive duplicates collapse too.
            val sig = smsSignature(
                values.get("address"),
                values.get("date"),
                values.get("body")
            )
            processed++
            if (!seen.add(sig)) {
                dupes++
                Log.d("DeviceManager", "SMS dedupe skip: $sig")
                continue
            }
            attempted++
            staged.add(sig)
            if (validCols.contains("sub_id")) {
                values.put("sub_id", -1)
            }
            // Restore the original conversation when it still exists. This used
            // to be dropped unconditionally, so a message deleted out of a
            // multi-message conversation came back in a brand-new thread rather
            // than the one it belonged to.
            if (archivedThread != null && archivedThread in liveThreads) {
                values.put("thread_id", archivedThread)
            }
            // Unconditional: EVERY row is staged, regardless of the sub_id
            // branch above (lint A-22 — the old indentation made this look
            // like it was inside the if, one brace refactor from dropping
            // every SMS row).
            batch.add(values)
            if (batch.size >= 50) {
                count += SmsViaRoot.insertRows(context.filesDir, batch) {}
                onProgress("Restoring Messages ($count)...")
                batch.clear()
            }
            }
            reader.endArray()
            if (batch.isNotEmpty()) count += SmsViaRoot.insertRows(context.filesDir, batch) {}
            // Ask the store, not the CLI. A rejected `--bind` is reported by the
            // CLI as usage text while it still exits 0, so `count` alone claimed
            // success for rows that were never written — the reported number is
            // now the number of staged messages the store actually holds.
            val notStored = countNotStored(context, staged)
            if (notStored >= 0) {
                if (notStored != attempted - count) {
                    Log.w(
                        "DeviceManager",
                        "SMS restoration: CLI reported ${attempted - count} not written, store says $notStored"
                    )
                }
                count = attempted - notStored
            }
            val failed = attempted - count
            Log.d(
                "DeviceManager",
                "SMS dedupe result: $processed archive rows, $dupes duplicates, $attempted staged"
            )
            Log.d("DeviceManager", "SMS restoration completed. Restored $count SMS ($dupes duplicates skipped).")
            onProgress(
                when {
                    failed > 0 -> "Restored $count Messages ($dupes duplicates skipped, $failed failed)."
                    dupes > 0 -> "Restored $count Messages ($dupes duplicates skipped)."
                    else -> "Restored $count Messages."
                }
            )
            // Partial is still a failure of this item: the store does not hold
            // every message the archive asked for, and both tabs must say so.
            return if (failed > 0) "$failed message(s) could not be written" else null
        } catch (e: Exception) {
            Log.e("DeviceManager", "Failed to restore SMS", e)
            onProgress("Error: ${e.message}")
            drainArray(reader)
            return "SMS restore failed: ${e.message}"
        }
    }

    /**
     * How many staged messages the provider does not hold right now, or -1 when
     * that cannot be determined.
     *
     * Retries briefly: a row inserted through the provider is not always visible
     * in a snapshot taken the same instant, and a false "failed" is its own bug.
     * Treating "could not read the store" as -1 keeps the caller from inventing
     * failures out of a failed snapshot.
     */
    private fun countNotStored(context: Context, staged: Set<String>): Int {
        if (staged.isEmpty()) return 0
        repeat(VERIFY_ATTEMPTS) { attempt ->
            val db = SmsViaRoot.openSnapshot(context.filesDir)
            if (db != null) {
                val missing = db.use { snap ->
                    val present = SmsViaRoot.readSignatures(snap)
                    staged.count { it !in present }
                }
                if (missing == 0 || attempt == VERIFY_ATTEMPTS - 1) return missing
            }
            try {
                Thread.sleep(400)
            } catch (_: InterruptedException) {
            }
        }
        return -1
    }

    private fun flushBatch(context: Context, uri: Uri, batch: List<ContentValues>): Int {
        val ops = ArrayList<ContentProviderOperation>()
        for (values in batch) ops.add(
            ContentProviderOperation.newInsert(uri).withValues(values).build()
        )
        return try {
            val results = context.contentResolver.applyBatch(uri.authority!!, ops)
            var successCount = 0; results.forEach { if (it.uri != null) successCount++ }
            if (successCount < batch.size) retryIndividually(
                context,
                uri,
                batch,
                getValidColumns(context, uri)
            ) else successCount
        } catch (_: Exception) {
            retryIndividually(context, uri, batch, getValidColumns(context, uri))
        }
    }

    private fun retryIndividually(
        context: Context,
        uri: Uri,
        batch: List<ContentValues>,
        validColumns: Set<String>
    ): Int {
        var successCount = 0
        val isSms = uri.toString().contains("sms")
        val isCall = uri.toString().contains("call")
        for (values in batch) {
            // Providers THROW (not return null) on rejected rows — the plain
            // insert must be caught separately so the safe-column retry below
            // still runs (previously a throw dropped the row entirely).
            try {
                if (context.contentResolver.insert(uri, values) != null) {
                    successCount++; continue
                }
            } catch (_: Exception) {
            }
            val safeValues = ContentValues()
            if (isSms) {
                listOf(
                    "address",
                    "body",
                    "date",
                    "type",
                    "read",
                    "date_sent",
                    "seen"
                ).forEach {
                    if (values.containsKey(it)) safeValues.put(
                        it,
                        values.getAsString(it)
                    )
                }
                if (validColumns.contains("sub_id")) safeValues.put("sub_id", -1)
            } else if (isCall) {
                listOf(
                    "number",
                    "date",
                    "duration",
                    "type",
                    "new"
                ).forEach {
                    if (values.containsKey(it)) safeValues.put(
                        it,
                        values.getAsString(it)
                    )
                }
            }
            if (safeValues.size() > 0) {
                try {
                    if (context.contentResolver.insert(
                            uri,
                            safeValues
                        ) != null
                    ) successCount++
                } catch (_: Exception) {
                }
            }
        }
        return successCount
    }

    private fun backupMms(context: Context, writer: JsonWriter, partsDir: File) {
        context.contentResolver.query(Telephony.Mms.CONTENT_URI, null, null, null, null)
            ?.use { cursor ->
                writer.name("mms").beginArray()
                while (cursor.moveToNext()) {
                    try {
                        writeMmsObject(context, cursor, writer, partsDir)
                    } catch (e: Exception) {
                        Log.e("DeviceManager", "Failed to backup MMS object", e)
                    }
                }
                try {
                    backupBugleRcs(context, writer, partsDir)
                } catch (e: Exception) {
                    Log.e("DeviceManager", "Failed to backup Bugle RCS", e)
                }
                writer.endArray()
            }
    }

    private fun writeMmsObject(
        context: Context,
        cursor: Cursor,
        writer: JsonWriter,
        partsDir: File
    ) {
        writer.beginObject()
        val mmsId = cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Mms._ID))
        for (i in 0 until cursor.columnCount) {
            val name = cursor.getColumnName(i)
            val type = cursor.getType(i)
            when (type) {
                Cursor.FIELD_TYPE_STRING -> writer.name(name).value(cursor.getString(i))
                Cursor.FIELD_TYPE_INTEGER -> writer.name(name).value(cursor.getLong(i))
                Cursor.FIELD_TYPE_FLOAT -> writer.name(name).value(cursor.getDouble(i))
                Cursor.FIELD_TYPE_NULL -> writer.name(name).nullValue()
                Cursor.FIELD_TYPE_BLOB -> writer.name(name).nullValue()
            }
        }
        writer.name("parts").beginArray()
        context.contentResolver.query(
            "content://mms/part".toUri(),
            null,
            "mid = ?",
            arrayOf(mmsId.toString()),
            null
        )?.use { pCursor ->
            while (pCursor.moveToNext()) {
                writer.beginObject()
                val partId = pCursor.getLong(pCursor.getColumnIndexOrThrow("_id"))
                for (j in 0 until pCursor.columnCount) {
                    val pName = pCursor.getColumnName(j)
                    when (pCursor.getType(j)) {
                        Cursor.FIELD_TYPE_STRING -> writer.name(pName).value(pCursor.getString(j))
                        Cursor.FIELD_TYPE_INTEGER -> writer.name(pName).value(pCursor.getLong(j))
                        Cursor.FIELD_TYPE_FLOAT -> writer.name(pName).value(pCursor.getDouble(j))
                        Cursor.FIELD_TYPE_NULL -> writer.name(pName).nullValue()
                        Cursor.FIELD_TYPE_BLOB -> writer.name(pName).nullValue()
                    }
                }
                val ct = pCursor.getString(pCursor.getColumnIndexOrThrow("ct"))
                if (ct != "text/plain" && !pCursor.isNull(pCursor.getColumnIndexOrThrow("_data"))) {
                    val pfn = "part_$partId"; writer.name("backup_file_name").value(pfn)
                    try {
                        context.contentResolver.openInputStream("content://mms/part/$partId".toUri())
                            ?.use { i ->
                                File(partsDir, pfn).outputStream().use { o -> i.copyTo(o) }
                            }
                    } catch (_: Exception) {
                    }
                }
                writer.endObject()
            }
        }; writer.endArray()
        writer.name("addrs").beginArray()
        context.contentResolver.query(
            "content://mms/$mmsId/addr".toUri(),
            null,
            null,
            null,
            null
        )?.use { aCursor ->
            while (aCursor.moveToNext()) {
                writer.beginObject()
                for (j in 0 until aCursor.columnCount) {
                    val aName = aCursor.getColumnName(j)
                    when (aCursor.getType(j)) {
                        Cursor.FIELD_TYPE_STRING -> writer.name(aName).value(aCursor.getString(j))
                        Cursor.FIELD_TYPE_INTEGER -> writer.name(aName).value(aCursor.getLong(j))
                        Cursor.FIELD_TYPE_FLOAT -> writer.name(aName).value(aCursor.getDouble(j))
                        Cursor.FIELD_TYPE_NULL -> writer.name(aName).nullValue()
                        Cursor.FIELD_TYPE_BLOB -> writer.name(aName).nullValue()
                    }
                }
                writer.endObject()
            }
        }; writer.endArray(); writer.endObject()
    }

    private fun backupBugleRcs(context: Context, writer: JsonWriter, partsDir: File) {
        val msgPkg = "com.google.android.apps.messaging"
        val dbFile = File(context.cacheDir, "bugle_db_temp")
        // ShellQuote.q everywhere: this is the one site that previously
        // hand-rolled quoting (audit finding). msgPkg is a compile-time
        // constant, but q() keeps the whole command on the central path.
        if (!Shell.cmd("cp ${ShellQuote.q("/data/data/$msgPkg/databases/bugle_db")} ${ShellQuote.q(dbFile.absolutePath)}")
                .exec().isSuccess
        ) {
            Log.d("DeviceManager", "RCS backup skipped (bugle_db unreadable)")
            return
        }
        Shell.cmd("chmod 600 ${ShellQuote.q(dbFile.absolutePath)}").exec()
        // Root's `cp` creates this as root:root with a bare app_data_file
        // label — the app's own SQLiteDatabase.openDatabase then fails EACCES.
        reclaimCacheDir(context, "bugle_db_temp")
        val db = try {
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        } catch (e: Exception) {
            Log.w("DeviceManager", "RCS backup skipped (db open failed)")
            try { dbFile.delete() } catch (_: Exception) {}
            return
        }
        val query =
            "SELECT m._id, m.received_timestamp, m.read, m.seen, m.message_status, m.conversation_id, m.self_id, (SELECT normalized_destination FROM participants WHERE _id = m.self_id) as my_number FROM messages m WHERE m.message_protocol = 3 AND m.sms_message_uri IS NULL"
        db.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                val bugleId = cursor.getLong(0)
                val convId = cursor.getLong(5)
                val selfId = cursor.getLong(6)
                val myNumber = cursor.getString(7)
                var remoteNumber: String? = null
                db.rawQuery(
                    "SELECT p.normalized_destination FROM participants p JOIN conversation_to_participants ctp ON p._id = ctp.participant_id WHERE ctp.conversation_id = ? AND p._id != ? LIMIT 1",
                    arrayOf(convId.toString(), selfId.toString())
                ).use { if (it.moveToFirst()) remoteNumber = it.getString(0) }
                if (remoteNumber == null || remoteNumber.contains("@")) continue
                writer.beginObject(); writer.name("ct_t")
                    .value("application/vnd.wap.multipart.related")
                val isOutgoing = cursor.getInt(4) in listOf(2, 4, 100, 1)
                writer.name("m_type").value(if (isOutgoing) 128 else 132); writer.name("msg_box")
                    .value(if (isOutgoing) 2 else 1)
                writer.name("date").value(cursor.getLong(1) / 1000); writer.name("read")
                    .value(cursor.getInt(2)); writer.name("seen").value(cursor.getInt(3))
                writer.name("tr_id").value("rcs_$bugleId"); writer.name("m_cls")
                    .value("personal"); writer.name("v").value(18); writer.name("sub_id").value(-1)
                writer.name("parts").beginArray()
                db.rawQuery(
                    "SELECT text, content_type, local_cache_path FROM parts WHERE message_id = ?",
                    arrayOf(bugleId.toString())
                ).use { pCursor ->
                    while (pCursor.moveToNext()) {
                        val text = pCursor.getString(0)
                        val ct = pCursor.getString(1)
                        val cachePath = pCursor.getString(2)
                        writer.beginObject(); writer.name("ct").value(ct)
                        if (text != null) writer.name("text").value(text)
                        if (cachePath != null && ct != "text/plain") {
                            val pfn =
                                "part_rcs_${bugleId}_${pCursor.position}"; writer.name("backup_file_name")
                                .value(pfn)
                            Shell.cmd(
                                "cp ${ShellQuote.q(cachePath)} ${ShellQuote.q(File(partsDir, pfn).absolutePath)}" +
                                    " && chmod 600 ${ShellQuote.q(File(partsDir, pfn).absolutePath)}"
                            ).exec()
                            reclaimCacheDir(context, "mms_parts")
                        }
                        writer.endObject()
                    }
                }; writer.endArray()
                writer.name("addrs").beginArray(); writer.beginObject(); writer.name("address")
                    .value(remoteNumber); writer.name("type")
                    .value(if (isOutgoing) 151 else 137); writer.endObject(); writer.endArray()
                if (myNumber != null) writer.name("threading_self_number").value(myNumber)
                writer.endObject()
            }
        }; db.close(); dbFile.delete()
    }

    /**
     * Gate a `content insert --bind <key>:` key before it reaches a root shell.
     *
     * The *values* in [forceInsertPart] / [forceInsertHeader] are escaped with
     * the `'\''` idiom and cannot break out, but the KEYS were interpolated
     * raw. Keys arrive from JSON object names inside a `Messages.shift`
     * archive, so a crafted vault put e.g. `x; touch /data/local/tmp/pwn; echo y`
     * straight into a root command. Content-provider column names are always
     * `[a-z_][a-z0-9_]*`, so an allowlist regex loses nothing real.
     */
    private fun isSafeBindKey(key: String): Boolean = BIND_KEY.matches(key)

    private fun forceInsertPart(mmsId: Long, values: ContentValues): Uri? {
        val cmd = StringBuilder("content insert --uri content://mms/$mmsId/part")
        if (!values.containsKey("mid")) values.put("mid", mmsId)
        for (key in values.keySet()) {
            val v = values.get(key) ?: continue
            if (key in listOf("_id", "sub_id", "_data")) continue
            if (!isSafeBindKey(key)) continue
            cmd.append(" --bind ").append(key).append(":")
            when (v) {
                is String -> cmd.append("s:'").append(v.replace("'", "'\\''")).append("'")
                is Int -> cmd.append("i:").append(v)
                is Long -> cmd.append("l:").append(v)
                is Float -> cmd.append("f:").append(v)
                is Double -> cmd.append("d:").append(v)
                is Boolean -> cmd.append("b:").append(if (v) "1" else "0")
            }
        }
        val res = Shell.cmd(cmd.toString()).exec()
        if (res.isSuccess) {
            val out = res.out.joinToString(" ")
            val m = Regex("content://\\S+").find(out); if (m != null) return m.value.toUri()
        }
        return null
    }

    private fun getActiveSubId(context: Context): Int {
        return try {
            context.contentResolver.query(
                "content://telephony/siminfo".toUri(),
                arrayOf("_id"),
                null,
                null,
                null
            )?.use { if (it.moveToFirst()) it.getInt(0) else -1 } ?: -1
        } catch (_: Exception) {
            -1
        }
    }

    @SuppressLint("HardwareIds")
    private fun getSelfNumbers(context: Context): Set<String> {
        val nums = mutableSetOf<String>()
        try {
            val sm =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? android.telephony.SubscriptionManager
            @Suppress("MissingPermission")
            sm?.activeSubscriptionInfoList?.forEach { sub ->
                @Suppress("DEPRECATION")
                sub.number?.let { if (it.isNotBlank()) nums.add(it) }
            }
        } catch (_: SecurityException) {
            Log.w("DeviceManager", "Missing READ_PHONE_STATE for SubscriptionManager")
        } catch (_: Exception) {
        }
        try {
            val tm =
                context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
            @Suppress("DEPRECATION")
            tm?.line1Number?.let { if (it.isNotBlank()) nums.add(it) }
        } catch (_: SecurityException) {
            Log.w("DeviceManager", "Missing READ_PHONE_STATE for TelephonyManager")
        } catch (_: Exception) {
        }
        try {
            context.contentResolver.query(
                "content://telephony/siminfo".toUri(),
                arrayOf("number"),
                null,
                null,
                null
            )?.use {
                while (it.moveToNext()) it.getString(0)
                    ?.let { n -> if (n.isNotBlank()) nums.add(n) }
            }
        } catch (_: Exception) {
        }
        val processed = nums.map { it.replace(Regex("[^0-9]"), "") }.filter { it.length >= 7 }
            .map { it.takeLast(10) }.toMutableSet()
        // PII: never log phone numbers, only the count.
        Log.d("DeviceManager", "Identified ${processed.size} self-number suffixes")
        return processed
    }

    private fun isSelfNumber(addr: String?, selfSuffixes: Set<String>): Boolean {
        if (addr.isNullOrBlank() || addr.contains("@")) return false
        val clean = addr.replace(Regex("[^0-9]"), "")
        if (clean.length < 7) return false
        return selfSuffixes.any { clean.endsWith(it) }
    }

    private fun getOrCreateThreadId(context: Context, addresses: List<String>): Long {
        if (addresses.isEmpty()) return -1
        val key = addresses.toSet(); threadIdCache[key]?.let { return it }
        return try {
            val tid = Telephony.Threads.getOrCreateThreadId(context, key); threadIdCache[key] =
                tid; tid
        } catch (e: Exception) {
            if (e.message?.contains("no such table", true) == true) ensureTelephonyDirs(context)
            try {
                val tid = Telephony.Threads.getOrCreateThreadId(context, key); threadIdCache[key] =
                    tid; tid
            } catch (_: Exception) {
                -1
            }
        }
    }

    private fun forceInsertHeader(uri: Uri, values: ContentValues): Uri? {
        val cmd = StringBuilder("content insert --uri $uri")
        for (key in values.keySet()) {
            val v = values.get(key) ?: continue
            if (key in listOf("_id", "thread_id", "creator")) continue
            if (!isSafeBindKey(key)) continue
            cmd.append(" --bind ").append(key).append(":")
            when (v) {
                is String -> cmd.append("s:'").append(v.replace("'", "'\\''")).append("'")
                is Int -> cmd.append("i:").append(v)
                is Long -> cmd.append("l:").append(v)
                is Float -> cmd.append("f:").append(v)
                is Double -> cmd.append("d:").append(v)
                is Boolean -> cmd.append("b:").append(if (v) "1" else "0")
            }
        }
        val res = Shell.cmd(cmd.toString()).exec()
        if (res.isSuccess) {
            val out = res.out.joinToString(" ")
            val m = Regex("content://\\S+").find(out); if (m != null) return m.value.toUri()
        }
        return null
    }

    /** Existing MMS signatures: (date, msg_box) — cheap idempotency key. */
    private fun existingMmsSignatures(context: Context): MutableSet<String> {
        val seen = HashSet<String>()
        try {
            context.contentResolver.query(
                "content://mms".toUri(),
                arrayOf("date", "msg_box"), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    seen.add("${c.getLong(0)}|${c.getInt(1)}")
                }
            }
        } catch (e: Exception) {
            Log.w("DeviceManager", "MMS dedup pre-load failed: ${e.message}")
        }
        return seen
    }

    private fun restoreMms(
        context: Context,
        reader: JsonReader,
        partsDir: File,
        onProgress: (String) -> Unit
    ) {
        Log.d("DeviceManager", "Starting MMS restoration...")
        val selfSuffixes = getSelfNumbers(context).toMutableSet()
        val activeSubId = getActiveSubId(context)
        var count = 0
        var dupes = 0
        // Idempotent restore: (date, msg_box) already present → this message
        // was restored by an earlier run; skip instead of duplicating.
        val existingMms = existingMmsSignatures(context)
        reader.beginArray()
        while (reader.hasNext()) {
            val mmsValues = ContentValues()
            val parts = mutableListOf<ContentValues>()
            val addrs = mutableListOf<ContentValues>()
            val partFiles = mutableMapOf<Int, File>()
            reader.beginObject()
            while (reader.hasNext()) {
                when (val name = reader.nextName()) {
                    "parts" -> {
                        reader.beginArray()
                        var pIdx = 0
                        while (reader.hasNext()) {
                            val pValues = ContentValues(); reader.beginObject()
                            while (reader.hasNext()) {
                                val pName = reader.nextName()
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull(); continue
                                }
                                if (pName == "_id" || pName == "backup_file_name") {
                                    val valStr =
                                        if (reader.peek() == JsonToken.NUMBER) reader.nextLong()
                                            .toString() else reader.nextString()
                                    val fName = if (pName == "_id") "part_$valStr" else valStr
                                    val f = File(partsDir, fName)
                                    if (f.exists()) partFiles[pIdx] = f; continue
                                }
                                when (reader.peek()) {
                                    JsonToken.STRING -> pValues.put(pName, reader.nextString())
                                    JsonToken.NUMBER -> pValues.put(pName, reader.nextLong())
                                    JsonToken.BOOLEAN -> pValues.put(pName, reader.nextBoolean())
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject(); parts.add(pValues); pIdx++
                        }
                        reader.endArray()
                    }

                    "addrs" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val aValues = ContentValues(); reader.beginObject()
                            while (reader.hasNext()) {
                                val aName = reader.nextName()
                                if (reader.peek() == JsonToken.NULL) {
                                    reader.nextNull(); continue
                                }
                                if (aName == "_id" || aName == "msg_id") {
                                    reader.skipValue(); continue
                                }
                                when (reader.peek()) {
                                    JsonToken.STRING -> aValues.put(aName, reader.nextString())
                                    JsonToken.NUMBER -> aValues.put(aName, reader.nextLong())
                                    JsonToken.BOOLEAN -> aValues.put(aName, reader.nextBoolean())
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject(); addrs.add(aValues)
                        }
                        reader.endArray()
                    }

                    "_id", "thread_id", "threading_self_number" -> reader.skipValue()
                    else -> {
                        if (reader.peek() == JsonToken.NULL) reader.nextNull()
                        else when (reader.peek()) {
                            JsonToken.STRING -> mmsValues.put(name, reader.nextString())
                            JsonToken.NUMBER -> mmsValues.put(name, reader.nextLong())
                            JsonToken.BOOLEAN -> mmsValues.put(name, reader.nextBoolean())
                            else -> reader.skipValue()
                        }
                    }
                }
            }
            reader.endObject()
            val keysToClear = listOf(
                "_id",
                "thread_id",
                "creator",
                "tr_id",
                "m_id",
                "ct_cls",
                "rpt_a",
                "resp_st",
                "retr_st",
                "st",
                "threading_self_number",
                "m_size",
                "text_only",
                "locked",
                "msg_id",
                "deleted"
            )
            keysToClear.forEach { mmsValues.remove(it) }
            // Do NOT force read/seen to 1. This hardcode made every restored MMS
            // come back already read, so an unread message was never restored as
            // unread — the exact symptom users reported. The archived values are
            // already in mmsValues (neither key is in keysToClear); re-assert
            // them explicitly so the intent survives future edits to that list,
            // and only fall back when the archive genuinely lacked the field.
            val mmsRead = mmsValues.getAsInteger("read") ?: 1
            val mmsSeen = mmsValues.getAsInteger("seen") ?: mmsRead
            mmsValues.put("read", mmsRead)
            mmsValues.put("seen", mmsSeen)
            if (mmsValues.containsKey("sub_id")) mmsValues.put("sub_id", activeSubId)

            val msgBox = mmsValues.getAsInteger("msg_box") ?: 1

            addrs.forEach { a ->
                val addr = a.getAsString("address")
                val type = a.getAsInteger("type")
                if (addr != null && ((msgBox == 2 && type == 137) || (msgBox == 1 && type == 151))) {
                    val clean = addr.replace(Regex("[^0-9]"), "")
                    if (clean.length >= 7) selfSuffixes.add(clean.takeLast(10))
                }
            }

            val addrStrings = addrs.mapNotNull { it.getAsString("address") }
                .filter { it.isNotBlank() && !it.contains("@") && !isSelfNumber(it, selfSuffixes) }

            if (addrStrings.isNotEmpty()) {
                val tid = getOrCreateThreadId(context, addrStrings)
                if (tid != -1L) mmsValues.put("thread_id", tid)
            }

            val mmsSig = "${mmsValues.getAsLong("date")}|$msgBox"
            if (!existingMms.add(mmsSig)) {
                dupes++
                continue
            }
            // `.toUri()` used to bind to the `else` branch ONLY, so this inferred as
            // Any and every INBOX MMS hit the cast below, throwing ClassCastException.
            // Both throws were caught, so the forceInsertHeader root fallback never
            // ran and the whole MMS was skipped — while the restore still reported
            // success. Inbox is every RECEIVED message, so this silently lost all
            // incoming MMS. Hoist the conversion out of the conditional and type it.
            val targetUri: Uri =
                (if (msgBox == 1) "content://mms/inbox" else "content://mms/sent").toUri()
            try {
                var mmsUri = try {
                    context.contentResolver.insert(targetUri, mmsValues)
                } catch (e: Exception) {
                    Log.e("DeviceManager", "Standard insert failed for MMS", e); null
                }
                if (mmsUri == null) {
                    Log.d("DeviceManager", "Attempting force insert for MMS header")
                    mmsUri = forceInsertHeader(targetUri, mmsValues)
                }
                if (mmsUri == null) {
                    Log.e("DeviceManager", "Failed to insert MMS header")
                    continue
                }
                val mmsId = mmsUri.lastPathSegment?.toLongOrNull() ?: continue
                count++; onProgress("Restoring Messages ($count)...")
                Log.d(
                    "DeviceManager",
                    "Restored MMS header ID: $mmsId, now restoring ${parts.size} parts and ${addrs.size} addresses"
                )

                parts.forEachIndexed { index, pValues ->
                    listOf("_id", "sub_id", "_data").forEach { pValues.remove(it) }
                    if (!pValues.containsKey("seq") || (pValues.get("seq") as? Int
                            ?: 0) < 0
                    ) pValues.put("seq", index)
                    if (pValues.get("ct") == "text/plain" && !pValues.containsKey("chset")) pValues.put(
                        "chset",
                        106
                    )
                    var pUri = try {
                        context.contentResolver.insert(
                            "content://mms/$mmsId/part".toUri(),
                            pValues
                        )
                    } catch (e: Exception) {
                        Log.e("DeviceManager", "Standard insert failed for part $index", e); null
                    }
                    if (pUri == null) {
                        Log.d("DeviceManager", "Attempting force insert for part $index")
                        pUri = forceInsertPart(mmsId, pValues)
                    }
                    if (pUri != null) {
                        partFiles[index]?.let { file ->
                            try {
                                context.contentResolver.openOutputStream(pUri)
                                    ?.use { o -> file.inputStream().use { i -> i.copyTo(o) } }
                                Log.d(
                                    "DeviceManager",
                                    "Restored attachment for part $index from ${file.name}"
                                )
                            } catch (e: Exception) {
                                Log.e(
                                    "DeviceManager",
                                    "Failed to restore attachment for part $index via ContentResolver",
                                    e
                                )
                            }
                        }
                    } else {
                        Log.e("DeviceManager", "Failed to insert part $index")
                    }
                }
                addrs.forEach { aValues ->
                    aValues.remove("_id"); aValues.remove("msg_id")
                    if (!aValues.containsKey("charset")) aValues.put("charset", 106)
                    try {
                        context.contentResolver.insert(
                            "content://mms/$mmsId/addr".toUri(),
                            aValues
                        )
                    } catch (e: Exception) {
                        Log.e("DeviceManager", "Failed to insert address", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("DeviceManager", "Critical failure restoring MMS object", e)
            }
        }
        reader.endArray()
        Log.d("DeviceManager", "MMS restoration completed. Restored $count MMS ($dupes duplicates skipped).")
        onProgress(
            if (dupes > 0) "Restored $count Messages ($dupes duplicates skipped)."
            else "Restored $count Messages."
        )
    }

    private fun backupCallLogs(context: Context, jsonFile: File) {
        val cursor =
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, null)
                ?: return
        jsonFile.outputStream().use { fos ->
            val writer =
                JsonWriter(OutputStreamWriter(fos, "UTF-8")).apply { setIndent("  "); beginArray() }
            while (cursor.moveToNext()) {
                writer.beginObject()
                for (i in 0 until cursor.columnCount) {
                    val name = cursor.getColumnName(i)
                    when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_STRING -> writer.name(name).value(cursor.getString(i))
                        Cursor.FIELD_TYPE_INTEGER -> writer.name(name).value(cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> writer.name(name).value(cursor.getDouble(i))
                        Cursor.FIELD_TYPE_NULL -> writer.name(name).nullValue()
                        Cursor.FIELD_TYPE_BLOB -> writer.name(name).nullValue()
                    }
                }
                writer.endObject()
            }
            writer.endArray(); writer.close()
        }
        cursor.close()
    }

    /** Existing call-log signatures: (number, date, duration, type). */
    private fun existingCallSignatures(context: Context): MutableSet<String> {
        val seen = HashSet<String>()
        try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    seen.add(callSignature(
                        if (c.isNull(0)) null else c.getString(0),
                        if (c.isNull(1)) null else c.getLong(1),
                        if (c.isNull(2)) null else c.getLong(2),
                        if (c.isNull(3)) null else c.getInt(3)
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w("DeviceManager", "call-log dedup pre-load failed: ${e.message}")
        }
        return seen
    }

    private fun callSignature(
        number: Any?,
        date: Any?,
        duration: Any?,
        type: Any?
    ): String = "$number|$date|$duration|$type"

    private fun restoreCallLogs(context: Context, jsonFile: File, onProgress: (String) -> Unit) {
        if (!jsonFile.exists()) return
        val validCols = getValidColumns(context, CallLog.Calls.CONTENT_URI)
        var count = 0
        var dupes = 0
        // Idempotent restore: same (number, date, duration, type) is the same
        // call. Blind re-inserts made every restore→backup cycle multiply the
        // call log (field-verified: archive grew 6.5 KB → 59 KB in a day).
        val seen = existingCallSignatures(context)
        try {
            jsonFile.inputStream().use { fis ->
                val reader = JsonReader(InputStreamReader(fis, "UTF-8")).apply { beginArray() }
                val batch = mutableListOf<ContentValues>()
                while (reader.hasNext()) {
                    val values = ContentValues(); reader.beginObject()
                    while (reader.hasNext()) {
                        val name = reader.nextName()
                        val peek = reader.peek()
                        if (peek == JsonToken.NULL) {
                            reader.nextNull(); continue
                        }
                        if (name == "_id" || name == "deleted" || (validCols.isNotEmpty() && !validCols.contains(name))) {
                            reader.skipValue(); continue
                        }
                        when (peek) {
                            JsonToken.STRING -> values.put(name, reader.nextString())
                            JsonToken.NUMBER -> values.put(name, reader.nextLong())
                            JsonToken.BOOLEAN -> values.put(name, reader.nextBoolean())
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    val sig = callSignature(
                        values.get(CallLog.Calls.NUMBER),
                        values.get(CallLog.Calls.DATE),
                        values.get(CallLog.Calls.DURATION),
                        values.get(CallLog.Calls.TYPE)
                    )
                    if (!seen.add(sig)) {
                        dupes++
                        continue
                    }
                    batch.add(values)
                    if (batch.size >= 50) {
                        count += flushBatch(
                            context,
                            CallLog.Calls.CONTENT_URI,
                            batch
                        ); onProgress("Restoring Calls ($count)..."); batch.clear()
                    }
                }
                reader.endArray()
                if (batch.isNotEmpty()) count += flushBatch(
                    context,
                    CallLog.Calls.CONTENT_URI,
                    batch
                )
            }
            onProgress(
                if (dupes > 0) "Restored $count Calls ($dupes duplicates skipped)."
                else "Restored $count Calls."
            )
        } catch (e: Exception) {
            onProgress("Error: ${e.message}")
        }
    }

    @Suppress("SameParameterValue")
    private fun triggerRescan(packageName: String = "com.google.android.apps.messaging") {
        ShellQuote.requirePackage(packageName)
        Shell.cmd("am force-stop $packageName && am force-stop com.google.android.gms").exec()
    }

    suspend fun runOperation(
        context: Context,
        isBackup: Boolean,
        doSms: Boolean,
        doCall: Boolean,
        doContacts: Boolean,
        doWifi: Boolean,
        doWallpaper: Boolean,
        doBluetooth: Boolean,
        savedPath: String,
        doFolders: Boolean = false,
        folderPaths: List<String> = emptyList(),
        /**
         * Per-item failure signal, shared by BOTH callers — the Migrate tab
         * and the automation runner. An item that refused (Messages cannot be
         * read for dedupe), could not be written, or produced no archive must
         * not be recorded as a success on either side; the pipeline is the
         * same for both, so the reporting has to be too. Default null keeps
         * the call sites that do not care unchanged.
         */
        onItemFailure: ((itemLabel: String, reason: String) -> Unit)? = null,
        /**
         * Per-item hook for live UIs: canonical item key (Folders, Calls, …),
         * an optional sub-item in flight (the SPECIFIC folder while the
         * Folders item runs), its own 0..100 fill, and the raw step.
         */
        onItem: ((itemLabel: String, subLabel: String?, itemProgress: Int, step: String) -> Unit)? = null,
        /**
         * Poll between items (and around each ContentResolver/shell step):
         * return true to abort. Thrown as [DeviceDataCancelled] so callers can
         * distinguish a user cancel from a failure — automation and manual
         * Migrate runs share this same hook.
         */
        shouldCancel: (() -> Boolean)? = null,
        /**
         * True when the CALLER already holds [RunCoordinator] (the automation
         * worker): this call must not try to take the same non-reentrant gate.
         * Positioned before [updateState] so the trailing-lambda call style
         * used by every caller keeps working.
         */
        gateHeld: Boolean = false,
        updateState: (step: String, progress: Int) -> Unit
    ) {
        if (shouldCancel?.invoke() == true) throw DeviceDataCancelled
        withContext(Dispatchers.IO) {
        DroidVaultLogger.i(
            "DeviceData",
            "${if (isBackup) "BACKUP" else "RESTORE"} run: sms=$doSms call=$doCall contacts=$doContacts " +
                "wifi=$doWifi wallpaper=$doWallpaper bluetooth=$doBluetooth folders=$doFolders(${folderPaths.size})"
        )
        ShellQuote.requireAbsPath(savedPath)
        val pkg = context.packageName
        val backupDir = "$savedPath/Device"
        val cacheDir = context.cacheDir.absolutePath
        val zapdosPath = "/data/adb/DroidVault/zapdos"
        // The app's own uid, used as the `--owner`/`--group` for every root-side
        // extraction into our cache: archives written by an earlier install
        // carry that install's uid, and tar would faithfully recreate files the
        // current app can neither read nor overwrite (the EACCES field bug).
        val appUid = android.os.Process.myUid()
        updateState(
            "Initializing...",
            -1
        ); threadIdCache.clear(); Shell.cmd("mkdir -p ${ShellQuote.q(backupDir)}").exec()
        // Reap staging files a killed run left in the vault.
        //
        // Every archive here is written to `<name>.shift.tmp-<nanos>` and
        // renamed over the live one, so a cancelled or crashed backup leaves
        // that temp file behind — and unlike the export's staging, these live
        // in the VAULT, which nothing ever cleans. They were therefore carried
        // into every subsequent export, growing a little each time, and were
        // never removed by anything.
        reapVaultStaging(backupDir)
        // Deterministic execution order (and the slice/label order that the
        // progress UI mirrors): every other item FIRST, Messages ALWAYS LAST —
        // the same sequence on backup and restore, for manual runs, Migration
        // and scheduled/automation runs alike. Nothing may start Messages
        // early: it is positioned last here and the code below executes in
        // exactly this order.
        val selectedItems = mutableListOf<String>()
        if (doCall) selectedItems.add("Calls")
        if (doContacts) selectedItems.add("Contacts")
        if (doWifi) selectedItems.add("Wi-Fi")
        if (doWallpaper) selectedItems.add("Wallpaper")
        if (doBluetooth) selectedItems.add("Bluetooth")
        if (doFolders) selectedItems.add("Folders")
        if (doSms) selectedItems.add("Messages")
        if (selectedItems.isEmpty()) return@withContext
        var currentItemIndex = 0
        // Sub-item label override: while the Folders item runs, this carries
        // the SPECIFIC folder being processed ("Android/media/com.whatsapp")
        // so the live UI can show it instead of the generic item name —
        // cleared when the item ends.
        var itemLabelOverride: String? = null
        val slice = 100f / selectedItems.size
        fun notify(step: String, internalProgress: Int) {
            // Cancel granularity = every step transition (each item starts
            // with notify(..., 0)), including inside the Folders helpers —
            // they receive this same closure. Finished items stay done.
            if (shouldCancel?.invoke() == true) throw DeviceDataCancelled
            onItem?.invoke(
                selectedItems.getOrElse(currentItemIndex) { "" },
                itemLabelOverride,
                internalProgress.coerceIn(0, 100),
                step
            )
            updateState(
                step,
                ((currentItemIndex * slice) + (internalProgress * slice / 100f)).toInt()
                    .coerceIn(0, 99)
            )
        }

        val perms = mutableListOf<String>()
        if (doSms) perms.addAll(
            listOf(
                "READ_SMS",
                "WRITE_SMS",
                "RECEIVE_SMS",
                "RECEIVE_MMS",
                "READ_PHONE_STATE"
            )
        )
        if (doCall) perms.addAll(listOf("READ_CALL_LOG", "WRITE_CALL_LOG"))
        if (doContacts) perms.addAll(listOf("READ_CONTACTS", "WRITE_CONTACTS"))
        ShellQuote.requirePackage(pkg)
        val allowedPerms = setOf(
            "READ_SMS", "WRITE_SMS", "RECEIVE_SMS", "RECEIVE_MMS", "READ_PHONE_STATE",
            "READ_CALL_LOG", "WRITE_CALL_LOG", "READ_CONTACTS", "WRITE_CONTACTS"
        )
        perms.filter { it in allowedPerms }.forEach { Shell.cmd("pm grant $pkg android.permission.$it").exec() }
        if (doSms) Shell.cmd("appops set $pkg WRITE_SMS allow").exec()
        if (doCall) Shell.cmd("appops set $pkg WRITE_CALL_LOG allow").exec()

        // Process-wide run gate (audit A-05): a device-data run must never
        // overlap a manual or scheduled run — two simultaneous SMS-role
        // handoffs fight over the default app, and content restores duplicate.
        val acquiredHere = if (gateHeld) false else RunCoordinator.tryAcquire("device-data")
        if (!gateHeld && !acquiredHere) {
            val holder = RunCoordinator.currentOwner ?: "another operation"
            android.util.Log.w("DeviceManager", "run refused: gate held by $holder")
            updateState("Another operation is in progress ($holder)", -1)
            // THROWN, not returned: a plain return was read as success by BOTH
            // callers — Migrate showed "Restore Complete!" and automation
            // recorded every selected item as SUCCESS — for a run that did
            // nothing. The app phase already refuses out loud
            // (MigratorManager's onComplete); the device phase now matches it
            // on both tabs.
            throw DeviceDataRefused(
                "Another operation is in progress",
                "$holder is still running. Please wait for it to finish and try again."
            )
        }
        try {
            // Fail-closed engine gate — the SAME one the automation runner
            // (runDeviceDataPhase) and the Migrate app phase (MigratorManager)
            // already apply. Without it a manual device-data run could pack or
            // extract with a stale/missing engine and report success for an
            // archive that contains nothing, while both other paths refused:
            // one pipeline, one truth, both tabs.
            if (!BackendInstaller.isEngineUpToDate(context)) {
                android.util.Log.w("DeviceManager", "Engine stale — attempting repair before device-data run")
                DroidVaultLogger.w("DeviceData", "Engine stale — attempting repair before device-data run")
                val repaired = BackendInstaller.installEngine(context)
                if (!repaired || !BackendInstaller.isEngineUpToDate(context)) {
                    val msg = "Engine update required but could not be applied. " +
                        "Grant root access to Droid Vault and try again."
                    updateState("Operation Failed!", -1)
                    throw DeviceDataRefused("Operation Failed!", msg)
                }
            }
            if (isBackup) {

                if (doCall) {
                    notify("Backing up Calls...", 0)
                    val callsJ = File(context.cacheDir, "calls.json")
                    backupCallLogs(context, callsJ)
                    if (!callsJ.exists()) {
                        // Null cursor (the provider refused the query): pack
                        // NOTHING. An empty tar still yields a valid-looking
                        // archive (zapdos exits 0 on empty input, verified) and
                        // would overwrite a good one — report instead.
                        onItemFailure?.invoke("Calls", "call log could not be read (CallLogs.shift not written)")
                    } else {
                        notify(
                            "Compressing Calls...",
                            80
                        )
                        packArchive(cacheDir, zapdosPath, "calls.json", "$backupDir/CallLogs.shift", "Calls", onItemFailure)
                    }
                    callsJ.delete(); currentItemIndex++
                }
                if (doContacts) {
                    notify("Backing up Contacts...", 0)
                    val vcf = File(context.cacheDir, "Contacts.vcf")
                    context.contentResolver.query(
                        ContactsContract.Contacts.CONTENT_URI,
                        null,
                        null,
                        null,
                        null
                    )?.use {
                        if (it.count > 0) {
                            val keys = StringBuilder(); while (it.moveToNext()) keys.append(
                                it.getString(
                                    it.getColumnIndexOrThrow(
                                        ContactsContract.Contacts.LOOKUP_KEY
                                    )
                                )
                            ).append(":"); context.contentResolver.openInputStream(
                                Uri.withAppendedPath(
                                    ContactsContract.Contacts.CONTENT_MULTI_VCARD_URI,
                                    Uri.encode(keys.toString())
                                )
                            )?.use { i -> vcf.outputStream().use { o -> i.copyTo(o) } }
                        }
                    }
                    if (vcf.exists()) {
                        notify(
                            "Compressing Contacts...",
                            80
                        )
                        packArchive(cacheDir, zapdosPath, "Contacts.vcf", "$backupDir/Contacts.shift", "Contacts", onItemFailure)
                        vcf.delete()
                    }
                    currentItemIndex++
                }
                if (doWifi) {
                    notify(
                        "Backing up Wi-Fi...",
                        0
                    ); Shell.cmd("mkdir -p ${ShellQuote.q("$cacheDir/Wi-Fi")} && sh /data/adb/DroidVault/DroidVault.sh --backup-wifi ${ShellQuote.q("$cacheDir/Wi-Fi")}")
                        .exec()
                    // Pack only if something was actually collected. `mkdir -p`
                    // succeeds even when the engine then fails or finds no Wi-Fi
                    // source on this ROM, and tar packs an EMPTY DIRECTORY just
                    // fine — the run would report success and replace a good
                    // archive with one holding nothing (invariant 23). Calls and
                    // Contacts guard this the same way; verified on device that
                    // zapdos exits 0 on empty input and `[ -s ]` passes on the
                    // 22-byte result.
                    if (hasContent(File(context.cacheDir, "Wi-Fi"))) {
                        notify(
                            "Compressing Wi-Fi...",
                            80
                        )
                        packArchive(cacheDir, zapdosPath, "Wi-Fi", "$backupDir/Wi-Fi.shift", "Wi-Fi", onItemFailure)
                    } else {
                        onItemFailure?.invoke(
                            "Wi-Fi",
                            "no saved networks were read (Wi-Fi.shift not written)"
                        )
                    }
                    rmRfCacheSubdir(cacheDir, "Wi-Fi"); currentItemIndex++
                }
                if (doWallpaper) {
                    notify(
                        "Backing up Wallpaper...",
                        0
                    ); Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --backup-wallpaper ${ShellQuote.q("$cacheDir/Wallpaper")}")
                        .exec()
                    // Same rule as Wi-Fi: an empty Wallpaper dir packs cleanly and
                    // would overwrite the only copy of the user's wallpaper with
                    // an archive that holds nothing.
                    if (hasContent(File(context.cacheDir, "Wallpaper"))) {
                        notify(
                            "Compressing Wallpaper...",
                            80
                        )
                        packArchive(cacheDir, zapdosPath, "Wallpaper", "$backupDir/Wallpaper.shift", "Wallpaper", onItemFailure)
                    } else {
                        onItemFailure?.invoke(
                            "Wallpaper",
                            "no wallpaper was read (Wallpaper.shift not written)"
                        )
                    }
                    rmRfCacheSubdir(cacheDir, "Wallpaper"); currentItemIndex++
                }
                if (doBluetooth) {
                    notify(
                        "Backing up Bluetooth...",
                        0
                    ); Shell.cmd("mkdir -p ${ShellQuote.q("$cacheDir/Bluetooth")} && sh /data/adb/DroidVault/DroidVault.sh --backup-bt ${ShellQuote.q("$cacheDir/Bluetooth")}")
                        .exec()
                    // Same rule. Losing bt_config.conf means losing every paired
                    // device, and it is the only copy of the pairing keys.
                    if (hasContent(File(context.cacheDir, "Bluetooth"))) {
                        notify(
                            "Compressing Bluetooth...",
                            80
                        )
                        packArchive(cacheDir, zapdosPath, "Bluetooth", "$backupDir/Bluetooth.shift", "Bluetooth", onItemFailure)
                    } else {
                        onItemFailure?.invoke(
                            "Bluetooth",
                            "no paired devices were read (Bluetooth.shift not written)"
                        )
                    }
                    rmRfCacheSubdir(cacheDir, "Bluetooth"); currentItemIndex++
                }
                if (doFolders) {
                    // Per-folder live progress: the hook's item label carries
                    // the actual folder path being packed, so the Processing
                    // screen reads "Android/media/com.whatsapp — Packing… 37%"
                    // instead of a generic "Folders".
                    backupFolders(
                        context,
                        folderPaths,
                        backupDir,
                        zapdosPath,
                        cacheDir,
                        onLabel = { itemLabelOverride = it },
                        onFailure = { reason -> onItemFailure?.invoke("Folders", reason) }
                    ) { step, prog ->
                        notify(step, prog)
                    }
                    itemLabelOverride = null
                    currentItemIndex++
                }
                if (doSms) {
                    // Messages deliberately sits LAST in the pipeline (see the
                    // selectedItems order above) — this block only runs after
                    // every other selected item has fully completed. Last is
                    // also where a failure is least disruptive: a Messages
                    // error must not interrupt folders, call logs, contacts,
                    // etc. mid-pack.
                    notify("Backing up Messages...", 0)
                    var smsBackupError: String? = null
                    val msgJ = File(context.cacheDir, "messages.json")
                    val partsD = File(context.cacheDir, "mms_parts")
                    // Clear any root-owned leftovers FIRST. A stale
                    // messages.json from an earlier root extraction (or a
                    // failed run) is unwritable by the app — this is the
                    // exact `EACCES (Permission denied)` in the field report.
                    // Deleting as root and recreating via java.io yields the
                    // correct owner + SELinux categories every time.
                    rmRfCacheSubdir(cacheDir, "messages.json")
                    rmRfCacheSubdir(cacheDir, "mms_parts")
                    partsD.mkdirs()
                    msgJ.outputStream().use { fos ->
                        val writer = JsonWriter(
                            OutputStreamWriter(
                                fos,
                                "UTF-8"
                            )
                        ).apply { setIndent("  "); beginObject() }
                        smsBackupError = backupSms(context, writer)
                        backupMms(context, writer, partsD)
                        writer.endObject(); writer.close()
                    }
                    notify(
                        "Compressing Messages...",
                        80
                    )
                    packArchive(
                        cacheDir, zapdosPath,
                        "--owner=0 --group=0 messages.json mms_parts",
                        "$backupDir/Messages.shift", "Messages", onItemFailure
                    )
                    msgJ.delete(); partsD.deleteRecursively(); currentItemIndex++
                    smsBackupError?.let { onItemFailure?.invoke("Messages", it) }
                }
            } else {

                if (doCall) {
                    notify(
                        "Extracting Calls...",
                        0
                    )
                    // Drop leftovers from a run that died mid-extract: a stale
                    // file would satisfy the exists() check below AND be
                    // restored as if it came from THIS archive.
                    rmRfCacheSubdir(cacheDir, "calls.json"); Shell.cmd("${ShellQuote.q(zapdosPath)} -d -q -c ${ShellQuote.q("$backupDir/CallLogs.shift")} | tar -xf - -C ${ShellQuote.q(cacheDir)} --owner=$appUid --group=$appUid 2>/dev/null")
                        .exec()
                    reclaimCacheDir(context, "calls.json")
                    val callsJ = File(context.cacheDir, "calls.json")
                    if (callsJ.exists()) {
                        restoreCallLogs(context, callsJ) { msg -> notify(msg, 50) }
                    } else {
                        // Nothing extracted (archive missing or unreadable):
                        // report it — otherwise this run reports a clean
                        // success for an item that never ran, on BOTH tabs.
                        onItemFailure?.invoke(
                            "Calls",
                            AutomationRunner.missingRestoreReason(com.phoenix.droidvault.models.DeviceDataItem.CALLS)
                        )
                    }
                    callsJ.delete(); currentItemIndex++
                }
                if (doContacts) {
                    notify(
                        "Extracting Contacts...",
                        0
                    )
                    rmRfCacheSubdir(cacheDir, "Contacts.vcf"); Shell.cmd("${ShellQuote.q(zapdosPath)} -d -q -c ${ShellQuote.q("$backupDir/Contacts.shift")} | tar -xf - -C ${ShellQuote.q(cacheDir)} --owner=$appUid --group=$appUid 2>/dev/null")
                        .exec()
                    reclaimCacheDir(context, "Contacts.vcf")
                    val vcf = File(context.cacheDir, "Contacts.vcf")
                    if (vcf.exists()) {
                        importVcf(context, vcf) { prog ->
                            notify(
                                "Importing Contacts...",
                                prog
                            )
                        }; vcf.delete()
                    } else {
                        // A Contacts.shift always carries Contacts.vcf (the
                        // backup only packs when it exists), so a missing one
                        // means the extraction produced nothing.
                        onItemFailure?.invoke(
                            "Contacts",
                            AutomationRunner.missingRestoreReason(com.phoenix.droidvault.models.DeviceDataItem.CONTACTS)
                        )
                    }
                    currentItemIndex++
                }
                if (doWifi) {
                    notify(
                        "Extracting Wi-Fi...",
                        0
                    )
                    rmRfCacheSubdir(cacheDir, "Wi-Fi"); Shell.cmd("${ShellQuote.q(zapdosPath)} -d -q -c ${ShellQuote.q("$backupDir/Wi-Fi.shift")} | tar -xf - -C ${ShellQuote.q(cacheDir)} --owner=$appUid --group=$appUid 2>/dev/null")
                        .exec(); reclaimCacheDir(context, "Wi-Fi")
                    if (!File(cacheDir, "Wi-Fi").exists()) {
                        // The shell "succeeds" on an empty dir; only the
                        // extracted output proves the archive was real.
                        onItemFailure?.invoke(
                            "Wi-Fi",
                            AutomationRunner.missingRestoreReason(com.phoenix.droidvault.models.DeviceDataItem.WIFI)
                        )
                    }
                    notify(
                        "Applying Wi-Fi...",
                        50
                    ); Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --restore-wifi ${ShellQuote.q("$cacheDir/Wi-Fi")}")
                        .exec(); rmRfCacheSubdir(cacheDir, "Wi-Fi"); currentItemIndex++
                }
                if (doWallpaper) {
                    notify(
                        "Extracting Wallpaper...",
                        0
                    )
                    rmRfCacheSubdir(cacheDir, "Wallpaper"); Shell.cmd("${ShellQuote.q(zapdosPath)} -d -q -c ${ShellQuote.q("$backupDir/Wallpaper.shift")} | tar -xf - -C ${ShellQuote.q(cacheDir)} --owner=$appUid --group=$appUid 2>/dev/null")
                        .exec()
                    // Wallpaper files are opened through java.io
                    // (WallpaperManager.setStream) — owner + SELinux repair is
                    // what makes them readable at all after a root extraction.
                    reclaimCacheDir(context, "Wallpaper")
                    if (!File(cacheDir, "Wallpaper").exists()) {
                        onItemFailure?.invoke(
                            "Wallpaper",
                            AutomationRunner.missingRestoreReason(com.phoenix.droidvault.models.DeviceDataItem.WALLPAPER)
                        )
                    }
                    notify(
                        "Applying Wallpaper...",
                        50
                    ); Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --restore-wallpaper ${ShellQuote.q("$cacheDir/Wallpaper")}")
                        .exec()
                    try {
                        val wm = WallpaperManager.getInstance(context)
                        val wp = File(cacheDir, "Wallpaper/wallpaper"); if (wp.exists()) {
                            FileInputStream(wp).use {
                                wm.setStream(
                                    it,
                                    null,
                                    true,
                                    WallpaperManager.FLAG_SYSTEM
                                )
                            }
                        }
                        val lk = File(cacheDir, "Wallpaper/wallpaper_lock")
                        val lko = File(cacheDir, "Wallpaper/wallpaper_lock_orig")
                        val fl =
                            if (lk.exists()) lk else if (lko.exists()) lko else null; if (fl != null) {
                            FileInputStream(fl).use {
                                wm.setStream(
                                    it,
                                    null,
                                    true,
                                    WallpaperManager.FLAG_LOCK
                                )
                            }
                        }
                    } catch (_: Exception) {
                    }
                    rmRfCacheSubdir(cacheDir, "Wallpaper"); currentItemIndex++
                }
                if (doBluetooth) {
                    notify(
                        "Extracting Bluetooth...",
                        0
                    )
                    rmRfCacheSubdir(cacheDir, "Bluetooth"); Shell.cmd("${ShellQuote.q(zapdosPath)} -d -q -c ${ShellQuote.q("$backupDir/Bluetooth.shift")} | tar -xf - -C ${ShellQuote.q(cacheDir)} --owner=$appUid --group=$appUid 2>/dev/null").exec(); reclaimCacheDir(context, "Bluetooth")
                    if (!File(cacheDir, "Bluetooth").exists()) {
                        onItemFailure?.invoke(
                            "Bluetooth",
                            AutomationRunner.missingRestoreReason(com.phoenix.droidvault.models.DeviceDataItem.BLUETOOTH)
                        )
                    }
                    notify(
                        "Applying Bluetooth...",
                        50
                    ); Shell.cmd("sh /data/adb/DroidVault/DroidVault.sh --restore-bt ${ShellQuote.q("$cacheDir/Bluetooth")}").exec(); rmRfCacheSubdir(cacheDir, "Bluetooth"); currentItemIndex++
                }
                if (doFolders) {
                    restoreFolders(
                        context,
                        folderPaths,
                        backupDir,
                        zapdosPath,
                        cacheDir,
                        onLabel = { itemLabelOverride = it },
                        onFailure = { reason -> onItemFailure?.invoke("Folders", reason) }
                    ) { step, prog -> notify(step, prog) }
                    itemLabelOverride = null
                    // Mirrors the backup branch: every executed item advances
                    // the counter itself. Without this, Folders+Messages runs
                    // reported Messages' progress against the Folders row.
                    currentItemIndex++
                }
                if (doSms) {
                    if (!Shell.cmd("[ -d /data/user/0/com.android.providers.telephony/databases ]")
                            .exec().isSuccess
                    ) ensureTelephonyDirs(context)
                    notify(
                        "Extracting Messages...",
                        0
                    )
                    rmRfCacheSubdir(cacheDir, "messages.json")
                    rmRfCacheSubdir(cacheDir, "mms_parts"); Shell.cmd("${ShellQuote.q(zapdosPath)} -d -q -c ${ShellQuote.q("$backupDir/Messages.shift")} | tar -xf - -C ${ShellQuote.q(cacheDir)} --owner=$appUid --group=$appUid 2>/dev/null")
                        .exec()
                    // Owner + SELinux repair for archives taken before the
                    // --owner=0 change, and for ROMs where extraction does not
                    // inherit the per-app categories (verified: root-extracted
                    // files land as s0 with NO c-ranges, which the app cannot
                    // read. restorecon alone does NOT fix it; chcon does.)
                    reclaimCacheDir(context, "messages.json", "mms_parts")
                    val msgJ = File(context.cacheDir, "messages.json")
                    var smsRestoreError: String? = null
                    if (msgJ.exists()) {
                        msgJ.inputStream().use { fis ->
                            val reader =
                                JsonReader(InputStreamReader(fis, "UTF-8")).apply { beginObject() }
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "sms" -> smsRestoreError =
                                        restoreSms(context, reader) { msg -> notify(msg, 10) }
                                    "mms" -> restoreMms(
                                        context,
                                        reader,
                                        File(context.cacheDir, "mms_parts")
                                    ) { msg -> notify(msg, 40) }

                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject(); reader.close()
                        }
                    } else {
                        // The archive extracted to NOTHING — the exact "restore
                        // reported success, wrote nothing" case. Report it
                        // through the same signal both tabs read.
                        smsRestoreError =
                            AutomationRunner.missingRestoreReason(com.phoenix.droidvault.models.DeviceDataItem.MESSAGES)
                    }
                    triggerRescan("com.google.android.apps.messaging")
                    msgJ.delete(); File(
                        context.cacheDir,
                        "mms_parts"
                    ).deleteRecursively(); currentItemIndex++
                    smsRestoreError?.let { onItemFailure?.invoke("Messages", it) }
                }
            }
        } finally {
            if (acquiredHere) RunCoordinator.release()
        }
        }
        DroidVaultLogger.i("DeviceData", "Device-data run finished")
        // ROM-survival self-backup — EVERY completed device-data run, backup AND
        // restore, no matter which items were selected (field report: a plain
        // Messages backup never refreshed DroidVault.apk because the only
        // snapshot call lived inside the Folders branch). Placement is safe on
        // both pipelines: BEFORE the caller's SMS role handback (the platform
        // kills this process when the role is released — a snapshot after the
        // release never runs). Hash-gated + timeout: a cheap no-op when nothing
        // changed, and a slow shell must never extend the run's tail.
        try {
            kotlinx.coroutines.withTimeoutOrNull(60000L) {
                AutomationSnapshot.snapshotSelfApk(context, savedPath)
                AutomationSnapshot.snapshotBackend(context, savedPath)
                // automation-state.json IS the primary store — mutations already
                // rewrote it; force a current serialization (cheap no-op when
                // the store rev is unchanged).
                AutomationStore.syncPrimaryStateFile(context)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * User cancelled mid-run (shouldCancel hook in [runOperation]). Separate
     * type from generic failure so callers can report "Cancelled — finished
     * items are kept" instead of an error.
     */
    object DeviceDataCancelled : Exception("cancelled by user")

    /**
     * The run was refused BEFORE any work happened (another run holds the
     * gate, or the engine could not be brought up to date).
     *
     * Thrown rather than returned: returning was read as success by both
     * callers — a Migrate restore printed "Restore Complete!" and automation
     * recorded SUCCESS for items it never touched. [title] mirrors the action
     * title the app phase already uses ("Another operation is in progress" /
     * "Operation Failed!"); the exception message is the detail line under it.
     */
    class DeviceDataRefused(val title: String, detail: String) : Exception(detail)

    /**
     * Delete leftover `<name>.shift.tmp-<nanos>` files in the vault's device
     * folder, and the automation state file's, if any.
     *
     * Only files matching that exact shape, so a real archive or a partial
     * write by a run happening right now is never touched. Named by suffix
     * rather than by a tracked path because the whole problem is that a dead
     * run cannot tell us which paths it owned.
     */
    private fun reapVaultStaging(backupDir: String) {
        for (dir in listOf(backupDir, "$backupDir/..")) {
            Shell.cmd(
                "find ${ShellQuote.q(dir)} -maxdepth 1 -type f -name '*.tmp-*' -delete 2>/dev/null"
            ).exec()
        }
    }

    /**
     * Pack one staged cache entry into its `.shift` archive and report a write
     * failure to BOTH callers through [onItemFailure].
     *
     * The pipeline exits with zapdos's status, so non-zero means nothing was
     * written (ENOSPC, missing binary → 127 — both verified on-device). The
     * inverse is the trap this is NOT for: zapdos exits 0 on EMPTY input and
     * still writes a valid-looking archive, so a missing input is caught on the
     * restore side instead, by checking the extracted output.
     */
    /**
     * Whether a staged device item actually collected something.
     *
     * A directory exists after `mkdir -p` even when the engine that was supposed
     * to fill it failed, and tar packs an empty directory into a valid archive.
     * The run then reports success and has replaced the user's only copy of their
     * networks / wallpaper / paired devices with an archive containing nothing.
     * Calls and Contacts already refuse to pack in that case; this is the same
     * rule for the three items that gathered into a directory instead of a file.
     *
     * A directory that holds only empty subdirectories counts as empty, since
     * those pack to nothing but a header.
     */
    internal fun hasContent(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val entries = dir.listFiles() ?: return false
        if (entries.isEmpty()) return false
        return entries.any { entry ->
            when {
                entry.isFile -> entry.length() > 0
                entry.isDirectory -> hasContent(entry)
                else -> false
            }
        }
    }

    private fun packArchive(
        cacheDir: String,
        zapdosPath: String,
        inputs: String,
        dest: String,
        itemLabel: String,
        onItemFailure: ((itemLabel: String, reason: String) -> Unit)?
    ) {
        // Stage to a temp file and rename over the live archive. Writing zapdos
        // straight at $dest truncates the good copy as it streams, so a tar that
        // dies midway (ENOSPC, a vanished source, a cancelled run) left a TRUNCATED
        // archive sitting where a valid one was — discovered only at restore time.
        // `res.isSuccess` could not catch it either: that is zapdos's exit status,
        // not tar's, and the file's own comment records that zapdos exits 0 on
        // empty input while still writing a valid-looking archive.
        // deleteArchivedFolders and the Rust archive::pack_path already stage+rename.
        val tmp = "$dest.tmp-${System.nanoTime()}"
        val res = Shell.cmd(
            "cd ${ShellQuote.q(cacheDir)} && tar -cf - $inputs 2>/dev/null | " +
                "${ShellQuote.q(zapdosPath)} -1 -f -q -o ${ShellQuote.q(tmp)} && " +
                "[ -s ${ShellQuote.q(tmp)} ] && mv ${ShellQuote.q(tmp)} ${ShellQuote.q(dest)}"
        ).exec()
        if (!res.isSuccess) {
            onItemFailure?.invoke(itemLabel, "failed to write ${dest.substringAfterLast('/')}")
            return
        }
        // Record the digest at the moment the archive is promoted. The engine
        // does this for the archives it writes (its `checksum` module), and a
        // device archive is frequently the only copy of what it holds — a
        // changed or truncated Messages.shift must be visible rather than
        // discovered at restore time. Best-effort: the archive is written, so
        // this cannot fail the part.
        VaultDigest.record(dest)
    }

    /**
     * Backup user-picked folders into one zapdos-compressed tar at
     * `<savedPath>/Device/Folders.shift`.
     *
     * Root shell (su) reads every path — including Android/data and
     * Android/obb under any user's storage,
     * which normal apps cannot list on Android 11+ — so no MANAGE_EXTERNAL
     * permission dance is needed. Each source is archived relative to its
     * parent (basename preserved in the tar), with a manifest inside so the
     * restore can put folders back to their exact original locations.
     */    private fun backupFolders(
        context: Context,
        folderPaths: List<String>,
        backupDir: String,
        zapdosPath: String,

        cacheDir: String,
        /** Sets the live sub-item label override: the SPECIFIC folder being packed. */
        onLabel: (String?) -> Unit = {},
        /** Run-level failure signal for this item (shared by both callers). */
        onFailure: (String) -> Unit = {},
        notify: (String, Int) -> Unit,
    ) {
        val folders = BackupConfigFolders.sanitize(folderPaths)
        if (folders.isEmpty()) {
            notify("No folders selected — skipping Folders", 100)
            return
        }
        val existing = mutableListOf<String>()
        folders.forEachIndexed { i, f ->
            notify("Checking folder ${i + 1}/${folders.size}...", i * 100 / folders.size)
            if (Shell.cmd("[ -d ${ShellQuote.q(f)} ] && echo y").exec().out.firstOrNull()?.trim() == "y") {
                existing.add(f)
            }
        }
        if (existing.isEmpty()) {
            notify("None of the selected folders exist — Folders skipped", 100)
            return
        }
        val staging = "$cacheDir/Folders"
        rmRfCacheSubdir(cacheDir, "Folders")
        // Created app-side (java.io): a root mkdir would leave the dir
        // root-owned and the manifest write below would fail with EACCES
        // (found by the on-device E2E). Root shells can still write into it.
        File(staging).mkdirs()

        // Manifest travels inside the archive: the restore reads it to put
        // each folder back to its exact original absolute path.
        File(staging, BackupConfigFolders.MANIFEST_NAME)
            .writeText(BackupConfigFolders.manifestJson(existing))
        // The app writes 600 by default; the restore extracts this file as
        // root and reads it back as the app — make it world-readable.
        Shell.cmd("chmod 644 ${ShellQuote.q("$staging/${BackupConfigFolders.MANIFEST_NAME}")}").exec()

        // Stage each source under a unique index name (F0, F1, …) so same-
        // named folders (e.g. two "WhatsApp" dirs) can't collide in the tar,
        // then archive everything in one stream. Sourcing through the staging
        // dir keeps the tar free of device-absolute prefixes and lets root
        // read Android/* paths the app's own uid could not traverse.
        notify("Packing ${existing.size} folder${if (existing.size == 1) "" else "s"}...", 0)
        val dst = "$backupDir/${BackupConfigFolders.ARCHIVE_FILE}"
        existing.forEachIndexed { idx, f ->
            // Per-folder live progress: name THIS folder in the label and
            // scale its own stage+tar cost across the whole item (check 10%,
            // staging 70%, tar closing 90%). The caller maps the label onto
            // the hero row, so the Processing screen shows the real path.
            onLabel(BackupConfigFolders.shortDisplayName(f))
            val base = idx * 100 / existing.size
            val span = 100 / existing.size
            notify("Packing ${BackupConfigFolders.shortDisplayName(f)}…", base + (span * 10 / 100))
            // Stage the folder's CONTENTS into F<idx> (F<idx> IS the folder,
            // not its parent) — the restore copies F<idx>/. back to the
            // original path. Hardlink for O(1) staging on the same
            // filesystem; fall back to a real copy when that fails (FUSE
            // views of Android/*).
            Shell.cmd(
                "mkdir -p ${ShellQuote.q("$staging/F$idx")} && cp -al ${ShellQuote.q("$f/.")} ${ShellQuote.q("$staging/F$idx/")} 2>/dev/null || { mkdir -p ${ShellQuote.q("$staging/F$idx")} && cp -a ${ShellQuote.q("$f/.")} ${ShellQuote.q("$staging/F$idx/")}; }"
            ).exec()
            notify("Compressing ${BackupConfigFolders.shortDisplayName(f)}…", base + (span * 90 / 100))
        }
        onLabel(null)
        // The staged copies (F0, F1, …) are what gets archived — never the
        // live originals — so backing up a folder that contains the backup
        // dir cannot recurse, and no device-absolute prefixes leak into the
        // tar. zapdos compresses the stream straight into the archive file.
        // --owner=0 --group=0: neutral ownership so the archive restores onto
        // any install/ROM. Without it `tar` records the staging uid, which a
        // reinstall changes — the same class of bug as the Messages archive.
        val tarArgs = StringBuilder("tar -cf - --owner=0 --group=0 -C ${ShellQuote.q(staging)} ${ShellQuote.q(BackupConfigFolders.MANIFEST_NAME)}")
        existing.forEachIndexed { idx, _ ->
            tarArgs.append(" -C ${ShellQuote.q(staging)} ${ShellQuote.q("F$idx")}")
        }
        // Stage and rename, like `packArchive` and the engine's own writer.
        // zapdos with `-o` straight at the live path truncates the good archive
        // as it streams (-f has discarded the original by then), so a tar that
        // died midway — a vanished source, ENOSPC, a cancelled run — left a
        // TRUNCATED Folders.shift where a valid one used to be, and the check
        // below only asked whether a file was there. This is the same defect
        // S-06 fixed for the app archives.
        val tmp = "$dst.tmp-${System.nanoTime()}"
        val packed = Shell.cmd(
            "$tarArgs | ${ShellQuote.q(zapdosPath)} -1 -f -q -o ${ShellQuote.q(tmp)} && " +
                "[ -s ${ShellQuote.q(tmp)} ] && mv ${ShellQuote.q(tmp)} ${ShellQuote.q(dst)}"
        ).exec()
        rmRfCacheSubdir(cacheDir, "Folders")

        if (packed.isSuccess &&
            Shell.cmd("[ -f ${ShellQuote.q(dst)} ] && echo y").exec().out.firstOrNull()?.trim() == "y"
        ) {
            // Only a promoted archive gets a digest: blessing a truncated one
            // would make it look verified for as long as it existed.
            VaultDigest.record(dst)
            notify(
                "Folders backed up (${existing.size} folder${if (existing.size == 1) "" else "s"}).",
                100
            )
        } else {
            Shell.cmd("rm -f -- ${ShellQuote.q(tmp)}").exec()
            notify("Failed to write Folders archive", 100)
            // "Backup Complete!" for an item that produced no archive is the
            // same silent success the other device items now report.
            onFailure("failed to write ${BackupConfigFolders.ARCHIVE_FILE}")
        }
    }

    /**
     * Restore folders from Folders.shift using the manifest inside the
     * archive. Each folder is extracted to a staging dir and copied back to
     * its recorded absolute path (created if missing, contents merged into
     * the existing dir when present).
     */
    private fun restoreFolders(
        context: Context,
        folderPaths: List<String>,
        backupDir: String,
        zapdosPath: String,
        cacheDir: String,
        /** Sets the live sub-item label override: the SPECIFIC folder being restored. */
        onLabel: (String?) -> Unit = {},
        /** Run-level failure signal for this item (shared by both callers). */
        onFailure: (String) -> Unit = {},
        notify: (String, Int) -> Unit,
    ) {
        val selectedPaths = BackupConfigFolders.sanitize(folderPaths).toSet()
        val src = "$backupDir/${BackupConfigFolders.ARCHIVE_FILE}"
        if (Shell.cmd("[ -f ${ShellQuote.q(src)} ] && echo y").exec().out.firstOrNull()?.trim() != "y") {
            notify("No Folders archive in the backup folder", 100)
            onFailure("no ${BackupConfigFolders.ARCHIVE_FILE} in the backup folder")
            return
        }
        val staging = "$cacheDir/Folders"
        rmRfCacheSubdir(cacheDir, "Folders")
        // App-owned staging (see backupFolders): tar runs as root and can
        // extract into it, while the manifest read below stays app-readable.
        File(staging).mkdirs()
        notify("Extracting Folders...", 0)
        // --owner/--group: an archive written by a PREVIOUS install stores that
        // install's uid; replaying it leaves every staged entry unreadable to
        // the current app (the manifest read below is java.io).
        Shell.cmd("${ShellQuote.q(zapdosPath)} -d -q -c ${ShellQuote.q(src)} | tar -xf - -C ${ShellQuote.q(staging)} --owner=${android.os.Process.myUid()} --group=${android.os.Process.myUid()} 2>/dev/null").exec()
        reclaimCacheDir(context, "Folders")
        Shell.cmd("chmod 644 ${ShellQuote.q("$staging/${BackupConfigFolders.MANIFEST_NAME}")} 2>/dev/null").exec()

        // Prefer the manifest inside the archive (index → original absolute
        // path); fall back to the config's current folder list.
        val manifestFolders = try {
            val mf = File(staging, BackupConfigFolders.MANIFEST_NAME)
            if (mf.exists()) {
                mf.readText().let { BackupConfigFolders.manifestFolders(it) }
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        val staged = mutableListOf<Pair<String, String>>() // (archiveDir, destPath)
        if (manifestFolders.isNotEmpty()) {
            manifestFolders.forEachIndexed { idx, dest ->
                // Caller-provided selection is authoritative when non-empty
                // (Migrate dialog checkbox subset / the config's own list);
                // an empty selection restores everything in the archive.
                if ((folderPaths.isEmpty() || dest in selectedPaths) &&
                    Shell.cmd("[ -d ${ShellQuote.q("$staging/F$idx")} ] && echo y").exec().out.firstOrNull()?.trim() == "y"
                ) {
                    staged.add("F$idx" to dest)
                }
            }
        } else {
            BackupConfigFolders.sanitize(folderPaths).forEachIndexed { idx, dest ->
                if (Shell.cmd("[ -d ${ShellQuote.q("$staging/F$idx")} ] && echo y").exec().out.firstOrNull()?.trim() == "y") {
                    staged.add("F$idx" to dest)
                }
            }
        }
        if (staged.isEmpty()) {
            notify("Folders archive is empty or unreadable", 100)
            onFailure("${BackupConfigFolders.ARCHIVE_FILE} is empty or unreadable")
            rmRfCacheSubdir(cacheDir, "Folders")
            return
        }

        var failed = 0
        staged.forEachIndexed { i, (archiveDir, dest) ->
            // Real folder name in the live label + progress that advances per
            // folder (never stuck at 0, never premature 100).
            onLabel(BackupConfigFolders.shortDisplayName(dest))
            val base = i * 100 / staged.size
            notify("Restoring ${BackupConfigFolders.shortDisplayName(dest)}…", base)
            Shell.cmd("mkdir -p ${ShellQuote.q(dest)}").exec()
            val res = Shell.cmd(
                "cp -a ${ShellQuote.q("$staging/$archiveDir/.")} ${ShellQuote.q("$dest/")}"
            ).exec()
            if (!res.isSuccess) failed++
            notify("Restored ${BackupConfigFolders.shortDisplayName(dest)}…", base + (100 / staged.size).coerceAtLeast(1))
        }
        onLabel(null)
        rmRfCacheSubdir(cacheDir, "Folders")
        notify(
            if (failed == 0) "Folders restored (${staged.size} folder${if (staged.size == 1) "" else "s"})."
            else "Folders restored with $failed failure${if (failed == 1) "" else "s"}.",
            100
        )
        if (failed > 0) onFailure("$failed of ${staged.size} folders could not be restored")
    }
}
