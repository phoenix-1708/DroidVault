package com.phoenix.droidvault.utils

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.util.Log
import com.topjohnwu.superuser.Shell
import java.io.File

/**
 * Root-mediated access to the system SMS store.
 *
 * The app used to READ and WRITE messages through its own ContentResolver, which
 * only works while Droid Vault holds `android.app.role.SMS`. Handing the role
 * back revokes the platform-granted `READ_SMS`/`WRITE_SMS` app-ops, and the
 * platform kills the process as it does — which is why every Messages restore
 * ended with the app disappearing ("killed after restoring").
 *
 * Root removes the need for the role entirely, so this object never touches
 * ContentResolver and never asks for the role:
 *
 *  - READ: root copies the live `mmssms.db` into app-private storage, chowned to
 *    this app's uid, and we open that snapshot with the platform SQLite that
 *    every app already ships. No bundled native binary, no NDK, and no parsing
 *    of the `content` CLI's unquoted, newline-bearing output.
 *  - WRITE: rows go back through the provider itself (`content insert` as root),
 *    because TelephonyProvider — not us — owns `canonical_addresses`,
 *    `threads` and observer notification. Writing those tables by hand would be
 *    version-fragile and produce messages no messaging app displays correctly.
 *
 * Field-verified on the POCO F6 (Android 16): root `content query`/`content
 * insert` against `content://sms` succeed while Google Messages remains the
 * role holder; `cp` + `chown` to the app uid yields a readable snapshot; toybox
 * `base64 -d` is present, which is what makes the write path quote-proof.
 */
object SmsViaRoot {

    private const val TAG = "SmsViaRoot"
    private const val TELEPHONY_PKG = "com.android.providers.telephony"
    private const val SNAPSHOT_NAME = "mmssms_snapshot.db"

    /**
     * The snapshot is a copy of a file another process is writing, so a copy
     * taken mid-transaction can be torn. Retrying once is enough: the window is
     * only open while a real transaction holds the journal.
     */
    private const val SNAPSHOT_ATTEMPTS = 2

    /** One row as the restore path needs it; mirrors the provider's own columns. */
    data class Row(
        val address: String?,
        val body: String?,
        val date: Long?,
        val type: Int?,
        val read: Int?,
        val seen: Int?,
        val serviceCenter: String?,
        val dateSent: Long?,
        val subId: Int?
    )

    /**
     * Read-only snapshot of the live SMS database, or null when it cannot be
     * produced (no root, provider gone, unreadable copy). Callers must treat
     * null as "fall back / report honestly" — never as "no messages".
     */
    fun openSnapshot(filesDir: File): SQLiteDatabase? {
        repeat(SNAPSHOT_ATTEMPTS) { attempt ->
            val dst = File(filesDir, SNAPSHOT_NAME)
            dst.delete()
            val uid = Process.myUid()
            val cmd = "cp ${ShellQuote.q(SRC_DB)} ${ShellQuote.q(dst.path)} && " +
                "chown $uid:$uid ${ShellQuote.q(dst.path)} && " +
                "chmod 600 ${ShellQuote.q(dst.path)}"
            val r = try {
                Shell.cmd(cmd).exec()
            } catch (e: Exception) {
                Log.w(TAG, "snapshot shell failed: ${e.message}")
                return null
            }
            if (!r.isSuccess || !dst.exists() || dst.length() == 0L) {
                Log.w(TAG, "snapshot copy failed (attempt ${attempt + 1}): ${r.err}")
                return null
            }
            try {
                // OPEN_READONLY: this is our copy, but there is no reason to
                // ever let the app write to it.
                return SQLiteDatabase.openDatabase(
                    dst.path, null, SQLiteDatabase.OPEN_READONLY
                )
            } catch (e: Exception) {
                // Torn copy (or a schema the platform cannot open) — try again.
                Log.w(TAG, "snapshot open failed (attempt ${attempt + 1}): ${e.message}")
                dst.delete()
            }
        }
        return null
    }

    /** Column names present in the snapshot's `sms` table, for archive fidelity. */
    fun smsColumns(db: SQLiteDatabase): List<String> = try {
        db.rawQuery("PRAGMA table_info(sms)", null).use { c ->
            val idx = c.getColumnIndexOrThrow("name")
            buildList { while (c.moveToNext()) add(c.getString(idx)) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "table_info failed: ${e.message}")
        emptyList()
    }

    /**
     * `_id`s of the conversations that still exist right now.
     *
     * Restoring an archived `thread_id` is only meaningful while that row is
     * still in `threads`. Deleting the last message of a conversation destroys
     * the thread row, and an archived `thread_id` then points at nothing —
     * binding it would strand the message in a thread the provider has no
     * record of, so it is verified here and dropped when stale. When the
     * thread is gone the provider re-threads the message by address, which
     * lands it in the correct (new) conversation anyway.
     */
    fun readThreadIds(db: SQLiteDatabase): Set<Long> = try {
        db.rawQuery("SELECT _id FROM threads", null).use { c ->
            val idx = c.getColumnIndexOrThrow("_id")
            buildSet { while (c.moveToNext()) add(c.getLong(idx)) }
        }
    } catch (e: Exception) {
        Log.w(TAG, "thread id read failed (restoring without thread_id): ${e.message}")
        emptySet()
    }

    /**
     * Every `sms` row, typed the way the archive expects. Reading from the
     * snapshot rather than the provider is what removes the need for the role on
     * the backup side.
     */
    fun readAllRows(db: SQLiteDatabase): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        try {
            db.rawQuery("SELECT * FROM sms", null).use { c ->
                val names = c.columnNames
                while (c.moveToNext()) {
                    val row = LinkedHashMap<String, Any?>(names.size)
                    for (i in names.indices) {
                        row[names[i]] = when (c.getType(i)) {
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> null // NULL / BLOB — archives never carried BLOBs
                        }
                    }
                    out.add(row)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "snapshot read failed: ${e.message}", e)
        }
        return out
    }

    /** Signature set for idempotent restore: address|date|body, one per row. */
    fun readSignatures(db: SQLiteDatabase): Set<String> {
        val seen = HashSet<String>()
        try {
            db.rawQuery("SELECT address, date, body FROM sms", null).use { c ->
                val a = c.getColumnIndexOrThrow("address")
                val d = c.getColumnIndexOrThrow("date")
                val b = c.getColumnIndexOrThrow("body")
                while (c.moveToNext()) {
                    seen.add(
                        signature(
                            if (c.isNull(a)) null else c.getString(a),
                            if (c.isNull(d)) null else c.getLong(d),
                            if (c.isNull(b)) null else c.getString(b)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "signature read failed (restoring without dedup): ${e.message}")
        }
        return seen
    }

    fun signature(address: Any?, date: Any?, body: Any?): String =
        "${address ?: ""}|${date ?: ""}|${body ?: ""}"

    /**
     * One message, in the shape the generated script needs. Deliberately a
     * plain data class rather than `ContentValues`: the generator is pure logic
     * and has to be unit-testable, and ContentValues is a framework stub under
     * unit tests.
     */
    data class SmsRow(
        val address: String?,
        val body: String?,
        val serviceCenter: String?,
        val date: Long,
        val type: Int,
        val read: Int,
        val seen: Int,
        val subId: Int,
        val dateSent: Long,
        /**
         * Original conversation, or null when the archive did not record one or
         * that conversation no longer exists. Only bound when non-null, so the
         * provider threads the message itself in the other case.
         */
        val threadId: Long?
    )

    /**
     * Insert rows through the provider, as root, in ONE shell invocation.
     *
     * Per-row `content insert` costs ~41 ms measured on-device (the CLI forks
     * and binds to the provider each call), so the whole batch travels as a
     * generated script: one `su`, N inserts, one result line. Bodies are
     * base64'd because message text contains quotes, spaces, semicolons and
     * newlines — every one of which would otherwise be shell syntax.
     *
     * [values] must already have been filtered to columns this provider version
     * accepts; the caller owns that decision.
     */
    fun insertRows(
        filesDir: File,
        values: List<ContentValues>,
        onProgress: (Int) -> Unit
    ): Int {
        if (values.isEmpty()) return 0
        val script = File(filesDir, "dv_sms_insert.sh")
        val rows = values.map { v ->
            SmsRow(
                address = v.getAsString("address"),
                body = v.getAsString("body"),
                serviceCenter = v.getAsString("service_center"),
                date = v.getAsLong("date") ?: 0L,
                type = v.getAsInteger("type") ?: 1,
                read = v.getAsInteger("read") ?: 1,
                seen = v.getAsInteger("seen") ?: 1,
                subId = v.getAsInteger("sub_id") ?: -1,
                dateSent = v.getAsLong("date_sent") ?: 0L,
                threadId = v.getAsLong("thread_id")
            )
        }

        var inserted = 0
        // Chunks keep the generated script small enough to exec cheaply and give
        // progress a chance to move on large archives.
        val chunk = rows.chunked(INSERT_CHUNK)
        var done = 0
        for (part in chunk) {
            script.writeText(buildScript(part))
            val r = try {
                Shell.cmd("sh ${ShellQuote.q(script.path)}").exec()
            } catch (e: Exception) {
                Log.w(TAG, "insert shell failed: ${e.message}")
                break
            }
            val m = Regex("DVCOUNT=(\\d+)").find(r.out.joinToString("\n"))
            inserted += m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            done += part.size
            onProgress(done)
        }
        script.delete()
        return inserted
    }

    private const val INSERT_CHUNK = 200

    internal fun buildScript(values: List<SmsRow>): String {
        val b = StringBuilder()
        b.append("#!/system/bin/sh\n")
        // Every argument is copied into a NAMED variable BEFORE the first
        // `set --`. Building the command with `set --` OVERWRITES $1..$9, so
        // reading "$4" after it yields the string "content://sms" instead of
        // the row's date. That shipped a restore that wrote
        // `--bind date:l:content://sms` for every row of a batch: `content`
        // prints usage, rejects the row, and still EXITS 0, so the run
        // cheerfully reported messages as restored that were never written.
        // A single-row script escaped it only because its fields were
        // interpolated by the caller instead of read from "$@".
        // The string values arrive with `cliEscape` already applied, so the
        // decoded text the CLI receives is escaped exactly once and unescapes to
        // the original body. See cliEscape for why that is not optional.
        b.append(
            "insert_one(){\n" +
                "  A=$(printf '%s' \"$1\" | base64 -d)\n" +
                "  M=$(printf '%s' \"$2\" | base64 -d)\n" +
                "  C=$(printf '%s' \"$3\" | base64 -d)\n" +
                "  D=$4; T=$5; R=$6; S=$7; SUB=$8; DS=$9; TH=\${10}\n" +
                "  set -- content insert --uri content://sms\n" +
                "  set -- \"\$@\" --bind address:s:\"\$A\" --bind body:s:\"\$M\"\n" +
                "  [ -n \"\$C\" ] && set -- \"\$@\" --bind service_center:s:\"\$C\"\n" +
                "  set -- \"\$@\" --bind date:l:\"\$D\" --bind type:i:\"\$T\"\n" +
                "  set -- \"\$@\" --bind read:i:\"\$R\" --bind seen:i:\"\$S\"\n" +
                "  set -- \"\$@\" --bind sub_id:i:\"\$SUB\" --bind date_sent:l:\"\$DS\"\n" +
                // Return the message to the conversation it came from. Without
                // this the provider re-threads by address, so a message deleted
                // from a multi-message conversation came back as a NEW thread.
                // Only bound when the caller verified the thread still exists
                // (a ${10} of "-" means "no usable thread_id").
                "  [ \"\$TH\" != \"-\" ] && set -- \"\$@\" --bind thread_id:i:\"\$TH\"\n" +
                // Success is read from the OUTPUT: `content insert` exits 0 even
                // when the provider rejects the row, but prints usage or the
                // exception text when it fails.
                "  out=$(\"\$@\" 2>&1)\n" +
                "  case \"\$out\" in *usage*|*xception*|*rror*) ;; *) N=\$((N+1));; esac\n" +
                "  return 0\n" +
                "}\n"
        )
        b.append("N=0\n")
        for (v in values) {
            // The three text fields are QUOTED. `b64` of an empty value is the
            // empty string, and an unquoted empty argument simply does not exist
            // for the shell: `insert_one A B  C D` passes three arguments, not
            // four, so every later field slid one slot left. A source row with
            // no service centre (normal for an inbound SMS) is enough to trigger
            // it — field-verified, the row was rejected with "[ERROR] For input
            // string: \"1788614163000\"", i.e. date_sent landing in
            // `--bind sub_id:i:`.
            b.append(
                "insert_one \"${b64(cliEscape(v.address))}\" \"${b64(cliEscape(v.body))}\" " +
                    "\"${b64(cliEscape(v.serviceCenter))}\" " +
                    "${v.date} ${v.type} ${v.read} ${v.seen} ${v.subId} ${v.dateSent} " +
                    "${v.threadId ?: "-"}\n"
            )
        }
        b.append("echo \"DVCOUNT=\$N\"\n")
        return b.toString()
    }

    // java.util.Base64, not android.util.Base64: the platform class is a
    // "not mocked" stub under unit tests, which is exactly where the generated
    // script needs to be assertable. minSdk is 29, well past its API 26 debut.
    private fun b64(v: String): String =
        java.util.Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8))

    /**
     * Escape a value for the `content` CLI's `--bind` parser.
     *
     * `--bind <column>:<type>:<value>` is parsed by `splitWithEscaping(argument,
     * ':')` and REJECTED unless the split yields exactly three pieces — the CLI
     * prints its whole usage text followed by "[ERROR] Binding not well formed:
     * <binding>". The row is then dropped, and because the CLI still exits 0 the
     * old output check only caught it by luck. Field-verified: 18 of 55 archived
     * messages carry a colon in the body ("Your NVIDIA verification code is:
     * 153856", "Your Apple Account code is: 476557", "[Uber] Your code: 0569"),
     * and every one of them was un-restorable — which is exactly the reported
     * symptom, since a user who deletes a couple of OTP texts and restores gets
     * nothing back while the run reports success.
     *
     * Backslash is the escape character, so it must be doubled FIRST: escaping
     * colons first would turn them into `\:`, and the backslash pass would then
     * double the escape itself (`\\:`) — leaving the colon to split the binding
     * again. `\` and `:` are the only two characters the parser treats specially.
     */
    internal fun cliEscape(v: String?): String =
        (v ?: "").replace("\\", "\\\\").replace(":", "\\:")

    private val SRC_DB = "/data/user/0/$TELEPHONY_PKG/databases/mmssms.db"
}