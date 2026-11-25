package com.garathon.flatdroid.camera

import java.nio.ByteBuffer

/**
 * The camera's per-frame work in C (`native/fdframe.c`): turning a frame, trading its chroma
 * order (NV21 and NV12), and the screen's RGBA pixels as NV21 or NV12. With NEON each is a few
 * times as fast as in Kotlin, and trading the chroma order costs nothing extra during a turn.
 */
object NativeFrames {
    init {
        System.loadLibrary("fdframe")
    }

    /**
     * [src] ([width]×[height] NV21 or NV12, even sides) turned clockwise by [degrees] (90, 180 or
     * 270) into [dst] (another array), which is then height×width for 90 and 270. With
     * [swapChroma], NV21 comes out as NV12 and the other way round.
     */
    fun rotate(src: ByteArray, width: Int, height: Int, degrees: Int, dst: ByteArray, swapChroma: Boolean = false) {
        require(turn(src, width, height, degrees, swapChroma, dst)) { "Can't turn a ${width}x$height frame by $degrees" }
    }

    /** [frame] ([width]×[height], even sides) from NV21 to NV12 or the other way round, in place. */
    fun swapChroma(frame: ByteArray, width: Int, height: Int) {
        require(swap(frame, width, height)) { "Can't swap a ${width}x$height frame's chroma" }
    }

    /**
     * [width]×[height] RGBA pixels (a direct buffer, read from its start; rows [rowStride] bytes
     * apart; even sides) as NV21, or NV12 with [into] [Nv21.Order.UV], into [out] ([Nv21.bytes]
     * long), in JPEG's full range (BT.601), as Android's cameras give: each pixel's Y, and each 2×2
     * block's U and V from its average.
     */
    fun fromRgba(rgba: ByteBuffer, rowStride: Int, width: Int, height: Int, out: ByteArray, into: Nv21.Order = Nv21.Order.VU) {
        require(convert(rgba, rowStride, width, height, out, into == Nv21.Order.UV)) { "Can't convert a ${width}x$height RGBA frame" }
    }

    @JvmStatic private external fun turn(src: ByteArray, width: Int, height: Int, degrees: Int, swap: Boolean, dst: ByteArray): Boolean
    @JvmStatic private external fun swap(frame: ByteArray, width: Int, height: Int): Boolean
    @JvmStatic private external fun convert(rgba: ByteBuffer, rowStride: Int, width: Int, height: Int, out: ByteArray, uvFirst: Boolean): Boolean
}
