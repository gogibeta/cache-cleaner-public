package com.cachecleaner.app.accessibility

/**
 * Pure, platform-independent automation policy for the cache-clean run.
 *
 * The timing values below mirror the publicly observable behavior of the
 * reference app (XCleaner 2.6, reverse-engineered for interoperability):
 *
 * - Per-package watchdog: 10 seconds (the Storage screen can be slow).
 * - Inter-package delay: 1000 ms normally, 0 ms in turbo mode.
 * - Pre-click delay (Storage / Clear cache / dialog OK): 100 ms normally,
 *   50 ms in turbo mode.
 *
 * Event-type handling: the service declares the full mask
 * (window-state-changed + view-scrolled + window-content-changed) statically
 * in accessibility_service_config.xml with notificationTimeout=0, so events
 * arrive immediately instead of being throttled to one batch per 100 ms.
 * The mask is never changed at runtime (runtime changes unbind the service).
 */
object AutomationPolicy {

    /** Per-package watchdog in milliseconds. */
    const val PACKAGE_TIMEOUT_MS = 10000L

    /** Inter-package delay, normal mode. */
    const val NORMAL_INTER_DELAY_MS = 1000L

    /** Inter-package delay, turbo mode. */
    const val TURBO_INTER_DELAY_MS = 0L

    /** Pre-click delay, normal mode. */
    const val NORMAL_PRE_CLICK_DELAY_MS = 100L

    /** Pre-click delay, turbo mode. */
    const val TURBO_PRE_CLICK_DELAY_MS = 50L

    const val EVENT_WINDOW_STATE_CHANGED = 32
    const val EVENT_WINDOW_CONTENT_CHANGED = 2048
    const val EVENT_VIEW_SCROLLED = 4096

    /** Event types the service listens to while a run is active (32 | 4096). */
    val RUN_EVENT_TYPES = EVENT_WINDOW_STATE_CHANGED or EVENT_VIEW_SCROLLED // 4128

    /** Inter-package delay for the current mode. */
    fun interDelayMs(turbo: Boolean): Long =
        if (turbo) TURBO_INTER_DELAY_MS else NORMAL_INTER_DELAY_MS

    /** Pre-click delay for the current mode. */
    fun preClickDelayMs(turbo: Boolean): Long =
        if (turbo) TURBO_PRE_CLICK_DELAY_MS else NORMAL_PRE_CLICK_DELAY_MS

    /** Enable window-content-changed events on the given event-type mask. */
    fun withContentChanged(eventTypes: Int): Int =
        eventTypes or EVENT_WINDOW_CONTENT_CHANGED

    /** Disable window-content-changed events on the given event-type mask. */
    fun withoutContentChanged(eventTypes: Int): Int =
        eventTypes and EVENT_WINDOW_CONTENT_CHANGED.inv()

}
