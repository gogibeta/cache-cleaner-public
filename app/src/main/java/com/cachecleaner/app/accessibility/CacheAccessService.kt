package com.cachecleaner.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.cachecleaner.app.data.FileLogger

/**
 * Accessibility service that powers the cache-clean automation.
 *
 * It does nothing on its own: [CacheClearEngine] enables aggressive event
 * monitoring only for the duration of a clean run
 * and every relevant event is forwarded to the engine, which performs the
 * actual Storage -> Clear cache -> OK clicks. Outside a run the service is idle.
 */
class CacheAccessService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CacheAccessService? = null
            private set

        /** True if the user has enabled this service in system Settings. */
        fun isEnabled(context: android.content.Context): Boolean {
            return try {
                val expected = "${context.packageName}/${CacheAccessService::class.java.name}"
                val enabled = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabled.split(':').any { it.equals(expected, ignoreCase = true) }
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        FileLogger.log("a11y", "service CONNECTED (bound by system)")
        // NOTE: the event types / flags / notification timeout are declared
        // statically in accessibility_service_config.xml and are NEVER
        // changed at runtime. Calling setServiceInfo() at runtime makes the
        // system unbind (then rebind) the service, which killed automation
        // on the user's device within a second of every toggle.
        // The engine ignores events while no run is active, so the static
        // mask costs nothing when idle.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        FileLogger.log("a11y", "service UNBOUND by system — automation will stop working until rebound", level = "WARN")
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            CacheClearEngineHolder.engine?.onAccessibilityEvent(event)
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
        FileLogger.log("a11y", "service INTERRUPTED by system", level = "WARN")
    }

    /**
     * No-op kept for API compatibility. Event monitoring is configured
     * statically in accessibility_service_config.xml and never toggled at
     * runtime: changing AccessibilityService info via setServiceInfo() makes
     * the system unbind/rebind the service (observed killing automation
     * within 0.1–0.6 s on the user's vivo). The engine already ignores
     * events while no run is active.
     */
    fun setAggressiveMonitoring(@Suppress("UNUSED_PARAMETER") enabled: Boolean) {
        // Intentionally does nothing; see above.
    }

    /**
     * No-op kept for API compatibility. See [setAggressiveMonitoring].
     */
    fun updateEventTypes(@Suppress("UNUSED_PARAMETER") transform: (Int) -> Int) {
        // Intentionally does nothing; see above.
    }

    /** Best-effort global BACK press (used to leave Settings after a run). */
    fun pressBack(): Boolean {
        return try {
            val ok = performGlobalAction(GLOBAL_ACTION_BACK)
            FileLogger.log("a11y", "GLOBAL_ACTION_BACK dispatched, accepted=$ok")
            ok
        } catch (e: Exception) {
            FileLogger.logException("a11y", "pressBack", e)
            false
        }
    }

    /**
     * Opens the given intent from the accessibility-service context.
     * A system-bound accessibility service is not subject to the background
     * activity-start restriction the same way a background app is, so this
     * is the reliable way to open the next App info screen mid-run.
     */
    fun startActivityForAutomation(intent: Intent) {
        try {
            startActivity(intent)
            FileLogger.log("a11y", "startActivity for automation dispatched: ${intent.data}")
        } catch (e: Exception) {
            FileLogger.logException("a11y", "startActivityForAutomation", e)
            throw e
        }
    }
}

/**
 * Process-wide holder so the engine (created with an application context) and
 * the service can find each other without leaks.
 */
object CacheClearEngineHolder {
    @Volatile
    var engine: CacheClearEngine? = null
}
