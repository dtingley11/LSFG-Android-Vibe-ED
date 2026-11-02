package com.lsfg.fork.session

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.lsfg.fork.shizuku.IShizukuCaptureService
import com.lsfg.fork.shizuku.IShizukuFrameCallback
import com.lsfg.fork.wsi.WsiBridge
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class RootCaptureService : RootService() {

    override fun onBind(intent: Intent): IBinder = Impl()

    inner class Impl : IShizukuCaptureService.Stub() {

        private val running = AtomicBoolean(false)
        private var worker: Thread? = null
        private var commandNs = -1L
        private var readNs = -1L
        private var decodeNs = -1L
        private var copyNs = -1L

        override fun startCapture(
            targetUid: Int,
            width: Int,
            height: Int,
            maxFps: Int,
            callback: IShizukuFrameCallback,
        ) {
            stopCapture()
            val periodMs = (1000L / maxFps.coerceIn(15, 120)).coerceAtLeast(8L)
            running.set(true)
            worker = Thread(
                { runCaptureLoop(targetUid, width, height, periodMs, callback) },
                "lsfg-root-capture",
            ).also { it.start() }
        }

        override fun stopCapture() {
            running.set(false)
            worker?.interrupt()
            worker = null
        }

        override fun describeBackend(): String =
            "root uid=${android.os.Process.myUid()} sdk=${android.os.Build.VERSION.SDK_INT}"

        override fun destroy() {
            stopCapture()
            System.exit(0)
        }

        private fun runCaptureLoop(
            targetUid: Int,
            width: Int,
            height: Int,
            periodMs: Long,
            callback: IShizukuFrameCallback,
        ) {
            // Primary backend: hidden ScreenCapture API (UID-filtered, GPU-side).
            val capture = runCatching { PrivilegedScreenCapture(width, height, targetUid) }
                .onFailure { e -> Log.w(TAG, "Privileged ScreenCapture unavailable", e) }
                .getOrNull()
            if (capture == null) {
                // Screencap fallback is always available with root; log once that
                // we are using it (e.g. ColorOS 16 may not support the hidden API).
                Log.w(TAG, "using screencap fallback backend (privileged ScreenCapture unavailable)")
            }

            var lastFrameNs = 0L
            val targetPeriodNs = periodMs * 1_000_000L
            var frameLogCount = 0
            var nullStreak = 0
            while (running.get()) {
                val started = SystemClock.uptimeMillis()
                val captureStartedNs = System.nanoTime()
                commandNs = -1; readNs = -1; decodeNs = -1; copyNs = -1
                // Try the GPU-side capture first; if it yields nothing (some
                // vendors return null / throw), fall back to screencap so the
                // session still gets frames.
                var hb = if (capture != null) {
                    runCatching { capture.captureHardwareBuffer() }
                        .onFailure {
                            Log.w(TAG, "captureHardwareBuffer failed", it)
                            callback.onError("Root capture failed: ${it.message ?: it.javaClass.simpleName}")
                        }
                        .getOrNull()
                } else null

                var source = "screenCapture"
                if (hb == null) {
                    nullStreak++
                    if (nullStreak == 10) {
                        Log.w(TAG, "ScreenCapture yielded 0 frames for 10 attempts; " +
                            "falling back to screencap for this cycle")
                    }
                    hb = captureScreencap(width, height)
                    source = "screencap"
                } else {
                    nullStreak = 0
                }

                if (hb != null) {
                    if (frameLogCount < 8) {
                        frameLogCount++
                        Log.i(TAG, "root frame #$frameLogCount src=$source uid=$targetUid ${hb.width}x${hb.height} fmt=${hb.format}")
                    }
                    val timestampNs = System.nanoTime()
                    val frameTimeNs = if (lastFrameNs > 0L) timestampNs - lastFrameNs else 0L
                    val pacingJitterNs = if (frameTimeNs > 0L) kotlin.math.abs(frameTimeNs - targetPeriodNs) else 0L
                    lastFrameNs = timestampNs
                    try {
                        callback.onCaptureMetrics(captureStartedNs, timestampNs - captureStartedNs,
                            commandNs, readNs, decodeNs, copyNs, source)
                        callback.onFrameMetrics(timestampNs, frameTimeNs, pacingJitterNs)
                        callback.onFrame(hb, timestampNs)
                    } catch (t: Throwable) {
                        Log.w(TAG, "frame callback failed", t)
                        running.set(false)
                    } finally {
                        runCatching { hb.close() }
                    }
                }

                val elapsed = SystemClock.uptimeMillis() - started
                val sleepMs = periodMs - elapsed
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        /**
         * Root `screencap` -> PNG in /data/local/tmp -> decode -> fresh AHB.
         * CPU path (slower than the GPU-side ScreenCapture) but works on every
         * rooted device, including ColorOS 16 where captureDisplay may return
         * null for a UID-filtered capture.
         */
        private fun captureScreencap(width: Int, height: Int): HardwareBuffer? {
            val path = "/data/local/tmp/lsfg_root_cap.png"
            val commandStart = System.nanoTime()
            val res = runCatching {
                com.topjohnwu.superuser.Shell.cmd("screencap -p $path").exec()
            }.getOrElse { return null }
            commandNs = System.nanoTime() - commandStart
            if (!res.isSuccess) return null
            val readStart = System.nanoTime()
            val png = runCatching { File(path).readBytes() }.getOrNull() ?: return null
            readNs = System.nanoTime() - readStart
            val decodeStart = System.nanoTime()
            val src = BitmapFactory.decodeByteArray(png, 0, png.size)
            decodeNs = System.nanoTime() - decodeStart
            runCatching { File(path).delete() }
            if (src == null) return null
            val copyStart = System.nanoTime()
            return try {
                val ahb = HardwareBuffer.create(
                    width, height, PixelFormat.RGBA_8888, 1,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
                )
                val scaled = if (src.width != width || src.height != height) {
                    android.graphics.Bitmap.createScaledBitmap(src, width, height, true)
                } else src
                val argb = IntArray(width * height)
                try { scaled.getPixels(argb, 0, width, 0, 0, width, height) } finally {
                    if (scaled !== src) scaled.recycle()
                }
                src.recycle()
                val rgba = ByteArray(width * height * 4)
                var o = 0
                for (a in argb) {
                    rgba[o++] = ((a shr 16) and 0xFF).toByte()
                    rgba[o++] = ((a shr 8) and 0xFF).toByte()
                    rgba[o++] = (a and 0xFF).toByte()
                    rgba[o++] = ((a shr 24) and 0xFF).toByte()
                }
                if (WsiBridge.fillAhb(ahb, rgba, width * 4) == 0L) {
                    ahb.close()
                    null
                } else ahb
            } catch (t: Throwable) {
                Log.w(TAG, "screencap fallback failed", t)
                null
            } finally { copyNs = System.nanoTime() - copyStart }
        }
    }

    companion object {
        private const val TAG = "RootCaptureService"
    }
}
