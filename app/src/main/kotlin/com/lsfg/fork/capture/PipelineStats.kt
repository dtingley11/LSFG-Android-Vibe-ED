package com.lsfg.fork.capture

import java.util.concurrent.atomic.AtomicLong
import com.lsfg.fork.performance.TimingWindow
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide pipeline telemetry. Every counter reflects an actual code
 * event, never a configured target or assumed multiplier:
 *
 *  - acquired:  source frames captured by the frame source (root/shizuku/
 *               mediaprojection/test pattern).
 *  - submitted: frames actually handed to the engine sink / presenter.
 *  - generated: frames produced by the framegen pipeline.
 *  - presented: read live from the WSI presenter (framesPresented), not
 *               counted here.
 *  - dropped:   frames lost before reaching the presenter (with a reason
 *               logged at the drop site).
 *  - errors:    capture / push exceptions.
 *
 * SessionManager starts a reporter thread that logs rates ~once per second
 * while a session is active and resets the counters at session start.
 */
object PipelineStats {
    val acquired = AtomicLong()
    val submitted = AtomicLong()
    val generated = AtomicLong()
    val dropped = AtomicLong()
    val errors = AtomicLong()
    val allocations = AtomicLong()
    val sourceIntervals = TimingWindow()
    val stages = ConcurrentHashMap<String, TimingWindow>()
    private val lastSourceNs = AtomicLong()
    val sessionId = AtomicLong()

    fun record(stage: String, durationNs: Long) {
        if (durationNs >= 0) stages.getOrPut(stage) { TimingWindow() }.record(durationNs / 1_000_000.0)
    }

    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = System.nanoTime()
        try { return block() } finally { record(stage, System.nanoTime() - start) }
    }

    fun sourceFrame(timestampNs: Long = System.nanoTime()) {
        acquired.incrementAndGet()
        val last = lastSourceNs.getAndSet(timestampNs)
        if (last > 0 && timestampNs > last) sourceIntervals.record((timestampNs - last) / 1_000_000.0)
    }

    fun reset() {
        acquired.set(0)
        submitted.set(0)
        generated.set(0)
        dropped.set(0)
        errors.set(0)
        allocations.set(0)
        sourceIntervals.clear()
        stages.clear()
        lastSourceNs.set(0)
        sessionId.incrementAndGet()
    }

    fun incAcquired() = sourceFrame()
    fun incSubmitted() = submitted.incrementAndGet()
    fun incGenerated(n: Long = 1) = generated.addAndGet(n)
    fun incDropped() = dropped.incrementAndGet()
    fun incErrors() = errors.incrementAndGet()
}
