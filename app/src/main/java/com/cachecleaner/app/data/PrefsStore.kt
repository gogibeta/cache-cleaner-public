package com.cachecleaner.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefs by preferencesDataStore(name = "stop_apps_prefs")

/**
 * Persisted user preferences: whitelisted packages + turbo mode.
 */
class PrefsStore(private val context: Context) {

    companion object {
        private val KEY_WHITELIST = stringSetPreferencesKey("whitelist")
        private val KEY_TURBO = booleanPreferencesKey("turbo")
        private val KEY_LAST_STOPPED = longPreferencesKey("last_stopped_time")

        /**
         * Packages whose stop attempt failed (the reference's
         * `mi_invalid_packs`, e.g. MIUI refusing the force-stop). They are
         * hidden from the running list until usage events rehabilitate them.
         */
        private val KEY_MI_INVALID = stringSetPreferencesKey("mi_invalid_packs")

        /**
         * Minimum cache bytes for an app to appear in the list. Apps with
         * less cache than this are excluded (they have nothing worth
         * cleaning). Only applied when usage access is granted (otherwise
         * every app reports 0 cache and the filter would hide everything).
         */
        private val KEY_CACHE_THRESHOLD = longPreferencesKey("cache_threshold_bytes")

        /** Default threshold: 1 MB. */
        const val DEFAULT_CACHE_THRESHOLD_BYTES = 1024L * 1024L
    }

    val whitelist: Flow<Set<String>> =
        context.prefs.data.map { it[KEY_WHITELIST] ?: emptySet() }

    val turbo: Flow<Boolean> =
        context.prefs.data.map { it[KEY_TURBO] ?: true }

    /**
     * Minimum cache bytes for an app to appear in the list.
     * Default 1 MB; 0 disables the filter (show everything).
     */
    val cacheThresholdBytes: Flow<Long> =
        context.prefs.data.map { it[KEY_CACHE_THRESHOLD] ?: DEFAULT_CACHE_THRESHOLD_BYTES }

    suspend fun setCacheThresholdBytes(bytes: Long) {
        context.prefs.edit { it[KEY_CACHE_THRESHOLD] = bytes.coerceAtLeast(0L) }
    }

    /**
     * When the last stop run finished (epoch millis). The reference default
     * is -1L ("never"); usage-event rehabilitation of MIUI-invalid packages
     * only runs when this differs from -1L.
     */
    val lastStoppedTime: Flow<Long> =
        context.prefs.data.map { it[KEY_LAST_STOPPED] ?: RunningClassifier.LAST_STOPPED_NEVER }

    suspend fun setLastStoppedTime(t: Long) {
        context.prefs.edit { it[KEY_LAST_STOPPED] = t }
    }

    /** Packages currently recorded as MIUI-invalid (stop attempt failed). */
    suspend fun miInvalidPacks(): Set<String> =
        context.prefs.data.map { it[KEY_MI_INVALID] ?: emptySet() }.first()

    /** Records packages whose stop attempt failed (reference `l2.b`). */
    suspend fun addMiInvalidPacks(pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        context.prefs.edit { prefs ->
            prefs[KEY_MI_INVALID] = (prefs[KEY_MI_INVALID] ?: emptySet()) + pkgs
        }
    }

    /** Removes rehabilitated packages from the MIUI-invalid set (`l2.h`). */
    suspend fun removeMiInvalidPacks(pkgs: Collection<String>) {
        if (pkgs.isEmpty()) return
        context.prefs.edit { prefs ->
            prefs[KEY_MI_INVALID] = (prefs[KEY_MI_INVALID] ?: emptySet()) - pkgs.toSet()
        }
    }

    suspend fun setTurbo(enabled: Boolean) {
        context.prefs.edit { it[KEY_TURBO] = enabled }
    }

    suspend fun addToWhitelist(pkg: String) {
        context.prefs.edit { prefs ->
            prefs[KEY_WHITELIST] = (prefs[KEY_WHITELIST] ?: emptySet()) + pkg
        }
    }

    suspend fun removeFromWhitelist(pkg: String) {
        context.prefs.edit { prefs ->
            prefs[KEY_WHITELIST] = (prefs[KEY_WHITELIST] ?: emptySet()) - pkg
        }
    }

    suspend fun isWhitelisted(pkg: String): Boolean =
        context.prefs.data.map { pkg in (it[KEY_WHITELIST] ?: emptySet()) }.first()
}
