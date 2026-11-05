package com.lsfg.fork

import android.content.Context
import android.util.Log as ALog
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Central logging for the LSFG-Fork app.
 *
 * Every log line is written to the logcat (tag "LSFG") AND appended to a
 * persistent on-device file:  <filesDir>/logs/lsfg-<build>-<date>.log
 *
 * The file is the source of truth for diagnosing on-device problems
 * (overlay attach failures, framegen init errors, capture failures, etc.)
 * because the logcat may not be visible. Users can pull it with:
 *
 *   adb shell run-as com.lsfg.fork cat files/logs/lsfg-*.log
 *   adb shell su -c "cat /data/data/com.lsfg.fork/files/logs/lsfg-*.log"
 *
 * or export it from the app's Logs tab (copy to Downloads / share).
 *
 * Threading: log calls are cheap and non-blocking. Lines are queued to a
 * single background writer thread so a slow SD card / encrypted fs can never
 * stall the capture pipeline. The queue is bounded (oldest lines dropped) so
 * a logging burst can never grow memory without limit.
 *
 * Levels: D < I < W < E. Native C++ code logs under the "lsfg-wsi" /
 * "lsfg-framegen" tags which also appear in logcat; this class is for the
 * Kotlin layer. To capture native lines into the same file, use adb logcat
 * redirection on-device:  logcat -s lsfg-wsi:V lsfg-framegen:V lsfg-service:V > log
 */
object Log {

    const val TAG = "LSFG"

    enum class Level(val code: Int, val label: String) {
        D(2, "D"),
        I(3, "I"),
        W(4, "W"),
        E(5, "E");

        companion object {
            fun fromCode(c: Int): Level = values().first { it.code == c }
        }
    }

    // Minimum level that gets persisted (I = info and above). Change to D for
    // verbose on-device debugging.
    const val MIN_PERSIST = 3 // I

    @Volatile
    private var logFile: File? = null
    @Volatile
    private var writerThread: Thread? = null
    @Volatile
    private var shutdown = false

    private val queue = LinkedBlockingQueue<String>(2048)

    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    data class Entry(val ts: String, val level: Level, val tag: String, val msg: String)

    private fun logDir(ctx: Context): File =
        File(ctx.filesDir, "logs").apply { if (!exists()) mkdirs() }

    /** Initialize the log file for this app process (idempotent). */
    fun init(ctx: Context) {
        if (logFile != null) return
        val f = File(logDir(ctx), "lsfg-${dayFmt.format(Date())}.log")
        logFile = f
        startWriter(f)
        d(TAG, "log file initialized: ${f.absolutePath}")
    }

    /**
     * Background writer that KEEPS THE FILE OPEN.
     *
     * This used to open a BufferedWriter(FileWriter(f, true)) and close it again
     * for EVERY log line. That was a real crash cause on this device, not just
     * inefficiency, so it must not come back:
     *
     * The Adreno driver, inside vkQueueSubmit -> gsl_syncobj_destroy, calls
     * close() on a descriptor it does not own; on this device that is fd 0. An
     * ordinary stray close(0) is harmless BECAUSE fd 0 is /dev/null. But a
     * per-line open grabs the LOWEST free descriptor, so the moment the driver
     * freed fd 0 the log writer claimed fd 0 and Java tagged it as owned by a
     * FileOutputStream. The driver's next stray close(0) then closed a
     * Java-owned descriptor, and bionic's fdsan aborted the process:
     *
     *   fdsan: attempted to close file descriptor 0 ... actually owned by
     *   FileOutputStream
     *   #04 libgsl.so gsl_syncobj_destroy
     *   #07 vulkan.adreno.so qglinternal::vkQueueSubmit
     *
     * Holding ONE handle open for the process lifetime means the log writer
     * never races to claim fd 0, so the driver's stray close frees only an
     * already-unowned /dev/null slot and the abort cannot be triggered.
     */
    private fun startWriter(f: File) {
        if (writerThread != null) return
        shutdown = false
        writerThread = thread(name = "lsfg-log-writer", isDaemon = true) {
            var w: BufferedWriter? = null
            var sinceFlush = 0

            while (!shutdown) {
                val line = try {
                    queue.take()
                } catch (ie: InterruptedException) {
                    if (shutdown) break else continue
                }
                try {
                    var out = w
                    if (out == null) {
                        out = BufferedWriter(FileWriter(f, true))
                        w = out
                    }
                    out.write(line)
                    out.newLine()
                    if (++sinceFlush >= 16) {
                        out.flush()
                        sinceFlush = 0
                    }
                } catch (_: Throwable) {
                    // Disk full / IO error: never crash the app for logging.
                    // Drop the handle so the next line retries a fresh open.
                    try { w?.close() } catch (_: Throwable) {}
                    w = null
                }
            }

            try {
                w?.flush()
                w?.close()
            } catch (_: Throwable) {}
        }
        // Rotate on start: keep at most 8 log files, drop the oldest.
        try {
            val dir = f.parentFile
            if (dir != null && dir.exists()) {
                val files = dir.listFiles { x -> x.name.startsWith("lsfg-") }
                    ?.sortedByDescending { it.lastModified() }
                if (files != null && files.size > 8) {
                    files.drop(8).forEach { try { it.delete() } catch (_: Throwable) {} }
                }
            }
        } catch (_: Throwable) {}
    }

    /** Log a line at the given level. Always mirrors to logcat. */
    fun log(level: Level, tag: String, msg: String) {
        // logcat first (immediate, for developers with adb connected)
        try {
            when (level) {
                Level.D -> ALog.d(TAG, "[$tag] $msg")
                Level.I -> ALog.i(TAG, "[$tag] $msg")
                Level.W -> ALog.w(TAG, "[$tag] $msg")
                Level.E -> ALog.e(TAG, "[$tag] $msg")
            }
        } catch (_: Throwable) {}

        val min = MIN_PERSIST
        if (level.code < min) return
        val line = "${tsFmt.format(Date())} ${level.label} [$tag] $msg"
        if (!queue.offer(line)) {
            // Queue full: drop oldest to make room (best effort, non-blocking).
            queue.poll()
            queue.offer(line)
        }
    }

    fun d(tag: String, msg: String) = log(Level.D, tag, msg)
    fun i(tag: String, msg: String) = log(Level.I, tag, msg)
    fun w(tag: String, msg: String) = log(Level.W, tag, msg)
    fun e(tag: String, msg: String) = log(Level.E, tag, msg)

    fun d(msg: String) = log(Level.D, TAG, msg)
    fun i(msg: String) = log(Level.I, TAG, msg)
    fun w(msg: String) = log(Level.W, TAG, msg)
    fun e(msg: String) = log(Level.E, TAG, msg)

    /**
     * Native-side log entry point. The C++ framegen/WSI libraries call this via
     * JNI (see nativeLog below) so their logcat lines ALSO get persisted into the
     * exported on-device log file. Previously the exported log only contained
     * Kotlin lines, so a failure like "framegen createSession FAILED" was logged
     * in Kotlin but the REAL reason (the C++ exception / Vulkan error) only went
     * to logcat and was invisible in the user's exported file (bugs 3/4).
     */
    /**
     * Native-side log entry point. The C++ framegen/WSI libraries call this via
     * JNI so their logcat lines ALSO get persisted into the exported on-device
     * log file. Previously the exported log only contained Kotlin lines, so a
     * failure like "framegen createSession FAILED" was logged in Kotlin but the
     * REAL reason (the C++ exception / Vulkan error) only went to logcat and was
     * invisible in the user's exported file.
     */
    @JvmStatic
    fun nativeLog(level: Int, tag: String, msg: String) {
        val lv = try { Level.fromCode(level) } catch (_: Throwable) { Level.I }
        log(lv, tag, msg)
    }

    /** Path to the current log file (for the Logs UI / sharing). */
    fun currentFile(ctx: Context): File {
        init(ctx)
        return logFile ?: File(logDir(ctx), "lsfg.log")
    }

    /** List all on-device log files, newest first. */
    fun listFiles(ctx: Context): List<File> =
        logDir(ctx).listFiles { it.isFile }?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /** Read the tail of the current log file (for in-app display). */
    fun tail(ctx: Context, maxBytes: Int = 32 * 1024): String {
        val f = logFile ?: return "(no log file yet)"
        if (!f.exists()) return "(log file not found)"
        return try {
            val size = f.length()
            val read = minOf(size, maxBytes.toLong()).toInt()
            if (read <= 0) return ""
            val raf = RandomAccessFile(f, "r")
            try {
                raf.seek(size - read)
                if (raf.filePointer > 0) raf.readLine() // drop partial first line
                val sb = StringBuilder()
                var line: String? = raf.readLine()
                while (line != null && sb.length < maxBytes) {
                    sb.append(line).append('\n')
                    line = raf.readLine()
                }
                sb.toString()
            } finally {
                raf.close()
            }
        } catch (t: Throwable) {
            "log read failed: ${t.message}"
        }
    }

    /**
     * Export the current log to a location the app can always write to.
     *
     * CRITICAL FIX (bug: "export failed: EPERM"): writing to
     * Environment.getExternalStorageDirectory() (i.e. /storage/emulated/0/) is
     * blocked by scoped storage on Android 10+ (EPERM). Use the app's own
     * external-files dir first (no permission needed), and fall back to the
     * Downloads bucket via MediaStore on API 29+ so the user actually sees it
     * in their file manager.
     */
    fun exportToDownloads(ctx: Context): String? {
        val src = logFile ?: return null
        if (!src.exists()) return null
        val name = "lsfg-export-${dayFmt.format(Date())}.log"

        // 1) App's private external dir - always writable, no permission.
        try {
            val ext = ctx.getExternalFilesDir(null)
            if (ext != null) {
                val dest = File(ext, name)
                src.copyTo(dest, overwrite = true)
                i("export OK -> ${dest.absolutePath}")
                return dest.absolutePath
            }
        } catch (t: Throwable) {
            w("export (app ext dir) failed: ${t.message}")
        }

        // 2) Public Downloads via MediaStore (scoped-storage compliant).
        try {
            val cr = ctx.contentResolver
            val uri = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val newUri = cr.insert(uri, values)
            if (newUri != null) {
                cr.openOutputStream(newUri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
                i("export OK (MediaStore Downloads) -> $newUri")
                return newUri.toString()
            }
        } catch (t: Throwable) {
            w("export (MediaStore) failed: ${t.message}")
        }

        // 3) Last resort: internal files dir (always present).
        try {
            val dest = File(ctx.filesDir, "exports/$name")
            dest.parentFile?.mkdirs()
            src.copyTo(dest, overwrite = true)
            i("export OK (files/exports) -> ${dest.absolutePath}")
            return dest.absolutePath
        } catch (t: Throwable) {
            w("export failed: ${t.message}")
            return null
        }
    }
}
