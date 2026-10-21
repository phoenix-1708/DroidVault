package com.phoenix.droidvault.utils

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import java.io.File

/**
 * The intent that opens the SAF folder picker **where the user wants to be**.
 *
 * ## Why this exists
 *
 * The picker used to be launched with no hint at all, so DocumentsUI opened
 * wherever the system last left it — on the reference device, the storage
 * root — and the user had to scroll down to `DroidVault` every single time to
 * pick the folder their backups already live in.
 *
 * `DocumentsContract.EXTRA_INITIAL_URI` is the platform's answer, and it is a
 * HINT: Android's documentation is explicit that it is where the picker should
 * be *displayed* when it loads, that it is not a restriction, and that a ROM
 * is free to ignore it. So this only ever changes where the listing opens; the
 * worst case is the behaviour the picker has today, never a dead end. The
 * codebase already relies on that (the transfer browser's starting folder).
 *
 * ## Why a hand-built intent
 *
 * `ActivityResultContracts.OpenDocumentTree()` cannot carry an extra: its
 * input is ignored, and the contract builds the intent itself. So the picker
 * is launched as a plain `ACTION_OPEN_DOCUMENT_TREE` with the extra attached.
 *
 * ## The hint is the vault's own folder
 *
 * The folder the backups are in — which is the answer to "where should this
 * open" for anyone who already has a vault, and is the default
 * `/storage/emulated/0/DroidVault` for anyone who does not. A hint that names
 * a folder which is not there is ignored by the platform, and the existence
 * check here means the app does not even offer one.
 */
object VaultFolderPicker {

    /** The default vault location, for a phone that has never moved its vault. */
    const val DEFAULT_VAULT_PATH = "/storage/emulated/0/DroidVault"

    /**
     * The intent for the folder picker, opening at [vaultPath] when it is
     * there and at the default when it is not.
     */
    fun intent(context: Context, vaultPath: String?): Intent {
        val base = android.os.Environment.getExternalStorageDirectory()?.absolutePath
        val hint = listOfNotNull(vaultPath, DEFAULT_VAULT_PATH)
            .firstOrNull { it.isNotBlank() && File(it).exists() }
        val initial = if (base != null && hint != null) {
            BackupFolderStore.documentUriForPath(hint, base)
        } else {
            null
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            )
            if (initial != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            }
        }
    }
}
