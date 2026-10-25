package com.garathon.flatdroid.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer

class Nv21Test {
    // A 4×2 image with rows padded to 6 bytes: Y 1..8, U 101 102, V 201 202; NV21 puts V first.
    private val width = 4
    private val height = 2
    private val stride = 6
    private val expected = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, -55, 101, -54, 102)

    private fun yPlane() = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 0, 0, 5, 6, 7, 8))

    /** Chroma as one buffer with U and V interleaved in [first]-then-second order, as the camera's memory holds it. */
    private fun interleaved(vFirst: Boolean): Pair<ByteBuffer, ByteBuffer> {
        val u = byteArrayOf(101, 102)
        val v = byteArrayOf(201.toByte(), 202.toByte())
        val (a, b) = if (vFirst) v to u else u to v
        val memory = byteArrayOf(a[0], b[0], a[1], b[1])
        // Each plane's buffer starts at its first sample and ends at its last: one byte shorter.
        val first = ByteBuffer.wrap(memory, 0, 3).slice()
        val second = ByteBuffer.wrap(memory, 1, 3).slice()
        return if (vFirst) second to first else first to second
    }

    private fun pack(u: ByteBuffer, v: ByteBuffer, pixelStride: Int, order: Nv21.Order?): ByteArray {
        val out = ByteArray(Nv21.bytes(width, height))
        Nv21.pack(width, height, yPlane(), stride, u, v, stride, pixelStride, order, out, ByteArray(width))
        return out
    }

    @Test
    fun nv21MemoryIsCopiedAsItIs() {
        val (u, v) = interleaved(vFirst = true)
        assertEquals(Nv21.Order.VU, Nv21.order(u, v))
        assertArrayEquals(expected, pack(u, v, 2, Nv21.Order.VU))
    }

    @Test
    fun nv12MemoryIsSwapped() {
        val (u, v) = interleaved(vFirst = false)
        assertEquals(Nv21.Order.UV, Nv21.order(u, v))
        assertArrayEquals(expected, pack(u, v, 2, Nv21.Order.UV))
    }

    @Test
    fun oneColourLeavesTheOrderOpen() {
        // U and V all 128, as in a camera's first dark frames: both orders fit.
        val memory = ByteArray(8) { 128.toByte() }
        val u = ByteBuffer.wrap(memory, 0, 7).slice()
        val v = ByteBuffer.wrap(memory, 1, 7).slice()
        assertNull(Nv21.order(u, v))
    }

    @Test
    fun planarChromaIsInterleaved() {
        val u = ByteBuffer.wrap(byteArrayOf(101, 102))
        val v = ByteBuffer.wrap(byteArrayOf(201.toByte(), 202.toByte()))
        assertArrayEquals(expected, pack(u, v, 1, null))
    }

    @Test
    fun separateBuffersWithAStrideOfTwoTakeTheSlowPath() {
        val u = ByteBuffer.wrap(byteArrayOf(101, 0, 102))
        val v = ByteBuffer.wrap(byteArrayOf(201.toByte(), 0, 202.toByte()))
        assertNull(Nv21.order(u, v))
        assertArrayEquals(expected, pack(u, v, 2, null))
    }

    @Test
    fun blackIsZeroLumaAndNeutralChroma() {
        val black = Nv21.black(4, 2)
        assertEquals(12, black.size)
        assertArrayEquals(ByteArray(8), black.copyOfRange(0, 8))
        assertArrayEquals(ByteArray(4) { 128.toByte() }, black.copyOfRange(8, 12))
    }

    @Test
    fun rotatesLumaAndChromaClockwise() {
        // 4×2: Y rows 1 2 3 4 / 5 6 7 8, one chroma row (V,U) (10,20) (30,40).
        val src = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 10, 20, 30, 40)
        val out = ByteArray(src.size)
        Nv21.rotate(src, 4, 2, 90, out)
        // 2×4: the left column read bottom-up becomes the top row.
        assertArrayEquals(byteArrayOf(5, 1, 6, 2, 7, 3, 8, 4, 10, 20, 30, 40), out)
        Nv21.rotate(src, 4, 2, 180, out)
        assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1, 30, 40, 10, 20), out)
        Nv21.rotate(src, 4, 2, 270, out)
        assertArrayEquals(byteArrayOf(4, 8, 3, 7, 2, 6, 1, 5, 30, 40, 10, 20), out)
    }

    @Test
    fun uprightForHowTheDeviceIsHeld() {
        val back = AndroidCameras.Camera("0", AndroidCameras.Facing.BACK, 1280, 720, sensorOrientation = 90)
        val front = AndroidCameras.Camera("1", AndroidCameras.Facing.FRONT, 1280, 720, sensorOrientation = 270)
        // A phone in portrait (its natural orientation) turns both; held landscape, neither.
        assertEquals(90, back.rotation(0))
        assertEquals(270, front.rotation(0))
        assertEquals(0, back.rotation(90))
        assertEquals(0, front.rotation(90))
    }
}
