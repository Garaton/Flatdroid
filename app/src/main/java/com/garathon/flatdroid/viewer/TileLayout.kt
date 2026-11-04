package com.garathon.flatdroid.viewer

/**
 * The framebuffer cut into square tiles, each its own bitmap, so an update copies and uploads to
 * the GPU only the tiles it changed. Each tile's bitmap holds one pixel more on every side than
 * its own area ([Tile.left] to [Tile.right]); drawn clipped to its own area, scaling then blends
 * the same neighbouring pixels across a seam as one bitmap would.
 */
class TileLayout(val width: Int, val height: Int, private val size: Int) {
    /** Its own area, and its bitmap's. */
    data class Tile(
        val left: Int, val top: Int, val right: Int, val bottom: Int,
        val bitmapLeft: Int, val bitmapTop: Int, val bitmapRight: Int, val bitmapBottom: Int,
    )

    private val columns = (width + size - 1) / size
    private val rows = (height + size - 1) / size

    val tiles: List<Tile> = List(columns * rows) { i ->
        val left = i % columns * size
        val top = i / columns * size
        val right = minOf(width, left + size)
        val bottom = minOf(height, top + size)
        Tile(left, top, right, bottom, maxOf(0, left - 1), maxOf(0, top - 1), minOf(width, right + 1), minOf(height, bottom + 1))
    }

    /** Calls [touched] with every tile whose bitmap holds part of the rectangle. */
    inline fun forEachTouching(x: Int, y: Int, width: Int, height: Int, touched: (Int) -> Unit) {
        for (i in tiles.indices) {
            val tile = tiles[i]
            if (tile.bitmapLeft < x + width && tile.bitmapRight > x && tile.bitmapTop < y + height && tile.bitmapBottom > y) touched(i)
        }
    }
}
