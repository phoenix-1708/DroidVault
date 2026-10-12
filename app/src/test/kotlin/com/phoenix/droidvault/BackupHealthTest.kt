package com.phoenix.droidvault

import com.phoenix.droidvault.models.AppVerification
import com.phoenix.droidvault.models.BackupHealth
import com.phoenix.droidvault.models.HealthSeverity
import com.phoenix.droidvault.models.VerificationReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup-health line on the dashboard.
 *
 * The whole value of this is refusing to say "healthy" when nothing has been
 * checked. A null result, a cancelled run and an engine that never started are
 * all "unknown", and each previously had somewhere to be quietly rendered as a
 * pass. These tests pin that, plus the damaged case actually naming the apps —
 * the same complaint as the trash naming nothing (X-03).
 */
class BackupHealthTest {

    private fun app(pkg: String, ok: Boolean) = AppVerification(
        label = pkg.substringAfterLast('.').uppercase(),
        packageName = pkg,
        partsChecked = 5,
        corruptParts = if (ok) emptyList() else listOf("Data.shift"),
        bytesReadable = 1024L
    )

    private fun report(
        apps: List<AppVerification> = listOf(app("com.a.one", true)),
        cancelled: Boolean = false,
        error: String = ""
    ) = VerificationReport(
        apps = apps,
        total = apps.size,
        okCount = apps.count { it.ok },
        corruptCount = apps.count { !it.ok },
        bytesReadable = 1024L,
        cancelled = cancelled,
        error = error
    )

    // --- unknown must never read as healthy --------------------------------

    @Test
    fun `no report at all is unknown and offers a check`() {
        val h = BackupHealth.from(null)
        assertEquals(HealthSeverity.UNKNOWN, h.severity)
        assertTrue(h.canVerify)
        assertFalse("an unknown health must not offer 'review'", h.canReview)
    }

    @Test
    fun `an empty vault is unknown, not healthy`() {
        // total == 0 is not a passing grade; `conclusive` already encodes that.
        val h = BackupHealth.from(VerificationReport())
        assertEquals(HealthSeverity.UNKNOWN, h.severity)
    }

    @Test
    fun `a cancelled check is unknown, not healthy`() {
        val h = BackupHealth.from(report(cancelled = true))
        assertEquals(HealthSeverity.UNKNOWN, h.severity)
    }

    @Test
    fun `an errored check says so and offers a retry`() {
        val h = BackupHealth.from(report(error = "engine unavailable"))
        assertEquals(HealthSeverity.ERROR, h.severity)
        assertTrue(h.detail!!.contains("engine unavailable"))
        assertTrue(h.canVerify)
    }

    // --- healthy ------------------------------------------------------------

    @Test
    fun `a conclusive clean report is healthy`() {
        val h = BackupHealth.from(report())
        assertEquals(HealthSeverity.HEALTHY, h.severity)
        assertTrue(h.title.contains("1"))
        assertFalse("nothing to review when everything is fine", h.canReview)
    }

    @Test
    fun `healthy states the count it checked`() {
        val h = BackupHealth.from(report(listOf(app("com.a.one", true), app("com.b.two", true))))
        assertEquals(HealthSeverity.HEALTHY, h.severity)
        assertTrue("must state the scope: ${h.title}", h.title.contains("2"))
    }

    // --- damaged ------------------------------------------------------------

    @Test
    fun `damage is reported as damaged and can be reviewed`() {
        val h = BackupHealth.from(
            report(listOf(app("com.a.one", true), app("com.b.two", false)))
        )
        assertEquals(HealthSeverity.DAMAGED, h.severity)
        assertTrue(h.canReview)
        assertTrue("must state how many: ${h.title}", h.title.contains("1 of 2"))
    }

    @Test
    fun `damaged names the apps`() {
        val h = BackupHealth.from(report(listOf(app("com.b.two", false))))
        assertTrue("must name it: ${h.detail}", h.detail!!.contains("TWO"))
    }

    @Test
    fun `a long damage list is summarised rather than dumped`() {
        val many = (1..6).map { app("com.p$it.bad", false) }
        val h = BackupHealth.from(report(many))
        assertEquals(HealthSeverity.DAMAGED, h.severity)
        assertTrue("should cap the list: ${h.detail}", h.detail!!.contains("and 3 more"))
    }

    @Test
    fun `an app with no label falls back to its package name`() {
        val nameless = AppVerification(
            label = "",
            packageName = "com.weird.pkg",
            partsChecked = 1,
            corruptParts = listOf("App.shift"),
            bytesReadable = 1L
        )
        val h = BackupHealth.from(report(listOf(nameless)))
        assertTrue("must not show a blank name", h.detail!!.contains("com.weird.pkg"))
    }
}