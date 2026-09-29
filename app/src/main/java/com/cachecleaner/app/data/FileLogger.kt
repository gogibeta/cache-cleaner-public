package com.cachecleaner.app.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Persistent, always-on diagnostic log.
 *
 * Every line is one JSON object (JSON Lines) appended to `log.json` in the
 * app's private files directory:
 *
 *   {"ts":1727...,"time":"2026-09-29T23:31:02.123+05:30","session":"a1b2c3d4",
 *    "level":"INFO","tag":"engine","msg":"Force-stop button found, clicking"}
 *
 * - The file is NEVER cleared on app start or on run start: stopping the app
 *   and opening it again keeps every previous session's lines, each tagged
 *   with its session id.
 * - Writes happen on a dedicated background thread; logging never blocks
 *   the UI or the automation.
 * - At 5 MB the file is rotated to `log-prev.json` (one backup kept).
 * - The user can download/share the file from the app's log panel.
 */
object FileLogger {

    private const val FILE_NAME = "log.json"
    private const val PREV_NAME = "log-prev.json"
    private const val MAX_BYTES = 5L * 1024 * 1024

    /** Short id regenerated on every app start; ties lines to one launch. */
    @Volatile
    var sessionId: String = UUID.randomUUID().toString().take(8)
        private set

    @Volatile
    private var dir: File? = null

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FileLogger").apply { isDaemon = true }
    }

    private val timeFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }
    private val fileNameFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    /** Must be called once from the Application class. */
    fun init(context: Context) {
        sessionId = UUID.randomUUID().toString().take(8)
        dir = context.filesDir
        try {
            val f = logFile()
            if (f != null && f.exists() && f.length() > MAX_BYTES) rotate()
        } catch (_: Exception) {
        }
        log(
            "app", "session started (log.json persists across restarts)",
            level = "INFO",
            data = mapOf(
                "device" to ("${Build.MANUFACTURER} ${Build.MODEL}"),
                "android" to Build.VERSION.RELEASE,
                "sdk" to Build.VERSION.SDK_INT.toString(),
                "miui" to miuiVersion()
            )
        )
    }

    fun log(tag: String, msg: String, level: String = "INFO", data: Map<String, String>? = null) {
        val d = dir ?: return
        val now = System.currentTimeMillis()
        val time = try {
            synchronized(timeFmt) { timeFmt.format(Date(now)) }
        } catch (_: Exception) {
            now.toString()
        }
        val sb = StringBuilder(256)
        sb.append('{')
        sb.append("\"ts\":").append(now).append(',')
        sb.append("\"time\":\"").append(esc(time)).append("\",")
        sb.append("\"session\":\"").append(sessionId).append("\",")
        sb.append("\"level\":\"").append(esc(level)).append("\",")
        sb.append("\"tag\":\"").append(esc(tag)).append("\",")
        sb.append("\"msg\":\"").append(esc(msg)).append('"')
        if (data != null) {
            for ((k, v) in data) {
                sb.append(",\"").append(esc(k)).append("\":\"").append(esc(v)).append('"')
            }
        }
        sb.append('}')
        val line = sb.toString()
        executor.execute {
            try {
                val f = File(d, FILE_NAME)
                if (f.exists() && f.length() > MAX_BYTES) rotate()
                f.appendText(line + "\n")
            } catch (_: Exception) {
            }
        }
    }

    fun logException(tag: String, where: String, e: Throwable) {
        log(
            tag, "EXCEPTION in $where: ${e.javaClass.simpleName}: ${e.message}",
            level = "ERROR",
            data = mapOf(
                "stack" to (e.stackTrace.take(12).joinToString(" <- ") { it.toString() })
            )
        )
    }

    /** The live log file (may not exist yet). */
    fun logFile(): File? {
        val d = dir ?: return null
        return File(d, FILE_NAME)
    }

    /** Number of JSON lines currently stored (live file + rotated backup). */
    fun lineCount(): Long {
        return try {
            var n = 0L
            for (name in arrayOf(FILE_NAME, PREV_NAME)) {
                val f = dir?.let { File(it, name) } ?: continue
                if (f.exists()) f.forEachLine { n++ }
            }
            n
        } catch (_: Exception) {
            -1
        }
    }

    private fun rotate() {
        try {
            val d = dir ?: return
            val cur = File(d, FILE_NAME)
            val prev = File(d, PREV_NAME)
            if (prev.exists()) prev.delete()
            if (cur.exists()) cur.renameTo(prev)
        } catch (_: Exception) {
        }
    }

    /**
     * Copies log.json (+ the rotated backup's tail) into the public
     * Downloads folder. Returns the display name on success, null otherwise.
     */
    fun copyToDownloads(context: Context): String? {
        return try {
            val src = logFile() ?: return null
            if (!src.exists()) return null
            val displayName = "cachecleaner-log-${fileNameFmt.format(Date())}.json"
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS
                    )
                }
                val uri: Uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                log("app", "log.json downloaded to Downloads/$displayName")
                displayName
            } else {
                @Suppress("DEPRECATION")
                val dest = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    displayName
                )
                src.copyTo(dest, overwrite = true)
                log("app", "log.json downloaded to ${dest.absolutePath}")
                displayName
            }
        } catch (e: Exception) {
            logException("app", "copyToDownloads", e)
            null
        }
    }

    /** Share intent for log.json via FileProvider (Drive, WhatsApp, …). */
    fun shareIntent(context: Context): Intent? {
        return try {
            val src = logFile() ?: return null
            if (!src.exists()) return null
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", src
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (e: Exception) {
            logException("app", "shareIntent", e)
            null
        }
    }

    private fun miuiVersion(): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val m = cls.getMethod("get", String::class.java)
            (m.invoke(cls, "ro.miui.ui.version.name") as? String).orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }
}
