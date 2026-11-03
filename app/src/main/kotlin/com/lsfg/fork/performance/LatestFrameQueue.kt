package com.lsfg.fork.performance

/** One pending frame. Replacement, expiry and shutdown release ownership exactly once. */
class LatestFrameQueue<T : AutoCloseable>(private val maxAgeNs: Long, private val dropped: () -> Unit = {}) {
    private var pending: Pair<T, Long>? = null
    private var closed = false
    @Synchronized fun offer(frame: T, timestampNs: Long): Boolean {
        if (closed) { frame.close(); return false }
        pending?.first?.close()?.also { dropped() }
        pending = frame to timestampNs
        return true
    }
    @Synchronized fun poll(nowNs: Long): T? {
        val item = pending ?: return null
        pending = null
        if (nowNs < item.second || nowNs - item.second > maxAgeNs) {
            item.first.close(); dropped(); return null
        }
        return item.first
    }
    @Synchronized fun size(): Int = if (pending == null) 0 else 1
    @Synchronized fun close() { closed = true; pending?.first?.close(); pending = null }
}

/** Supported integer multipliers contain m-1 interior phases followed by the real endpoint. */
object InterpolationPlan {
    private val plans = arrayOf(listOf(1.0), listOf(.5, 1.0), listOf(1.0/3, 2.0/3, 1.0), listOf(.25, .5, .75, 1.0))
    fun phases(multiplier: Int): List<Double> {
        require(multiplier in 1..4)
        return plans[multiplier - 1]
    }
}
