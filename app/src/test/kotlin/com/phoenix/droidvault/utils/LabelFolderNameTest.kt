package com.phoenix.droidvault.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The label → folder rule, pinned against what the storage actually refuses.
 *
 * Measured on the reference device (POCO F6, Android 17): root can create
 * `: * ? < > | " \` under `/sdcard`; the adb shell — and therefore `adb push`,
 * MTP, SAF and every file manager — gets EPERM, and a `tar` extracting as the
 * shell uid fails file by file. Windows and exFAT cannot hold them either.
 *
 * A vault folder carrying one could be copied OFF the phone and never put
 * back, which is the recovery path this app exists for. 35 of the reference
 * vault's 112 folders were in that state, and they stopped the other 33.7 GB
 * from being pushed back too.
 */
class LabelFolderNameTest {

    /** Every character the non-root storage policy refuses. */
    private val refused = listOf(':', '*', '?', '<', '>', '|', '"', '\\')

    @Test
    fun everyRefusedCharacterIsGoneFromTheFolderName() {
        for (c in refused) {
            val name = ShellQuote.safeLabel("Aniyomi${c}AnimeFLV")
            assertEquals("still carries '$c': $name", -1, name.indexOf(c))
            assertFalse("empty name for '$c'", name.isBlank())
        }
    }

    /** The reference vault's two families, verbatim. */
    @Test
    fun realLabelsBecomeTheNamesTheReferenceVaultNowHas() {
        assertEquals("Aniyomi - AnimeFLV", ShellQuote.safeLabel("Aniyomi: AnimeFLV"))
        assertEquals(
            "Tachiyomi - Mangakakalot",
            ShellQuote.safeLabel("Tachiyomi: Mangakakalot")
        )
        assertEquals(
            "Tachiyomi - Manga District",
            ShellQuote.safeLabel("Tachiyomi: Manga District")
        )
        // No space after the colon keeps its own spacing.
        assertEquals("Tachiyomi-MangaDex", ShellQuote.safeLabel("Tachiyomi:MangaDex"))
    }

    /**
     * No churn: a label that is already portable comes back byte-identical.
     *
     * Every folder already in a vault is named by this function, so a rule
     * that "tidied" working names would rename data that is not broken.
     */
    @Test
    fun portableLabelsAreUntouched() {
        val untouched = listOf(
            "Apple Music",
            "1DM+",
            "MiX PDF",
            "Call of Duty",
            "AB DM",
            "MicroG RE",
            "MicroG Services",
            "Swift Backup",
            "Device Info",
            "Hero App",
            "Instant Mail",
            "Keep notes",
            "Neutron Player",
            "PS Helper",
            "Reseam Manager",
            "Local Dream",
            "Candy Browser",
            "Tachiyomi",
            "WPS Office",
            "Payload Dumper Android",
            "MiX Archive",
            "Tata Play Smart",
            "YouTube Morphe",
            "Duolingo",
            "Brave",
            "BitChord",
            "MJ PDF",
            "MiX Codecs",
            "MiX Encrypt",
            "MiX Image",
            "MiX Tagger",
            "Tachiyomi-MangaDex"
        )
        for (label in untouched) {
            assertEquals("churned a portable label", label, ShellQuote.safeLabel(label))
        }
    }

    /**
     * The migration renames a folder by running this function on the name that
     * is already there, so it must be a fixed point — otherwise a folder would
     * be renamed again on every launch.
     */
    @Test
    fun applyingTheRuleTwiceChangesNothing() {
        val labels = listOf(
            "Aniyomi: AnimeFLV",
            "Tachiyomi: Manga District",
            "Tachiyomi: Mangakakalot",
            "Foo.",
            "Foo ",
            "CON",
            "a*b?c<d>e|f\"g\\h",
            "a/b|c",
            "Apple Music",
            "..",
            "/",
            "|",
            ""
        )
        for (label in labels) {
            val once = ShellQuote.safeLabel(label)
            assertEquals("not a fixed point for '$label'", once, ShellQuote.safeLabel(once))
        }
    }

    /** Windows and FAT cannot hold a trailing dot or space. */
    @Test
    fun trailingDotsAndSpacesAreStripped() {
        assertEquals("Foo", ShellQuote.safeLabel("Foo."))
        assertEquals("Foo", ShellQuote.safeLabel("Foo. "))
        assertEquals("Foo", ShellQuote.safeLabel("Foo..."))
    }

    /** Reserved device names are names Windows cannot create, with or without an extension. */
    @Test
    fun reservedDeviceNamesArePrefixed() {
        assertEquals("_CON", ShellQuote.safeLabel("CON"))
        assertEquals("_con", ShellQuote.safeLabel("con"))
        assertEquals("_COM1", ShellQuote.safeLabel("COM1"))
        assertEquals("_LPT9.txt", ShellQuote.safeLabel("LPT9.txt"))
        // Not reserved: CONSOLE, COM10.
        assertEquals("CONSOLE", ShellQuote.safeLabel("CONSOLE"))
        assertEquals("COM10", ShellQuote.safeLabel("COM10"))
    }

    /** The structural rules this function already had must keep holding. */
    @Test
    fun structuralRulesSurviveThePortabilityRule() {
        assertEquals("app", ShellQuote.safeLabel("/"))
        assertEquals("app", ShellQuote.safeLabel("|"))
        assertEquals("app", ShellQuote.safeLabel(".."))
        assertEquals("app", ShellQuote.safeLabel(""))
        assertFalse(ShellQuote.safeLabel("a/b").contains('/'))
        assertFalse(ShellQuote.safeLabel("a" + '$' + "(id)").contains('$'))
        assertFalse(ShellQuote.safeLabel("a" + '`' + "id" + '`').contains('`'))
        assertFalse(ShellQuote.safeLabel("a\"b").contains('"'))
        assertFalse(ShellQuote.safeLabel("a'b").contains('\''))
        assertTrue(ShellQuote.safeLabel("x".repeat(500)).length <= 128)
        // Nothing may start with a dot either: a dot-prefixed entry is hidden,
        // and "." / ".." are not names at all.
        assertFalse(ShellQuote.safeLabel("...hidden").startsWith('.'))
    }
}
