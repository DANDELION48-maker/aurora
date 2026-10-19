package com.aurora.music.desktop.ui

import com.aurora.music.desktop.linux.X11MoveResize
import org.junit.Assert.assertEquals
import org.junit.Test
import java.awt.Dimension
import java.awt.Rectangle

class TitleBarTest {
    private val from = Rectangle(100, 100, 1200, 800)
    private val min = Dimension(960, 600)

    @Test fun movingKeepsTheSize() =
        assertEquals(Rectangle(130, 90, 1200, 800), dragBounds(from, min, X11MoveResize.MOVE, 30, -10))

    @Test fun edgesMoveOnlyTheirOwnSides() {
        assertEquals(Rectangle(80, 100, 1220, 800), dragBounds(from, min, X11MoveResize.LEFT, -20, 50))
        assertEquals(Rectangle(100, 100, 1200, 850), dragBounds(from, min, X11MoveResize.BOTTOM, 40, 50))
        assertEquals(Rectangle(100, 60, 1240, 840), dragBounds(from, min, X11MoveResize.TOP_RIGHT, 40, -40))
    }

    @Test fun shrinkingStopsAtTheMinimumWithTheOppositeEdgeFixed() {
        assertEquals(Rectangle(340, 300, 960, 600), dragBounds(from, min, X11MoveResize.TOP_LEFT, 900, 900))
        assertEquals(Rectangle(100, 100, 960, 600), dragBounds(from, min, X11MoveResize.BOTTOM_RIGHT, -900, -900))
    }
}
