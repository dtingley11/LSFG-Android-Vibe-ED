package com.lsfg.fork.performance
import org.junit.Assert.*
import org.junit.Test
class LatestFrameQueueTest {
    private class Frame : AutoCloseable { var closed = 0; override fun close() { closed++ } }
    @Test fun replacementExpiryAndStopReleaseExactlyOnce() {
        var drops = 0
        val queue = LatestFrameQueue<Frame>(100) { drops++ }
        val a = Frame(); val b = Frame(); val c = Frame()
        queue.offer(a, 0); queue.offer(b, 10)
        assertEquals(1, a.closed); assertEquals(1, queue.size())
        assertNull(queue.poll(111)); assertEquals(1, b.closed); assertEquals(2, drops)
        queue.offer(c, 200); queue.close(); queue.close()
        assertEquals(1, c.closed)
        val d = Frame(); assertFalse(queue.offer(d, 300)); assertEquals(1, d.closed)
    }
    @Test fun consumerOwnsPolledFrameAndRestartUsesFreshQueue() {
        val q = LatestFrameQueue<Frame>(100); val a = Frame()
        q.offer(a, 10); assertSame(a, q.poll(20)); q.close(); assertEquals(0, a.closed)
        a.close(); val fresh = LatestFrameQueue<Frame>(100); val b = Frame()
        assertTrue(fresh.offer(b, 30)); assertSame(b, fresh.poll(31)); b.close()
    }
    @Test fun phasesAreCorrectIntegerInterpolationPositions() {
        assertEquals(listOf(1.0), InterpolationPlan.phases(1))
        assertEquals(listOf(.5, 1.0), InterpolationPlan.phases(2))
        assertEquals(listOf(1.0/3, 2.0/3, 1.0), InterpolationPlan.phases(3))
        assertEquals(listOf(.25, .5, .75, 1.0), InterpolationPlan.phases(4))
        assertThrows(IllegalArgumentException::class.java) { InterpolationPlan.phases(5) }
    }
}
