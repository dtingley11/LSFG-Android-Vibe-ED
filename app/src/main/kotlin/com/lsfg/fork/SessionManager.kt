package com.lsfg.fork

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lsfg.fork.performance.*
import com.lsfg.fork.capture.CaptureEngine
import com.lsfg.fork.capture.PipelineStats
import com.lsfg.fork.capture.RootCaptureEngineAdapter
import com.lsfg.fork.capture.ShizukuCaptureEngineAdapter
import com.lsfg.fork.capture.reportPipelineStats
import com.lsfg.fork.framegen.FramegenBridge
import com.lsfg.fork.session.NativeBridge
import com.lsfg.fork.testpattern.TestPattern
import com.lsfg.fork.wsi.WsiBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * Session orchestrator.
 *
 * Pipeline:  frame source (TestPattern | CaptureEngine)
 *          -> framegen (LSFG v3_1 Vulkan pipeline, user or bundled shader DLL)
 *          -> WSI presenter (Vulkan swapchain on the overlay surface).
 *
 * Framegen is ON by default; if the shader DLL or Vulkan session fails the
 * engine transparently falls back to passthrough (source frames presented as-is).
 */
class SessionManager : ViewModel() {

    companion object {
        /** Process-wide shared instance (main UI + service + notification). */
        @Volatile
        var shared: SessionManager = SessionManager()

        /** MediaProjection consent result data (from the system dialog),
         *  stashed by the activity that received onActivityResult. */
        @Volatile
        var pendingProjectionData: android.content.Intent? = null

        /**
         * Live MediaProjection created IMMEDIATELY at consent time (in the
         * activity, on the main thread). Created here rather than later in
         * the service's beginSession because getMediaProjection() can return
         * null if the consent data is consumed late / from another process
         * context (reproduced on Android 14: 'MediaProjection created' then
         * 'no MediaProjection consent' -> zero frames). beginSession consumes
         * this first and falls back to pendingProjectionData.
         */
        @Volatile
        var pendingProjection: MediaProjection? = null

        const val TAG = "lsfg-fork"
    }

    data class State(
        val running: Boolean = false,
        val captureMode: String = "root",
        val rootInfo: RootChecker.RootState = RootChecker.RootState(false, "none", false, -1),
        val fps: Float = 0f,
        val captureLatencyMs: Float = 0f,
        val targetPkg: String? = null,
        val multiplier: Int = 2,
        val flowScale: Float = 1.0f,
        val performance: Boolean = false,
        val framegenBypass: Boolean = false,
        val framegenReady: Boolean = false,
        val framegenSource: String = "bundled",
        val statusMsg: String = "idle",
        val targetFps: Float = 90f,
        val operatingMode: OperatingMode = OperatingMode.ADAPTIVE,
        val gpuCeiling: Float = 85f,
        val processingScale: Float = .5f,
        val effectiveMultiplier: Int = 1,
        val effectiveTarget: Float = 30f,
        val adaptiveReason: String = "Measuring capture and processing",
        val gpuPercent: Double? = null,
        val thermalStatus: Int? = null,
        val displayW: Int = 0,
        val displayH: Int = 0,
        val queueDepth: Int = 0,
        val testPattern: Boolean = false,
        val lastCaptureProbe: RootChecker.ProbeResult? = null,
        // Honest pipeline telemetry (fixes "claims pass when it fails"):
        val framesPresented: Long = 0,
        val pipelineOk: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: CaptureEngine? = null
    private var privilegedCapture: RootCaptureEngineAdapter? = null
    private var shizukuCapture: ShizukuCaptureEngineAdapter? = null
    private var testPattern: TestPattern? = null
    private var testThread: Thread? = null
    private var sessionStatsThread: Thread? = null
    @Volatile private var windowPtr: Long = 0L
    @Volatile private var mediaProjection: MediaProjection? = null

    // Real frame-generation session (LSFG pipeline). false => passthrough.
    @Volatile private var framegenSessionOk: Boolean = false
    @Volatile private var capturedFrameIdx: Int = 0
    private var exportedOutputPool = false
    private val dstPool = java.util.ArrayList<HardwareBuffer>()
    @Volatile private var dstPoolIdx: Int = 0
    @Volatile private var displayW: Int = 0
    @Volatile private var displayH: Int = 0

    private data class SourceFrame(val buffer: HardwareBuffer, val timestampNs: Long, val requestNs: Long, val release: () -> Unit) : AutoCloseable {
        override fun close() = release()
    }
    @Volatile private var acceptingFrames = false
    @Volatile private var terminalFailure = false
    @Volatile private var effectiveDecision = SmoothnessDecision(1, 30.0, "Measuring capture and processing")
    private var sourceQueue: LatestFrameQueue<SourceFrame>? = null
    private var processingThread: Thread? = null
    private val controller = AdaptiveController()
    private val cadence = OutputCadence()
    private var previousSourceNs = 0L
    private var processingEstimateNs = 0L
    private var historyReady = false
    private var failedSourceFrame: SourceFrame? = null
    private var settingsStore: android.content.SharedPreferences? = null

    init {
        val r = RootChecker.detect()
        _state.update { it.copy(rootInfo = r) }
        // Default capture mode: root if available, else nonroot.
        val mode = if (r.root) "root" else "nonroot"
        _state.update { it.copy(captureMode = mode) }
        Log.i(TAG, "SessionManager init: root=${r.root} provider=${r.provider} defaultMode=$mode")
    }

    fun setTargetPkg(pkg: String?) = _state.update { it.copy(targetPkg = pkg) }
    fun setMultiplier(v: Int) = _state.update { it.copy(multiplier = v) }
    fun setFlowScale(v: Float) = _state.update { it.copy(flowScale = v) }
    fun setPerformance(v: Boolean) = _state.update { it.copy(performance = v) }
    fun setFramegenBypass(v: Boolean) = _state.update { it.copy(framegenBypass = v) }
    fun setCaptureMode(mode: String) = _state.update { it.copy(captureMode = mode) }
    fun setTargetFps(v: Float) { _state.update { it.copy(targetFps = v.coerceIn(15f, 240f)) }; saveSettings() }
    fun setOperatingMode(v: OperatingMode) { _state.update { it.copy(operatingMode = v) }; saveSettings() }
    fun setGpuCeiling(v: Float) { _state.update { it.copy(gpuCeiling = v.coerceIn(30f, 95f)) }; saveSettings() }
    fun setProcessingScale(v: Float) { if (!isRunning()) { _state.update { it.copy(processingScale = v.coerceIn(.25f, 1f)) }; saveSettings() } }
    fun loadSettings(context: Context) {
        if (settingsStore != null) return
        val prefs = context.getSharedPreferences("smoothness", Context.MODE_PRIVATE)
        settingsStore = prefs
        _state.update { it.copy(
            operatingMode = runCatching { OperatingMode.valueOf(prefs.getString("mode", "ADAPTIVE")!!) }.getOrDefault(OperatingMode.ADAPTIVE),
            targetFps = prefs.getFloat("limit", 90f).coerceIn(15f, 240f),
            gpuCeiling = prefs.getFloat("gpu", 85f).coerceIn(30f, 95f),
            processingScale = prefs.getFloat("scale", .5f).coerceIn(.25f, 1f)) }
    }
    private fun saveSettings() {
        val s = _state.value
        settingsStore?.edit()?.putString("mode", s.operatingMode.name)?.putFloat("limit", s.targetFps)
            ?.putFloat("gpu", s.gpuCeiling)?.putFloat("scale", s.processingScale)?.apply()
    }
    fun setTestPattern(v: Boolean) = _state.update { it.copy(testPattern = v) }

    fun refreshRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            val r = RootChecker.detect()
            withContext(Dispatchers.Main) { _state.update { it.copy(rootInfo = r) } }
            Log.i(TAG, "refreshRoot: root=${r.root} provider=${r.provider}")
        }
    }

    /**
     * Run a real root capture round-trip probe (bug 2 / bug 6). Returns the
     * result synchronously on the caller thread (call from a background thread
     * or viewModelScope).
     */
    fun runCaptureProbe(appContext: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            Log.i(TAG, "running capture probe (mode=${_state.value.captureMode})")
            val probe = RootChecker.testCapture(appContext)
            Log.i(TAG, "capture probe: ok=${probe.ok} detail=${probe.detail}")
            withContext(Dispatchers.Main) {
                _state.update { it.copy(lastCaptureProbe = probe) }
            }
        }
    }

    /**
     * Begin the session on the overlay surface.
     *
     * @param surface the overlay Surface (Vulkan WSI target).
     * @param w/h     presentation size in pixels.
     * @param appContext used for shader DLL extraction + MediaProjection.
     * @param userDllPath optional user-supplied shader DLL.
     */
    @Synchronized
    fun beginSession(
        surface: android.view.Surface,
        w: Int,
        h: Int,
        appContext: Context,
        userDllPath: String? = null
    ) {
        if (terminalFailure) { _state.update { it.copy(statusMsg = "Restart the app after the GPU failure") }; return }
        loadSettings(appContext)
        if (_state.value.running) {
            Log.w(TAG, "beginSession: already running, ignored")
            return
        }
        val s = _state.value
        this.displayW = w
        this.displayH = h

        Log.i(TAG, "beginSession ${w}x${h} mode=${s.captureMode} test=${s.testPattern} " +
                "framegen=${if (s.framegenBypass) "off" else "on"} mult=${s.multiplier} " +
                "flow=${s.flowScale} perf=${s.performance} dll=${userDllPath ?: "bundled"}")

        // If the surface isn't ready yet (attach added the window on main but
        // surfaceCreated hasn't fired), wait for it HERE on this background
        // thread. Never block the main thread (that deadlocks surfaceCreated).
        var activeSurface: android.view.Surface = surface
        if (!activeSurface.isValid) {
            Log.w(TAG, "beginSession: surface not valid yet, waiting (bg thread)")
            val waited = com.lsfg.fork.overlay.OverlayHost.awaitSurface(5000L)
            if (waited != null && waited.isValid) {
                activeSurface = waited
            }
        }
        if (!activeSurface.isValid) {
            Log.e(TAG, "beginSession: overlay surface never became ready")
            _state.update { it.copy(statusMsg = "overlay surface never became ready") }
            return
        }

        // Acquire a fresh ANativeWindow reference for this session (balanced by endSession).
        val nativeWindow = WsiBridge.nativeSurfaceToWindow(activeSurface)
        if (nativeWindow == 0L) {
            Log.e(TAG, "beginSession: surface -> ANativeWindow failed")
            _state.update { it.copy(statusMsg = "overlay surface -> window failed") }
            return
        }
        this.windowPtr = nativeWindow

        // 1. Presenter up (Vulkan swapchain on the overlay).
        val wsiOk = try {
            WsiBridge.start(nativeWindow, w, h, s.targetFps)
            WsiBridge.isRunning()
        } catch (t: Throwable) {
            Log.e(TAG, "beginSession: WSI start threw: ${t.message}")
            false
        }
        if (!wsiOk) {
            Log.e(TAG, "beginSession: WSI presenter is NOT running; aborting")
            _state.update { it.copy(statusMsg = "WSI presenter failed to start") }
            WsiBridge.nativeReleaseWindow(nativeWindow)
            this.windowPtr = 0L
            return
        }
        _state.update { it.copy(running = true, statusMsg = "presenter up", displayW = w, displayH = h) }
        Log.i(TAG, "beginSession: WSI presenter up, targetFps=${s.targetFps}")

        // 2. Framegen session (unless bypassed).
        framegenSessionOk = false
        capturedFrameIdx = 0
        dstPoolIdx = 0
        dstPool.clear()
        run {
            val dllPath = userDllPath?.takeIf { it.isNotBlank() } ?: extractShaderDll(appContext)
            val srcLabel = if (userDllPath != null && userDllPath.isNotBlank()) "user" else "bundled"
            Log.i(TAG, "beginSession: setting up framegen from $dllPath ($srcLabel)")
            val ok = setupFramegen(w, h, s, dllPath, appContext)
            framegenSessionOk = ok
            _state.update {
                it.copy(
                    framegenReady = ok,
                    framegenSource = srcLabel,
                    statusMsg = if (ok) "framegen ON (LSFG v3.1, $srcLabel dll)"
                                else "framegen unavailable -> passthrough"
                )
            }
            Log.i(TAG, "beginSession: framegen ${if (ok) "READY" else "NOT ready -> passthrough"}")
        }

        // 3. Destination AHB pool for generated frames (presenter reads these).
        val poolSize = 8
        exportedOutputPool = false
        try {
            val exported = if (framegenSessionOk) FramegenBridge.createOutputBuffers(w, h, poolSize) else null
            if (exported != null) {
                dstPool.addAll(exported)
                exportedOutputPool = true
                Log.i(TAG, "Output buffers: Vulkan-owned AHB export ring (${exported.size}, ${w}x${h})")
            } else {
                repeat(poolSize) {
                    dstPool.add(HardwareBuffer.create(w, h, PixelFormat.RGBA_8888, 1, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE))
                }
                Log.i(TAG, "Output buffers: imported app allocations ($poolSize, ${w}x${h})")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Output buffer setup failed: ${t.message}")
            endSession()
            _state.update { it.copy(statusMsg = "Output buffer setup failed: ${t.message}") }
            return
        }

        // 4. Frame source: built-in test pattern OR capture engine.
        // Privileged (root/shizuku) engines feed frames through NativeBridge;
        // wire the session sink (framegen drive or passthrough) before start.
        PipelineStats.reset()
        controller.reset(); cadence.reset(); historyReady = false; previousSourceNs = 0L; processingEstimateNs = 0L
        acceptingFrames = true
        com.lsfg.fork.overlay.OverlayHost.setOutputVisible(s.testPattern || s.framegenBypass || s.operatingMode == OperatingMode.FIXED)
        effectiveDecision = SmoothnessDecision(1, 30.0, "Measuring capture and processing")
        sourceQueue = LatestFrameQueue(80_000_000L) { PipelineStats.incDropped() }
        val queue = sourceQueue!!
        processingThread = thread(name = "lsfg-processing", isDaemon = true) {
            while (acceptingFrames) {
                val frame = queue.poll(System.nanoTime())
                if (frame == null) { try { Thread.sleep(1) } catch (_: InterruptedException) { break }; continue }
                try {
                    driveFramegen(frame.buffer, WsiBridge.nativeAhbPtr(frame.buffer), effectiveDecision.multiplier, frame.timestampNs, frame.requestNs)
                } catch (t: Throwable) {
                    terminalFailure = true; acceptingFrames = false; queue.close()
                    PipelineStats.incErrors()
                    _state.update { it.copy(statusMsg = t.message ?: "Processing failed; restart the app") }
                    Log.e(TAG, "Processing stopped: ${t.message}")
                } finally {
                    PipelineStats.record("captureRequestToProcessed", System.nanoTime() - frame.requestNs)
                    if (terminalFailure) failedSourceFrame = frame else frame.close()
                }
            }
        }
        NativeBridge.sessionFrameSink = { hb, timestamp ->
            val retained = WsiBridge.nativeRetainBuffer(hb)
            enqueueSource(retained, timestamp) { retained.close() }
        }

        val onStats = { fps: Float, lat: Float, err: String? ->
            // Honest health check: the session is only "OK" if the presenter
            // has actually presented at least one frame (Vulkan path live).
            val presented = try { WsiBridge.framesPresented() } catch (_: Throwable) { 0L }
            val healthy = presented > 0L && err == null
            val status = if (err != null) err
                else if (effectiveDecision.multiplier == 1 && !s.testPattern && !s.framegenBypass && s.operatingMode != OperatingMode.FIXED) "monitoring; native game visible"
                else if (presented == 0L) "running BUT presenter produced 0 frames (check logcat lsfg-wsi)"
                else "running: ${s.captureMode} -> LSFG -> overlay"
            if (!terminalFailure) _state.update {
                it.copy(
                    fps = fps,
                    captureLatencyMs = lat,
                    queueDepth = try { WsiBridge.queueSize() } catch (_: Throwable) { 0 },
                    framesPresented = presented,
                    pipelineOk = healthy,
                    statusMsg = status
                )
            }
        }

        var usedPrivileged = false
        if (s.testPattern) {
            val pattern = TestPattern(w, h)
            testPattern = pattern
            // Feed the pattern at 30 fps (canonical framegen test source).
            testThread = thread(name = "lsfg-testpattern", isDaemon = true) {
                val intervalNs = 1_000_000_000L / 30
                var ok = 0
                while (_state.value.running && acceptingFrames) {
                    // keep producing while the session is live
                    if (!_state.value.running) break
                    val t0 = System.nanoTime()
                    try {
                        PipelineStats.captureStartedNs.set(System.nanoTime())
                        val ahb = PipelineStats.measure("syntheticRender") { pattern.next() }
                        if (ahb != null) {
                            PipelineStats.incAcquired()
                            val retained = WsiBridge.nativeRetainBuffer(ahb)
                            enqueueSource(retained, System.nanoTime()) { WsiBridge.nativeCancel(WsiBridge.nativeAhbPtr(retained)); retained.close() }
                            ok++
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "testpattern frame failed: ${t.message}")
                    }
                    // Pace to ~30 fps.
                    val elapsed = System.nanoTime() - t0
                    val sleep = intervalNs - elapsed
                    if (sleep > 0) {
                        // BUG FIX (real-device crash): the old code split the sleep
                        // into Thread.sleep(seconds) + Thread.sleep(0, nanos).
                        // intervalNs = 33,333,333 ns, so seconds = 0 and nanos =
                        // 33,333,333 -> Thread.sleep(0, 33_333_333) threw
                        // IllegalArgumentException("nanos > 999999: ...") on the
                        // 8 Gen 2 device and killed the whole app.
                        // Thread.sleep(millis, nanos) requires nanos < 1,000,000.
                        val millis = sleep / 1_000_000L
                        val nanos = (sleep % 1_000_000L).toInt()
                        try { Thread.sleep(millis, nanos) } catch (_: InterruptedException) { break }
                    }
                }
                Log.i(TAG, "testpattern stopped after $ok frames")
            }
            Log.i(TAG, "beginSession: test pattern source started (30 fps)")
        } else {
            val mode = s.captureMode
            // Privileged ScreenCapture path (ported reference skeleton):
            // root -> libsu RootService binder, shizuku -> Shizuku user service.
            // If the privileged backend is not ready (no root grant / Shizuku
            // not running), fall back to the legacy screencap/MediaProjection
            // paths so the session still starts.
            if (mode == "root") {
                val adapter = RootCaptureEngineAdapter(appContext)
                if (adapter.isReady()) {
                    privilegedCapture = adapter
                    usedPrivileged = true
                    startPrivilegedCapture(adapter, s, appContext)
                }
            } else if (mode == "shizuku") {
                val adapter = ShizukuCaptureEngineAdapter(appContext)
                if (adapter.isReady()) {
                    shizukuCapture = adapter
                    usedPrivileged = true
                    startPrivilegedCapture(adapter, s, appContext)
                }
            }
            if (usedPrivileged) {
                Log.i(TAG, "beginSession: privileged capture started (mode=$mode)")
            } else if (mode == "root" || mode == "shizuku") {
                Log.w(TAG, "beginSession: mode=$mode backend not ready -> fallback")
                _state.update { it.copy(statusMsg = "$mode backend not ready -> fallback") }
            }
            if (!usedPrivileged) {
            var proj: MediaProjection? = null
            if (mode == "nonroot") {
                proj = createProjection(appContext)
                if (proj == null) {
                    Log.e(TAG, "beginSession: nonroot but no MediaProjection consent")
                    _state.update {
                        it.copy(statusMsg = "nonroot needs MediaProjection consent - use Start in the app")
                    }
                }
            }
            val eng = CaptureEngine(
                appContext = appContext,
                width = w,
                height = h,
                minIntervalMs = 1L,
                onStats = onStats,
                projection = proj
            )
            eng.mode = mode
            eng.ownedFrameSink = { buffer, timestamp, release -> enqueueSource(buffer, timestamp, release) }
            // CRITICAL (Jan: "capture by any means possible"): if this is a
            // non-root session but the device actually has a working root, arm
            // the root `screencap` fallback. Then if MediaProjection ever yields
            // no frames (consent lost / picker killed the app / getMediaProjection
            // failed), the engine still produces frames via root. On a truly
            // non-root device this stays off and non-root uses MediaProjection.
            val canRoot = try { RootChecker.detect().root } catch (_: Throwable) { false }
            if (mode == "nonroot" && canRoot) {
                eng.rootFallback = true
                Log.i(TAG, "beginSession: nonroot session armed with ROOT screencap fallback")
            }
            engine = eng
            eng.start()
            mediaProjection = proj
            Log.i(TAG, "beginSession: capture engine started (mode=$mode rootFallback=${eng.rootFallback})")
            }
        }

        // Per-second pipeline telemetry for modes without an adapter stats
        // loop (test pattern, nonroot/MediaProjection, root screencap fallback).
        startSessionStatsReporter(appContext)

        if (framegenSessionOk) {
            _state.update { it.copy(statusMsg = "running: ${s.captureMode} -> LSFG v3.1 -> overlay") }
            Log.i(TAG, "beginSession: RUNNING (framegen path active)")
        } else {
            Log.i(TAG, "beginSession: RUNNING (passthrough path)")
        }
    }

    /**
     * Start a privileged capture engine (root or shizuku). Frames arrive via
     * AIDL -> NativeBridge.sessionFrameSink (framegen or passthrough).
     * The capture size is the display size; framegen operates on its own
     * aligned session extent, so no resize is needed here.
     */
    private fun startPrivilegedCapture(
        adapter: Any,
        s: State,
        appContext: Context
    ) {
        val onStats = { fps: Float, lat: Float, err: String? ->
            val presented = try { WsiBridge.framesPresented() } catch (_: Throwable) { 0L }
            val healthy = presented > 0L && err == null
            val status = if (err != null) err
                else if (effectiveDecision.multiplier == 1 && !s.testPattern && !s.framegenBypass && s.operatingMode != OperatingMode.FIXED) "monitoring; native game visible"
                else if (presented == 0L) "running BUT presenter produced 0 frames (check logcat lsfg-wsi)"
                else "running: ${s.captureMode} -> LSFG -> overlay"
            if (!terminalFailure) _state.update {
                it.copy(
                    fps = fps,
                    captureLatencyMs = lat,
                    queueDepth = try { WsiBridge.queueSize() } catch (_: Throwable) { 0 },
                    framesPresented = presented,
                    pipelineOk = healthy,
                    statusMsg = status
                )
            }
        }
        val onStatsShizuku: (Float, Float, String?) -> Unit = onStats
        val target = s.targetPkg?.takeIf { it.isNotBlank() } ?: appContext.packageName
        val maxFps = s.targetFps.toInt().coerceIn(15, 120)
        when (adapter) {
            is RootCaptureEngineAdapter -> {
                adapter.onStats = onStats
                adapter.onError = { msg -> _state.update { it.copy(statusMsg = msg) } }
                adapter.startCapture(target, displayW, displayH, maxFps)
            }
            is ShizukuCaptureEngineAdapter -> {
                adapter.onStats = onStatsShizuku
                adapter.onError = { msg -> _state.update { it.copy(statusMsg = msg) } }
                adapter.startCapture(target, displayW, displayH, maxFps)
            }
        }
    }

    private fun createProjection(appContext: Context): MediaProjection? {
        // Fast path (reliable): the activity created a live MediaProjection at
        // consent time (main thread, fresh token). Consume it directly.
        pendingProjection?.let { mp ->
            pendingProjection = null
            registerProjectionCallback(mp)
            Log.i(TAG, "using MediaProjection created at consent")
            return mp
        }
        // Fallback: create from the stashed consent data (legacy path).
        return try {
            val data = pendingProjectionData
            if (data == null) {
                Log.w(TAG, "no MediaProjection consent data; nonroot unavailable")
                null
            } else {
                val mpm = appContext.getSystemService(MediaProjectionManager::class.java)
                // The first arg MUST be the consent dialog's result code
                // (Activity.RESULT_OK = -1). getMediaProjection() returns null
                // when it is not RESULT_OK, so passing Build.VERSION.SDK_INT
                // (the old bug) made nonroot capture produce zero frames.
                val mp = mpm.getMediaProjection(android.app.Activity.RESULT_OK, data)
                if (mp != null) {
                    registerProjectionCallback(mp)
                    Log.i(TAG, "MediaProjection created (nonroot)")
                } else {
                    Log.w(TAG, "getMediaProjection returned null (consent data stale?)")
                }
                mp
            }
        } catch (t: Throwable) {
            Log.e(TAG, "createProjection failed: ${t.message}")
            null
        }
    }

    /**
     * Android 14 (U) REQUIRES a MediaProjection.Callback to be registered
     * before the projection is used to create a VirtualDisplay
     * ("Must register a callback before starting capture"). We register on a
     * main-thread Handler so the callback fires on the main looper.
     */
    private fun registerProjectionCallback(mp: MediaProjection) {
        try {
            mp.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        Log.w(TAG, "MediaProjection.onStop (system revoked capture)")
                    }
                },
                android.os.Handler(android.os.Looper.getMainLooper())
            )
        } catch (t: Throwable) {
            Log.w(TAG, "registerProjectionCallback failed: ${t.message}")
        }
    }

    /**
     * Drive the LSFG pipeline for one captured source frame.
     */
    private fun enqueueSource(buffer: HardwareBuffer, timestamp: Long, release: () -> Unit) {
        if (!acceptingFrames) { release(); return }
        sourceQueue?.offer(SourceFrame(buffer, timestamp, PipelineStats.captureStartedNs.get().takeIf { it > 0 }?.coerceAtMost(timestamp) ?: timestamp, release), timestamp) ?: release()
    }

    private fun driveFramegen(srcAhb: HardwareBuffer, srcAhbPtr: Long, multiplier: Int, sourceNs: Long = System.nanoTime(), requestNs: Long = sourceNs): Int {
        if (srcAhbPtr == 0L) return 0
        val started = System.nanoTime()
        val mode = _state.value.operatingMode
        val m = if (_state.value.framegenBypass) 1 else multiplier.coerceIn(1, 4)
        val monitorOnly = m == 1 && !_state.value.testPattern && !_state.value.framegenBypass && mode != OperatingMode.FIXED
        if (monitorOnly) {
            historyReady = false; previousSourceNs = sourceNs
            PipelineStats.record("processing", System.nanoTime() - started)
            return 0
        }
        if (!framegenSessionOk) {
            // The fallback presenter owns the buffer until its GPU read completes.
            // A MediaProjection Image lease must outlive that read as well.
            if (!WsiBridge.nativeReserve(srcAhbPtr)) return 0
            WsiBridge.nativePushTimed(srcAhbPtr, sourceNs, sourceNs, 0)
            PipelineStats.incSubmitted()
            val timeout = System.nanoTime() + 500_000_000L
            while (!WsiBridge.nativeReserve(srcAhbPtr)) {
                check(System.nanoTime() < timeout) { "Presenter did not release the source buffer" }
                Thread.sleep(1)
            }
            WsiBridge.nativeCancel(srcAhbPtr)
            return 1
        }
        val interval = if (previousSourceNs > 0) (sourceNs - previousSourceNs).coerceIn(4_000_000L, 100_000_000L) else 33_333_333L
        val previous = previousSourceNs.takeIf { it > 0 } ?: sourceNs
        val slot = capturedFrameIdx % 2
        val interpolate = m > 1 && historyReady
        if (m > 1) {
            if (!historyReady) {
                FramegenBridge.writeSource(srcAhbPtr, 0); FramegenBridge.writeSource(srcAhbPtr, 1)
                historyReady = true
            } else FramegenBridge.writeSource(srcAhbPtr, slot)
        } else historyReady = false
        if (interpolate) FramegenBridge.dispatch(m - 1)
        val phases = InterpolationPlan.phases(if (interpolate) m else 1)
        val count = phases.size
        val base = maxOf(System.nanoTime(), sourceNs + processingEstimateNs)
        var produced = 0
        repeat(count) { index ->
            val contentNs = if (interpolate) previous + (interval * phases[index]).toLong() else sourceNs
            val selected = cadence.select(contentNs, effectiveDecision.targetFps)
            val dst = dstPool.firstOrNull { WsiBridge.nativeReserve(WsiBridge.nativeAhbPtr(it)) }
            if (dst == null) {
                // Drain an unselected interpolation phase without writing into an occupied buffer.
                if (interpolate && index < m - 1) FramegenBridge.acquire()
                PipelineStats.incDropped(); return@repeat
            }
            val ptr = WsiBridge.nativeAhbPtr(dst)
            var transferred = false
            try {
                if (interpolate && index < m - 1) {
                    FramegenBridge.acquireToDst(ptr); PipelineStats.incGenerated()
                } else FramegenBridge.copySource(srcAhbPtr, ptr)
                if (selected) {
                    val due = if (interpolate) base + interval * index / m else 0L
                    WsiBridge.nativePushTimed(ptr, sourceNs, contentNs, due, exportedOutputPool)
                    transferred = true; PipelineStats.incSubmitted(); produced++
                } else PipelineStats.incDropped()
            } finally { if (!transferred) WsiBridge.nativeCancel(ptr) }
        }
        if (interpolate) capturedFrameIdx++
        previousSourceNs = sourceNs
        val cost = System.nanoTime() - started
        processingEstimateNs = maxOf(cost, (processingEstimateNs * .9 + cost * .1).toLong())
        PipelineStats.record("processing", cost)
        PipelineStats.record("sourceToOutput", System.nanoTime() - sourceNs)
        PipelineStats.record("captureRequestToOutput", System.nanoTime() - requestNs)
        return produced
    }

    private fun startSessionStatsReporter(context: Context) {
        stopSessionStatsReporter()
        val monitor = DevicePerformanceMonitor(context)
        val scheduledRate = RateMeter().apply { reset() }
        val missedRate = RateMeter().apply { reset() }
        val sourceRate = RateMeter().apply { reset() }
        val presentRate = RateMeter().apply { reset() }
        val display = context.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(android.view.Display.DEFAULT_DISPLAY)
        sessionStatsThread = thread(name = "lsfg-session-stats", isDaemon = true) {
            var lastNs = System.nanoTime()
            while (_state.value.running) {
                try { Thread.sleep(1000L) } catch (_: InterruptedException) { break }
                if (!WsiBridge.isRunning()) {
                    terminalFailure = true; acceptingFrames = false; sourceQueue?.close()
                    _state.update { it.copy(statusMsg = "Presenter stopped; restart the app") }
                    break
                }
                val now = System.nanoTime(); val dt = (now - lastNs) / 1e9; lastNs = now
                val source = sourceRate.sample(PipelineStats.acquired.get(), dt)
                val presented = presentRate.sample(WsiBridge.framesPresented(), dt)
                val intervals = PipelineStats.sourceIntervals.summary()
                val processing = PipelineStats.stages["processing"]?.summary()?.p95 ?: 0.0
                val wsiTimings = NativeTimingSnapshot(WsiBridge.performanceStats())
                val counters = WsiBridge.nativePresentationCounters()
                val scheduled = scheduledRate.sample(counters[0], dt)
                val missed = missedRate.sample(counters[1], dt)
                val latency = maxOf(PipelineStats.stages["captureRequestToProcessed"]?.summary()?.p95 ?: 0.0,
                    if (effectiveDecision.multiplier > 1) wsiTimings.p95("captureToPresentRequest") ?: 0.0 else 0.0)
                val signals = monitor.sample(now / 1_000_000)
                val s = _state.value
                val decision = controller.update(PerformanceSample(now / 1_000_000, source, intervals.p50, intervals.p95,
                    processing, latency, presented,
                    if (scheduled > 0) missed / scheduled else 0.0,
                    WsiBridge.queueSize() + (sourceQueue?.size() ?: 0), if (WsiBridge.queueSize() > 0) wsiTimings.p95("queueAge") ?: 0.0 else 0.0,
                    display?.refreshRate?.toDouble() ?: 60.0, signals.gpuPercent, signals.thermalStatus, signals.thermalLoad),
                    SmoothnessSettings(s.operatingMode, s.targetFps.toDouble(), s.gpuCeiling.toDouble(), s.multiplier))
                effectiveDecision = decision
                val showOutput = s.testPattern || s.framegenBypass || s.operatingMode == OperatingMode.FIXED || decision.multiplier > 1
                WsiBridge.setPaused(!showOutput)
                com.lsfg.fork.overlay.OverlayHost.setOutputVisible(showOutput)
                WsiBridge.setTargetFps(decision.targetFps.toFloat())
                _state.update { it.copy(effectiveMultiplier = decision.multiplier, effectiveTarget = decision.targetFps.toFloat(),
                    adaptiveReason = decision.reason, gpuPercent = signals.gpuPercent, thermalStatus = signals.thermalStatus,
                    fps = presented.toFloat(), framesPresented = WsiBridge.framesPresented()) }
                Log.i(TAG, "adaptive mode=${s.operatingMode} multiplier=${decision.multiplier} target=${decision.targetFps} gpu=${signals.gpuPercent ?: "unavailable"} provider=${signals.gpuSource} thermal=${signals.thermalStatus} reason=${decision.reason}")
                reportPipelineStats("lsfg-session")
            }
        }
    }

    private fun stopSessionStatsReporter() {
        try { sessionStatsThread?.interrupt() } catch (_: Throwable) {}
        sessionStatsThread = null
    }

    private fun setupFramegen(w: Int, h: Int, s: State, dllPath: String, appContext: Context): Boolean {
        return try {
            // Keep the pipeline cache in the app's writable internal directory.
            try {
                val cacheDir = java.io.File(appContext.filesDir, "lsfg-cache").apply { mkdirs() }
                FramegenBridge.setCacheDir(cacheDir.absolutePath)
                Log.i(TAG, "pipeline cache dir: ${cacheDir.absolutePath} (writable=${cacheDir.canWrite()})")
            } catch (t: Throwable) {
                Log.w(TAG, "setCacheDir fallback to env/default: ${t.message}")
            }
            // Performance mode selects the shader variants when the instance initializes.
            val okInit = FramegenBridge.init(dllPath, allowFP16 = s.performance)
            if (!okInit) {
                Log.e(TAG, "framegen init FAILED for $dllPath")
                _state.update { it.copy(statusMsg = "framegen init failed: shader DLL ($dllPath)") }
                return false
            }
            // CRITICAL FIX (bug: "no framegen ever happens" in every mode):
            // the v3.1 pipeline builds a 7-level mipmap chain by halving the
            // extent each level. An ODD height makes a level fractional
            // (e.g. 2186 -> 1093 -> 546.5) and image creation fails ->
            // createSession throws -> passthrough. Since v1.4 the overlay is
            // the *visible* area (below the status bar), which on the user's
            // device is 1080x2186 (2186 = 2*1093, prime) -> framegen always
            // failed. Align the COMPUTE session down to a multiple of 16 (the
            // WSI swapchain still renders the real overlay size, and the
            // source/dest AHBs stay the real capture size; framegen just
            // operates on the aligned extent). 2186 -> 2176 = 2^4 * 136,
            // every mipmap level stays even.
            val align = 16
            val fw = ((w * s.processingScale).toInt() / align) * align
            val fh = ((h * s.processingScale).toInt() / align) * align
            val okSession = if (fw >= align && fh >= align) {
                FramegenBridge.createSession(fw, fh, s.flowScale, s.performance)
            } else {
                false
            }
            if (fw != w || fh != h) {
                Log.i(TAG, "framegen session aligned ${w}x${h} -> ${fw}x${fh} (mipmap-safe)")
            }
            if (!okSession) {
                FramegenBridge.destroy()
                Log.e(TAG, "framegen createSession FAILED ${w}x${h} flow=${s.flowScale}")
                _state.update { it.copy(statusMsg = "framegen createSession failed") }
                return false
            }
            Log.i(TAG, "framegen session created ${w}x${h} flow=${s.flowScale} perf=${s.performance}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "framegen setup threw: ${t.message}")
            _state.update { it.copy(statusMsg = "framegen setup threw: ${t.message}") }
            false
        }
    }

    private val ASSET_DLL = "lsfg-vk.dll"

    /** Copy the bundled LSFG shader DLL asset into the app's private files dir. */
    private fun extractShaderDll(ctx: Context): String {
        val app = if (ctx is android.app.Application) ctx else ctx.applicationContext
        val target = java.io.File(app.filesDir, ASSET_DLL)
        if (!target.exists() || target.length() == 0L) {
            try {
                app.assets.open(ASSET_DLL).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "extracted bundled shader DLL -> ${target.absolutePath} (${target.length()}B)")
            } catch (t: Throwable) {
                Log.e(TAG, "extractShaderDll failed: ${t.message}")
            }
        }
        return target.absolutePath
    }

    @Synchronized
    fun endSession() {
        if (!_state.value.running) return
        Log.i(TAG, "endSession")
        acceptingFrames = false
        _state.update { it.copy(running = false) }
        stopSessionStatsReporter()
        try { testThread?.interrupt() } catch (_: Throwable) {}
        try { testThread?.join(2000) } catch (_: InterruptedException) {}
        if (testThread?.isAlive == true) { terminalFailure = true; return }
        testThread = null
        try { testPattern?.stop() } catch (_: Throwable) {}
        testPattern = null
        try { engine?.stop() } catch (t: Throwable) { terminalFailure = true; Log.w(TAG, "engine release: ${t.message}") }
        try { privilegedCapture?.release() } catch (_: Throwable) {}
        privilegedCapture = null
        try { shizukuCapture?.release() } catch (_: Throwable) {}
        shizukuCapture = null
        NativeBridge.sessionFrameSink = null
        sourceQueue?.close()
        try { processingThread?.join(6500) } catch (_: InterruptedException) {}
        if (processingThread?.isAlive == true) {
            terminalFailure = true
            _state.update { it.copy(statusMsg = "Processing did not stop; restart the app") }
            return // Keep every Image, buffer and Vulkan owner alive until the blocked worker exits.
        }
        processingThread = null; sourceQueue = null
        if (!terminalFailure) { try { engine?.release() } catch (_: Throwable) {}; engine = null }
        try { WsiBridge.stop() } catch (t: Throwable) { Log.w(TAG, "wsi stop: ${t.message}") }
        if (framegenSessionOk) {
            try { FramegenBridge.idle() } catch (_: Throwable) {}
            try { FramegenBridge.destroy() } catch (_: Throwable) {}
        }
        framegenSessionOk = false
        for (buf in dstPool) { try { buf.close() } catch (_: Throwable) {} }
        dstPool.clear()
        try { mediaProjection?.stop() } catch (_: Throwable) {}
        mediaProjection = null
        pendingProjection?.let { try { it.stop() } catch (_: Throwable) {} }
        pendingProjection = null
        if (windowPtr != 0L) {
            WsiBridge.nativeReleaseWindow(windowPtr)
            windowPtr = 0L
        }
        _state.update {
            it.copy(running = false, fps = 0f, captureLatencyMs = 0f, queueDepth = 0, statusMsg = "stopped")
        }
        Log.i(TAG, "endSession done")
    }

    fun isRunning(): Boolean = _state.value.running

    /** The live MediaProjection object (nonroot only), or null. Used by the
     *  service to decide whether it may declare the FGS mediaProjection type
     *  (only legal while a real projection exists on API 34+). */
    val activeProjection: MediaProjection? get() = mediaProjection

    override fun onCleared() {
        endSession()
    }
}
