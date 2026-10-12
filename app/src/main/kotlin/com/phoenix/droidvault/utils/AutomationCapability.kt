package com.phoenix.droidvault.utils

import com.phoenix.droidvault.models.BackupComponent
import com.phoenix.droidvault.models.Capability
import com.phoenix.droidvault.models.CapabilitySet

/**
 * Capability resolver: intent (planner) separated from mechanism (libsu).
 * Takes a plain boolean so the planner stays unit-testable without root.
 *
 * Engine truth: every backup component executes through the root shell backend,
 * and this app requires root globally (NoRoot screen). So NORMAL mode can
 * process nothing — reported explicitly, never silently downgraded.
 */
object AutomationCapability {

    fun resolve(
        hasRoot: Boolean,
        allowPermRestore: Boolean,
        allowSsaidRestore: Boolean
    ): CapabilitySet {
        if (!hasRoot) {
            return CapabilitySet(Capability.NORMAL, emptySet())
        }
        val available = mutableSetOf(
            BackupComponent.APK,
            BackupComponent.PRIVATE_DATA,
            BackupComponent.MEDIA
        )
        // Consent gates are capabilities too (mirrors MigratorManager effectiveComps).
        if (allowPermRestore) available.add(BackupComponent.PERMISSIONS)
        if (allowSsaidRestore) available.add(BackupComponent.ANDROID_ID)
        return CapabilitySet(Capability.ROOT, available)
    }

    /**
     * Why a component is unavailable: root vs explicit consent vs unsupported.
     * Used so preview UI can point at Settings → Safety Consents instead of a
     * bare "unavailable".
     */
    fun reasonFor(
        component: BackupComponent,
        hasRoot: Boolean,
        allowPermRestore: Boolean,
        allowSsaidRestore: Boolean
    ): String = when {
        !hasRoot -> "needs root"
        component == BackupComponent.PERMISSIONS && !allowPermRestore ->
            "needs consent (Safety Consents → Restore permissions)"
        component == BackupComponent.ANDROID_ID && !allowSsaidRestore ->
            "needs consent (Safety Consents → Restore Android ID)"
        else -> "unsupported on this device"
    }
}
