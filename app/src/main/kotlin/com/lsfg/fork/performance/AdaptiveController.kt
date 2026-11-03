package com.lsfg.fork.performance

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class OperatingMode(val label: String) {
    ADAPTIVE("Adaptive Smoothness"), STABLE("Stable Cap"), MAXIMUM("Maximum Performance"), FIXED("Fixed multiplier")
}

data class SmoothnessSettings(
    val mode: OperatingMode = OperatingMode.ADAPTIVE,
    val targetLimit: Double = 90.0,
    val gpuCeiling: Double = 85.0,
    val fixedMultiplier: Int = 2,
    val maxLatencyMs: Double = 80.0
)

/** Rates describe acquired source buffers, not an independently measured game render rate. */
data class PerformanceSample(
    val nowMs: Long,
    val sourceFps: Double,
    val sourceIntervalP50Ms: Double,
    val sourceIntervalP95Ms: Double,
    val processingP95Ms: Double,
    val latencyP95Ms: Double,
    val presentFps: Double,
    val missedFraction: Double,
    val queueDepth: Int,
    val queueAgeMs: Double,
    val refreshHz: Double,
    val totalGpuPercent: Double? = null,
    val thermalStatus: Int? = null,
    val thermalLoad: Double? = null
)

data class SmoothnessDecision(val multiplier: Int, val targetFps: Double, val reason: String)

/** Stateful policy with no Android dependencies. Work grows only after sustained, measured stability. */
class AdaptiveController {
    private var decision = SmoothnessDecision(1, 30.0, "Measuring capture and processing")
    private var startedMs = -1L
    private var lastChangeMs = -1L
    private var lastSampleMs = -1L
    private var sourceEma = 0.0
    private var good = 0
    private var bad = 0
    private var previousMode: OperatingMode? = null

    fun reset() {
        decision = SmoothnessDecision(1, 30.0, "Measuring capture and processing")
        startedMs = -1; lastChangeMs = -1; lastSampleMs = -1
        sourceEma = 0.0; good = 0; bad = 0; previousMode = null
    }

    fun update(s: PerformanceSample, settings: SmoothnessSettings): SmoothnessDecision {
        if (lastSampleMs >= 0 && s.nowMs <= lastSampleMs) return decision
        lastSampleMs = s.nowMs
        if (startedMs < 0) { startedMs = s.nowMs; lastChangeMs = s.nowMs }
        val refresh = s.refreshHz.takeIf { it.isFinite() && it >= 20 } ?: 60.0
        val limit = min(settings.targetLimit.takeIf { it.isFinite() }?.coerceIn(15.0, 240.0) ?: 60.0, refresh)
        val validSource = s.sourceFps.isFinite() && s.sourceFps in .1..1000.0
        if (!validSource) {
            good = 0; bad = 0
            decision = SmoothnessDecision(1, min(limit, 30.0), "Waiting for source frames")
            return decision
        }
        sourceEma = if (sourceEma == 0.0) s.sourceFps else sourceEma * .75 + s.sourceFps * .25
        val source = min(sourceEma, s.sourceFps * 1.15)
        val period = 1000.0 / source
        val jitter = if (s.sourceIntervalP50Ms > 0)
            max(0.0, s.sourceIntervalP95Ms / s.sourceIntervalP50Ms - 1) else 1.0
        val gpu = s.totalGpuPercent?.takeIf { it.isFinite() && it > 0 && it <= 100 }
        val ceiling = settings.gpuCeiling.coerceIn(30.0, 95.0)
        val hot = (s.thermalStatus ?: 0) >= 2 || (s.thermalLoad?.takeIf { it.isFinite() } ?: 0.0) >= .9
        val critical = (s.thermalStatus ?: 0) >= 3
        val overloaded = hot || jitter > .65 || (gpu != null && gpu > ceiling + 2) ||
            s.missedFraction > .12 || s.queueDepth > 2 || s.queueAgeMs > settings.maxLatencyMs ||
            s.latencyP95Ms > settings.maxLatencyMs || s.processingP95Ms > period * .9
        val healthy = !overloaded && jitter < .25 && s.missedFraction <= .03 &&
            s.queueDepth <= 1 && s.processingP95Ms > 0 && s.processingP95Ms < period * .65 &&
            s.latencyP95Ms < settings.maxLatencyMs * .8 && (gpu == null || gpu < ceiling - 8)
        good = if (healthy) good + 1 else 0
        bad = if (overloaded) bad + 1 else 0

        fun change(multiplier: Int, target: Double, reason: String): SmoothnessDecision {
            val next = SmoothnessDecision(multiplier.coerceIn(1, 4), min(target, limit).coerceAtLeast(1.0), reason)
            if (next.multiplier != decision.multiplier || abs(next.targetFps - decision.targetFps) > .5)
                lastChangeMs = s.nowMs
            decision = next
            return decision
        }

        val modeChanged = previousMode != settings.mode
        previousMode = settings.mode
        if (critical || source < 12 || (source >= refresh * .93 && jitter < .1))
            return change(1, min(source, limit), when {
                critical -> "Thermal protection"
                source < 12 -> "Capture too slow for responsive interpolation"
                else -> "Source already near display refresh"
            })

        if (critical || bad >= 2) {
            good = 0
            return change(max(1, decision.multiplier - 1),
                min(decision.targetFps, source * max(1, decision.multiplier - 1)), when {
                    hot -> "Reducing work for thermal conditions"
                    gpu != null && gpu > ceiling -> "Reducing work: total GPU budget"
                    else -> "Reducing work: latency or missed deadlines"
                })
        }
        // Settings can reduce work immediately; increasing remains subject to the same guardrails.
        if (decision.targetFps > limit + .5)
            return change(min(decision.multiplier, ceil(limit / source).toInt()), limit, "Output limit")
        if (s.nowMs - startedMs < 3000 || (!modeChanged && s.nowMs - lastChangeMs < 5000))
            return decision.copy(reason = if (overloaded) "Checking transient load" else decision.reason).also { decision = it }

        val requested = when (settings.mode) {
            OperatingMode.FIXED -> min(limit, source * settings.fixedMultiplier.coerceIn(1, 4))
            OperatingMode.STABLE -> limit
            OperatingMode.MAXIMUM -> limit
            OperatingMode.ADAPTIVE -> {
                // Prefer a refresh divisor; arbitrary requested caps remain available in Stable Cap.
                val choices = (1..4).map { refresh / it }.filter { it > source * 1.1 && it <= limit + .5 }.sorted()
                val next = choices.firstOrNull { it > decision.targetFps + 2 }
                next ?: min(limit, source * 2)
            }
        }
        val desiredMultiplier = ceil(requested / source).toInt().coerceIn(1, 4)
        val capacityPerOutputMs = s.processingP95Ms / decision.multiplier
        val estimatedCost = capacityPerOutputMs * desiredMultiplier
        val stablePresentation = decision.multiplier == 1 || s.presentFps >= min(decision.targetFps, source * decision.multiplier) * .85
        if (good >= 3 && stablePresentation && estimatedCost <= period * .8 && !overloaded) {
            val nextMultiplier = min(desiredMultiplier, decision.multiplier + 1)
            val target = min(requested, source * nextMultiplier)
            return change(nextMultiplier, target, if (gpu == null) "Adapting from frame timing; GPU load unavailable" else "Stable pacing within total GPU budget")
        }
        return decision.copy(reason = when {
            gpu != null && gpu >= ceiling - 8 -> "Maintaining GPU headroom"
            !stablePresentation -> "Waiting for stable presentation"
            else -> "Maintaining sustainable output"
        }).also { decision = it }
    }
}

/** Selects existing integer-phase outputs for a nominal cap; never duplicates frames. */
class OutputCadence {
    private var lastNs = Long.MIN_VALUE
    private var nextNs = Long.MIN_VALUE
    private var previousFps = 0.0
    fun reset() { lastNs = Long.MIN_VALUE; nextNs = Long.MIN_VALUE; previousFps = 0.0 }
    fun select(timestampNs: Long, targetFps: Double): Boolean {
        if (!targetFps.isFinite() || targetFps <= 0) return false
        if (lastNs != Long.MIN_VALUE && timestampNs <= lastNs) return false
        val interval = (1e9 / targetFps).toLong().coerceAtLeast(1)
        if (previousFps != targetFps && lastNs != Long.MIN_VALUE) nextNs = lastNs + interval
        previousFps = targetFps
        if (nextNs != Long.MIN_VALUE && timestampNs + 100 < nextNs) return false
        nextNs = if (nextNs == Long.MIN_VALUE) timestampNs + interval
            else nextNs + maxOf(1L, (timestampNs - nextNs) / interval + 1) * interval
        lastNs = timestampNs
        return true
    }
}
