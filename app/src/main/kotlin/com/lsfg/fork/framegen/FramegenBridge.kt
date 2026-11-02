package com.lsfg.fork.framegen

/** JNI bridge for the LSFG v3_1 compute pipeline (framegen). */
object FramegenBridge {

    private external fun nativeSetCacheDir(dir: String)
    private external fun nativeInit(dllPath: String, allowFP16: Boolean): Long
    private external fun nativeCreateSession(
        handle: Long,
        width: Int, height: Int, flowScale: Float, performance: Boolean
    ): Boolean

    private external fun nativeWriteSource(handle: Long, srcAhb: Long, slot: Int)
    private external fun nativeDispatch(handle: Long, total: Int)
    private external fun nativeAcquire(handle: Long)
    private external fun nativeAcquireToDst(handle: Long, dstAhb: Long)
    private external fun nativeIdle(handle: Long)
    private external fun nativeDestroy(handle: Long)

    /** "srcWritten,dispatches,framesAcquired" — real GPU operation counters. */
    private external fun nativeGetStats(): String

    init {
        try {
            System.loadLibrary("lsfg_framegen")
        } catch (t: Throwable) {
            println("FramegenBridge: failed to load lsfg_framegen: $t")
        }
    }

    var loaded: Boolean = false
        private set

    /**
     * Set the pipeline-cache directory BEFORE init. Must be a directory the
     * process can always write to (app internal files dir on Android).
     * Never /tmp/ (read-only tmpfs on HyperOS even with root).
     */
    fun setCacheDir(dir: String) {
        try { nativeSetCacheDir(dir) } catch (t: Throwable) { println("FramegenBridge.setCacheDir: $t") }
    }

    @Synchronized
    fun init(dllPath: String, allowFP16: Boolean): Boolean {
        val h = try {
            nativeInit(dllPath, allowFP16)
        } catch (t: Throwable) {
            println("FramegenBridge.init: $t")
            0L
        }
        loaded = h != 0L
        instance = h
        return loaded
    }

    private var instance = 0L

    @Synchronized
    fun createSession(
        width: Int, height: Int,
        flowScale: Float, performance: Boolean
    ): Boolean {
        if (instance == 0L) return false
        return try {
            nativeCreateSession(instance, width, height, flowScale, performance)
        } catch (t: Throwable) {
            println("FramegenBridge.createSession: $t")
            false
        }
    }

    @Synchronized
    fun writeSource(srcAhb: Long, slot: Int) {
        if (instance == 0L) return
        try { nativeWriteSource(instance, srcAhb, slot) } catch (_: Throwable) {}
    }

    @Synchronized
    fun dispatch(total: Int) {
        if (instance == 0L) return
        try { nativeDispatch(instance, total) } catch (_: Throwable) {}
    }

    @Synchronized
    fun acquire() {
        if (instance == 0L) return
        try { nativeAcquire(instance) } catch (_: Throwable) {}
    }

    /** Acquire the next generated frame and blit it into the shared dst AHB. */
    @Synchronized
    fun acquireToDst(dstAhb: Long) {
        if (instance == 0L) return
        try { nativeAcquireToDst(instance, dstAhb) } catch (_: Throwable) {}
    }

    @Synchronized
    fun idle() {
        if (instance == 0L) return
        try { nativeIdle(instance) } catch (_: Throwable) {}
    }

    @Synchronized
    fun destroy() {
        if (instance == 0L) return
        try { nativeDestroy(instance) } catch (_: Throwable) {}
        instance = 0L
        loaded = false
    }

    /**
     * Framegen GPU operation counters as "srcWritten,dispatches,framesAcquired".
     * Used by the pipeline stats report to show real generated-frame rates.
     * Returns "0,0,0" when the native lib is not loaded.
     */
    @Synchronized
    fun getStats(): Triple<Long, Long, Long> {
        if (instance == 0L) return Triple(0L, 0L, 0L)
        return try {
            val p = nativeGetStats().split(',')
            Triple(p[0].toLong(), p[1].toLong(), p[2].toLong())
        } catch (_: Throwable) {
            Triple(0L, 0L, 0L)
        }
    }
}
