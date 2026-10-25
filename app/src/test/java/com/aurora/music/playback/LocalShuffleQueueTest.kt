package com.aurora.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalShuffleQueueTest {
    @Test fun restoresOriginalOrderWithDuplicatesAndTracksAddedWhileShuffled() {
        val queue = listOf("c", "a", "x", "b", "a")
        val original = listOf("a", "b", "removed", "c", "a")
        assertEquals(listOf(1, 3, 0, 4, 2), LocalShuffleQueue.restoredOrder(queue, original))
    }

    @Test fun restoringAHugeQueueStaysLinear() {
        val original = List(50_000) { "t$it" }
        val queue = original.shuffled()
        val started = System.nanoTime()
        val order = LocalShuffleQueue.restoredOrder(queue, original)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(original, order.map(queue::get))
        assertTrue("restoring 50k tracks took ${tookMs}ms", tookMs < 2_000)
    }
}
