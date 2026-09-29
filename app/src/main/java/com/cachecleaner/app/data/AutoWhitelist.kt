package com.cachecleaner.app.data

/**
 * Packages that must never be offered for force-stop.
 *
 * The hard-coded list below was recovered by reverse-engineering the reference
 * implementation (AppSleep 2.4): these are the system packages it silently
 * excludes so the phone keeps working (System UI, Settings, dialer, SMS,
 * package installer bits, OEM managers, ...).
 *
 * On top of that we always exclude dynamically: this app itself, the default
 * launcher, the active keyboard, disabled apps and apps with no launcher icon.
 */
object AutoWhitelist {

    /** Hard-coded system packages, never shown as stoppable. */
    val SYSTEM_PACKAGES: Set<String> = setOf(
        // AOSP / core
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.mms",
        "com.android.contacts",
        "com.android.camera",
        "com.google.android.documentsui",
        "com.android.externalstorage",
        "com.android.providers.contacts",
        "com.android.cellbroadcastreceiver",
        "com.android.defcontainer",
        "com.android.gallery3d",
        "com.android.vending",
        "com.android.inputmethod",
        "android.process.media",
        "android.process.acore",
        "android",
        "com.miui.home",
        // Google
        "com.google.android.gms",
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.messaging",
        // Samsung
        "com.sec.android.inputmethod",
        "com.samsung.android.settingsreceiver",
        "com.samsung.android.forest",
        "com.samsung.android.bixby.agent",
        "com.samsung.knox.securefolder",
        "com.samsung.android.lool",
        "com.samsung.android.app.aodservice",
        "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.honeyboard",
        // Xiaomi
        "com.miui.securitycenter",
        // Huawei
        "com.huawei.appmarket",
        "com.huawei.systemmanager",
        "com.huawei.contacts",
        "com.huawei.android.totemweather",
        "com.huawei.lbs",
        "com.huawei.recsys",
        "com.huawei.hiai",
        "com.huawei.hwid",
        "com.huawei.tips",
        "com.huawei.search",
        "com.huawei.intelligent",
        "com.huawei.hwvoipservice",
        "com.huawei.parentcontrol",
        "com.huawei.hiassistantoversea",
        // Oppo / ColorOS / Realme
        "com.coloros.oppoguardelf",
        "com.coloros.athena",
        "com.coloros.weather.service",
        "com.coloros.notificationmanager",
        "com.coloros.securitypermission",
        "com.coloros.deepthinker",
        "com.coloros.exserviceui",
        "com.oppo.gestureservice",
        "com.oppo.oppopowermonitor",
        "com.oppo.resmonitor",
        // Misc
        "com.wssyncmldm"
    )

    /**
     * Settings-like packages whose windows the automation is allowed to drive.
     * The force-stop clicker only acts inside these packages.
     */
    val SETTINGS_PACKAGES: Set<String> = setOf(
        "com.android.settings",          // AOSP / Pixel / Motorola / Nokia ...
        "com.miui.securitycenter",       // Xiaomi MIUI / HyperOS app-info host
        "com.samsung.android.settings",  // Samsung One UI (falls back to AOSP ids)
        "com.coloros.settings",          // Oppo / ColorOS
        "com.oplus.settings",            // Oppo / OnePlus (newer)
        "com.huawei.systemmanager",      // Huawei (kept for id-matching safety)
        "com.android.settings.intelligence"
    )
}
