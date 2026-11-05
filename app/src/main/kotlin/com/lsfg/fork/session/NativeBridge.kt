package com.lsfg.fork.session

import android.hardware.HardwareBuffer
import com.lsfg.fork.capture.PipelineStats
import com.lsfg.fork.wsi.WsiBridge

/**
 * JNI entry points used by the ported LSFG-Android session/ capture skeleton
 * (RootCaptureEngine / ShizukuCaptureEngine). In the reference app this object
 * is the single native bridge into liblsfg-android.so; in this fork the native
 * side is split between liblsfg_wsi.so (WsiBridge) and liblsfg_framegen.so
 * (FramegenBridge), so this object routes the calls accordingly.
 */
object NativeBridge {

    /**
     * Session-level frame sink set by SessionManager: receives every captured
     * frame. Callbacks received after session stop are discarded.
     */
    @Volatile
    var sessionFrameSink: ((HardwareBuffer, Long) -> Unit)? = null

    /**
     * Hands a fresh capture frame (HardwareBuffer) to the pipeline. The
     * sink/presenter takes its own reference, so the caller may close the AHB
     * right after this returns.
     */
    fun pushFrame(hardwareBuffer: HardwareBuffer, timestampNs: Long) {
        val sink = sessionFrameSink
        if (sink != null) {
            try {
                sink(hardwareBuffer, timestampNs)
                return
            } catch (t: Throwable) {
                PipelineStats.incDropped()
                PipelineStats.incErrors()
                LsfgLog.w(TAG, "sessionFrameSink threw: ${t.message}")
            }
        }
        // A late Binder callback after stop must not revive the presentation queue.
        PipelineStats.incDropped()
    }

    /** Completed interpolated output copies, excluding original endpoints. */
    fun getGeneratedFrameCount(): Long = PipelineStats.generated.get()

    /** Total frames actually posted to the overlay surface (ground truth). */
    fun getPostedFrameCount(): Long =
        try { WsiBridge.framesPresented() } catch (t: Throwable) { 0L }

    /** Unique game-render frames are not measured by capture callbacks. */
    fun getUniqueCaptureCount(): Long = 0L

    /** Shizuku timing side channel (pacing only, never the visible source). */
    fun reportShizukuTiming(timestampNs: Long, frameTimeNs: Long, pacingJitterNs: Long) {
        // Accepted but unused in the fork: pacing is driven by the capture
        // loop's own interval, not by Shizuku timing samples.
    }

    private const val TAG = "NativeBridge"
}
