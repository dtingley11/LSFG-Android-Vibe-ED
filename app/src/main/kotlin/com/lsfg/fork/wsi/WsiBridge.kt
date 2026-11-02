package com.lsfg.fork.wsi

import android.hardware.HardwareBuffer
import android.view.Surface

/** JNI bridge for the Vulkan WSI presenter (overlay swapchain). */
object WsiBridge {

    init {
        System.loadLibrary("lsfg_wsi")
    }

    private external fun nativeStart(windowPtr: Long, width: Int, height: Int, targetFps: Float)
    private external fun nativeStop()
    private external fun nativeIsRunning(): Boolean
    private external fun nativePerformanceStats(): String
    fun performanceStats(): String = try { nativePerformanceStats() } catch (_: Throwable) { "unavailable" }
    private external fun nativeSetPaused(paused: Boolean)
    private external fun nativeSetTargetFps(fps: Float)

    /** Convert a Java HardwareBuffer to a native AHardwareBuffer* (no ref). */
    external fun nativeAhbPtr(buffer: HardwareBuffer): Long

    /** Push a native AHardwareBuffer* into the frame queue. */
    external fun nativePushFrame(ahbPtr: Long)

    external fun nativeSurfaceToWindow(surface: Surface): Long
    external fun nativeReleaseWindow(windowPtr: Long)

    /** Number of frames currently queued for presentation (backpressure monitor). */
    external fun nativeQueueSize(): Int

    /** Successful present requests; physical display completion is not measured. */
    external fun nativeFramesPresented(): Long

    /**
     * Fast CPU fill of a HardwareBuffer ring slot from raw RGBA8888 bytes
     * (e.g. an ImageReader plane from a MediaProjection VirtualDisplay).
     * srcRowStride is in bytes and may exceed width*4 (plane padding).
     */
    external fun nativeFillAhb(ahb: HardwareBuffer, bytes: ByteArray, srcRowStride: Int)

    fun start(windowPtr: Long, width: Int, height: Int, targetFps: Float) =
        nativeStart(windowPtr, width, height, targetFps)

    fun stop() = nativeStop()
    fun isRunning() = nativeIsRunning()
    fun setPaused(paused: Boolean) = nativeSetPaused(paused)
    fun setTargetFps(fps: Float) = nativeSetTargetFps(fps)

    fun queueSize(): Int = try { nativeQueueSize() } catch (_: Throwable) { 0 }

    /** How many frames the presenter has actually presented. Used to report a
     *  TRUE pass/fail (0 while running == the Vulkan path never produced a
     *  frame, even though the UI may have said "running"). */
    fun framesPresented(): Long = try { nativeFramesPresented() } catch (_: Throwable) { 0L }

    /**
     * Fill [ahb] (width x height RGBA8888) from [bytes] and return the native
     * AHardwareBuffer pointer to feed framegen/presenter, or 0 on failure.
     */
    fun fillAhb(ahb: HardwareBuffer, bytes: ByteArray, srcRowStride: Int): Long {
        try {
            nativeFillAhb(ahb, bytes, srcRowStride)
        } catch (t: Throwable) {
            println("WsiBridge.fillAhb: $t")
            return 0L
        }
        return nativeAhbPtr(ahb)
    }
}
