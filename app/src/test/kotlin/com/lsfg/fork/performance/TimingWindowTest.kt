package com.lsfg.fork.performance

import org.junit.Assert.*
import org.junit.Test

class TimingWindowTest {
    @Test fun boundedPercentilesAndInvalidSamples() {
        val w = TimingWindow(100)
        (1..200).forEach { w.record(it.toDouble()) }
        w.record(Double.NaN); w.record(-1.0)
        assertEquals(TimingWindow.Summary(100, 150.0, 195.0, 199.0), w.summary())
        w.clear()
        assertEquals(TimingWindow.Summary.EMPTY, w.summary())
    }
    @Test fun ratesUseDeltasAndHandleCounterReset() {
        val meter = RateMeter()
        assertEquals(0.0, meter.sample(100, 1.0), 0.0)
        assertEquals(20.0, meter.sample(140, 2.0), 0.0)
        assertEquals(0.0, meter.sample(2, 1.0), 0.0)
        assertEquals(3.0, meter.sample(5, 1.0), 0.0)
        meter.reset()
        assertEquals(5.0, meter.sample(5, 1.0), 0.0)
    }
}
