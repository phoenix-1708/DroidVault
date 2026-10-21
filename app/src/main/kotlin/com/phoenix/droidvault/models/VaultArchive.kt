package com.phoenix.droidvault.models

/**
 * The vault's archive vocabulary: what an archive is called, and how an
 * archive written under the old name is still found.
 *
 * ## Why the extension moved
 *
 * A vault archive used to be `*.shift`, a name inherited from the project this
 * app was forked from. It is now `*.vault` — the archive belongs to the
 * vault, and the vault is called Droid Vault.
 *
 * ## The rule that matters: an old archive is still an archive
 *
 * The extension is a **wire format**, not a label. Every archive already on a
 * user's card was written as `*.shift`, and an app that stopped looking for
 * those would report an empty vault and back up nothing — the user's backups
 * would be invisible rather than missing.
 *
 * So the name is RESOLVED, never assumed:
 *
 *  - a WRITE always uses the new name;
 *  - a READ resolves the new name first and the old one second;
 *  - and a one-time migration renames the archives already on disk, so the
 *    vault never holds two copies of the same thing.
 *
 * The read fallback is what makes the migration safe: if it does not run (no
 * root yet, a card that cannot be written), the old archives are still found,
 * still restored, and still verified. The migration is a tidy-up, not a
 * dependency.
 */
object VaultArchive {

    /** The extension a vault archive carries. */
    const val EXT = "vault"

    /** The extension the app used before, and still reads. */
    const val LEGACY_EXT = "shift"

    /** The file name an archive is written as. */
    fun name(base: String): String = "$base.$EXT"

    /** The file names an archive may have, newest first. */
    fun names(base: String): List<String> = listOf("$base.$EXT", "$base.$LEGACY_EXT")

    /**
     * Is there an archive for this base in the vault index?
     *
     * The index is the app's map of every file under the vault, so this
     * answers "is this app's data backed up" for either extension.
     */
    fun present(index: Map<String, Long>, dir: String, base: String): Boolean =
        names(base).any { index.containsKey("$dir/$it") }

    /**
     * The name the archive actually has, newest first — `null` when there is
     * none. The caller reads THAT file, so an archive written under the old
     * name is restored and verified exactly as a new one is.
     */
    fun existing(index: Map<String, Long>, dir: String, base: String): String? =
        names(base).firstOrNull { index.containsKey("$dir/$it") }

    /**
     * The archive that carries a device-data item.
     *
     * The base name lives on the item itself ([DeviceDataItem.archiveBase]),
     * so there is one table of them and not two.
     */
    fun deviceBase(item: DeviceDataItem): String = item.archiveBase

    /** The archive file name for a device-data item. */
    fun deviceName(item: DeviceDataItem): String = name(item.archiveBase)

    /**
     * The device-data archive that is actually on disk, newest first.
     *
     * The device archives live directly in the vault's `Device/` folder, and
     * they are the only copy of the user's messages, calls and contacts on the
     * device — so they are resolved, never assumed.
     */
    fun existingDevice(index: Map<String, Long>, deviceDir: String, item: DeviceDataItem): String? =
        existing(index, deviceDir, item.archiveBase)

    /**
     * The item an archive file belongs to, under either extension.
     *
     * The verification reports the file it actually read, so a legacy-named
     * archive arrives here with the old name — and the item it belongs to is
     * the same either way. A report that lost its item would put a confident
     * label on the wrong file.
     */
    fun deviceItemFor(name: String): DeviceDataItem? =
        DeviceDataItem.entries.firstOrNull { names(it.archiveBase).contains(name) }

    /** The suffixes that mark a file as a vault archive, under either extension. */
    fun isArchive(name: String): Boolean =
        name.endsWith(".$EXT") || name.endsWith(".$LEGACY_EXT")
}
