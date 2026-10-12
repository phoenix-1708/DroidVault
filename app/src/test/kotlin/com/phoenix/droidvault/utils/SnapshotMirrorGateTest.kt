package com.phoenix.droidvault.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ROM-survival mirror gating (DroidVault.apk + DroidVault.sh in the Droid Vault
 * folder): the hash dedupe must never skip a MISSING mirror. Field case: the
 * only snapshot calls used to live inside narrow run branches, and after a
 * Settings folder move the new folder could end up without mirrors because
 * the saved hash matched — the gate now requires BOTH an unchanged hash AND
 * the file actually present in the target folder.
 */
class SnapshotMirrorGateTest {

    @Test fun skipsOnlyWhenHashUnchangedAndMirrorPresent() {
        // The one and only skip case: nothing changed, file is there.
        assertTrue(AutomationSnapshot.shouldSkipMirror(hashMatchesSaved = true, mirrorPresent = true))
    }

    @Test fun missingMirrorIsNeverSkipped() {
        // Folder moved / file deleted / folder replaced → re-copy even though
        // the content hash is unchanged.
        assertFalse(AutomationSnapshot.shouldSkipMirror(hashMatchesSaved = true, mirrorPresent = false))
    }

    @Test fun changedContentIsNeverSkipped() {
        // New APK build or updated engine script → always re-copy.
        assertFalse(AutomationSnapshot.shouldSkipMirror(hashMatchesSaved = false, mirrorPresent = true))
        assertFalse(AutomationSnapshot.shouldSkipMirror(hashMatchesSaved = false, mirrorPresent = false))
    }
}
