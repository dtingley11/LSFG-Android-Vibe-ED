package com.lsfg.fork.performance
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class MonitoringTest {
    @Test fun gpuWindowRejectsIdleFrozenAndInvalidReadings() {
        val dir = File("build/test-counter-fixtures").apply { mkdirs() }
        val file = File.createTempFile("kgsl", ".txt", dir)
        try {
            file.writeText("40 100")
            val monitor = KgslGpuMonitor(file)
            assertNull(monitor.samplePercent())
            assertNull(monitor.samplePercent())
            file.writeText("85 100"); assertEquals(85.0, monitor.samplePercent()!!, .001)
            for (value in listOf("0 100", "101 100", "1 0", "-1 100", "bad", "1 2 3")) {
                file.writeText(value); assertNull(monitor.samplePercent())
            }
        } finally { file.delete() }
    }
    @Test fun unavailableCountersNeverBecomeZeroLatencyMeasurements() {
        val v = NativeTimingSnapshot("queueAge=12,1,2,3;broken;invalid=1,NaN,2,3;")
        assertEquals(2.0, v.p95("queueAge")!!, .001)
        assertNull(v.p95("invalid")); assertNull(v.p95("missing"))
    }
}
