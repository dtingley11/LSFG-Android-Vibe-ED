package com.lsfg.fork.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.projection.MediaProjection
import com.lsfg.fork.Log
import com.lsfg.fork.wsi.WsiBridge
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Screen capture engine with three modes:
 *  - "root":     root `screencap` -> PNG file in the app's private dir ->
 *                direct File read (fast, no base64 over the shell pipe).
 *  - "shizuku":  Shizuku/shell `screencap` -> /data/local/tmp -> base64 read
 *                (shell uid cannot write into the app's private dir).
 *  - "nonroot":  MediaProjection + VirtualDisplay + ImageReader (scrcpy-style).
 *
 * All modes write into a fixed ring of [HardwareBuffer] slots so the WSI
 * presenter only ever imports a small, stable set of Vulkan images (no per-frame
 * AHB import / GPU-memory growth).
 */
class CaptureEngine(
    private val appContext: Context,
    private val width: Int,
    private val height: Int,
    private val densityDpi: Int = 0,
    private val minIntervalMs: Long = 33,
    private val onStats: (framesPerSec: Float, avgLatencyMs: Float, err: String?) -> Unit = { _, _, _ -> },
    /** When set, each captured source frame (AHB + native ptr) drives framegen.
     *  Return the number of frames pushed (0 => passthrough fallback). */
    @Volatile var frameSink: ((srcAhb: HardwareBuffer, srcAhbPtr: Long) -> Int)? = null,
    /** MediaProjection for nonroot mode (null for root/shizuku). */
    private val projection: MediaProjection?,
    /**
     * CRITICAL (Jan: "ensure capture can start by any means possible"): when a
     * non-root session has no working MediaProjection (consent lost, picker
     * killed the app, getMediaProjection failed) but the device is rooted, fall
     * back to root `screencap` so capture STILL starts. Default false.
     */
    @Volatile var rootFallback: Boolean = false
) {
    @Volatile var running: Boolean = false
        private set

    @Volatile var mode: String = "root"
    @Volatile var lastError: String? = null
    @Volatile var lastFrameSource: String = ""

    private var worker: Thread? = null
    private val lock = Any()
    private val tmpPath = "/data/local/tmp/lsfg_cap.png"   // shizuku path
    private val rootFile: File = File(appContext.filesDir, "lsfg_cap.png") // root path

    // ---- capture source AHBs (fresh per frame, NOT reused) ----
    // CRITICAL FIX (bug: native crash sig=6 in WSI present loop on first
    // captured frame): the old code reused a fixed 6-AHB ring, but the WSI
    // caches the imported VkImage by AHB pointer and re-reads it on the next
    // frame while the capture thread has already overwritten that ring slot ->
    // use-after-free -> abort. The test pattern (fresh AHB per frame) never
    // hit this, which is why "all modes" testing looked fine. Fresh AHBs per
    // frame (as the reference and the test pattern use) eliminate the race.
    // The WSI releases the source AHB after the GPU finishes with it.

    // ---- nonroot (MediaProjection) state ----
    @Volatile private var imageReader: ImageReader? = null
    @Volatile private var virtualDisplay: VirtualDisplay? = null

    // ---- stats ----
    @Volatile private var frames = 0
    @Volatile private var latencySum = 0L
    @Volatile private var lastStatsTime = 0L
    @Volatile private var consecutiveErrors = 0
    @Volatile private var lastFrameMs = 0L

    private fun newSourceAhb(): HardwareBuffer {
        PipelineStats.allocations.incrementAndGet()
        return HardwareBuffer.create(
            width, height, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
    }

    /**
     * Release the Java reference to a source AHB once the frame has been
     * consumed (framegen blit complete / pushed to the presenter). The native
     * side holds its own AHardwareBuffer ref until the GPU is done with it, so
     * closing the Java object here is safe and stops the per-frame native
     * buffer leak (each captured frame allocated a fresh AHB).
     */
    private fun releaseSource(ahb: HardwareBuffer) {
        try { ahb.close() } catch (_: Throwable) {}
    }

    fun start() {
        synchronized(lock) {
            if (running) return
            running = true
            frames = 0
            latencySum = 0
            lastError = null
            consecutiveErrors = 0
            lastStatsTime = System.currentTimeMillis()

            if (mode == "nonroot") {
                setupNonroot(projection)
            }
            Log.i("CaptureEngine", "start mode=$mode ${width}x${height} interval=${minIntervalMs}ms")

            worker = thread(name = "lsfg-capture") {
                while (running) {
                    val t0 = System.currentTimeMillis()
                    try {
                        val ahb = PipelineStats.measure("capture") { captureOnce() }
                        if (ahb != null) {
                            PipelineStats.incAcquired()
                            val ahbPtr = WsiBridge.nativeAhbPtr(ahb)
                            val sink = frameSink
                            val produced = if (sink != null) {
                                try {
                                    sink(ahb, ahbPtr)
                                } catch (t: Throwable) {
                                    lastError = t.message ?: "framegen failed"
                                    PipelineStats.incErrors()
                                    Log.w("CaptureEngine", "frameSink: $lastError")
                                    0
                                }
                            } else 0

                            if (produced <= 0 && ahbPtr != 0L) {
                                // Passthrough (framegen off / unavailable): present source.
                                WsiBridge.nativePushFrame(ahbPtr)
                                PipelineStats.incSubmitted()
                            }
                            frames++
                            latencySum += System.currentTimeMillis() - t0
                            consecutiveErrors = 0
                            releaseSource(ahb)
                        } else {
                            // Capture-by-any-means: if MediaProjection produced
                            // nothing this cycle and we have a root fallback,
                            // use a root screencap so the session still gets frames.
                            var fallbackUsed = false
                            if (mode == "nonroot" && rootFallback) {
                                val fb = captureRootFallback()
                                if (fb != null) {
                                    PipelineStats.incAcquired()
                                    val ahbPtr = WsiBridge.nativeAhbPtr(fb)
                                    val sink = frameSink
                                    val produced = if (sink != null) {
                                        try { sink(fb, ahbPtr) } catch (t: Throwable) { lastError = t.message; PipelineStats.incErrors(); 0 }
                                    } else 0
                                    if (produced <= 0 && ahbPtr != 0L) {
                                        WsiBridge.nativePushFrame(ahbPtr)
                                        PipelineStats.incSubmitted()
                                    }
                                    lastFrameSource = "root-fallback"
                                    frames++
                                    latencySum += System.currentTimeMillis() - t0
                                    consecutiveErrors = 0
                                    releaseSource(fb)
                                    fallbackUsed = true
                                }
                            }
                            if (!fallbackUsed && mode == "nonroot") {
                                Thread.sleep(4)
                            }
                        }
                    } catch (t: Throwable) {
                        lastError = t.message ?: "capture failed"
                        PipelineStats.incErrors()
                        consecutiveErrors++
                        if (consecutiveErrors in listOf(1, 5, 20) || consecutiveErrors % 25 == 0) {
                            Log.w("CaptureEngine", "capture error #$consecutiveErrors: $lastError")
                        }
                        if (consecutiveErrors >= 25) {
                            onStats(0f, 0f, "capture failing repeatedly: ${lastError}")
                            consecutiveErrors = 0
                        }
                    }

                    // Pacing.
                    val elapsed = System.currentTimeMillis() - t0
                    val sleepMs = minIntervalMs - elapsed
                    if (sleepMs > 0 && running) {
                        try { Thread.sleep(sleepMs) } catch (_: InterruptedException) { break }
                    }

                    // Stats every second.
                    val now = System.currentTimeMillis()
                    if (now - lastStatsTime >= 1000L) {
                        val dt = (now - lastStatsTime) / 1000f
                        val fps = frames / dt
                        val avgLat = if (frames > 0) latencySum / frames.toFloat() else 0f
                        if (fps > 0f) Log.i("CaptureEngine", String.format("%.1f fps cap avg %.0fms src=%s", fps, avgLat, lastFrameSource))
                        onStats(fps, avgLat, lastError)
                        frames = 0
                        latencySum = 0
                        lastStatsTime = now
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        Log.i("CaptureEngine", "stop")
        try { worker?.interrupt() } catch (_: Throwable) {}
        try { worker?.join(800) } catch (_: Throwable) {}
        worker = null
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Throwable) {}
        imageReader = null
        try { ShellUtils.fastCmd("rm -f $tmpPath") } catch (_: Throwable) {}
        try { if (rootFile.exists()) rootFile.delete() } catch (_: Throwable) {}
    }

    /** Set up MediaProjection -> VirtualDisplay -> ImageReader (nonroot). */
    private fun setupNonroot(proj: MediaProjection?) {
        if (proj == null) {
            lastError = "nonroot mode requested but no MediaProjection available"
            Log.w("CaptureEngine", lastError!!)
            return
        }
        val dpi = if (densityDpi > 0) densityDpi else 320
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4)
        imageReader = reader
        val vd = proj.createVirtualDisplay(
            "lsfg-capture", width, height, dpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, null
        )
        virtualDisplay = vd
        lastFrameSource = "mediaprojection"
        Log.i("CaptureEngine", "nonroot VirtualDisplay ${width}x${height}@${dpi}dpi")
    }

    /** Capture one frame into the next ring slot. Returns the filled AHB or null. */
    private fun captureOnce(): HardwareBuffer? {
        return when (mode) {
            "nonroot" -> captureNonroot()
            "shizuku" -> captureShizuku()
            else -> captureRoot()
        }
    }

    // ---- nonroot: MediaProjection ImageReader -> ring (fast CPU fill) ----
    private fun captureNonroot(): HardwareBuffer? {
        val reader = imageReader ?: run {
            lastError = "nonroot: ImageReader not initialized (grant screen capture)"
            return null
        }
        val image = reader.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val buf: ByteBuffer = plane.buffer
            val n = buf.remaining()
            if (n < width * height * 4) return null
            val copyStart = System.nanoTime()
            val bytes = ByteArray(n)
            buf.duplicate().get(bytes)
            val ahb = newSourceAhb()
            val ptr = WsiBridge.fillAhb(ahb, bytes, plane.rowStride)
            if (ptr == 0L) {
                lastError = "nonroot: fillAhb failed"
                return null
            }
            PipelineStats.record("pixelCopy", System.nanoTime() - copyStart)
            lastFrameSource = "mediaprojection"
            return ahb
        } finally {
            image.close()
        }
    }

    /** Root fallback for non-root mode: same screencap->file path as root mode. */
    private fun captureRootFallback(): HardwareBuffer? {
        val res = Shell.cmd("screencap -p ${rootFile.absolutePath}").exec()
        if (!res.isSuccess) {
            Log.w("CaptureEngine", "rootFallback screencap failed (code ${res.code}): ${res.err}")
            return null
        }
        val src = BitmapFactory.decodeFile(rootFile.absolutePath)
        try { if (rootFile.exists()) rootFile.delete() } catch (_: Throwable) {}
        if (src == null) {
            Log.w("CaptureEngine", "rootFallback png decode failed")
            return null
        }
        return fillRing(src)
    }

    // ---- root: screencap -> app filesDir -> direct File read (fast) ----
    private fun captureRoot(): HardwareBuffer? {
        // screencap writes a PNG into the app's private files dir. Root can
        // write there even though the dir is app-owned; the app then reads the
        // file directly (no base64 transfer over the shell pipe).
        val res = Shell.cmd("screencap -p ${rootFile.absolutePath}").exec()
        if (!res.isSuccess) {
            lastError = "screencap failed (code ${res.code}): ${res.err}"
            return null
        }
        val src = BitmapFactory.decodeFile(rootFile.absolutePath)
        try { if (rootFile.exists()) rootFile.delete() } catch (_: Throwable) {}
        if (src == null) {
            lastError = "png decode failed (root)"
            return null
        }
        return fillRing(src)
    }

    // ---- shizuku: screencap -> /data/local/tmp -> base64 read ----
    private fun captureShizuku(): HardwareBuffer? {
        val res = Shell.cmd("screencap -p $tmpPath").exec()
        if (!res.isSuccess) {
            lastError = "screencap failed (code ${res.code}): ${res.err}"
            return null
        }
        val png = readFileB64()
        try { ShellUtils.fastCmd("rm -f $tmpPath") } catch (_: Throwable) {}
        if (png == null) {
            lastError = "screencap read-back failed (shizuku)"
            return null
        }
        val src = BitmapFactory.decodeByteArray(png, 0, png.size)
        if (src == null) {
            lastError = "png decode failed (shizuku)"
            return null
        }
        return fillRing(src)
    }

    /** Scale (if needed) and blit a decoded source bitmap into the next ring slot.
     *
     * CRITICAL FIX (bug: "capture error: Immutable bitmap passed to Canvas
     * constructor" on every frame -> root capture produced ZERO frames -> the
     * overlay was never visibly updated):
     *  - BitmapFactory.decodeFile/decodeByteArray return IMMUTABLE bitmaps on
     *    modern Android. The old code wrapped the ring AHB in a
     *    Bitmap.wrapHardwareBuffer bitmap and did Canvas(hw).drawBitmap(...).
     *    A wrapped-HardwareBuffer bitmap is IMMUTABLE, and the Canvas(Bitmap)
     *    constructor rejects an immutable bitmap -> threw every frame.
     *  - Fix: never construct a Canvas on a wrapped-HardwareBuffer bitmap.
     *    Scale into a small throwaway MUTABLE CPU bitmap (Canvas on a CPU
     *    bitmap is always legal; drawBitmap() only READS the immutable source
     *    so that is fine), then copy the pixels into the AHB with a plain CPU
     *    memcpy (WsiBridge.nativeFillAhb). This path has no Canvas-on-hardware
     *    buffer and no immutability assumption, so it works on every device.
     */
    /**
     * Read [src] into a fresh RGBA8888 ByteArray sized [w]x[h], scaling if needed.
     * Uses getPixels() (ARGB IntArray) which is always legal to READ from an
     * immutable bitmap, then packs to RGBA8888 bytes. No Canvas, no immutability.
     */
    private fun readRgba8888(src: Bitmap, w: Int, h: Int): ByteArray? {
        val scaled: Bitmap = if (src.width != w || src.height != h) {
            Bitmap.createScaledBitmap(src, w, h, true)
        } else src
        val argb = IntArray(w * h)
        try { scaled.getPixels(argb, 0, w, 0, 0, w, h) } finally {
            if (scaled !== src) scaled.recycle()
        }
        val out = ByteArray(w * h * 4)
        var o = 0
        for (a in argb) {
            out[o++] = ((a shr 16) and 0xFF).toByte()   // R
            out[o++] = ((a shr 8) and 0xFF).toByte()    // G
            out[o++] = (a and 0xFF).toByte()            // B
            out[o++] = ((a shr 24) and 0xFF).toByte()   // A
        }
        return out
    }

    private fun fillRing(src: Bitmap): HardwareBuffer? {
        val ahb = newSourceAhb()
        try {
            val rgba = readRgba8888(src, width, height)
                ?: run { lastError = "fillRing: readRgba8888 failed"; return null }
            val ptr = WsiBridge.fillAhb(ahb, rgba, width * 4)
            if (ptr == 0L) { lastError = "fillRing: fillAhb failed"; return null }
            lastFrameSource = if (mode == "shizuku") "shizuku" else "root"
            return ahb
        } catch (t: Throwable) {
            lastError = "fillRing: ${t.message}"
            return null
        } finally {
            try { src.recycle() } catch (_: Throwable) {}
        }
    }

    private fun readFileB64(): ByteArray? {
        val out = Shell.cmd("base64 -w 0 $tmpPath").exec()
        if (!out.isSuccess) return null
        val b64 = out.getOut().joinToString("")
        if (b64.isEmpty()) return null
        return try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Throwable) {
            null
        }
    }

    fun release() {
        stop()
    }

    /** FPS listener contract shared by the ported Root/Shizuku capture engines. */
    fun interface FpsListener {
        fun onFpsUpdate(realFps: Float, totalFps: Float)
    }

    /** Frame-graph (real vs generated fps) listener contract. */
    fun interface FrameGraphListener {
        fun onFrameGraphSample(realFps: Float, generatedFps: Float)
    }
}
