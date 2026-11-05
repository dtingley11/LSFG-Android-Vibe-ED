package com.lsfg.fork.performance

import org.junit.Assert.*
import org.junit.Test

class AdaptiveControllerTest {
    private fun sample(t: Long, source: Double = 30.0, gpu: Double? = 45.0) = PerformanceSample(
        t, source, 1000 / source, 1000 / source * 1.05, 5.0, 20.0,
        120.0, 0.0, 0, 0.0, 120.0, gpu, 0)
    private fun warm(controller: AdaptiveController, settings: SmoothnessSettings = SmoothnessSettings(),
        gpu: Double? = 45.0): SmoothnessDecision {
        var d = controller.update(sample(0, gpu = gpu), settings)
        for (i in 1..20) d = controller.update(sample(i * 1000L, gpu = gpu), settings)
        return d
    }
    @Test fun defaultModeAndTotalBudget() {
        assertEquals(OperatingMode.ADAPTIVE, SmoothnessSettings().mode)
        assertEquals(85.0, SmoothnessSettings().gpuCeiling, 0.0)
        val c = AdaptiveController()
        assertTrue(warm(c).multiplier >= 2)
        val settings = SmoothnessSettings()
        c.update(sample(21_000, gpu = 95.0), settings)
        val before = c.update(sample(22_000, gpu = 95.0), settings)
        val after = c.update(sample(23_000, gpu = 95.0), settings)
        assertTrue(after.multiplier <= before.multiplier)
        assertEquals(1, c.update(sample(24_000, gpu = 95.0), settings).multiplier)
    }
    @Test fun unavailableAndInvalidGpuUseTimingRatherThanUnlimitedWork() {
        for (gpu in listOf(null, Double.NaN, -1.0, 0.0, 101.0)) {
            val c = AdaptiveController()
            val d = warm(c, gpu = gpu)
            assertTrue(d.multiplier in 1..4)
            val bad = sample(21_000, gpu = gpu).copy(processingP95Ms = 200.0, latencyP95Ms = 200.0)
            c.update(bad, SmoothnessSettings())
            val reduced = c.update(bad.copy(nowMs = 22_000), SmoothnessSettings())
            assertTrue(reduced.multiplier < d.multiplier || d.multiplier == 1)
        }
    }
    @Test fun thermalProtectionOverridesEveryMode() {
        for (mode in OperatingMode.entries) {
            val settings = SmoothnessSettings(mode = mode, targetLimit = 120.0)
            val c = AdaptiveController(); warm(c, settings)
            val d = c.update(sample(21_000).copy(thermalStatus = 4), settings)
            assertEquals(1, d.multiplier)
        }
    }
    @Test fun thermalHeadroomAndMissedDeadlinesReduceWork() {
        for (kind in 0..2) {
            val c = AdaptiveController(); val before = warm(c)
            val bad = when (kind) {
                0 -> sample(21_000).copy(thermalLoad = .95)
                1 -> sample(21_000).copy(missedFraction = .3)
                else -> sample(21_000).copy(queueAgeMs = 150.0, queueDepth = 4)
            }
            c.update(bad, SmoothnessSettings())
            val after = c.update(bad.copy(nowMs = 22_000), SmoothnessSettings())
            assertTrue(after.multiplier < before.multiplier)
        }
    }
    @Test fun smoothSourceAtRefreshAndSlowCaptureBypassInterpolation() {
        for (fps in listOf(120.0, 5.0)) {
            val c = AdaptiveController()
            val d = c.update(sample(0, fps).copy(sourceIntervalP95Ms = 1000 / fps), SmoothnessSettings())
            assertEquals(1, d.multiplier)
        }
    }
    @Test fun hysteresisRejectsOneBadWindowAndPreventsRapidGrowth() {
        val c = AdaptiveController(); val d = warm(c)
        assertEquals(d.multiplier, c.update(sample(21_000, gpu = 99.0), SmoothnessSettings()).multiplier)
        val recovered = c.update(sample(22_000), SmoothnessSettings())
        assertTrue(recovered.multiplier <= d.multiplier + 1)
        assertEquals(recovered, c.update(sample(21_000), SmoothnessSettings()))
    }
    @Test fun stableCapRespectsDisplayAndFixedModeKeepsIntegerLimit() {
        val c = AdaptiveController()
        val d = warm(c, SmoothnessSettings(mode = OperatingMode.STABLE, targetLimit = 240.0))
        assertTrue(d.targetFps <= 120)
        val fixed = warm(AdaptiveController(), SmoothnessSettings(mode = OperatingMode.FIXED, fixedMultiplier = 2))
        assertTrue(fixed.multiplier <= 2)
    }
    @Test fun unstableSourceDoesNotPromoteAndSustainedJitterReduces() {
        val c = AdaptiveController(); val before = warm(c)
        repeat(3) { c.update(sample(21_000L + it * 1000).copy(sourceIntervalP95Ms = 100.0), SmoothnessSettings()) }
        val d = c.update(sample(24_000).copy(sourceIntervalP95Ms = 100.0), SmoothnessSettings())
        assertTrue(d.multiplier < before.multiplier)
    }
    @Test fun resetStartsFreshAndRejectsInvalidSourceMeasurements() {
        val c = AdaptiveController(); warm(c); c.reset()
        assertEquals(1, c.update(sample(0), SmoothnessSettings()).multiplier)
        assertEquals(1, c.update(sample(1000, Double.NaN), SmoothnessSettings()).multiplier)
    }
    @Test fun captureAlreadyAtCapDoesNotTriggerUnnecessaryCeilingRounding() {
        val c = AdaptiveController()
        for (i in 0..25) {
            val d = c.update(sample(i * 1000L, source = 89.5), SmoothnessSettings(targetLimit = 90.0))
            assertEquals(1, d.multiplier)
        }
    }
    @Test fun failedProbeDoesNotRepeatEveryFiveSeconds() {
        val c = AdaptiveController(); warm(c)
        c.update(sample(21_000).copy(missedFraction = .3), SmoothnessSettings())
        val reduced = c.update(sample(22_000).copy(missedFraction = .3), SmoothnessSettings())
        for (i in 23..45) assertTrue(c.update(sample(i * 1000L), SmoothnessSettings()).multiplier <= reduced.multiplier)
        assertTrue(c.update(sample(53_000), SmoothnessSettings()).multiplier >= reduced.multiplier)
    }
    @Test fun fractionalOutputSelectionDoesNotInventInterpolationOrDuplicateFrames() {
        val cadence = OutputCadence()
        var selected = 0
        repeat(360) { if (cadence.select(it * 1_000_000_000L / 90, 60.0)) selected++ }
        assertTrue("selected=$selected", selected in 239..241)
        assertFalse(cadence.select(0, 60.0))
        cadence.reset()
        assertTrue(cadence.select(0, 60.0))
        assertFalse(cadence.select(0, 60.0))
    }
}
