package com.cachecleaner.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.cachecleaner.app.data.CacheStats
import com.cachecleaner.app.data.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Drives one cache-clean run: for every target package it opens the system
 * "App info" screen and clicks Storage -> "Clear cache" (-> dialog OK),
 * using the [CacheAccessService] accessibility events as its eyes.
 *
 * The automation mirrors the publicly observable behavior of the reference
 * app (XCleaner 2.6, `com.anysoft.zerocleaner`, reverse-engineered for
 * interoperability). Android offers no API for clearing another app's
 * cache, so the reference drives the Settings UI:
 *
 * - Only window-state-changed (32) + view-scrolled (4096) +
 *   window-content-changed (2048) events are monitored. The full mask is
 *   declared statically in accessibility_service_config.xml and NEVER
 *   changed at runtime: calling setServiceInfo() at runtime makes the
 *   system unbind/rebind the service, which killed automation within
 *   0.1-0.6 s on the user's vivo (log analysis 2026-09-29). Events are
 *   ignored while no run is active.
 * - The App info screen is opened with
 *   `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` ("package:<pkg>").
 * - Watched hosts: `com.android.settings` and (on MIUI)
 *   `com.miui.securitycenter`.
 * - "Storage" is matched by the *localized* Settings strings for the
 *   resource names `app_manager_menu_clear_data` (MIUI),
 *   `storage_settings_for_app` / `storage_settings` / `storage_use`
 *   (AOSP), resolved from the Settings package that raised the event;
 *   English "Storage" / "Storage & cache" are fallbacks. If the row is not
 *   visible the App info list is scrolled forward (up to 3 scrolls), like
 *   the reference's scroll handler.
 * - "Clear cache" is matched by the localized Settings strings for
 *   `clear_cache_btn_text` / `app_manager_clear_cache` (English fallback
 *   "Clear cache"). Some OEM App info screens show the button directly;
 *   then the Storage step is skipped, like the reference's
 *   SKIP_NEXT_STEP.
 * - If a confirmation dialog appears, its positive button is clicked via
 *   the view ids `com.android.settings:id/button1` /
 *   `android:id/button1`, falling back to the localized system OK / Yes
 *   strings.
 * - MIUI is detected via the `ro.miui.ui.version.name` system property,
 *   like the reference (and like AppSleep's `u.w()`).
 * - Per-package watchdog: 10 seconds normally, 6 seconds in turbo mode.
 *   One reopen-and-retry per package.
 * - Inter-package delay: 1000 ms normally, 0 ms in turbo mode.
 * - Pre-click delay: 100 ms normally, 0 ms in turbo mode.
 * - Confirmation-dialog watchdog after the clear click: 2500 ms normally,
 *   600 ms in turbo mode.
 * - All clicks are fire-and-forget like the reference: the click result is
 *   not checked and there is no post-click verification. Once "Clear
 *   cache" is clicked the attempt counts as cleaned.
 * - Freed space is measured with StorageStatsManager (the reference's
 *   `queryStatsForUid(...).getCacheBytes()`): cache bytes per package are
 *   read before the attempt, and freed = sum over cleaned packages of
 *   max(0, before - after). Best-effort; 0 without usage access.
 *
 * Kept deliberately from the proven Stop Apps engine:
 * - Never clicks a node after recycling it, and always clicks the
 *   *actionable* (clickable) ancestor rather than a text child.
 * - Window ids already handled are skipped (deduplication).
 * - The run refuses to start when the accessibility service is enabled but
 *   not connected, instead of hanging on a dead run.
 */
class CacheClearEngine(private val appContext: Context) {

    interface Listener {
        fun onLog(line: String)
        fun onProgress(done: Int, total: Int, currentPackage: String?)
        fun onFinished(result: RunResult)
    }

    data class RunResult(
        val cleaned: List<String>,
        val failed: List<String>,
        val skipped: List<String>,
        /**
         * Sum over cleaned packages of max(0, cacheBefore - cacheAfter),
         * in bytes. Best-effort; 0 when usage access is missing.
         */
        val cacheFreedBytes: Long = 0L,
        /** Wall-clock time the run took, in milliseconds. */
        val durationMs: Long = 0L
    )

    private enum class Stage { IDLE, WAIT_APP_INFO, WAIT_STORAGE, WAIT_DIALOG }

    @Volatile
    var running = false
        private set

    @Volatile
    var currentPackage: String? = null
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: kotlinx.coroutines.Job? = null
    private var listener: Listener? = null
    private var turbo = false

    private var stage = Stage.IDLE
    private var targetPkg = ""
    private var attemptSignal: CompletableDeferred<AttemptSignal>? = null
    private var clearCacheClicked = false
    private var scrollTries = 0
    /**
     * Completed by [onServiceRebound] when the accessibility service comes
     * back after [onServiceUnbound] parked the current attempt with
     * [AttemptSignal.SERVICE_LOST]. Null when no rebind is being awaited.
     */
    @Volatile
    private var rebindSignal: CompletableDeferred<Unit>? = null

    private val handledWindowIds = mutableSetOf<Int>()
    private val settingsTextCache = mutableMapOf<String, Set<String>>()
    private val cacheStats = CacheStats(appContext)
    /**
     * Watchdog that bounds how long we wait for the Clear-cache button to
     * appear on the Storage screen. The screen populates asynchronously,
     * so the button is often absent from the first window event.
     */
    private var storageWatchdog: kotlinx.coroutines.Job? = null
    private var storageWaitLogged = false
    /**
     * Watchdog for "the Storage click succeeded but the Storage screen
     * never appeared". Phone logs show this happening (click result=true,
     * then silence): without a bound the engine sits in WAIT_STORAGE until
     * the 30 s package timeout and the user has to intervene manually.
     */
    private var storageOpenWatchdog: kotlinx.coroutines.Job? = null
    private var storageClickTime = 0L
    private var storageScreenSeen = false
    // MIUI detection mirrors the reference: the
    // `ro.miui.ui.version.name` system property, not the manufacturer.
    private val miui: Boolean = detectMiui()

    private fun detectMiui(): Boolean {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val method = cls.getMethod("get", String::class.java)
            val raw = method.invoke(cls, "ro.miui.ui.version.name")
            val value = raw as? String
            value != null && value.isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ run

    /**
     * Starts a run. Returns false (and reports the failure through
     * [listener]) when the accessibility service is not connected, so the
     * caller can tell the user what to do instead of hanging on a dead run.
     */
    fun start(packages: List<String>, turbo: Boolean, listener: Listener): Boolean {
        if (running) return true
        val service = CacheAccessService.instance
        FileLogger.log(
            "engine", "start requested",
            data = mapOf(
                "packages" to packages.size.toString(),
                "turbo" to turbo.toString(),
                "service_connected" to (service != null).toString(),
                "miui" to miui.toString()
            )
        )
        if (service == null) {
            listener.onLog("Accessibility service is enabled but not connected yet. Please wait a moment and try again.")
            listener.onFinished(RunResult(emptyList(), packages.toList(), emptyList()))
            return false
        }
        this.listener = listener
        this.turbo = turbo
        running = true
        handledWindowIds.clear()
        settingsTextCache.clear()
        stage = Stage.IDLE
        val queue = packages.toList()
        job = scope.launch {
            try {
                runQueue(queue)
            } finally {
                finish()
            }
        }
        return true
    }

    fun cancel() {
        running = false
        try {
            job?.cancel()
        } catch (_: Exception) {
        }
    }

    private suspend fun runQueue(queue: List<String>) {
        val t0 = System.currentTimeMillis()
        val cleaned = mutableListOf<String>()
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val cacheBefore = mutableMapOf<String, Long>()
        for ((index, pkg) in queue.withIndex()) {
            if (!running) break
            targetPkg = pkg
            currentPackage = pkg
            listener?.onProgress(index, queue.size, pkg)
            listener?.onLog("# ${index + 1}/${queue.size} ${appLabel(pkg)}")
            cacheBefore[pkg] = withContext(Dispatchers.IO) { cacheStats.cacheBytes(pkg) }
            when (val outcome = cleanOnePackage(pkg)) {
                PackageOutcome.CLEANED -> {
                    cleaned += pkg
                    listener?.onLog("Cache cleared for ${appLabel(pkg)}")
                }
                PackageOutcome.SKIPPED -> {
                    skipped += pkg
                    listener?.onLog("Skipped ${appLabel(pkg)}")
                }
                PackageOutcome.FAILED -> {
                    failed += pkg
                    listener?.onLog("Could not clear cache for ${appLabel(pkg)}")
                }
            }
            currentPackage = null
            if (running && index < queue.size - 1) {
                val delayMs = AutomationPolicy.interDelayMs(turbo)
                FileLogger.log("engine", "inter-package delay ${delayMs}ms before ${queue[index + 1]}")
                delay(delayMs)
            }
        }
        listener?.onProgress(queue.size, queue.size, null)
        // Freed space: per cleaned package, before minus after (clamped).
        var freed = 0L
        if (cleaned.isNotEmpty()) {
            val after = withContext(Dispatchers.IO) {
                cleaned.associateWith { cacheStats.cacheBytes(it) }
            }
            for (pkg in cleaned) {
                val delta = (cacheBefore[pkg] ?: 0L) - (after[pkg] ?: 0L)
                if (delta > 0) freed += delta
            }
        }
        val durationMs = System.currentTimeMillis() - t0
        FileLogger.log(
            "engine", "run finished",
            data = mapOf(
                "cache_freed_mb" to (freed / 1_048_576L).toString(),
                "duration_s" to (durationMs / 1000L).toString()
            )
        )
        listener?.onFinished(RunResult(cleaned, failed, skipped, freed, durationMs))
    }

    private fun finish() {
        running = false
        currentPackage = null
        stage = Stage.IDLE
        attemptSignal = null
        rebindSignal = null
        storageWatchdog?.cancel()
        storageWatchdog = null
        storageOpenWatchdog?.cancel()
        storageOpenWatchdog = null
    }

    /**
     * Called by [CacheAccessService.onUnbind] on the main thread. If a
     * package attempt is in flight, park it with [AttemptSignal.SERVICE_LOST]
     * so [cleanOnePackage] can wait for the rebind instead of burning the
     * per-package watchdog on a dead service (vivo battery management tears
     * the service down ~0.1 s after App info opens).
     */
    fun onServiceUnbound() {
        val s = attemptSignal
        if (!running || s == null || s.isCompleted) return
        FileLogger.log(
            "engine", "service unbound mid-run, waiting for rebind",
            data = mapOf("pkg" to targetPkg)
        )
        listener?.onLog("  [dbg] service unbound mid-run, waiting for rebind")
        rebindSignal = CompletableDeferred()
        s.complete(AttemptSignal.SERVICE_LOST)
    }

    /** Called by [CacheAccessService.onServiceConnected] on the main thread. */
    fun onServiceRebound() {
        val rs = rebindSignal
        if (rs != null && !rs.isCompleted) {
            FileLogger.log("engine", "service rebound")
            rs.complete(Unit)
        }
    }

    // ------------------------------------------------------------ one package

    private suspend fun cleanOnePackage(pkg: String): PackageOutcome {
        var retried = false
        while (true) {
            if (!running) return PackageOutcome.FAILED
            stage = Stage.WAIT_APP_INFO
            clearCacheClicked = false
            scrollTries = 0
            storageScreenSeen = false
            storageClickTime = 0L
            storageOpenWatchdog?.cancel()
            storageOpenWatchdog = null
            attemptSignal = CompletableDeferred()
            openAppDetails(pkg)
            val signal = try {
                withTimeout(AutomationPolicy.packageTimeoutMs(turbo)) {
                    attemptSignal!!.await()
                }
            } catch (_: TimeoutCancellationException) {
                AttemptSignal.TIMEOUT
            } catch (_: CancellationException) {
                return PackageOutcome.FAILED
            }
            FileLogger.log(
                "engine", "attempt signal for $pkg",
                data = mapOf(
                    "signal" to signal.toString(),
                    "retried" to retried.toString()
                )
            )
            if (signal == AttemptSignal.SERVICE_LOST) {
                // The accessibility service was torn down mid-attempt.
                // Wait for the rebind (vivo can take up to ~60 s), then
                // retry this package from a clean App info open.
                val rs = rebindSignal
                val waitStart = SystemClock.uptimeMillis()
                val rebound = if (rs == null) {
                    false
                } else try {
                    withTimeout(REBIND_WAIT_MS) { rs.await() }
                    true
                } catch (_: TimeoutCancellationException) {
                    false
                } catch (e: CancellationException) {
                    // Run was cancelled while waiting — propagate, don't
                    // silently fail the package.
                    throw e
                } catch (_: Exception) {
                    false
                }
                val waitedMs = SystemClock.uptimeMillis() - waitStart
                rebindSignal = null
                if (rebound) {
                    listener?.onLog("  [dbg] service rebound after ${waitedMs}ms, retrying ${appLabel(pkg)}")
                    FileLogger.log(
                        "engine", "service rebound, retrying package",
                        data = mapOf("pkg" to pkg, "waited_ms" to waitedMs.toString())
                    )
                    retried = true
                    handledWindowIds.clear()
                    continue
                }
                listener?.onLog("  [dbg] service did not rebound in ${waitedMs}ms, failing ${appLabel(pkg)}")
                FileLogger.log(
                    "engine", "service did not rebound, failing package",
                    data = mapOf("pkg" to pkg, "waited_ms" to waitedMs.toString())
                )
                return PackageOutcome.FAILED
            }
            when (val step = nextStep(signal, retried, miui)) {
                is Step.Terminal -> {
                    if (step.outcome != PackageOutcome.CLEANED) {
                        listener?.onLog("  [dbg] giving up on ${appLabel(pkg)} (signal=$signal)")
                    }
                    FileLogger.log(
                        "engine", "package terminal",
                        data = mapOf(
                            "pkg" to pkg,
                            "outcome" to step.outcome.toString(),
                            "signal" to signal.toString()
                        )
                    )
                    return step.outcome
                }
                Step.Retry -> {
                    listener?.onLog("  [dbg] retrying ${appLabel(pkg)} (signal=$signal)")
                    retried = true
                    handledWindowIds.clear()
                }
            }
        }
    }

    private fun openAppDetails(pkg: String) {
        // Flags mirror the reference exactly: NEW_TASK | CLEAR_TOP |
        // EXCLUDE_FROM_RECENTS | NO_HISTORY (1417707520). CLEAR_TOP is
        // essential: without it, consecutive opens for different packages
        // can reuse a stale App info screen.
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg")
        ).setFlags(1417707520)
        // Mid-run our app is in the background (a Settings screen is
        // foreground). On Android 10+ a background app cannot start
        // activities; opening through the system-bound accessibility
        // service does not have this problem.
        val service = CacheAccessService.instance
        var usedService = false
        try {
            if (service != null) {
                service.startActivityForAutomation(intent)
                usedService = true
            } else {
                appContext.startActivity(intent)
            }
            FileLogger.log(
                "engine", "App info opened for $pkg",
                data = mapOf("via_a11y_service" to usedService.toString())
            )
        } catch (e: Exception) {
            FileLogger.logException("engine", "openAppDetails($pkg)", e)
            attemptSignal?.complete(AttemptSignal.TIMEOUT)
        }
    }

    // ----------------------------------------------------------------- events

    /**
     * Called on the main thread by [CacheAccessService] for every
     * accessibility event.
     *
     * Mirrors the reference dispatcher:
     * - the tree is ALWAYS `event.source` (never `rootInActiveWindow`);
     * - the source must be a container (`childCount != 0`) with a valid
     *   window id;
     * - windows already handled (a button was found and clicked in them)
     *   are skipped via [handledWindowIds].
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!running) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val eventPkg = event.packageName?.toString() ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val cls = try { event.className?.toString() } catch (_: Exception) { null }
            FileLogger.log(
                "engine", "window-state-changed",
                data = mapOf(
                    "pkg" to eventPkg,
                    "class" to (cls ?: "?"),
                    "stage" to stage.toString()
                )
            )
        }
        if (!isSettingsHost(eventPkg)) return
        val source: AccessibilityNodeInfo = try {
            event.source
        } catch (_: Exception) {
            null
        } ?: return
        try {
            if (source.childCount == 0) return
            val winId = source.windowId
            if (winId <= 0) return
            if (winId in handledWindowIds) return
            when (stage) {
                Stage.WAIT_APP_INFO -> handleAppInfoWindow(source, eventPkg)
                Stage.WAIT_STORAGE -> handleStorageWindow(source, eventPkg)
                Stage.WAIT_DIALOG -> handleDialogWindow(source)
                Stage.IDLE -> Unit
            }
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------- app-info stage

    private fun handleAppInfoWindow(root: AccessibilityNodeInfo, eventPkg: String) {
        if (stage != Stage.WAIT_APP_INFO) return
        // Some OEM App info screens show "Clear cache" directly: click it
        // and skip the Storage step (reference SKIP_NEXT_STEP).
        val direct = findClearCacheButton(root, eventPkg)
        if (direct != null) {
            listener?.onLog("  [dbg] Clear-cache button found directly on App info, clicking")
            markHandled(direct)
            clickClearCache(direct)
            return
        }
        val storage = findStorageRow(root, eventPkg)
        if (storage == null) {
            // Not visible yet: scroll the App info list like the
            // reference's scroll handler, then give up after MAX_SCROLLS.
            if (scrollTries < MAX_SCROLLS && tryScrollForward(root)) {
                scrollTries++
                listener?.onLog("  [dbg] Storage row not visible, scrolling ($scrollTries/$MAX_SCROLLS)")
                return
            }
            // Log what IS on screen so we can diagnose why Storage wasn't
            // found (e.g. different label on this OEM).
            val visibleTexts = collectRowTexts(root).take(20)
            listener?.onLog("  [dbg] Storage row not found on App info")
            FileLogger.log(
                "engine", "storage row not found, visible rows",
                data = mapOf("pkg" to targetPkg, "rows" to visibleTexts.joinToString(" | "))
            )
            completeAttempt(AttemptSignal.STORAGE_MISSING)
            return
        }
        markHandled(storage)
        try {
            if (!storage.isEnabled) {
                listener?.onLog("  [dbg] Storage row found but DISABLED")
                completeAttempt(AttemptSignal.STORAGE_MISSING)
                return
            }
            listener?.onLog("  [dbg] Storage row found, clicking")
            val node = storage
            scope.launch {
                delay(AutomationPolicy.preClickDelayMs(turbo))
                val clickResult = try {
                    withContext(Dispatchers.Main) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                } catch (_: Exception) {
                    false
                } finally {
                    try { node.recycle() } catch (_: Exception) {}
                }
                listener?.onLog("  [dbg] Storage click dispatched, result=$clickResult")
                if (!clickResult) {
                    // The click didn't land (stale node, not yet laid out).
                    // Don't advance to WAIT_STORAGE — the Storage screen will
                    // never open. Fail this attempt with a retryable signal
                    // so the package is reopened from a clean App info.
                    listener?.onLog("  [dbg] Storage click failed, retrying package")
                    completeAttempt(AttemptSignal.STORAGE_MISSING)
                    return@launch
                }
                // Fire-and-forget like the reference: the Storage screen's
                // own window-state-changed event drives the next stage.
                // The storage-open watchdog (below) catches the case where
                // the click "succeeded" but the screen never appears.
                storageClickTime = SystemClock.uptimeMillis()
                storageScreenSeen = false
                advanceStage(Stage.WAIT_STORAGE)
                startStorageOpenWatchdog()
            }
        } catch (_: Exception) {
            try { storage.recycle() } catch (_: Exception) {}
        }
    }

    // ---------------------------------------------------------- storage stage

    private fun handleStorageWindow(root: AccessibilityNodeInfo, eventPkg: String) {
        if (stage != Stage.WAIT_STORAGE) return
        // The Storage screen is really here (we got a window event for it).
        // Cancel the open-watchdog: the click did navigate.
        if (!storageScreenSeen) {
            storageScreenSeen = true
            storageOpenWatchdog?.cancel()
            storageOpenWatchdog = null
        }
        val button = findClearCacheButton(root, eventPkg)
        if (button == null) {
            // The Storage screen populates asynchronously: the Clear-cache
            // button is often missing from the first window event but
            // appears once storage stats finish computing (1-3 s on cold
            // load). Wait for it (bounded) instead of failing immediately —
            // the instant CLEAR_CACHE_MISSING was the top failure mode in
            // phone logs. Subsequent window-content-changed events re-enter
            // here; the watchdog below bounds the wait if the screen goes
            // quiet.
            if (!storageWaitLogged) {
                storageWaitLogged = true
                listener?.onLog("  [dbg] Storage screen shown, waiting for Clear-cache button")
            }
            startStorageWatchdog()
            return
        }
        storageWatchdog?.cancel()
        storageWatchdog = null
        markHandled(button)
        listener?.onLog("  [dbg] Clear-cache button found, clicking")
        clickClearCache(button)
    }

    /**
     * Bounds the wait for the Clear-cache button. Fires only if the Storage
     * screen stops sending events without the button ever appearing.
     */
    private fun startStorageWatchdog() {
        if (storageWatchdog?.isActive == true) return
        storageWatchdog = scope.launch {
            delay(AutomationPolicy.STORAGE_SETTLE_TIMEOUT_MS)
            if (stage == Stage.WAIT_STORAGE && running) {
                listener?.onLog("  [dbg] Clear-cache button never appeared, giving up")
                FileLogger.log("engine", "storage settle timeout, Clear-cache button not found")
                completeAttempt(AttemptSignal.CLEAR_CACHE_MISSING)
            }
        }
    }

    /**
     * Fires if the Storage screen never appears after a successful Storage
     * click. Presses back (to return to App info) and completes the attempt
     * with TIMEOUT so the package is retried from a clean open instead of
     * hanging until the 30 s package timeout.
     */
    private fun startStorageOpenWatchdog() {
        storageOpenWatchdog?.cancel()
        storageOpenWatchdog = scope.launch {
            delay(STORAGE_OPEN_TIMEOUT_MS)
            if (stage == Stage.WAIT_STORAGE && running && !storageScreenSeen) {
                listener?.onLog("  [dbg] Storage screen never opened after click, going back")
                FileLogger.log("engine", "storage open timeout, pressing back")
                try {
                    withContext(Dispatchers.Main) {
                        CacheAccessService.instance?.performGlobalAction(
                            AccessibilityService.GLOBAL_ACTION_BACK
                        )
                    }
                } catch (_: Exception) {
                }
                delay(500)
                completeAttempt(AttemptSignal.TIMEOUT)
            }
        }
    }

    private fun clickClearCache(button: AccessibilityNodeInfo) {
        val node = button
        scope.launch {
            // The button can be momentarily disabled while the screen is
            // still settling (seen on vivo). Re-check after a short pause;
            // a persistently disabled Clear-cache button means the app has
            // nothing to clear — skip it, don't fail it.
            val disabled = try { !node.isEnabled } catch (_: Exception) { true }
            if (disabled) {
                delay(800)
                val stillDisabled = try { !node.isEnabled } catch (_: Exception) { true }
                if (stillDisabled) {
                    try { node.recycle() } catch (_: Exception) {}
                    listener?.onLog("  [dbg] Clear-cache button disabled, already clean, skipping")
                    completeAttempt(AttemptSignal.SKIPPED_CLEAN)
                    return@launch
                }
                // Enabled now — fall through and click it.
            }
            delay(AutomationPolicy.preClickDelayMs(turbo))
            val clickResult = try {
                withContext(Dispatchers.Main) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } catch (_: Exception) {
                false
            } finally {
                try { node.recycle() } catch (_: Exception) {}
            }
            listener?.onLog("  [dbg] Clear-cache click dispatched, result=$clickResult")
            // Fire-and-forget like the reference: the click result is
            // not checked. A confirmation dialog may or may not appear;
            // the dialog stage handles it, and its watchdog completes
            // the attempt either way.
            clearCacheClicked = true
            advanceStage(Stage.WAIT_DIALOG)
            delay(AutomationPolicy.dialogWatchdogMs(turbo))
            if (stage == Stage.WAIT_DIALOG && clearCacheClicked) {
                listener?.onLog("  [dbg] no confirmation dialog appeared, finishing package")
                completeAttempt(AttemptSignal.CACHE_CLEARED)
            }
        }
    }

    // ----------------------------------------------------------- dialog stage

    private fun handleDialogWindow(root: AccessibilityNodeInfo) {
        if (stage != Stage.WAIT_DIALOG || !clearCacheClicked) return
        val ok = findDialogOkButton(root) ?: return
        markHandled(ok)
        listener?.onLog("  [dbg] confirmation dialog OK found, clicking")
        stage = Stage.IDLE
        scope.launch {
            delay(AutomationPolicy.preClickDelayMs(turbo))
            try {
                withContext(Dispatchers.Main) {
                    ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
            } catch (_: Exception) {
            } finally {
                try { ok.recycle() } catch (_: Exception) {}
            }
            listener?.onLog("  [dbg] dialog OK click dispatched")
            completeAttempt(AttemptSignal.CACHE_CLEARED)
        }
    }

    private fun completeAttempt(signal: AttemptSignal) {
        val s = attemptSignal ?: return
        if (!s.isCompleted) s.complete(signal)
    }

    private fun markHandled(node: AccessibilityNodeInfo) {
        try {
            handledWindowIds.add(node.windowId)
        } catch (_: Exception) {
        }
    }

    /**
     * Move to the next stage. The handled-window set is cleared because the
     * next screen may legitimately live in the SAME window (e.g. the Storage
     * screen as a fragment inside the App info window on some OEM skins);
     * keeping the old window id suppressed would make the engine ignore the
     * new screen and time out. Within a stage, markHandled() still prevents
     * double-clicking the same row. The Storage settle watchdog is
     * (re)started when entering WAIT_STORAGE and cancelled on exit.
     */
    private fun advanceStage(next: Stage) {
        if (stage == Stage.WAIT_STORAGE && next != Stage.WAIT_STORAGE) {
            storageWatchdog?.cancel()
            storageWatchdog = null
            storageOpenWatchdog?.cancel()
            storageOpenWatchdog = null
        }
        stage = next
        handledWindowIds.clear()
        if (next == Stage.WAIT_STORAGE) {
            storageWaitLogged = false
        }
    }

    private fun tryScrollForward(root: AccessibilityNodeInfo): Boolean {
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty()) {
            val n = deque.removeFirst()
            try {
                if (n.isScrollable) {
                    return try {
                        n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    } catch (_: Exception) {
                        false
                    }
                }
                for (i in 0 until n.childCount) {
                    try { n.getChild(i)?.let { deque.add(it) } } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * Collects the visible text of rows on the App info screen for
     * diagnostics when the Storage row isn't found.
     */
    private fun collectRowTexts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        val deque = ArrayDeque<AccessibilityNodeInfo>()
        deque.add(root)
        while (deque.isNotEmpty() && out.size < 40) {
            val n = deque.removeFirst()
            try {
                val t = n.text?.toString()?.trim()
                if (!t.isNullOrEmpty() && t.length < 60) out.add(t)
                for (i in 0 until n.childCount) {
                    try { n.getChild(i)?.let { deque.add(it) } } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            }
        }
        return out.distinct()
    }

    // -------------------------------------------------------------- node find

    private fun findStorageRow(
        root: AccessibilityNodeInfo,
        eventPkg: String
    ): AccessibilityNodeInfo? {
        for (text in localizedSettingsTexts(eventPkg, STORAGE_RES_NAMES)) {
            findClickableByText(root, text)?.let { return it }
        }
        for (text in STORAGE_EN_FALLBACK) {
            findClickableByText(root, text)?.let { return it }
        }
        return null
    }

    private fun findClearCacheButton(
        root: AccessibilityNodeInfo,
        eventPkg: String
    ): AccessibilityNodeInfo? {
        for (text in localizedSettingsTexts(eventPkg, CLEAR_CACHE_RES_NAMES)) {
            findClickableByText(root, text)?.let { return it }
        }
        for (text in CLEAR_CACHE_EN_FALLBACK) {
            findClickableByText(root, text)?.let { return it }
        }
        return null
    }

    private fun findDialogOkButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in DIALOG_OK_VIEW_IDS) {
            findClickableByViewId(root, id)?.let { return it }
        }
        for (resId in intArrayOf(android.R.string.ok, android.R.string.yes)) {
            val text = try { appContext.getString(resId) } catch (_: Exception) { null }
            if (text.isNullOrEmpty()) continue
            findClickableByText(root, text)?.let { return it }
        }
        return null
    }

    // --- node finders ---
    //
    // These deliberately do NOT recycle the nodes they walk. Buttons found
    // here are clicked after a short delay, so the search tree must stay
    // valid until the click lands; recycling it first would be a
    // use-after-recycle bug. Search trees are small and short-lived — the
    // garbage collector reclaims them. Only the button that was actually
    // clicked is recycled, after performAction() returns.

    private fun findClickableByText(
        root: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        if (text.isEmpty()) return null
        val exact = try {
            root.findAccessibilityNodeInfosByText(text)
        } catch (_: Exception) {
            emptyList()
        }
        for (n in exact) {
            try {
                if (n.isClickable) return n
                val ancestor = n.clickableAncestor()
                if (ancestor != null) return ancestor
            } catch (_: Exception) {
            }
        }
        return clickableAncestorByTextContains(root, text)
    }

    private fun clickableAncestorByTextContains(
        node: AccessibilityNodeInfo,
        text: String
    ): AccessibilityNodeInfo? {
        try {
            val nodeText = node.text?.toString()
            if (!nodeText.isNullOrEmpty() &&
                nodeText.contains(text, ignoreCase = true)
            ) {
                return node.clickableAncestor()
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                val hit = clickableAncestorByTextContains(child, text)
                if (hit != null) return hit
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun findClickableByViewId(
        root: AccessibilityNodeInfo,
        fullId: String
    ): AccessibilityNodeInfo? {
        val nodes = try {
            root.findAccessibilityNodeInfosByViewId(fullId)
        } catch (_: Exception) {
            emptyList()
        }
        for (n in nodes) {
            try {
                if (n.isClickable) return n
                val ancestor = n.clickableAncestor()
                if (ancestor != null) return ancestor
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun AccessibilityNodeInfo.clickableAncestor(): AccessibilityNodeInfo? {
        var p: AccessibilityNodeInfo? = this
        return try {
            while (p != null && !p.isClickable) p = p.parent
            p
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------ localized strings

    /**
     * The localized Settings strings for the given resource names, resolved
     * from the Settings package that raised the event — exactly like the
     * reference. Cached per run.
     */
    private fun localizedSettingsTexts(
        eventPkg: String,
        resNames: Array<String>
    ): Set<String> {
        val key = eventPkg + "|" + resNames.joinToString(",")
        return settingsTextCache.getOrPut(key) {
            val out = mutableSetOf<String>()
            try {
                val res = appContext.packageManager.getResourcesForApplication(eventPkg)
                for (name in resNames) {
                    val id = res.getIdentifier(name, "string", eventPkg)
                    if (id != 0) {
                        try { out += res.getString(id) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
            }
            out
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun isSettingsHost(pkg: String): Boolean =
        pkg == "com.android.settings" || pkg == "com.miui.securitycenter"

    private fun appLabel(pkg: String): String {
        return try {
            val ai = appContext.packageManager.getApplicationInfo(pkg, 0)
            appContext.packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }
    }

    companion object {
        /**
         * How long to wait for the accessibility service to rebind after a
         * mid-run unbind. Phone logs show vivo rebinds in 6-56 s; 60 s
         * covers the observed range without hanging forever.
         */
        private const val REBIND_WAIT_MS = 60000L

        /** Settings string resource names for the "Storage" row. */
        private val STORAGE_RES_NAMES = arrayOf(
            "app_manager_menu_clear_data", // MIUI App info row
            "storage_settings_for_app",    // AOSP variants
            "storage_settings",
            "storage_use"
        )
        private val STORAGE_EN_FALLBACK = arrayOf("Storage & cache", "Storage")

        /** Settings string resource names for the "Clear cache" button. */
        private val CLEAR_CACHE_RES_NAMES = arrayOf(
            "clear_cache_btn_text",
            "app_manager_clear_cache"
        )
        private val CLEAR_CACHE_EN_FALLBACK = arrayOf("Clear cache")

        private val DIALOG_OK_VIEW_IDS = arrayOf(
            "com.android.settings:id/button1",
            "android:id/button1"
        )

        /** Max scroll attempts looking for the Storage row. */
        private const val MAX_SCROLLS = 5

        /**
         * How long to wait for the Storage screen to appear after a
         * successful Storage-row click before pressing back and retrying.
         * Phone logs show the screen normally appears in <2 s; 8 s bounds
         * the "click succeeded but nothing happened" hang without burning
         * the full 30 s package timeout.
         */
        private const val STORAGE_OPEN_TIMEOUT_MS = 8000L
    }
}
