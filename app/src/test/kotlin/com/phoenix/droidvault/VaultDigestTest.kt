package com.phoenix.droidvault

import com.phoenix.droidvault.utils.VaultDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sidecar format, which is an interface with the Rust engine.
 *
 * The engine's `checksum` module writes and parses exactly these lines, and the
 * file is meant to be checkable by tools that know nothing about Droid Vault:
 *
 * ```sh
 * sha256sum -c Messages.shift.sha256
 * ```
 *
 * A writer and a reader that disagree about the same record is how export came
 * to write `configurations` and import to read `configs`, with a count that was
 * permanently zero and a test that passed because its fixture was written from
 * the same wrong assumption. These tests pin the format itself.
 */
class VaultDigestTest {

    private val digest = "6be9a24d921f0f6724217ebab81b29b0c7c6e6dee6fbc60d69814c3c308f2b4d"

    @Test
    fun `the line is what sha256sum writes and parses`() {
        // Two spaces, the BASE NAME, one trailing newline: coreutils' own shape.
        assertEquals("$digest  Messages.shift\n", VaultDigest.sidecarLine(digest, "Messages.shift"))
    }

    @Test
    fun `the sidecar sits beside its archive`() {
        assertEquals(
            "/storage/emulated/0/DroidVault/Device/Messages.shift.sha256",
            VaultDigest.sidecarFor("/storage/emulated/0/DroidVault/Device/Messages.shift")
        )
    }

    @Test
    fun `a name that cannot be expressed on one line is refused`() {
        // An escaped or truncated name would leave a sidecar vouching for a
        // different file — worse than no record at all.
        assertNull(VaultDigest.sidecarLine(digest, "odd\nname.shift"))
        assertNull(VaultDigest.sidecarLine(digest, "odd\rname.shift"))
        assertNull(VaultDigest.sidecarLine(digest, ""))
    }

    @Test
    fun `only a real digest is ever written`() {
        // Whatever `sha256sum` printed — an error message, a partial line, the
        // output of a missing tool — must never become a record.
        assertNull(VaultDigest.sidecarLine("not a digest", "Messages.shift"))
        assertNull(VaultDigest.sidecarLine("", "Messages.shift"))
        assertNull(VaultDigest.sidecarLine(digest.dropLast(1), "Messages.shift"))
        assertNull(VaultDigest.sidecarLine(digest + "a", "Messages.shift"))
        assertNull(VaultDigest.sidecarLine(digest.replace('6', 'g'), "Messages.shift"))
        assertTrue(VaultDigest.isDigest(digest))
        // Uppercase hex is the same digest; the engine lowercases it on read.
        assertTrue(VaultDigest.isDigest(digest.uppercase()))
        assertFalse(VaultDigest.isDigest("sha256sum: no such file or directory"))
    }
}
