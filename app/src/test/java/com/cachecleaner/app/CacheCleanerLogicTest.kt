package com.cachecleaner.app

import com.cachecleaner.app.accessibility.AttemptSignal
import com.cachecleaner.app.accessibility.AutomationPolicy
import com.cachecleaner.app.accessibility.NodeMatchers
import com.cachecleaner.app.accessibility.PackageOutcome
import com.cachecleaner.app.accessibility.Step
import com.cachecleaner.app.accessibility.nextStep
import com.cachecleaner.app.data.AutoWhitelist
import com.cachecleaner.app.data.RunningClassifier
import com.cachecleaner.app.ui.formatBytes
import com.cachecleaner.app.ui.formatRunMarker
import com.cachecleaner.app.ui.parseRunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the pure logic in the app:
 * dialog matching, automation policy, retry policy, run-marker
 * round-trips, byte formatting and the automatic safety whitelist.
 */
class CacheCleanerLogicTest {

    // ---------- NodeMatchers: dialog confirm ----------

    @Test
    fun confirmId_matchesDialogOkButton() {
        assertTrue(NodeMatchers.isConfirmId("com.android.settings:id/button1"))
        assertTrue(NodeMatchers.isConfirmId("android:id/button1"))
    }

    @Test
    fun confirmId_rejectsOtherButtons() {
        assertFalse(NodeMatchers.isConfirmId("com.android.settings:id/button2"))
        assertFalse(NodeMatchers.isConfirmId("android:id/clear_cache_btn_text"))
        assertFalse(NodeMatchers.isConfirmId(null))
        assertFalse(NodeMatchers.isConfirmId(""))
    }

    @Test
    fun confirmText_matchesOkLabels() {
        assertTrue(NodeMatchers.isConfirmText("OK"))
        assertTrue(NodeMatchers.isConfirmText("ok"))
        assertTrue(NodeMatchers.isConfirmText("Yes"))
        assertFalse(NodeMatchers.isConfirmText("Clear cache"))
        assertFalse(NodeMatchers.isConfirmText(null))
    }

    @Test
    fun confirmId_matchesFullSettingsIds() {
        // The dialog positive-button ids the engine searches for.
        for (id in listOf(
            "com.android.settings:id/button1",
            "android:id/button1"
        )) {
            assertTrue("expected $id to match", NodeMatchers.isConfirmId(id))
        }
    }

    // ---------- AutoWhitelist ----------

    @Test
    fun whitelist_coversCriticalSystemPackages() {
        val pkgs = AutoWhitelist.SYSTEM_PACKAGES
        assertTrue(pkgs.contains("com.android.systemui"))
        assertTrue(pkgs.contains("com.android.settings"))
        assertTrue(pkgs.contains("android"))
    }

    @Test
    fun whitelist_doesNotContainOrdinaryApps() {
        val pkgs = AutoWhitelist.SYSTEM_PACKAGES
        assertFalse(pkgs.contains("com.whatsapp"))
        assertFalse(pkgs.contains("com.instagram.android"))
    }

    @Test
    fun whitelist_hasExpectedSize() {
        assertEquals(56, AutoWhitelist.SYSTEM_PACKAGES.size)
    }

    // ---------- AutomationPolicy ----------

    @Test
    fun policy_packageWatchdog_isTenSeconds() {
        assertEquals(10000L, AutomationPolicy.PACKAGE_TIMEOUT_MS)
    }

    @Test
    fun policy_interDelay_normalIs1000_turboIs0() {
        assertEquals(1000L, AutomationPolicy.interDelayMs(false))
        assertEquals(0L, AutomationPolicy.interDelayMs(true))
    }

    @Test
    fun policy_preClickDelay_normalIs100_turboIs0() {
        assertEquals(100L, AutomationPolicy.preClickDelayMs(false))
        assertEquals(0L, AutomationPolicy.preClickDelayMs(true))
    }

    @Test
    fun policy_packageTimeout_normalIs10s_turboIs6s() {
        assertEquals(10000L, AutomationPolicy.packageTimeoutMs(false))
        assertEquals(6000L, AutomationPolicy.packageTimeoutMs(true))
    }

    @Test
    fun policy_dialogWatchdog_normalIs2500_turboIs600() {
        assertEquals(2500L, AutomationPolicy.dialogWatchdogMs(false))
        assertEquals(600L, AutomationPolicy.dialogWatchdogMs(true))
    }

    @Test
    fun policy_runEventTypes_matchReference4128() {
        // window-state-changed (32) | view-scrolled (4096)
        assertEquals(4128, AutomationPolicy.RUN_EVENT_TYPES)
    }

    @Test
    fun policy_withContentChanged_setsBit2048() {
        assertEquals(2048, AutomationPolicy.withContentChanged(0) and 2048)
        assertEquals(
            4128 + 2048,
            AutomationPolicy.withContentChanged(AutomationPolicy.RUN_EVENT_TYPES)
        )
    }

    @Test
    fun policy_withoutContentChanged_clearsBit2048() {
        val with = AutomationPolicy.withContentChanged(AutomationPolicy.RUN_EVENT_TYPES)
        assertEquals(
            AutomationPolicy.RUN_EVENT_TYPES,
            AutomationPolicy.withoutContentChanged(with)
        )
    }

    @Test
    fun policy_eventTypeToggle_roundTrip() {
        val base = AutomationPolicy.RUN_EVENT_TYPES
        assertEquals(base, AutomationPolicy.withoutContentChanged(AutomationPolicy.withContentChanged(base)))
    }

    // ---------- RunningClassifier (kept from proven logic) ----------

    private fun installedApp(
        packageName: String,
        enabled: Boolean = true,
        flags: Int = 0
    ) = RunningClassifier.InstalledApp(packageName, enabled, flags)

    @Test
    fun classifier_flagStopped_matchesReferenceBit() {
        // ApplicationInfo.FLAG_STOPPED == 1 << 21 (kept as a constant so the
        // classifier stays JVM-testable without android.jar).
        assertEquals(1 shl 21, RunningClassifier.FLAG_STOPPED)
    }

    @Test
    fun classifier_filterRunning_keepsEligibleInInstalledOrder() {
        val installed = listOf(
            installedApp("com.zed"),
            installedApp("com.abc")
        )
        val out = RunningClassifier.filterRunning(
            installed, emptySet(), emptySet()
        ) { true }
        assertEquals(listOf("com.zed", "com.abc"), out)
    }

    @Test
    fun classifier_filterRunning_dropsForceStoppedApps() {
        val stopped = RunningClassifier.FLAG_STOPPED
        val installed = listOf(
            installedApp("com.dead", flags = stopped),
            installedApp("com.alive")
        )
        val out = RunningClassifier.filterRunning(
            installed, emptySet(), emptySet()
        ) { true }
        assertEquals(listOf("com.alive"), out)
    }

    @Test
    fun classifier_filterRunning_dropsDisabledApps() {
        val installed = listOf(
            installedApp("com.off", enabled = false),
            installedApp("com.on")
        )
        val out = RunningClassifier.filterRunning(
            installed, emptySet(), emptySet()
        ) { true }
        assertEquals(listOf("com.on"), out)
    }

    @Test
    fun classifier_filterRunning_dropsNonLaunchable() {
        val installed = listOf(
            installedApp("com.noui"),
            installedApp("com.ui")
        )
        val out = RunningClassifier.filterRunning(
            installed, emptySet(), emptySet()
        ) { it == "com.ui" }
        assertEquals(listOf("com.ui"), out)
    }

    @Test
    fun classifier_filterRunning_dropsExclusions() {
        val installed = listOf(
            installedApp("com.me"),
            installedApp("com.you")
        )
        val out = RunningClassifier.filterRunning(
            installed, setOf("com.me"), emptySet()
        ) { true }
        assertEquals(listOf("com.you"), out)
    }

    @Test
    fun classifier_filterRunning_dropsSafetyList() {
        val installed = listOf(
            installedApp("com.android.systemui"),
            installedApp("com.you")
        )
        val out = RunningClassifier.filterRunning(
            installed, emptySet(), setOf("com.android.systemui")
        ) { true }
        assertEquals(listOf("com.you"), out)
    }

    @Test
    fun classifier_filterRunning_dropsEmptyPackageNames() {
        val installed = listOf(installedApp(""), installedApp("com.you"))
        val out = RunningClassifier.filterRunning(
            installed, emptySet(), emptySet()
        ) { true }
        assertEquals(listOf("com.you"), out)
    }

    @Test
    fun classifier_eventPackages_firstSeenOrderDeduped() {
        val out = RunningClassifier.eventPackages(
            listOf("com.b", "com.a", "com.b", "com.a", "com.c")
        ) { true }.toList()
        assertEquals(listOf("com.b", "com.a", "com.c"), out)
    }

    @Test
    fun classifier_eventPackages_dropsNonLaunchableAndEmpty() {
        val out = RunningClassifier.eventPackages(
            listOf("", "com.noui", "com.ui")
        ) { it == "com.ui" }.toList()
        assertEquals(listOf("com.ui"), out)
    }

    // ---------- nextStep (retry policy) ----------

    @Test
    fun nextStep_cacheCleared_alwaysCleaned() {
        assertEquals(
            Step.Terminal(PackageOutcome.CLEANED),
            nextStep(AttemptSignal.CACHE_CLEARED, false, false)
        )
        assertEquals(
            Step.Terminal(PackageOutcome.CLEANED),
            nextStep(AttemptSignal.CACHE_CLEARED, true, true)
        )
    }

    @Test
    fun nextStep_missingOrTimeout_retriesOnceThenFails() {
        for (signal in listOf(
            AttemptSignal.STORAGE_MISSING,
            AttemptSignal.CLEAR_CACHE_MISSING,
            AttemptSignal.TIMEOUT
        )) {
            assertEquals(Step.Retry, nextStep(signal, false, false))
            assertEquals(
                Step.Terminal(PackageOutcome.FAILED),
                nextStep(signal, true, false)
            )
            // Same on MIUI.
            assertEquals(Step.Retry, nextStep(signal, false, true))
            assertEquals(
                Step.Terminal(PackageOutcome.FAILED),
                nextStep(signal, true, true)
            )
        }
    }

    @Test
    fun nextStep_skippedClean_alwaysSkippedNoRetry() {
        // A disabled Clear-cache button means "already clean": terminal
        // SKIPPED whether or not a retry already happened.
        assertEquals(
            Step.Terminal(PackageOutcome.SKIPPED),
            nextStep(AttemptSignal.SKIPPED_CLEAN, false, false)
        )
        assertEquals(
            Step.Terminal(PackageOutcome.SKIPPED),
            nextStep(AttemptSignal.SKIPPED_CLEAN, true, true)
        )
    }

    @Test
    fun nextStep_serviceLost_mapsToRetry() {
        // SERVICE_LOST is normally handled inline by the engine (wait for
        // rebind); the policy mapping just keeps the when exhaustive.
        assertEquals(Step.Retry, nextStep(AttemptSignal.SERVICE_LOST, false, false))
        assertEquals(Step.Retry, nextStep(AttemptSignal.SERVICE_LOST, true, true))
    }

    // ---------- run marker ----------

    @Test
    fun marker_roundTrip() {
        val marker = formatRunMarker(3, 1, 2, 5L * 1_048_576L, 42_000L, true)
        assertTrue(marker.startsWith("run finished: "))
        val s = parseRunSummary(marker)!!
        assertEquals(3, s.cleaned)
        assertEquals(1, s.failed)
        assertEquals(2, s.skipped)
        assertEquals(5L * 1_048_576L, s.cacheFreedBytes)
        assertEquals(42_000L, s.durationMs)
        assertTrue(s.turbo)
    }

    @Test
    fun marker_missingOptionalFields_defaultToZero() {
        val s = parseRunSummary("run finished: cleaned=2 failed=0 skipped=1")!!
        assertEquals(2, s.cleaned)
        assertEquals(0L, s.cacheFreedBytes)
        assertEquals(0L, s.durationMs)
        assertFalse(s.turbo)
    }

    @Test
    fun marker_malformed_returnsNull() {
        assertNull(parseRunSummary("hello world"))
        assertNull(parseRunSummary("run finished: cleaned=x failed=0 skipped=0"))
    }

    // ---------- formatBytes ----------

    @Test
    fun formatBytes_scales() {
        assertEquals("1.50 GB", formatBytes((1.5 * 1_073_741_824).toLong()))
        assertEquals("42 MB", formatBytes(42L * 1_048_576L))
        assertEquals("7 KB", formatBytes(7L * 1024L))
        assertEquals("0 KB", formatBytes(0L))
        // Never negative.
        assertEquals("0 KB", formatBytes(-100L))
    }
}
