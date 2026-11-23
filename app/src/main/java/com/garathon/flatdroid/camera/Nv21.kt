package com.garathon.flatdroid.camera

import java.nio.ByteBuffer

/**
 * Packs a camera image's YUV 4:2:0 planes into NV21: the Y plane, then V and U interleaved, the
 * format Android's JPEG encoder ([android.graphics.YuvImage]) takes; or into NV12 (U before V), the
 * raw format apps take. Android's cameras mostly give interleaved chroma ([Order.VU] as NV21 or
 * [Order.UV] as NV12 in memory), whose rows are copied whole, in the camera's order; planar
 * chroma is interleaved here. Turning a frame, trading its chroma order and the screen's RGBA
 * pixels are [NativeFrames]' (C).
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
     * Writes [width]×[height] NV21 (or NV12 with [into] [Order.UV]) into [out] ([bytes] long),
     * and returns the order written: interleaved chroma stays in the camera's [order] (its rows
     * copied whole), so when that isn't [into], [NativeFrames] trades it, during the turn if the
     * frame is turned. [order] is [Nv21.order]'s answer for a pixel stride of 2; [row] is scratch
     * space of at least [width] bytes.
     */
    fun pack(
        width: Int, height: Int,
        y: ByteBuffer, yRowStride: Int,
        u: ByteBuffer, v: ByteBuffer, uvRowStride: Int, uvPixelStride: Int, order: Order?,
        out: ByteArray, row: ByteArray, into: Order = Order.VU,
    ): Order {
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
        return if (uvPixelStride == 2 && order != null) order else into
    }

    private const val SAMPLES = 64
}
