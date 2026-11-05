package com.lsfg.fork.testpattern

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import com.lsfg.fork.wsi.WsiBridge
import kotlin.math.cos
import kotlin.math.sin

/**
 * Built-in test pattern: a spinning cube rendered at a fixed 30 fps into a
 * ring of HardwareBuffers.
 *
 * Purpose (bug 4): verify frame-generation settings in real time WITHOUT a
 * target app or screen capture. The generated frames flow through the exact
 * same pipeline as real capture:
 *
 *   TestPattern (30 fps source) -> framegen (LSFG v3.1) -> overlay presenter
 *
 * A 30 fps source at 2x is the canonical framegen test: smooth output means
 * interpolation is working; stutters at 30 fps means the pipeline is
 * passing frames through.
 *
 * Rendering is a small software rasterizer (painter's algorithm, 6 cube
 * faces, flat shading) in pure Kotlin. No GL dependency; cheap enough to
 * hold 30 fps on a modern phone.
 */
class TestPattern(private val w: Int, private val h: Int) {

    @Volatile private var running = true
    private val ringSize = 6
    private val ring: Array<HardwareBuffer> = Array(ringSize) {
        HardwareBuffer.create(
            w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_CPU_READ_RARELY or
                    HardwareBuffer.USAGE_CPU_WRITE_RARELY
        )
    }
    private var ringIdx = 0

    private val px = IntArray(w * h)
    private val byteOut = ByteArray(w * h * 4)

    // Unit cube corners.
    private val cube = floatArrayOf(
        -1f, -1f, -1f,  1f, -1f, -1f,  1f, 1f, -1f,  -1f, 1f, -1f,
        -1f, -1f,  1f,  1f, -1f,  1f,  1f, 1f,  1f,  -1f, 1f,  1f
    )
    private val faces = arrayOf(
        intArrayOf(0, 1, 2, 3),   // back  (-z)
        intArrayOf(4, 7, 6, 5),   // front (+z)
        intArrayOf(0, 4, 5, 1),   // left  (-x)
        intArrayOf(2, 6, 7, 3),   // right (+x)
        intArrayOf(3, 7, 5, 1),   // top   (+y)
        intArrayOf(0, 4, 6, 2)    // bottom(-y)
    )
    private val faceColors = intArrayOf(
        0xFF2563EB.toInt(), 0xFF16A34A.toInt(), 0xFFDC2626.toInt(), 0xFFD97706.toInt(), 0xFF7C3AED.toInt(), 0xFF0891B2.toInt()
    )
    private val faceShade = floatArrayOf(0.85f, 1.0f, 0.7f, 0.9f, 1.0f, 0.6f)

    private val proj = FloatArray(16)   // 8 corners * (x, y) in px
    private val camZ = FloatArray(8)    // 8 corners * camera-space depth
    private val faceOrder = IntArray(6)

    /** Render the next cube frame and fill the next ring slot. */
    fun next(): HardwareBuffer? {
        if (!running) return null
        val ahb = ring.firstOrNull { WsiBridge.nativeReserve(WsiBridge.nativeAhbPtr(it)) } ?: return null
        val timeSec = (System.nanoTime() % 1_000_000_000_000L) / 1_000_000_000f
        render(timeSec)

        var o = 0
        for (i in px.indices) {
            val c = px[i]
            byteOut[o] = (c and 0xFF).toByte()
            byteOut[o + 1] = ((c shr 8) and 0xFF).toByte()
            byteOut[o + 2] = ((c shr 16) and 0xFF).toByte()
            byteOut[o + 3] = ((c shr 24) and 0xFF).toByte()
            o += 4
        }
        if (WsiBridge.fillAhb(ahb, byteOut, w * 4) == 0L) { WsiBridge.nativeCancel(WsiBridge.nativeAhbPtr(ahb)); return null }
        return ahb
    }

    fun stop() {
        running = false
        ring.forEach { try { it.close() } catch (_: Throwable) {} }
    }

    // ------------------------------------------------------------------
    // Software cube rasterizer
    // ------------------------------------------------------------------

    private fun render(time: Float) {
        // Dark vertical gradient background (gives interpolation motion
        // reference beyond the cube).
        for (y in 0 until h) {
            val t = y / h.toFloat()
            val r = (8 + 8 * t).toInt()
            val g = (14 + 12 * t).toInt()
            val b = (24 + 16 * t).toInt()
            val c = (0xFF000000.toInt() and -16777216) or (b shl 16) or (g shl 8) or r
            java.util.Arrays.fill(px, y * w, (y + 1) * w, c)
        }

        // Moving reference dot (top-left) so simple-motion interpolation is
        // also visible.
        val dotX = ((w * 0.05f) + (0.5f + 0.5f * sin(time * 1.7f)) * (w * 0.12f)).toInt()
        val dotY = (h * 0.10f + 18f * sin(time * 3.1f)).toInt()
        if (dotX in 0 until w && dotY in 0 until h) {
            px[dotY * w + dotX] =
                (0xFF000000.toInt() and -16777216) or (0x22 shl 16) or (0xD3 shl 8) or 0x22
        }

        // Perspective camera: cube at origin, camera on -z at distance 4.
        val dist = 4.0f
        val rotY = time * 0.9f
        val rotX = 0.45f * sin(time * 0.6f)
        val cy = cos(rotY).toFloat(); val sy = sin(rotY).toFloat()
        val cxr = cos(rotX).toFloat(); val sxr = sin(rotX).toFloat()

        val screenC = w / 2f
        val screenR = h / 2f
        val focal = minOf(w, h).toFloat()

        for (i in 0 until 8) {
            val vx0 = cube[i * 3]
            val vy0 = cube[i * 3 + 1]
            val vz0 = cube[i * 3 + 2]
            // rotate Y
            val x1 = vx0 * cy - vz0 * sy
            val z1 = vx0 * sy + vz0 * cy
            // rotate X
            val y2 = vy0 * cxr - z1 * sxr
            val z2 = vy0 * sxr + z1 * cxr
            val zc = z2 + dist
            camZ[i] = zc
            if (zc <= 0.1f) { proj[i * 2] = 0f; proj[i * 2 + 1] = 0f; continue }
            val s = focal / zc
            proj[i * 2] = screenC + x1 * s
            proj[i * 2 + 1] = screenR + y2 * s
        }

        // Painter's sort: largest average camera depth (farthest) first.
        for (i in 0 until 6) faceOrder[i] = i
        for (i in 0 until 5) {
            for (j in i + 1 until 6) {
                if (avgDepth(faceOrder[j]) > avgDepth(faceOrder[i])) {
                    val t = faceOrder[i]; faceOrder[i] = faceOrder[j]; faceOrder[j] = t
                }
            }
        }

        for (fi in faceOrder) {
            val face = faces[fi]
            var minX = 1e9f; var maxX = -1e9f
            var minY = 1e9f; var maxY = -1e9f
            for (k in 0 until 4) {
                val x = proj[face[k] * 2]
                val y = proj[face[k] * 2 + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val x0 = maxOf(0, (minX - 1).toInt())
            val x1 = minOf(w - 1, (maxX + 1).toInt())
            val y0 = maxOf(0, (minY - 1).toInt())
            val y1 = minOf(h - 1, (maxY + 1).toInt())
            if (x1 < x0 || y1 < y0) continue

            val p0x = proj[face[0] * 2]; val p0y = proj[face[0] * 2 + 1]
            val p1x = proj[face[1] * 2]; val p1y = proj[face[1] * 2 + 1]
            val p2x = proj[face[2] * 2]; val p2y = proj[face[2] * 2 + 1]
            val p3x = proj[face[3] * 2]; val p3y = proj[face[3] * 2 + 1]

            val sh = faceShade[fi]
            val base = faceColors[fi]
            val rr = ((base shr 16) and 0xFF) * sh
            val gg = ((base shr 8) and 0xFF) * sh
            val bb = (base and 0xFF) * sh
            val c = (0xFF000000.toInt() and -16777216) or
                    ((bb.toInt() and 0xFF) shl 16) or
                    ((gg.toInt() and 0xFF) shl 8) or
                    (rr.toInt() and 0xFF)

            for (y in y0..y1) {
                val row = y * w
                for (x in x0..x1) {
                    if (insideQuad(x, y, p0x, p0y, p1x, p1y, p2x, p2y, p3x, p3y)) {
                        px[row + x] = c
                    }
                }
            }
        }
    }

    private fun avgDepth(fi: Int): Float {
        val face = faces[fi]
        var z = 0f
        for (k in 0 until 4) z += camZ[face[k]]
        return z * 0.25f
    }

    /** Convex quad membership test (all four edge cross products same sign). */
    private fun insideQuad(
        x: Int, y: Int,
        p0x: Float, p0y: Float, p1x: Float, p1y: Float,
        p2x: Float, p2y: Float, p3x: Float, p3y: Float
    ): Boolean {
        val e0 = (p1x - p0x) * (y - p0y) - (p1y - p0y) * (x - p0x)
        val e1 = (p2x - p1x) * (y - p1y) - (p2y - p1y) * (x - p1x)
        val e2 = (p3x - p2x) * (y - p2y) - (p3y - p2y) * (x - p2x)
        val e3 = (p0x - p3x) * (y - p3y) - (p0y - p3y) * (x - p3x)
        val pos = e0 >= -0.5f && e1 >= -0.5f && e2 >= -0.5f && e3 >= -0.5f
        val neg = e0 <= 0.5f && e1 <= 0.5f && e2 <= 0.5f && e3 <= 0.5f
        return pos || neg
    }
}
