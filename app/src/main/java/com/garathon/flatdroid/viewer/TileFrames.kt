package com.garathon.flatdroid.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import java.nio.ByteBuffer

/**
 * The framebuffer in tiles ([TileLayout]) and whole, for Android before 10 ([NativeFrames] needs
 * it): a small update copies and uploads only the tiles it changed, one changing most of the
 * screen the whole bitmap (one upload costs less than many). Tiles changed out of sight (under
 * the keyboard, outside a zoomed-in view) wait until they come into sight.
 */
class TileFrames(private val size: Int = SIZE) : FrameDrawer {
    private var fb: Framebuffer? = null
    private var layout = TileLayout(0, 0, size)
    private var tiles: Array<Bitmap> = emptyArray()
    private var whole: Bitmap? = null
    /** Tiles whose bitmap is behind the framebuffer. */
    private var dirty = BooleanArray(0)
    private var drawWhole = true
    /** Small updates in a row while drawing the whole bitmap: enough of them switch back to tiles. */
    private var smallUpdates = 0
    private val scratch = ByteArray((size + 2) * (size + 2) * Rfb.BYTES_PER_PIXEL)
    private val tileMatrix = Matrix()
    private val tileClip = RectF()

    override val hasHidden: Boolean get() = !drawWhole && dirty.any { it }

    override fun frame(fb: Framebuffer, visible: Rect): Boolean {
        val new = fb !== this.fb
        if (new) allocate(fb)
        val inSight = dirty.indices.count { dirty[it] && inSight(it, visible) }
        val changed = dirty.any { it }
        when {
            // New, or what changed in sight is most of the screen: the whole bitmap, one upload.
            new || tiles.size == 1 || inSight * 2 > tiles.size -> {
                copyWhole(fb)
                drawWhole = true
                smallUpdates = 0
                return true
            }
            !changed -> return false
            // Small, while the whole bitmap is current: it stays so for a few, then the tiles take over.
            drawWhole && ++smallUpdates < SMALL_UPDATES_TO_TILES -> {
                copyWhole(fb)
                return true
            }
            drawWhole -> {
                drawWhole = false
                dirty.fill(true)
                copyInSight(fb, visible)
                return true
            }
            else -> return copyInSight(fb, visible)
        }
    }

    override fun reveal(fb: Framebuffer, visible: Rect): Boolean = fb === this.fb && !drawWhole && copyInSight(fb, visible)

    private fun allocate(fb: Framebuffer) {
        this.fb = fb
        tiles.forEach(Bitmap::recycle)
        whole?.recycle()
        layout = TileLayout(fb.width, fb.height, size)
        tiles = Array(layout.tiles.size) {
            val tile = layout.tiles[it]
            bitmap(tile.bitmapRight - tile.bitmapLeft, tile.bitmapBottom - tile.bitmapTop)
        }
        whole = bitmap(fb.width, fb.height)
        dirty = BooleanArray(tiles.size) { true }
        fb.onChange = { x, y, width, height -> layout.forEachTouching(x, y, width, height) { dirty[it] = true } }
    }

    private fun bitmap(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }

    private fun inSight(i: Int, visible: Rect): Boolean {
        val tile = layout.tiles[i]
        return tile.bitmapLeft < visible.right && tile.bitmapRight > visible.left && tile.bitmapTop < visible.bottom && tile.bitmapBottom > visible.top
    }

    private fun copyWhole(fb: Framebuffer) {
        whole!!.copyPixelsFromBuffer(ByteBuffer.wrap(fb.pixels, 0, fb.height * fb.stride))
        dirty.fill(false)
    }

    private fun copyInSight(fb: Framebuffer, visible: Rect): Boolean {
        var copied = false
        for (i in dirty.indices) {
            if (!dirty[i] || !inSight(i, visible)) continue
            copyTile(fb, i)
            dirty[i] = false
            copied = true
        }
        return copied
    }

    private fun copyTile(fb: Framebuffer, i: Int) {
        val tile = layout.tiles[i]
        val row = (tile.bitmapRight - tile.bitmapLeft) * Rfb.BYTES_PER_PIXEL
        val left = tile.bitmapLeft * Rfb.BYTES_PER_PIXEL
        var to = 0
        for (y in tile.bitmapTop until tile.bitmapBottom) {
            System.arraycopy(fb.pixels, y * fb.stride + left, scratch, to, row)
            to += row
        }
        tiles[i].copyPixelsFromBuffer(ByteBuffer.wrap(scratch, 0, to))
    }

    override fun draw(canvas: Canvas, matrix: Matrix, paint: Paint) {
        val wholeBitmap = whole ?: return
        if (drawWhole) return canvas.drawBitmap(wholeBitmap, matrix, paint)
        for (i in tiles.indices) {
            val tile = layout.tiles[i]
            canvas.save()
            // Only the tile's own area: its extra pixels are there for scaling, the neighbours draw them.
            tileClip.set(tile.left.toFloat(), tile.top.toFloat(), tile.right.toFloat(), tile.bottom.toFloat())
            matrix.mapRect(tileClip)
            canvas.clipRect(tileClip)
            tileMatrix.set(matrix)
            tileMatrix.preTranslate(tile.bitmapLeft.toFloat(), tile.bitmapTop.toFloat())
            canvas.drawBitmap(tiles[i], tileMatrix, paint)
            canvas.restore()
        }
    }

    override fun hashRows(fb: Framebuffer, out: IntArray): Boolean {
        ScrollMeter.hashRows(fb.pixels, fb.stride, fb.width, fb.height, out)
        return true
    }

    companion object {
        /** Measured best at 50, 75 and 100% Resolution; smaller tiles cost more to draw than they save. */
        const val SIZE = 192
        /** Small updates in a row before the tiles are used again after a large one. */
        private const val SMALL_UPDATES_TO_TILES = 3
    }
}
