package com.cachecleaner.app.data

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.util.LinkedHashMap

/**
 * Process-wide LRU cache for app icons, loaded lazily on demand.
 *
 * Icons used to be loaded eagerly for EVERY installed package inside
 * [AppRepository.loadApps] (a `pm.getApplicationIcon()` per package, hundreds
 * of APK resource opens before the first frame). On slow devices that kept
 * the home screen on its loading spinner for many minutes and churned
 * bitmaps through the heap — the CI emulator ANR'd on launch because of it
 * (runs 94/95, 2026-09-29).
 *
 * Now the list loads with no icons at all and each visible row fetches its
 * own icon off the UI thread on first composition (see HomeScreen's AppIcon);
 * rows show a placeholder until the icon arrives. Callers must invoke [get]
 * off the main thread.
 */
object IconCache {
    private const val MAX_ENTRIES = 128

    private val cache =
        object : LinkedHashMap<String, Drawable?>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, Drawable?>
            ): Boolean = size > MAX_ENTRIES
        }

    /** Returns the cached icon, loading and caching it on a miss. */
    @Synchronized
    fun get(pm: PackageManager, packageName: String): Drawable? {
        if (cache.containsKey(packageName)) return cache[packageName]
        val d = try {
            pm.getApplicationIcon(packageName)
        } catch (_: Exception) {
            null
        }
        cache[packageName] = d
        return d
    }

    @Synchronized
    fun clear() {
        cache.clear()
    }
}
