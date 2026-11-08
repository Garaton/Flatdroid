package com.garathon.flatdroid.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class TileLayoutTest {
    @Test
    fun tilesCoverTheScreenWithAPixelMoreOnEverySideForScaling() {
        val layout = TileLayout(width = 300, height = 150, size = 128)
        assertEquals(6, layout.tiles.size)
        assertEquals(TileLayout.Tile(0, 0, 128, 128, 0, 0, 129, 129), layout.tiles[0])
        assertEquals(TileLayout.Tile(128, 0, 256, 128, 127, 0, 257, 129), layout.tiles[1])
        assertEquals(TileLayout.Tile(256, 128, 300, 150, 255, 127, 300, 150), layout.tiles[5])
    }

    @Test
    fun aChangeReachesEveryTileThatHoldsItIncludingTheirExtraPixels() {
        val layout = TileLayout(width = 300, height = 150, size = 128)
        fun touched(x: Int, y: Int, w: Int, h: Int) = buildList { layout.forEachTouching(x, y, w, h) { add(it) } }
        assertEquals(listOf(0), touched(10, 10, 5, 5))
        assertEquals(listOf(0, 1), touched(127, 10, 1, 1)) // the first tile's edge: also the next one's extra column
        assertEquals(listOf(0, 1, 3, 4), touched(127, 127, 2, 2))
        assertEquals(emptyList<Int>(), touched(0, 0, 0, 0))
    }
}
