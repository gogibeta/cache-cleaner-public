package com.cachecleaner.app

import android.app.Application
import com.cachecleaner.app.accessibility.CacheClearEngine
import com.cachecleaner.app.accessibility.CacheClearEngineHolder
import com.cachecleaner.app.data.FileLogger

/**
 * Creates the process-wide [CacheClearEngine] early so the accessibility
 * service can forward events to it from the moment it connects.
 */
class CacheCleaner : Application() {
    override fun onCreate() {
        super.onCreate()
        // Persistent diagnostic log first — everything below is recorded.
        try {
            FileLogger.init(this)
        } catch (_: Exception) {
        }
        FileLogger.log("app", "Application.onCreate")
        if (CacheClearEngineHolder.engine == null) {
            try {
                CacheClearEngineHolder.engine = CacheClearEngine(applicationContext)
                FileLogger.log("app", "CacheClearEngine created")
            } catch (e: Exception) {
                FileLogger.logException("app", "CacheClearEngine creation", e)
            }
        }
    }
}
