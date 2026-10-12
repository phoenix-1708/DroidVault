package com.phoenix.droidvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShellQuoteTest {
    @Test fun quotesSingleQuote() {
        assertEquals("'a'\"'\"'b'", ShellQuote.q("a'b"))
    }
    @Test fun quotesPathWithSpaces() {
        assertEquals("'/sdcard/My ROM/a.zip'", ShellQuote.q("/sdcard/My ROM/a.zip"))
    }
    @Test fun rejectsBadPackage() {
        try {
            ShellQuote.requirePackage("a';id;'")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("com.example.app", ShellQuote.requirePackage("com.example.app"))
    }
    @Test fun rejectsBadPartition() {
        try {
            ShellQuote.requirePartition("../boot")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("boot_a", ShellQuote.requirePartition("boot_a"))
    }
    @Test fun rejectsBadPath() {
        try {
            ShellQuote.requireAbsPath("rel/path")
            fail("expected rejection")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun rejectsQuoteBreakoutPaths() {
        listOf(
            "/sdcard/a'b",
            "/sdcard/a\"b",
            "/sdcard/a\$b",
            "/sdcard/a`b",
            "/sdcard/a\nb"
        ).forEach { p ->
            try {
                ShellQuote.requireAbsPath(p)
                fail("expected rejection for $p")
            } catch (_: IllegalArgumentException) {
            }
        }
        // Legit names with spaces/parens still pass.
        assertEquals(
            "/storage/emulated/0/My Folder (1)/DroidVault",
            ShellQuote.requireAbsPath("/storage/emulated/0/My Folder (1)/DroidVault")
        )
    }
    @Test fun safeLabelStrips() {
        assertEquals("MyApp", ShellQuote.safeLabel("My|App\n"))
        assertTrue(ShellQuote.safeLabel("x".repeat(200)).length <= 128)
    }

    /**
     * A label is another app's `android:label`, i.e. fully attacker-controlled,
     * and it reaches a root shell (systemize/debloat). `"`, `$` and a backtick
     * are the characters that turn that interpolation into code execution —
     * confirmed on a rooted device, where a label of `$(id)` and a label of
     * `"; id; echo "` both ran as root. They are stripped, not rejected, so a
     * legitimately-named app stays usable.
     */
    @Test fun safeLabelIsInertInEveryShellContext() {
        listOf(
            "\$(id)",
            "\"\$(id)\"",
            "\"; id; echo \"",
            "`id`",
            "a\$b",
            "a\"b",
            "a`b",
            "'; id; '",
            "|id",
            "\$(touch /data/local/tmp/pwn)"
        ).forEach { evil ->
            val out = ShellQuote.safeLabel(evil)
            listOf('"', '$', '`', '\'', '|', '\n', '\r').forEach { bad ->
                assertTrue("label ${evil} -> $out still contains $bad", !out.contains(bad))
            }
        }
    }

    /** The stripped label must still be a usable single-line directory name. */
    @Test fun safeLabelKeepsNamesAroundTheStrippedCharacters() {
        assertEquals("App(id)", ShellQuote.safeLabel("App\$(id)"))
        assertEquals("My App (2024)", ShellQuote.safeLabel("My App (2024)"))
        // A label made only of dangerous characters still yields the stable
        // fallback rather than an empty directory name.
        assertEquals("app", ShellQuote.safeLabel("\$"))
        assertEquals("app", ShellQuote.safeLabel("\""))
    }

    @Test fun safeLabelIsDirectorySafe() {
        // Path traversal must never survive: labels become vault directories.
        // Slashes become underscores; leading dots are stripped, so the result
        // can never be a relative-path component like "..".
        val traversal = ShellQuote.safeLabel("../../..")
        assertTrue("got: $traversal", !traversal.contains('/'))
        assertTrue("got: $traversal", !traversal.startsWith("."))
        // "../etc" → slashes become underscores first (".._etc"), then leading
        // dots strip → "_etc": unambiguous, and never a traversal component.
        assertEquals("_etc", ShellQuote.safeLabel("../etc"))
        assertEquals("a_b_c", ShellQuote.safeLabel("a/b/c"))
        assertTrue(!ShellQuote.safeLabel("a\\\\b").contains('\\'))
    }

    @Test fun safeLabelNeverReturnsEmptyOrDotOnly() {
        assertEquals("app", ShellQuote.safeLabel(""))
        assertEquals("app", ShellQuote.safeLabel("..."))
        assertEquals("app", ShellQuote.safeLabel("|"))
        assertEquals("app", ShellQuote.safeLabel("  |  "))
        assertEquals("app", ShellQuote.safeLabel("/"))
    }

    @Test fun safeLabelKeepsLegitNames() {
        assertEquals("Telegram", ShellQuote.safeLabel("Telegram"))
        assertEquals("My App (2024)", ShellQuote.safeLabel("My App (2024)"))
        // Trailing dots are legal in a directory name; leading dots are not.
        assertEquals("App...", ShellQuote.safeLabel("App..."))
        assertEquals("App", ShellQuote.safeLabel("...App"))
    }
}
