package com.phoenix.droidvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for audit A-02: the engine (root) installs every line of
 * the target file it reads, so a crafted APK label containing a newline
 * injected an extra install line. Every field must be protocol-proof.
 */
class InstallerTargetLineTest {

    private val path = "/data/local/tmp/droidvault_install/com.example_123.apk"

    @Test fun normalLineIsAccepted() {
        assertEquals(
            "$path|com.example|Example App",
            ShellQuote.installerTargetLine(path, "com.example", "Example App")
        )
    }

    @Test fun labelNewlineIsStrippedNotInjected() {
        val line = ShellQuote.installerTargetLine(
            path, "com.example", "Evil\n/data/local/tmp/evil.apk|com.evil|X"
        )
        assertTrue(line != null)
        assertFalse("newline must never survive", line!!.contains('\n'))
        assertFalse(line.contains("\r"))
        // Exactly two protocol separators: path|pkg|label — an injected line
        // would add a third (new fields) or a newline.
        assertEquals(2, line.count { it == '|' })
        // The injected payload is flattened into the same (display-only) label.
        assertFalse(line.startsWith("/data/local/tmp/evil.apk|"))
    }

    @Test fun labelPipesAreStripped() {
        val line = ShellQuote.installerTargetLine(path, "com.example", "A|B|C")
        assertEquals("$path|com.example|ABC", line)
    }

    @Test fun pathWithNewlineIsRejectedEntirely() {
        assertNull(ShellQuote.installerTargetLine("/tmp/a\n/tmp/b|com.x|L", "com.example", "L"))
        assertNull(ShellQuote.installerTargetLine("/tmp/a|b", "com.example", "L"))
        assertNull(ShellQuote.installerTargetLine("", "com.example", "L"))
    }

    @Test fun invalidPackageIsRejected() {
        assertNull(ShellQuote.installerTargetLine(path, "com.evil;rm -rf /", "L"))
        assertNull(ShellQuote.installerTargetLine(path, "", "L"))
    }
}
