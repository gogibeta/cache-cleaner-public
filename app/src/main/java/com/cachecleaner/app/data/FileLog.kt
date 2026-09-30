package com.cachecleaner.app.data

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DIAG-only automatic file log collection.
 *
 * Every [RunLog] line (the `[dbg]` automation trace) is mirrored here, so a
 * phone tester only needs to copy ONE file:
 *
 *   /sdcard/Android/data/com.cachecleaner.app/files/logs/runlog-<yyyyMMdd-HHmmss>.txt
 *
 * `getExternalFilesDir("logs")` needs no runtime permission. All failures are
 * swallowed: logging must never crash the app. Writes are cheap enough to run
 * inline (one open-append-close per line); the uncaught-exception path also
 * writes here and must not depend on background threads.
 */
object FileLog {

    @Volatile
    private var file: File? = null

    private val lock = Any()

    private val lineFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Creates the run's log file and writes the header. Call once from Application.onCreate. */
    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir("logs") ?: return
            if (!dir.exists()) dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val f = File(dir, "runlog-$stamp.txt")
            val pkg = context.packageName
            var vName = "?"
            var vCode = -1L
            try {
                @Suppress("DEPRECATION")
                val pi = context.packageManager.getPackageInfo(pkg, 0)
                vName = pi.versionName ?: "?"
                vCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            } catch (_: Exception) {
            }
            val header = buildString {
                appendLine("=== Cache Cleaner diag runlog ===")
                appendLine("package=$pkg")
                appendLine("versionName=$vName versionCode=$vCode")
                appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("androidSdkInt=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}")
                appendLine("started=${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("================================")
            }
            f.writeText(header)
            synchronized(lock) { file = f }
        } catch (_: Exception) {
            // Never fail init because of logging.
        }
    }

    /** Appends one line with a timestamp prefix. Thread-safe; never throws. */
    fun append(line: String) {
        val f = synchronized(lock) { file } ?: return
        try {
            val ts = synchronized(lineFmt) { lineFmt.format(Date()) }
            synchronized(lock) {
                f.appendText("$ts $line\n")
            }
        } catch (_: Exception) {
            // Logging must never crash the app.
        }
    }

    /** The current run's log file, or null if init failed / hasn't run. */
    fun currentFile(): File? = synchronized(lock) { file }
}
