package com.lsfg.fork.performance

import android.content.Context
import android.os.Build
import android.os.PowerManager
import java.io.File

/** Providers must report the whole GPU, never just this application's queue. */
interface TotalGpuMonitor {
    val description: String
    fun samplePercent(): Double?
}

/** Optional KGSL whole-device busy/total window. No shell, root request or frequency-based estimate. */
class KgslGpuMonitor internal constructor(private val counter: File) : TotalGpuMonitor {
    override val description = "KGSL busy/total"
    private var previous: String? = null
    private var failures = 0
    override fun samplePercent(): Double? {
        if (failures >= 3) return null
        return runCatching {
            val text = counter.readText().trim()
            val old = previous; previous = text
            // A frozen window or zero busy count cannot establish available headroom.
            if (old == null || old == text) return null
            val values = text.split(Regex("\\s+"))
            if (values.size != 2) return null
            val busy = values[0].toLongOrNull() ?: return null
            val total = values[1].toLongOrNull() ?: return null
            if (busy <= 0 || total <= 0 || busy > total) return null
            failures = 0
            100.0 * busy / total
        }.getOrElse { failures++; null }
    }
    companion object {
        fun detect(): TotalGpuMonitor {
            val parent = File("/sys/class/kgsl")
            val candidate = parent.listFiles()?.asSequence()?.filter { it.name.startsWith("kgsl-") }
                ?.map { File(it, "gpubusy") }?.firstOrNull { it.isFile && it.canRead() }
            return candidate?.let { KgslGpuMonitor(it) } ?: object : TotalGpuMonitor {
                override val description = "unavailable"
                override fun samplePercent(): Double? = null
            }
        }
    }
}

class DevicePerformanceMonitor(context: Context, private val gpu: TotalGpuMonitor = KgslGpuMonitor.detect()) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var lastThermalLoadMs = -10000L
    private var thermalLoad: Double? = null
    data class Reading(val gpuPercent: Double?, val gpuSource: String, val thermalStatus: Int?, val thermalLoad: Double?)
    fun sample(nowMs: Long): Reading {
        if (Build.VERSION.SDK_INT >= 30 && nowMs - lastThermalLoadMs >= 10000) {
            lastThermalLoadMs = nowMs
            thermalLoad = runCatching { power.getThermalHeadroom(10).toDouble() }.getOrNull()?.takeIf { it.isFinite() && it >= 0 }
        }
        return Reading(gpu.samplePercent(), gpu.description,
            runCatching { power.currentThermalStatus }.getOrNull(), thermalLoad)
    }
}
