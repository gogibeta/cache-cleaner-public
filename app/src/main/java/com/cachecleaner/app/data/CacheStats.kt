package com.cachecleaner.app.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Per-package cache size via StorageStatsManager, mirroring the reference
 * (XCleaner 2.6 `C2660f.a`: `queryStatsForUid(storageUuid, uid).cacheBytes`).
 *
 * Requires usage access (PACKAGE_USAGE_STATS app-op); returns 0 without it.
 */
class CacheStats(private val context: Context) {

    fun cacheBytes(packageName: String): Long {
        return try {
            val pm = context.packageManager
            val ai = if (Build.VERSION.SDK_INT >= 33) {
                pm.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val ssm = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            ssm.queryStatsForUid(ai.storageUuid, ai.uid).cacheBytes
        } catch (_: Exception) {
            0L
        }
    }
}
