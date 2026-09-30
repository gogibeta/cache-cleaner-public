package com.cachecleaner.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.cachecleaner.app.MainActivity
import com.cachecleaner.app.R
import com.cachecleaner.app.accessibility.CacheClearEngine
import com.cachecleaner.app.accessibility.CacheClearEngineHolder
import com.cachecleaner.app.accessibility.CacheAccessService
import com.cachecleaner.app.data.FileLogger
import com.cachecleaner.app.data.FileLog
import com.cachecleaner.app.ui.formatBytes
import com.cachecleaner.app.ui.formatRunMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that hosts a stop run so the automation survives while
 * the Settings app is in the foreground. Shows live progress in its
 * notification and a summary when done.
 */
class CacheRunnerService : Service() {

    companion object {
        const val ACTION_START = "com.cachecleaner.app.action.START"
        const val ACTION_CANCEL = "com.cachecleaner.app.action.CANCEL"
        const val EXTRA_PACKAGES = "packages"
        const val EXTRA_TURBO = "turbo"

        private const val CHANNEL_ID = "clean_run"
        private const val NOTIF_ID = 1001

        fun start(context: Context, packages: List<String>, turbo: Boolean) {
            val intent = Intent(context, CacheRunnerService::class.java).apply {
                action = ACTION_START
                putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(packages))
                putExtra(EXTRA_TURBO, turbo)
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
            }
        }

        fun cancel(context: Context) {
            try {
                context.startService(
                    Intent(context, CacheRunnerService::class.java).apply { action = ACTION_CANCEL }
                )
            } catch (_: Exception) {
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var engine: CacheClearEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.log(
            "service", "onStartCommand",
            data = mapOf("action" to (intent?.action ?: "null"))
        )
        when (intent?.action) {
            ACTION_CANCEL -> {
                engine?.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val packages =
                    intent.getStringArrayListExtra(EXTRA_PACKAGES)?.toList() ?: emptyList()
                val turbo = intent.getBooleanExtra(EXTRA_TURBO, false)
                if (packages.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!CacheAccessService.isEnabled(this)) {
                    notifySummary(
                        getString(R.string.notif_accessibility_needed_title),
                        getString(R.string.notif_accessibility_needed_text)
                    )
                    stopSelf()
                    return START_NOT_STICKY
                }
                startAsForeground(getString(R.string.notif_starting))
                beginRun(packages, turbo)
            }
        }
        return START_NOT_STICKY
    }

    private fun beginRun(packages: List<String>, turbo: Boolean) {
        val eng = CacheClearEngineHolder.engine
            ?: CacheClearEngine(applicationContext).also { CacheClearEngineHolder.engine = it }
        engine = eng
        eng.start(packages, turbo, object : CacheClearEngine.Listener {
            override fun onLog(line: String) {
                RunLog.append(line)
            }

            override fun onProgress(done: Int, total: Int, currentPackage: String?) {
                updateProgress(done, total, currentPackage)
            }

            override fun onFinished(result: CacheClearEngine.RunResult) {
                scope.launch {
                    val text = getString(
                        R.string.notif_done_text,
                        result.cleaned.size,
                        result.failed.size,
                        result.skipped.size,
                        formatBytes(result.cacheFreedBytes)
                    )
                    notifySummary(getString(R.string.notif_done_title), text)
                    // Machine-readable completion marker for the UI card and
                    // logcat-based testing (CI emulator run and on-device
                    // debugging).
                    RunLog.append(
                        "[dbg] " + formatRunMarker(
                            result.cleaned.size,
                            result.failed.size,
                            result.skipped.size,
                            result.cacheFreedBytes,
                            result.durationMs,
                            turbo
                        )
                    )
                    FileLogger.log(
                        "service", "run finished",
                        data = mapOf(
                            "cleaned" to result.cleaned.size.toString(),
                            "failed" to result.failed.size.toString(),
                            "skipped" to result.skipped.size.toString(),
                            "cache_freed_mb" to (result.cacheFreedBytes / 1_048_576L).toString(),
                            "duration_s" to (result.durationMs / 1000L).toString()
                        )
                    )
                    // Return the user to the app. After the last OK click we are
                    // sitting on the last App info screen, so without this
                    // the user is left staring at Settings with no success
                    // message. BACK closes the App info screen (it was
                    // opened NO_HISTORY) and reveals our MainActivity; the
                    // explicit launch is a fallback.
                    // Runs in NonCancellable: the service scope can be
                    // cancelled under us (e.g. the system destroying the
                    // service right as the run ends), which used to abort
                    // the return with a JobCancellationException and leave
                    // the user stuck on the Settings screen.
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            kotlinx.coroutines.delay(800) // let the last OK click land
                            val svc = CacheAccessService.instance
                            if (svc != null) {
                                svc.pressBack()
                                kotlinx.coroutines.delay(600)
                            }
                            try {
                                val home = Intent(applicationContext, MainActivity::class.java)
                                    .setFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    )
                                startActivity(home)
                                FileLogger.log("service", "returned to MainActivity after run")
                            } catch (e: Exception) {
                                FileLogger.logException("service", "return to MainActivity", e)
                            }
                        }
                    } catch (e: Exception) {
                        FileLogger.logException("service", "return-to-app", e)
                    }
                    // Keep the summary visible briefly, then stop.
                    try {
                        kotlinx.coroutines.delay(4000)
                    } catch (_: Exception) {
                    }
                    engine = null
                    stopSelf()
                }
            }
        })
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text, null, 0, 0)
        try {
            // FOREGROUND_SERVICE_TYPE_SPECIAL_USE exists only on API 34+;
            // passing its (unknown) type bit on older releases is undefined
            // behavior, so use the untyped overload there.
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notification)
            }
        } catch (_: Exception) {
            // As a last resort keep the service running without foreground
            // state; the run itself does not depend on it.
        }
    }

    private fun updateProgress(done: Int, total: Int, currentPackage: String?) {
        val label = currentPackage?.let { pkg ->
            try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(ai).toString()
            } catch (_: Exception) {
                pkg
            }
        }
        val text = if (label != null) {
            getString(R.string.notif_progress_text, done + 1, total, label)
        } else {
            getString(R.string.notif_finishing)
        }
        notifyProgress(buildNotification(text, label, done, total))
    }

    private fun buildNotification(
        text: String,
        label: String?,
        done: Int,
        total: Int
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, CacheRunnerService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.cancel),
                cancelIntent
            )
        if (total > 0) builder.setProgress(total, done.coerceIn(0, total), false)
        return builder.build()
    }

    private fun notifyProgress(notification: Notification) {
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun notifySummary(title: String, text: String) {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .notify(NOTIF_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW
                    ).apply {
                        description = getString(R.string.notif_channel_desc)
                    }
                )
            }
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        try {
            engine?.cancel()
        } catch (_: Exception) {
        }
        scope.cancel()
        super.onDestroy()
    }
}

/** In-memory ring buffer of the current/last run's log lines for the UI. */
object RunLog {
    private const val MAX = 400
    private val lock = Any()
    private val lines = ArrayDeque<String>()

    @Volatile
    var version: Long = 0
        private set

    fun append(line: String) {
        // Mirror every run-log line (including [dbg] engine diagnostics) to
        // logcat so `adb logcat` captures the full automation trace.
        try {
            Log.d("CacheCleaner", line)
        } catch (_: Exception) {
        }
        // …and to the persistent log.json so the whole history survives
        // app restarts and can be downloaded from the app.
        try {
            FileLogger.log("run", line)
        } catch (_: Exception) {
        }
        // …and to the diag runlog-*.txt file on external files dir
        // (DIAG builds: the file the tester sends back).
        try {
            FileLog.append(line)
        } catch (_: Exception) {
        }
        synchronized(lock) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
            version++
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            version++
        }
    }
}
