package com.lsfg.fork.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceView
import android.view.WindowManager
import com.lsfg.fork.Log
import java.util.concurrent.CountDownLatch

/**
 * Hosts the presentation [SurfaceView] as a system overlay window
 * (TYPE_APPLICATION_OVERLAY) instead of a translucent activity.
 *
 * Why an overlay window (and not a translucent Activity):
 *  - a fullscreen translucent *activity* covering a game is what MIUI/Xiaomi
 *    starves or drops (the overlay "never draws" / crashes the target app);
 *  - a real overlay window never becomes the foreground activity, so the game
 *    keeps rendering and keeps its touch input;
 *  - it is visual-only (FLAG_NOT_TOUCHABLE) so it NEVER steals taps (bug 1);
 *  - it stays above the target app on MIUI.
 *
 * The [Surface] is handed to the WSI presenter, which acquires/releases its
 * own ANativeWindow reference around each session (balanced, no leaks).
 */
class OverlayController(private val appContext: Context) {

    private val wm: WindowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var surfaceView: SurfaceView? = null

    @Volatile
    var surface: Surface? = null
        private set

    /** Released on the main thread when the SurfaceView's surface is created.
     *  Callers wait on this from a BACKGROUND thread (never the main thread, or
     *  surfaceCreated can never be dispatched -> the old 3s deadlock). */
    private var surfaceLatch = CountDownLatch(1)

    private val dm = DisplayMetrics()

    /**
     * VISIBLE display size, i.e. the area *below the status bar*.
     *
     * CRITICAL FIX (Ante0: "the menu appears under my status bar so I can't tap
     * anything there"): the old code used getRealMetrics(), which returns the
     * FULL panel INCLUDING the status bar, and paired it with
     * FLAG_LAYOUT_IN_SCREEN. Together they made the overlay cover the status
     * bar and pushed the top row of the UI (tabs) underneath it -> untappable.
     *
     * getMetrics() returns the area *excluding* system bars, so the overlay
     * now starts below the status bar and every control is reachable.
     */
    fun displaySize(): Pair<Int, Int> {
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * Attach the full-screen overlay window (main-thread, FAST, non-blocking).
     * Returns true once the window has been added successfully (the surface may
     * still be pending for one frame).
     *
     * CRITICAL FIX (bug: "waitForSurface -> TIMEOUT" on every start):
     * The old code blocked the MAIN thread here in a polling loop, but
     * `surfaceCreated` is delivered ON the main thread. Blocking the main
     * thread prevented the callback from ever running -> the surface never
     * became ready -> permanent 3s timeout -> "overlay attach failed".
     *
     * Now: addView + register the callback happen on the main thread (required
     * for WindowManager/SurfaceView), and the callback releases a latch when
     * the surface exists. The actual readiness wait is done by [waitForSurface]
     * on a BACKGROUND thread, so the main Looper is free to dispatch
     * surfaceCreated. Callers must use [waitForSurface] before presenting.
     *
     * For MIUI/Xiaomi reliability this should still be attached while the app is
     * in the FOREGROUND (see MainActivity.startFlow).
     */
    fun attach(): Boolean {
        if (surfaceView != null) {
            if (surface != null) return true
            // Window exists but surface was destroyed. Reset the latch and let
            // waitForSurface (background) re-wait for it to be recreated.
            surfaceLatch = CountDownLatch(1)
            Log.i("Overlay", "attach: window exists, awaiting surface recreation")
            return true
        }

        val (w, h) = displaySize()
        Log.i("Overlay", "attaching overlay ${w}x${h}")
        val sv = SurfaceView(appContext)
        sv.holder.setFormat(PixelFormat.RGBA_8888)

        val type = if (Build.VERSION.SDK_INT >= 23)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            w, h,
            type,
            // NEVER steal touch (bug 1): NOT_TOUCHABLE passes every tap through
            // to the game underneath. NOT_FOCUSABLE so the game keeps input.
            //
            // CRITICAL FIX (Ante0 status-bar overlap): FLAG_LAYOUT_IN_SCREEN was
            // removed. It made the window extend under the status bar (covering
            // it and hiding the top UI). Without it, a TOP|START-gravity window
            // sized to the *visible* area (see displaySize()) sits cleanly below
            // the status bar and every control stays tappable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(sv, lp)
            Log.i("Overlay", "addView OK (window created)")
        } catch (t: Throwable) {
            Log.e("Overlay", "addView FAILED: ${t.message}")
            surfaceView = null
            return false
        }
        surfaceView = sv

        sv.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                surface = holder.surface
                Log.i("Overlay", "surfaceCreated: ${holder.surface}")
                surfaceLatch.countDown()  // unblocks the background waitForSurface
            }

            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i("Overlay", "surfaceChanged ${width}x${height} fmt=$format")
            }

            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                Log.w("Overlay", "surfaceDestroyed")
                surface = null
            }
        })

        // Do NOT block the main thread here. Return once the window is added;
        // the surface becomes ready on the next main-thread frame.
        return true
    }

    /**
     * Block (on a BACKGROUND thread) until the overlay surface is ready, or
     * [timeoutMs] elapses. Returns the ready [Surface], or null on timeout.
     *
     * Must NOT be called on the main thread (that would re-introduce the
     * surfaceCreated deadlock). The main thread only ever calls [attach].
     */
    fun waitForSurface(timeoutMs: Long): Surface? {
        // If already ready, return immediately.
        val existing = surface
        if (existing != null) {
            Log.i("Overlay", "waitForSurface -> READY (already)")
            return existing
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        // Poll with a small sleep so we also observe the latch being released
        // from the main thread's surfaceCreated callback.
        while (surface == null && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20)
            } catch (_: InterruptedException) {
                break
            }
        }
        val s = surface
        if (s != null) {
            Log.i("Overlay", "waitForSurface -> READY")
        } else {
            Log.e("Overlay", "waitForSurface -> TIMEOUT (${timeoutMs}ms)")
        }
        return s
    }

    /** Keep the Surface alive while letting the original game show through. */
    fun setOutputVisible(visible: Boolean) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val view = surfaceView ?: return@post
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            val alpha = if (visible) 1f else 0f
            if (params.alpha != alpha) {
                params.alpha = alpha
                runCatching { wm.updateViewLayout(view, params) }
            }
        }
    }

    fun detach() {
        try {
            surfaceView?.let { if (it.parent != null) wm.removeView(it) }
        } catch (t: Throwable) {
            Log.w("Overlay", "removeView: ${t.message}")
        }
        surface = null
        surfaceView = null
        Log.i("Overlay", "detached")
    }

    fun isAttached(): Boolean = surfaceView != null
}

/**
 * Single shared overlay instance so the Activity (foreground attach) and the
 * foreground Service (keep-alive / session control) reference the same window.
 * Attaching from two different controllers would create two competing
 * fullscreen overlays and cause the touch/kickback issues.
 */
object OverlayHost {

    @Volatile
    private var controller: OverlayController? = null

    @Volatile
    private var appContext: Context? = null

    fun bind(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun controller(): OverlayController {
        val ctx = appContext ?: error("OverlayHost.bind not called")
        return controller ?: OverlayController(ctx).also { controller = it }
    }

    fun attach(): Boolean = controller().attach()
    fun detach() { controller().detach() }
    fun setOutputVisible(visible: Boolean) { controller().setOutputVisible(visible) }

    /** Current overlay surface (null until attached). A property, so callers
     *  use `OverlayHost.surface` without parentheses. */
    val surface: Surface? get() = controller().surface

    /**
     * Block (on a BACKGROUND thread) until the overlay surface is ready.
     * attach() adds the window fast (main thread); this waits for the
     * surfaceCreated callback to deliver the actual Surface. Do NOT call on
     * the main thread (would deadlock surfaceCreated).
     */
    fun awaitSurface(timeoutMs: Long = 4000L): Surface? = controller().waitForSurface(timeoutMs)

    fun size(): Pair<Int, Int> = controller().displaySize()
    fun isAttached(): Boolean = controller().isAttached()
}
