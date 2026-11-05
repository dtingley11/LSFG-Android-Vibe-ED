package com.lsfg.fork.capture

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Android's raw screencap format (API 29+): four little-endian words, then packed rows. */
object RawScreenshot {
    data class Header(val width: Int, val height: Int, val format: Int, val colorSpace: Int) {
        val byteCount: Int get() = width * height * 4
    }

    fun header(bytes: ByteArray): Header {
        require(bytes.size == 16) { "Truncated screenshot header" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val h = Header(b.int, b.int, b.int, b.int)
        require(h.width in 1..8192 && h.height in 1..8192 && h.width.toLong() * h.height <= 32_000_000) {
            "Unsupported screenshot dimensions"
        }
        require(h.format == 1 || h.format == 2) { "Raw screenshot requires RGBA/RGBX" }
        // A wide-gamut/HDR source needs explicit conversion; preserve the PNG fallback.
        require(h.colorSpace == 0 || h.colorSpace == 1) { "Raw screenshot requires sRGB" }
        return h
    }

    fun readFully(stream: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = stream.read(bytes, offset, bytes.size - offset)
            check(n > 0) { "Truncated screenshot pixels" }
            offset += n
        }
    }
}
