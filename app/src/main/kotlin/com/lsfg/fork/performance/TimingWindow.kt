package com.lsfg.fork.performance

import kotlin.math.ceil

/** Bounded recent samples; recording does not allocate. Values are milliseconds. */
class TimingWindow(private val capacity: Int = 256) {
    private val values = DoubleArray(capacity.also { require(it > 0) })
    private var next = 0
    private var size = 0

    data class Summary(val count: Int, val p50: Double, val p95: Double, val p99: Double) {
        companion object { val EMPTY = Summary(0, 0.0, 0.0, 0.0) }
    }

    @Synchronized fun record(ms: Double) {
        if (!ms.isFinite() || ms < 0) return
        values[next] = ms
        next = (next + 1) % capacity
        size = minOf(size + 1, capacity)
    }

    @Synchronized fun clear() { next = 0; size = 0 }

    @Synchronized fun summary(): Summary {
        if (size == 0) return Summary.EMPTY
        val sorted = values.copyOf(size).apply { sort() }
        fun percentile(p: Double) = sorted[(ceil(size * p).toInt() - 1).coerceIn(0, size - 1)]
        return Summary(size, percentile(.50), percentile(.95), percentile(.99))
    }
}

/** Session-local cumulative counters become rates only after taking a delta. */
class RateMeter {
    private var previous: Long? = null
    fun sample(total: Long, seconds: Double): Double {
        val old = previous
        previous = total
        return if (old == null || total < old || seconds <= 0 || !seconds.isFinite()) 0.0
        else (total - old) / seconds
    }
    fun reset(total: Long = 0) { previous = total }
}
