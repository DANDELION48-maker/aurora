package com.aurora.music.data

import com.aurora.music.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimingTest {
    @Test
    fun parsesEnhancedWordTags() {
        val lines = LyricsRepository.parseLrc("[00:01.50]<00:01.50>Paper <00:02.00>lanterns <00:03.25>\n[00:04.00]Quiet harbor")
        assertEquals(1500L, lines[0].startMs)
        assertEquals("Paper lanterns", lines[0].text)
        assertEquals(listOf(LyricWord(1500, 2000, "Paper "), LyricWord(2000, 3250, "lanterns")), lines[0].words)
        assertEquals("Quiet harbor", lines[1].text)
        assertTrue(lines[1].words.isEmpty())
    }

    @Test
    fun repeatedTimestampsShiftWords() {
        val lines = LyricsRepository.parseLrc("[00:01.00][00:11.00]<00:01.00>Echo <00:01.40>again")
        assertEquals(listOf(11000L, 11400L), lines[1].words.map { it.startMs })
    }

    @Test
    fun estimatesWordsForLineSyncedLyrics() {
        val lyrics = Lyrics(LyricsRepository.parseLrc("[00:10.00]Paper lanterns drift\n[00:14.00]Quiet harbor"), true, "test")
            .withWordTiming()
        val words = lyrics.lines[0].words
        assertEquals("Paper lanterns drift", lyrics.lines[0].displayText)
        assertEquals(10000L, words.first().startMs)
        assertTrue(words.last().endMs <= 14000L)
        assertTrue(words.zipWithNext().all { (a, b) -> a.endMs == b.startMs })
        val fill = lyrics.lines[0].charFill(words[1].startMs)
        assertEquals(1f, fill[0])
        assertEquals(0f, fill.last())
    }

    @Test
    fun closesOpenLastWord() {
        val lyrics = Lyrics(LyricsRepository.parseLrc("[00:01.00]<00:01.00>One <00:01.50>two\n[00:09.00]Three"), true, "test")
            .withWordTiming()
        val last = lyrics.lines[0].words.last()
        assertTrue(last.endMs in 1501L..9000L)
    }
}
