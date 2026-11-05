package com.lsfg.fork.performance

import com.lsfg.fork.capture.RawScreenshot
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RawScreenshotTest {
    private fun header(w: Int = 1272, h: Int = 2800, format: Int = 1, color: Int = 1) =
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(w).putInt(h).putInt(format).putInt(color).array()

    @Test fun readsAndroidHeaderWithoutGuessingPixelOffsets() {
        assertEquals(1272 * 2800 * 4, RawScreenshot.header(header()).byteCount)
        assertEquals(2, RawScreenshot.header(header(format = 2)).format)
    }
    @Test fun rejectsCorruptDimensionsFormatsAndWideGamut() {
        for (bytes in listOf(header(0), header(-1), header(8192, 8192), header(format = 4),
            header(color = 2), ByteArray(12))) {
            assertThrows(IllegalArgumentException::class.java) { RawScreenshot.header(bytes) }
        }
    }
    @Test fun rejectsTruncationAndReadsExactPayload() {
        val bytes = ByteArray(4)
        assertThrows(IllegalStateException::class.java) {
            RawScreenshot.readFully(ByteArrayInputStream(byteArrayOf(1, 2)), bytes)
        }
        RawScreenshot.readFully(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 99)), bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), bytes)
    }
}
