package com.aurora.music.data

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
    fun countsSyllables() {
        assertEquals(1, syllables("love", true))
        assertEquals(3, syllables("beautiful,", true))
        assertEquals(2, syllables("table", true))
        assertEquals(1, syllables("walked", true))
        assertEquals(2, syllables("wanted", true))
        assertEquals(1, syllables("hmm", true))
        assertEquals(0, syllables("—", true))
        assertEquals(4, syllables("愛してる", false))
        assertEquals(2, syllables("любовь", false))
    }

    private fun song(vararg starts: Long) = Lyrics(starts.map { LyricsRepository.timedLine(it, "la la la la la la la la") }, true, "test")
        .withWordTiming().lines

    @Test
    fun slowSongsFillTheirLines() {
        val lines = song(0, 6000, 12000, 18000, 24000)
        assertTrue(lines[2].words.last().endMs - lines[2].startMs > 5000)
    }

    @Test
    fun fastSongsFitTheirLines() {
        val lines = song(0, 2000, 4000, 6000, 8000)
        assertTrue(lines[2].words.last().endMs - lines[2].startMs <= 2000)
    }

    @Test
    fun linesBeforeAGapKeepTheSongsPace() {
        val lines = song(0, 2000, 4000, 6000, 18000, 20000)
        val words = lines[3].words
        assertTrue(words[3].startMs - lines[3].startMs < 1100)
        assertTrue(words.last().endMs - lines[3].startMs < 4000)
    }

    @Test
    fun closesOpenLastWord() {
        val lyrics = Lyrics(LyricsRepository.parseLrc("[00:01.00]<00:01.00>One <00:01.50>two\n[00:09.00]Three"), true, "test")
            .withWordTiming()
        val last = lyrics.lines[0].words.last()
        assertTrue(last.endMs in 1501L..9000L)
    }
}
