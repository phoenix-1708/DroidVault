package com.phoenix.droidvault.utils

import android.content.Context
import android.net.Uri
import com.phoenix.droidvault.models.FlashAction
import com.phoenix.droidvault.models.FlashZip

/**
 * Flash domain ops. Thin validated facade over FlashManager + shell ORS.
 * MainViewModel delegates here; UI keeps calling MainViewModel.
 */
object FlashOps {
    suspend fun processZips(
        uris: List<Uri>,
        current: List<FlashZip>,
        append: Boolean,
        context: Context
    ): List<FlashZip> = FlashManager.processZips(uris, current, append, context)

    fun buildActions(
        zips: List<FlashZip>,
        wipes: Set<String>,
        formatData: Boolean
    ): List<FlashAction> = buildList {
        val allowedWipes = setOf("dalvik", "cache", "data", "metadata", "system")
        wipes.filter { it in allowedWipes }.takeIf { it.isNotEmpty() }?.let {
            add(FlashAction.Wipe(it.toSet()))
        }
        if (formatData) add(FlashAction.FormatData)
        zips.forEach { add(FlashAction.InstallZip(it)) }
    }

    fun generateOrs(actions: List<FlashAction>, rebootOption: String) {
        val safeReboot = when (rebootOption) {
            "system", "recovery", "bootloader", "none" -> rebootOption
            else -> "none"
        }
        FlashManager.generateOrsAndProceed(actions, safeReboot)
    }
}
