package com.cachecleaner.app.data

/**
 * Pure running-app classification logic.
 *
 * Mirrors the reference app (AppSleep 2.4, method `v2.c.c`, reverse-engineered
 * for interoperability) exactly:
 *
 * 1. Exclusions = own package + default launcher + active keyboard + user
 *    whitelist.
 * 2. The persisted MIUI-invalid set (`mi_invalid_packs`) is read. When it is
 *    nonempty, `last_stopped_time` (default -1L) is read; when that is not
 *    -1L, `UsageStatsManager.queryEvents(lastStopped, now)` collects
 *    packages (any event type counts, first-seen order, only packages with a
 *    launch intent). Invalid packages WITH such events are rehabilitated
 *    (removed from the persisted invalid set); invalid packages WITHOUT
 *    them are added to the exclusions.
 * 3. `PackageManager.getInstalledApplications(0)` is enumerated and a
 *    package counts as running only when: name nonempty, not excluded, not
 *    in the safety list, `ApplicationInfo.enabled`, the `FLAG_STOPPED` bit
 *    clear, and `getLaunchIntentForPackage(pkg) != null` (cached).
 *
 * There is intentionally no usage-recency window: the reference has none,
 * and windowing is what produced the 7-vs-16 count mismatch.
 *
 * This object is pure Kotlin (no Android imports) so it stays JVM-testable;
 * the Android calls live in [AppRepository].
 */
object RunningClassifier {

    /**
     * The `ApplicationInfo.FLAG_STOPPED` bit (1 shl 21). The OS sets it on an
     * app that was force-stopped; the reference excludes such apps from the
     * running list. Declared as a constant (rather than referencing
     * `android.content.pm.ApplicationInfo`) so this object stays JVM-testable.
     */
    const val FLAG_STOPPED: Int = 1 shl 21

    /**
     * Default of the reference's `last_stopped_time` preference. Usage-event
     * rehabilitation only runs when the persisted value differs from this.
     */
    const val LAST_STOPPED_NEVER: Long = -1L

    /** Minimal installed-app data needed by [filterRunning]. */
    data class InstalledApp(
        val packageName: String,
        val enabled: Boolean,
        val flags: Int
    )

    /**
     * The reference's final enumeration loop: installed apps in order, kept
     * only when the package name is nonempty, not excluded, not in the
     * safety list, enabled, `FLAG_STOPPED` clear, and having a launch
     * intent.
     */
    fun filterRunning(
        apps: List<InstalledApp>,
        exclusions: Set<String>,
        safetyList: Set<String>,
        hasLaunchIntent: (String) -> Boolean
    ): List<String> {
        val out = ArrayList<String>()
        for (app in apps) {
            val pkg = app.packageName
            if (pkg.isEmpty()) continue
            if (pkg in exclusions) continue
            if (pkg in safetyList) continue
            if (!app.enabled) continue
            if ((app.flags and FLAG_STOPPED) != 0) continue
            if (!hasLaunchIntent(pkg)) continue
            out += pkg
        }
        return out
    }

    /**
     * Collects rehabilitation candidates from usage events, mirroring the
     * reference loop: any event type counts, first-seen order, and a package
     * is kept only when `hasLaunchIntent` is true (each package is checked
     * at most once, like the reference's LinkedHashMap + cached check).
     */
    fun eventPackages(
        events: List<String>,
        hasLaunchIntent: (String) -> Boolean
    ): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        val checked = HashSet<String>()
        for (pkg in events) {
            if (pkg.isEmpty()) continue
            if (pkg in out || pkg in checked) continue
            checked += pkg
            if (hasLaunchIntent(pkg)) out += pkg
        }
        return out
    }

    /**
     * The reference's MIUI-invalid rehabilitation step.
     *
     * @param invalid packages currently persisted as MIUI-invalid.
     * @param eventPkgs packages with usage events since `last_stopped_time`
     *   that have a launch intent (see [eventPackages]).
     * @return `stillInvalid` (must be added to the exclusions, stays
     *   persisted) to `rehabilitated` (must be removed from the persisted
     *   invalid set, stays eligible for the running list).
     */
    fun rehabilitate(
        invalid: List<String>,
        eventPkgs: Set<String>
    ): Pair<List<String>, List<String>> {
        val stillInvalid = ArrayList<String>()
        val rehabilitated = ArrayList<String>()
        for (pkg in invalid) {
            if (pkg in eventPkgs) rehabilitated += pkg
            else stillInvalid += pkg
        }
        return stillInvalid to rehabilitated
    }
}
