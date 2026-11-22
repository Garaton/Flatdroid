package com.garathon.flatdroid.camera

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Packs a camera image's YUV 4:2:0 planes into NV21: the Y plane, then V and U interleaved, the
 * format Android's JPEG encoder ([android.graphics.YuvImage]) takes; or into NV12 (U before V), the
 * raw format apps take. Android's cameras mostly give interleaved chroma ([Order.VU] as NV21 or
 * [Order.UV] as NV12 in memory), whose rows are copied whole; planar chroma is interleaved here.
 * The turn ([rotate]) moves chroma pairs whole, so it serves both. The screen's RGBA pixels are
 * turned into either by [fromRgba].
 */
object Nv21 {
    /** How the U and V planes of a pixel stride of 2 lie in memory. */
    enum class Order { UV, VU }

    fun bytes(width: Int, height: Int) = width * height * 3 / 2

    /** A black frame: Y 0, U and V 128. */
    fun black(width: Int, height: Int): ByteArray =
        ByteArray(bytes(width, height)).also { it.fill(128.toByte(), width * height, it.size) }

    /**
     * For a pixel stride of 2: whether [u] and [v] are views of one UVUV buffer (each U followed
     * by its V) or one VUVU buffer, from a few samples; null when they are neither (packed
     * through [pack]'s slow path then) or when the samples fit both, as in an image of one colour
     * (ask again with a later frame).
     */
    fun order(u: ByteBuffer, v: ByteBuffer): Order? {
        val samples = minOf(u.limit(), v.limit()) / 2
        if (samples <= 0) return null
        val step = maxOf(1, samples / SAMPLES)
        var uv = true
        var vu = true
        var k = 0
        while (k < samples && (uv || vu)) {
            if (u.get(2 * k + 1) != v.get(2 * k)) uv = false
            if (v.get(2 * k + 1) != u.get(2 * k)) vu = false
            k += step
        }
        return when {
            uv && vu -> null
            uv -> Order.UV
            vu -> Order.VU
            else -> null
        }
    }

    /**
     * Writes [width]×[height] NV21 (or NV12 with [into] [Order.UV]) into [out] ([bytes] long).
     * [order] is [Nv21.order]'s answer for a pixel stride of 2; [row] is scratch space of at least
     * [width] bytes.
     */
    fun pack(
        width: Int, height: Int,
        y: ByteBuffer, yRowStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int, order: Order?,
        out: ByteArray, row: ByteArray, into: Order = Order.VU,
    ) {
        for (r in 0 until height) {
            y.position(r * yRowStride)
            y.get(out, r * width, width)
        }
        val chroma = width * height
        val half = width / 2
        for (r in 0 until height / 2) {
            val base = chroma + r * width
            val start = r * uvRowStride
            when {
                uvPixelStride == 2 && order != null -> {
                    // One row of interleaved chroma, copied whole; the buffer of the first plane
                    // ends one byte early, which the other plane has.
                    val (first, second) = if (order == Order.UV) u to v else v to u
                    val n = minOf(width, first.limit() - start)
                    first.position(start)
                    first.get(out, base, n)
                    if (n < width) out[base + width - 1] = second.get(start + 2 * (half - 1))
                    if (order != into) {
                        for (i in base until base + width step 2) {
                            val swap = out[i]
                            out[i] = out[i + 1]
                            out[i + 1] = swap
                        }
                    }
                }
                uvPixelStride == 1 -> {
                    val (first, second) = if (into == Order.VU) v to u else u to v
                    first.position(start)
                    first.get(row, 0, half)
                    for (c in 0 until half) out[base + 2 * c] = row[c]
                    second.position(start)
                    second.get(row, 0, half)
                    for (c in 0 until half) out[base + 2 * c + 1] = row[c]
                }
                else -> {
                    val (first, second) = if (into == Order.VU) v to u else u to v
                    for (c in 0 until half) {
                        out[base + 2 * c] = first.get(start + c * uvPixelStride)
                        out[base + 2 * c + 1] = second.get(start + c * uvPixelStride)
                    }
                }
            }
        }
    }

    /**
     * [width]×[height] RGBA pixels (4 bytes each, rows [rowStride] apart in [rgba]; even sizes) as
     * NV21, or NV12 with [into] [Order.UV], into [out] ([bytes] long), in JPEG's full range
     * (BT.601), as Android's cameras give: each pixel's Y, and each 2×2 block's U and V from its
     * average. [rows] is scratch space of at least 2 × [width] pixels. Pixels are read as whole
     * little-endian ints (R in the low byte), a row at a time.
     */
    fun fromRgba(rgba: ByteBuffer, rowStride: Int, width: Int, height: Int, out: ByteArray, rows: IntArray, into: Order = Order.VU) {
        val pixels = rgba.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val chroma = width * height
        val (vAt, uAt) = if (into == Order.VU) 0 to 1 else 1 to 0
        for (r in 0 until height step 2) {
            pixels.position(r * rowStride)
            pixels.asIntBuffer().get(rows, 0, width)
            pixels.position((r + 1) * rowStride)
            pixels.asIntBuffer().get(rows, width, width)
            val top = r * width
            val uv = chroma + (r / 2) * width
            var c = 0
            while (c < width) {
                val a = rows[c]
                val b = rows[c + 1]
                val d = rows[width + c]
                val e = rows[width + c + 1]
                out[top + c] = luma(a)
                out[top + c + 1] = luma(b)
                out[top + width + c] = luma(d)
                out[top + width + c + 1] = luma(e)
                val red = (a and 0xFF) + (b and 0xFF) + (d and 0xFF) + (e and 0xFF)
                val green = (a shr 8 and 0xFF) + (b shr 8 and 0xFF) + (d shr 8 and 0xFF) + (e shr 8 and 0xFF)
                val blue = (a shr 16 and 0xFF) + (b shr 16 and 0xFF) + (d shr 16 and 0xFF) + (e shr 16 and 0xFF)
                val u = ((-43 * red - 85 * green + 128 * blue + 512) shr 10) + 128
                val v = ((128 * red - 107 * green - 21 * blue + 512) shr 10) + 128
                out[uv + c + vAt] = (if (v > 255) 255 else if (v < 0) 0 else v).toByte()
                out[uv + c + uAt] = (if (u > 255) 255 else if (u < 0) 0 else u).toByte()
                c += 2
            }
        }
    }

    private fun luma(pixel: Int): Byte =
        ((77 * (pixel and 0xFF) + 150 * (pixel shr 8 and 0xFF) + 29 * (pixel shr 16 and 0xFF) + 128) shr 8).toByte()

    /**
     * [src] ([width]×[height] NV21) turned clockwise by [degrees] (90, 180 or 270) into [dst], which
     * is then height×width for 90 and 270.
     */
    fun rotate(src: ByteArray, width: Int, height: Int, degrees: Int, dst: ByteArray) {
        val (outW, outH) = if (degrees % 180 == 0) width to height else height to width
        // Each source row lands at a fixed step in the turned plane, so only its start is worked out.
        // Y, one byte per pixel.
        val step = step(outW, degrees)
        for (y in 0 until height) {
            val row = y * width
            var to = target(0, y, width, height, outW, degrees)
            for (x in 0 until width) {
                dst[to] = src[row + x]
                to += step
            }
        }
        // Interleaved V and U at half size, two bytes per sample.
        val cw = width / 2
        val ch = height / 2
        val outCw = outW / 2
        val chroma = width * height
        val outChroma = outW * outH
        val chromaStep = 2 * step(outCw, degrees)
        for (y in 0 until ch) {
            val row = chroma + y * width
            var to = outChroma + 2 * target(0, y, cw, ch, outCw, degrees)
            for (x in 0 until cw) {
                dst[to] = src[row + 2 * x]
                dst[to + 1] = src[row + 2 * x + 1]
                to += chromaStep
            }
        }
    }

    /** How far [target] moves in a plane [outW] wide for one step along a source row. */
    private fun step(outW: Int, degrees: Int): Int = when (degrees) {
        90 -> outW
        180 -> -1
        270 -> -outW
        else -> 1
    }

    /** Where pixel ([x], [y]) of a [w]×[h] plane lands, as an index into the turned plane [outW] wide. */
    private fun target(x: Int, y: Int, w: Int, h: Int, outW: Int, degrees: Int): Int = when (degrees) {
        90 -> x * outW + (h - 1 - y)
        180 -> (h - 1 - y) * outW + (w - 1 - x)
        270 -> (w - 1 - x) * outW + y
        else -> y * outW + x
    }

    private const val SAMPLES = 64
}
