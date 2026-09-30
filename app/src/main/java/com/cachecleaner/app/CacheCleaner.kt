package com.cachecleaner.app

import android.app.Application
import com.cachecleaner.app.accessibility.CacheClearEngine
import com.cachecleaner.app.accessibility.CacheClearEngineHolder
import com.cachecleaner.app.data.FileLog
import com.cachecleaner.app.data.FileLogger
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Creates the process-wide [CacheClearEngine] early so the accessibility
 * service can forward events to it from the moment it connects.
 */
class CacheCleaner : Application() {
    override fun onCreate() {
        super.onCreate()
        // DIAG file log first — everything below is recorded to
        // /sdcard/Android/data/<pkg>/files/logs/runlog-*.txt.
        try {
            FileLog.init(this)
        } catch (_: Exception) {
        }
        // Capture fatal crashes into the same file, then chain to the
        // previous handler so the system still shows the crash dialog.
        try {
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    val sw = StringWriter()
                    e.printStackTrace(PrintWriter(sw))
                    FileLog.append("FATAL: uncaught exception on thread ${t.name}: $e")
                    FileLog.append(sw.toString())
                } catch (_: Exception) {
                }
                try {
                    prev?.uncaughtException(t, e)
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
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
