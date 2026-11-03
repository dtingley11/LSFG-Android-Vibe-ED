package com.lsfg.fork.performance

/** Native summaries contain count,P50,P95,P99 in milliseconds. Missing counters stay unavailable. */
class NativeTimingSnapshot(text: String) {
    private val values = text.split(';').mapNotNull { entry ->
        val pair = entry.split('=', limit = 2)
        if (pair.size != 2) return@mapNotNull null
        val fields = pair[1].split(',')
        if (fields.size != 4) return@mapNotNull null
        val count = fields[0].toLongOrNull() ?: return@mapNotNull null
        val numbers = fields.drop(1).map { it.toDoubleOrNull() ?: return@mapNotNull null }
        if (count <= 0 || numbers.any { !it.isFinite() || it < 0 }) return@mapNotNull null
        pair[0] to numbers
    }.toMap()
    fun p95(stage: String): Double? = values[stage]?.get(1)
}
