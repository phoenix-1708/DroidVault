package com.phoenix.droidvault.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for audit A-12: provider-controlled document paths flow
 * into `install <path>` OpenRecoveryScript lines executed by recovery as
 * root. A newline appends arbitrary ORS commands; quotes/backslashes confuse
 * the tokenizer; `..` traverses.
 */
class OrsPathSafetyTest {

    @Test fun normalZipPathsAreAccepted() {
        assertTrue(FlashManager.isSafeOrsPath("/sdcard/Download/ROM.zip"))
        assertTrue(FlashManager.isSafeOrsPath("/storage/emulated/0/My Folder/PA (2024).zip"))
        assertTrue(FlashManager.isSafeOrsPath("/storage/1234-5678/Zips/Kernel-1.2+.zip"))
        // Unicode names are not special to a line/token parser.
        assertTrue(FlashManager.isSafeOrsPath("/sdcard/Téléchargements/édition.zip"))
    }

    @Test fun newlineInjectionIsRejected() {
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a.zip\nformat data"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a.zip\r\nreboot recovery"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a.zip\ninstall /sdcard/evil.zip"))
    }

    @Test fun quotesBackslashesAndTraversalAreRejected() {
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a'zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a\"zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a`zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a\\zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/../data/local/tmp/evil.zip"))
    }

    /**
     * The `$` gap that let a provider-controlled docId reach root as code.
     *
     * These paths were interpolated inside `su -c "unzip -l '<path>'"`, where
     * `$` is live syntax. Verified on a rooted device: a docId of
     * `/sdcard/a$(id)/x.apk` ran `id` as root. `$` was the one shell
     * metacharacter the old allowlist forgot, which is exactly why this case
     * is pinned here rather than left to the interpolation sites.
     */
    @Test fun commandSubstitutionIsRejected() {
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a\$(id)zip"))
        assertFalse(FlashManager.isSafeOrsPath("/storage/emulated/0/\$(touch /data/local/tmp/pwn)/a.zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a\${IFS}zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a\$HOME"))
    }

    /** `;`, `|`, `&`, `<`, `>` terminate or chain commands in the same way. */
    @Test fun commandSeparatorsAndRedirectionAreRejected() {
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a;rm -rf /data.zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a|sh.zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a&&id.zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a>out.zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/a<input.zip"))
    }

    /** A `$` in a directory component must not sneak past either. */
    @Test fun substitutionInAnyPathComponentIsRejected() {
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/\$(id)/a.zip"))
        assertFalse(FlashManager.isSafeOrsPath("/sdcard/Download\$IFS/a.zip"))
    }

    @Test fun relativeAndEmptyPathsAreRejected() {
        assertFalse(FlashManager.isSafeOrsPath("sdcard/a.zip"))
        assertFalse(FlashManager.isSafeOrsPath(""))
        assertFalse(FlashManager.isSafeOrsPath(null))
        assertFalse(FlashManager.isSafeOrsPath("/" + "a".repeat(600)))
    }
}
