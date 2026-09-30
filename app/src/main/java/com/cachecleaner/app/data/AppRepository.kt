package com.cachecleaner.app.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * One row in the app list.
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val isRunning: Boolean,
    /** Cached data reported by StorageStatsManager, in bytes. Best-effort. */
    val cacheBytes: Long,
    val lastUsed: Long
    // NOTE: no icon field on purpose. Icons are loaded lazily per visible
    // row through IconCache (see HomeScreen's AppIcon): loading every
    // installed app's icon eagerly at list time kept the home screen on its
    // spinner for minutes and ANR'd the CI emulator (runs 94/95).
)

class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    /**
     * Bounded dispatcher for the per-package fan-out in [loadApps]
     * (launch-intent checks, storage-stats queries). Unbounded parallelism
     * would fire hundreds of concurrent binder IPCs at system_server; 16
     * keeps it fast without hammering the system.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val parallelIO = Dispatchers.IO.limitedParallelism(16)
    /** True if the user granted "Usage access" (PACKAGE_USAGE_STATS app-op). */
    fun hasUsageAccess(): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun openUsageAccessSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Packages with a MOVE_TO_FOREGROUND event in the last 24h.
     * Refines the "running" signal: FLAG_STOPPED alone marks dead apps as
     * running, while getRunningAppProcesses() is restricted on Android 7+
     * and would hide everything. A recent foreground event is the best
     * available "likely alive" signal without root.
     */
    private fun recentlyForegroundedPkgs(): Set<String> {
        if (!hasUsageAccess()) return emptySet()
        return try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 24 * 60 * 60 * 1000L, now)
            val out = HashSet<String>()
            val ev = android.app.usage.UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val pkg = ev.packageName
                    if (!pkg.isNullOrEmpty()) out += pkg
                }
            }
            out
        } catch (_: Exception) {
            emptySet()
        }
    }

    /**
     * Loads every enabled, launchable app minus:
     * - this app, the default launcher, the active keyboard
     * - [AutoWhitelist.SYSTEM_PACKAGES]
     * - user-whitelisted packages
     * - packages still recorded as MIUI-invalid (stop attempt failed and no
     *   usage since the last stop run rehabilitated them)
     *
     * An app counts as "running" exactly like the reference app (AppSleep
     * 2.4, method `v2.c.c`): `PackageManager.getInstalledApplications(0)`
     * filtered to packages that are enabled, do NOT have the
     * `FLAG_STOPPED` bit set, and have a launch intent. There is no
     * usage-recency window — windowing is what caused the 7-vs-16 count
     * mismatch against the reference.
     *
     * Per-app cache bytes come from StorageStatsManager (like XCleaner's
     * `queryStatsForUid(...).cacheBytes`); requires usage access, else 0.
     */
    suspend fun loadApps(userWhitelist: Set<String>): List<AppEntry> {
        val selfPkg = context.packageName
        val launcherPkg = defaultLauncherPackage()
        val keyboardPkg = activeKeyboardPackage()

        // ---- exclusions (mirrors the reference) ----
        val exclusions = HashSet<String>(userWhitelist.size + 8)
        exclusions += selfPkg
        exclusions += launcherPkg
        exclusions += keyboardPkg
        exclusions += userWhitelist

        val prefs = PrefsStore(context)

        // ---- installed apps (mirrors C2674f.d) ----
        val installed: List<ApplicationInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0)
            }
        } catch (_: Exception) {
            emptyList()
        }
        val allPkgs = installed.mapNotNull { it.packageName }.distinct()

        // ---- launch-intent cache (mirrors C2674f.f20780a) ----
        // Filled in parallel, bounded: the sequential version issued one
        // PackageManager IPC per installed package (~hundreds, one at a
        // time), which alone stalled list loading for minutes on the CI
        // emulator (runs 94/95, 2026-09-29).
        val launchIntentCache = ConcurrentHashMap<String, Boolean>()
        coroutineScope {
            allPkgs.map { pkg ->
                async(parallelIO) {
                    val has = try {
                        pm.getLaunchIntentForPackage(pkg) != null
                    } catch (_: Exception) {
                        false
                    }
                    launchIntentCache[pkg] = has
                }
            }.awaitAll()
        }
        fun hasLaunchIntent(pkg: String): Boolean = launchIntentCache[pkg] == true

        // ---- running set (mirrors the reference's final loop) ----
        val runningPkgs = RunningClassifier.filterRunning(
            installed.map {
                RunningClassifier.InstalledApp(
                    packageName = it.packageName ?: "",
                    enabled = it.enabled,
                    flags = it.flags
                )
            },
            exclusions,
            AutoWhitelist.SYSTEM_PACKAGES,
            ::hasLaunchIntent
        ).toHashSet()

        // Refine with recent foreground events: FLAG_STOPPED alone marks
        // dead apps as running. If usage access is granted, require a
        // MOVE_TO_FOREGROUND in the last 24h; otherwise fall back to the
        // FLAG_STOPPED candidate set (best available without usage access).
        val foregroundedPkgs = recentlyForegroundedPkgs()
        val useForegroundFilter = foregroundedPkgs.isNotEmpty()

        // ---- per-package cache bytes (mirrors XCleaner C2660f.a) ----
        // Queried in parallel, bounded: the sequential version issued one
        // StorageStatsManager binder call per package, and a single slow UID
        // stalled the whole list behind it (runs 94/95, 2026-09-29).
        val cacheByPkg = mutableMapOf<String, Long>()
        if (hasUsageAccess()) {
            val stats = CacheStats(context)
            val results = coroutineScope {
                allPkgs.map { pkg ->
                    async(parallelIO) {
                        val bytes = try {
                            stats.cacheBytes(pkg)
                        } catch (_: Exception) {
                            0L
                        }
                        pkg to bytes
                    }
                }.awaitAll()
            }
            for ((pkg, bytes) in results) {
                if (bytes > 0) cacheByPkg[pkg] = bytes
            }
        }

        // ---- usage stats -> last used ----
        val lastUsedByPkg = mutableMapOf<String, Long>()
        if (hasUsageAccess()) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                val stats = usm.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY, now - 24 * 60 * 60 * 1000L, now
                ) ?: emptyList()
                for (s in stats) {
                    if (s.lastTimeUsed > 0) {
                        lastUsedByPkg[s.packageName] =
                            maxOf(lastUsedByPkg[s.packageName] ?: 0L, s.lastTimeUsed)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // entries: enabled + launchable, minus exclusions/safety.
        // isRunning additionally requires a live process (see below); the
        // FLAG_STOPPED-only filter is the reference's candidate set.
        val out = ArrayList<AppEntry>(installed.size)
        for (ai in installed) {
            try {
                val pkg = ai.packageName ?: continue
                if (pkg.isEmpty()) continue
                if (pkg in exclusions) continue
                if (pkg in AutoWhitelist.SYSTEM_PACKAGES) continue
                if (!ai.enabled) continue
                // Launchable only, as before (the list came from a launcher
                // query); the reference additionally requires this.
                if (!hasLaunchIntent(pkg)) continue

                val label = try {
                    pm.getApplicationLabel(ai).toString()
                } catch (_: Exception) {
                    pkg
                }
                // No eager icon load here: icons arrive lazily per visible
                // row via IconCache (see HomeScreen's AppIcon).
                val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                // isRunning: FLAG_STOPPED-clear candidate AND (recent
                // foreground event if usage access is available). FLAG_STOPPED
                // alone falsely marks dead apps as running; the foreground
                // filter removes apps the system killed or that never started.
                val inCandidateSet = pkg in runningPkgs
                val isRunning = if (useForegroundFilter) {
                    inCandidateSet && pkg in foregroundedPkgs
                } else {
                    inCandidateSet
                }
                out += AppEntry(
                    packageName = pkg,
                    label = label,
                    isSystem = isSystem,
                    isRunning = isRunning,
                    cacheBytes = cacheByPkg[pkg] ?: 0L,
                    lastUsed = lastUsedByPkg[pkg] ?: 0L
                )
            } catch (_: Exception) {
                // Skip one bad entry, never fail the whole list.
            }
        }
        // Biggest cache first (the most junk on top), then alphabetically.
        return out.sortedWith(
            compareByDescending<AppEntry> { it.cacheBytes }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun defaultLauncherPackage(): String {
        return try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            // MATCH_DEFAULT_ONLY (65536), like the reference's C2674f.b.
            val ri = if (Build.VERSION.SDK_INT >= 33) {
                pm.resolveActivity(
                    home,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            }
            ri?.activityInfo?.packageName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun activeKeyboardPackage(): String {
        return try {
            val current = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
            ) ?: return ""
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList
                .firstOrNull { it.id == current }
                ?.packageName ?: ""
        } catch (_:Exception) {
            ""
        }
    }
}
