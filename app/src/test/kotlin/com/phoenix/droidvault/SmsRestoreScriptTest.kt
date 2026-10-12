package com.phoenix.droidvault

import com.phoenix.droidvault.utils.SmsViaRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the generated SMS restore script.
 *
 * This script is the only thing that actually writes restored messages, and it
 * ships as a string — the failure mode it had was invisible: `content insert`
 * exits 0 even when the provider rejects a row (it prints usage instead), so a
 * broken script reports success and the run claims messages were restored while
 * the store never changed. Field-verified symptom: a 5-message restore logged
 * "Restored 5 SMS" and restored nothing.
 *
 * The root cause was POSIX positional-parameter clobbering: `set -- content
 * insert …` REPLACES $1..$9, so any later `"$4"` reads "content://sms" rather
 * than the row's date. These assertions fail the build if that ever comes back.
 *
 * A second, independent liar lived in the same command: the CLI parses a
 * binding as `splitWithEscaping(arg, ':')` and drops the row unless that yields
 * exactly three pieces, so every body containing a colon (18 of 55 in the
 * field archive) was silently un-restorable. `values with colons survive the
 * CLI binding parser` below re-implements that parser and asserts the emitted
 * binding round-trips.
 */
class SmsRestoreScriptTest {

    private fun script(vararg rows: SmsViaRoot.SmsRow): String =
        SmsViaRoot.buildScript(rows.toList())

    /** The `insert_one` argument list of the first emitted row. */
    private fun firstArgs(s: String): List<String> =
        shellWords(
            s.lineSequence().first { it.startsWith("insert_one ") }
                .removePrefix("insert_one ")
        )

    /**
     * Word-splits the way /system/bin/sh does: `""` is one (empty) argument,
     * while a bare empty gap between spaces produces none at all.
     */
    private fun shellWords(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var seen = false
        for (c in s.trim()) when {
            c == '"' -> {
                quoted = !quoted; seen = true
            }
            c == ' ' && !quoted -> {
                if (seen) out.add(cur.toString())
                cur.setLength(0); seen = false
            }
            else -> {
                cur.append(c); seen = true
            }
        }
        if (seen) out.add(cur.toString())
        return out
    }

    private fun decode(v: String): String =
        String(java.util.Base64.getDecoder().decode(v), Charsets.UTF_8)

    /**
     * Mirrors `Content.Parser.splitWithEscaping(arg, ':')`: a backslash escapes
     * the following character, everything else is split on.
     */
    private fun cliSplit(arg: String): List<String> {
        val res = ArrayList<String>()
        val cur = StringBuilder()
        var i = 0
        while (i < arg.length) {
            val c = arg[i]
            when {
                c == '\\' -> {
                    i++
                    if (i == arg.length) return emptyList() // CLI: "Invalid escaping"
                    cur.append(arg[i])
                }
                c == ':' -> {
                    res.add(cur.toString()); cur.setLength(0)
                }
                else -> cur.append(c)
            }
            i++
        }
        res.add(cur.toString())
        return res
    }

    private fun sampleRow(date: Long = 1750000000000L) = SmsViaRoot.SmsRow(
        address = "+15550100",
        body = "hello\nworld with \"quotes\" and ; semicolons",
        serviceCenter = "+919811002485",
        date = date,
        type = 1,
        read = 0,
        seen = 1,
        subId = -1,
        dateSent = date,
        threadId = null
    )

    @Test
    fun `positional params are captured before set clobbers them`() {
        val s = script(sampleRow())

        // The named copies must appear BEFORE the first `set --`, otherwise the
        // values they read are already gone.
        val firstSet = s.indexOf("set --")
        val namedCapture = s.indexOf("D=\$4; T=\$5; R=\$6; S=\$7; SUB=\$8; DS=\$9")
        assertTrue(
            "row fields must be copied into named variables before `set --` overwrites \$1..\$9",
            namedCapture > 0 && namedCapture < firstSet
        )
    }

    @Test
    fun `no bind reads a raw positional parameter after set`() {
        val s = script(sampleRow())
        val afterFirstSet = s.substring(s.indexOf("set --"))
        for (n in 1..9) {
            assertTrue(
                "no bind may read \$$n after `set --` (it is the CLI's own argument, not the row's)",
                !afterFirstSet.contains("--bind date:l:\"$$n\"") &&
                    !Regex("--bind (type|read|seen|sub_id|date_sent):[il]:\"\\$$n\"").containsMatchIn(afterFirstSet)
            )
        }
    }

    @Test
    fun `row values are base64 so bodies cannot break the shell`() {
        val s = script(sampleRow())
        val call = s.lineSequence().first { it.startsWith("insert_one ") }
        // Raw newlines, quotes and semicolons must not appear as arguments.
        assertTrue("insert call must be a single line", !call.contains("\n"))
        assertTrue("body must be base64 encoded, not inlined", !call.contains("hello"))
        assertTrue("body must be base64 encoded, not inlined", !call.contains(";"))
    }

    @Test
    fun `success is decided by output not by exit code`() {
        val s = script(sampleRow())
        assertTrue(
            "`content insert` exits 0 on rejection, so success must be read from its output",
            s.contains("out=\$(\"\$@\" 2>&1)") && s.contains("*usage*")
        )
    }

    @Test
    fun `values with colons survive the CLI binding parser`() {
        val body = "Your NVIDIA verification code is: 153856. Expires in 10 min"
        val args = firstArgs(script(sampleRow().copy(body = body, serviceCenter = "+91:82")))
        val decoded = decode(args[1])

        // The emitted value is escaped for the CLI, so the parser must recover
        // exactly one binding and the original text.
        val split = cliSplit("body:s:$decoded")
        assertEquals("a colon in a body must not split the binding", 3, split.size)
        assertEquals("the escaped value must decode back to the body", body, split[2])
        assertEquals(3, cliSplit("service_center:s:${decode(args[2])}").size)
    }

    @Test
    fun `backslashes are doubled before colons are escaped`() {
        // Ordering matters: escaping colons first would turn them into "\:",
        // and the backslash pass would double that escape into "\\:", leaving
        // the colon to split the binding again.
        val body = "path C:\\Users\\dv and time 10:30"
        val decoded = decode(firstArgs(script(sampleRow().copy(body = body)))[1])
        val split = cliSplit("body:s:$decoded")
        assertEquals(3, split.size)
        assertEquals(body, split[2])
        assertEquals("build a backslash and a colon", "a\\:b", SmsViaRoot.cliEscape("a:b"))
        assertEquals("double the backslash", "a\\\\b", SmsViaRoot.cliEscape("a\\b"))
    }

    @Test
    fun `empty values keep the argument list aligned`() {
        // A message with no service_center (normal for inbound SMS) and an empty
        // body used to emit an empty UNQUOTED argument, which the shell drops —
        // shifting date_sent into the sub_id bind and failing the whole row with
        // "[ERROR] For input string". The generator must therefore quote them.
        val row = sampleRow().copy(body = "", serviceCenter = null)
        val args = firstArgs(script(row))
        assertEquals("insert_one must always receive 10 arguments", 10, args.size)
        assertEquals("an empty body stays in the body slot", "", decode(args[1]))
        assertEquals("+15550100", decode(args[0]))
        assertEquals("an empty service centre stays in its own slot", "", decode(args[2]))
        assertEquals("date_sent must not slide into sub_id", row.dateSent.toString(), args[8])
        assertEquals("sub_id must not slide into seen", row.subId.toString(), args[7])
    }

    @Test
    fun `thread_id is bound so a message returns to its original conversation`() {
        // Field-verified on the POCO F6: without this binding the provider
        // re-threads by address, so a message deleted out of a multi-message
        // conversation came back in a brand-new thread.
        val s = script(sampleRow().copy(threadId = 107L))
        assertTrue(
            "the generated script must bind thread_id",
            s.contains("--bind thread_id:i:\"\$TH\"")
        )
        assertEquals("the thread id must reach the script as the 10th argument", "107", firstArgs(s)[9])
    }

    @Test
    fun `a stale thread id is omitted rather than bound`() {
        // The caller passes "-" when the conversation no longer exists; the
        // generator must not emit a thread_id binding in that case, or the
        // message is stranded in a thread the provider has no record of.
        val s = script(sampleRow().copy(threadId = null))
        assertEquals("no thread id must be passed as '-' sentinel", "-", firstArgs(s)[9])
        assertTrue(
            "the bind must be conditional on the sentinel",
            s.contains("[ \"\$TH\" != \"-\" ] && set -- \"\$@\" --bind thread_id:i:\"\$TH\"")
        )
    }

    @Test
    fun `thread_id is captured before set clobbers the positional params`() {
        val s = script(sampleRow().copy(threadId = 5L))
        // `\${10}` must reach the shell script VERBATIM. Written as `${10}` in a
        // Kotlin template — including inside a triple-quoted raw string, which
        // still interpolates — it becomes the literal text "10", so the shell
        // would read a constant instead of the 10th argument and every row
        // would be bound to thread 10.
        val capture = s.indexOf("DS=\$9; TH=\${10}")
        val firstSet = s.indexOf("set --")
        assertTrue(
            "thread_id must be read into a named variable before `set --`",
            capture > 0 && capture < firstSet
        )
        assertTrue(
            "`\${10}` must not be interpolated away by the Kotlin template",
            !s.contains("DS=\$9; TH=10")
        )
    }

    @Test
    fun `one insert call is emitted per row`() {
        val s = script(sampleRow(), sampleRow(date = 1750000000001L))
        assertTrue("each row needs its own insert call", s.lines().count { it.startsWith("insert_one ") } == 2)
        assertTrue("the count line must still be emitted", s.contains("echo \"DVCOUNT=\$N\""))
    }
}