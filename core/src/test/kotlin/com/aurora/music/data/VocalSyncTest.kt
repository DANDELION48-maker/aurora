package com.aurora.music.data

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class VocalSyncTest {
    private class Result(val starts: List<Long>, val ends: List<Long>, val words: List<List<LyricWord>>)

    private fun align(song: SyntheticSong): Result {
        val activity = song.analyze()
        assertTrue(activity.reliable)
        val timed = song.lyrics().withWordTiming(activity).lines
        val starts = song.lines.flatMapIndexed { i, line ->
            line.words.mapIndexed { k, w -> abs(timed[i].words[k].startMs - (w.start * 1000).toLong()) }
        }
        val ends = song.lines.mapIndexed { i, line -> abs(timed[i].words.last().endMs - (line.end * 1000).toLong()) }
        return Result(starts, ends, timed.map { it.words })
    }

    // back-to-back lines with no breaks, timestamps a little off either way
    @Test
    fun fastSingingFollowsEveryWord() {
        val song = SyntheticSong(30.0, tempo = 140.0)
        val melody = doubleArrayOf(220.0, 247.0, 262.0, 294.0, 262.0, 247.0, 220.0, 196.0)
        var t = 2.0
        listOf("I keep on missing you every day and night", "baby I am calling but you never pick it up",
            "running round the city with your picture in my hand", "tell me what to do now cause I cannot let it go",
        ).forEachIndexed { i, text ->
            t = song.line(t, stampShift = listOf(0.0, -.12, .08, -.05)[i]) {
                text.split(" ").forEachIndexed { k, w ->
                    val count = syllables(w, true)
                    word(w, count * (.17 + .03 * ((k + i) % 3)), *DoubleArray(count) { melody[(k + i + it * 3) % 8] })
                }
            }.end + .08
        }
        val result = align(song)
        assertTrue("mean ${result.starts.average()}", result.starts.average() < 50)
        assertTrue("p90 ${result.starts.sorted()[result.starts.size * 9 / 10]}", result.starts.sorted()[result.starts.size * 9 / 10] < 60)
    }

    // held notes, a melisma, a breath inside a line, instrumental leads between lines and a note held over the next timestamp
    @Test
    fun slowSingingKeepsHeldNotesAndGapsApart() {
        val song = SyntheticSong(50.0, tempo = 70.0)
        song.line(4.0) {
            word("I", .45, 196.0); word("remember", 1.1, 220.0, 247.0, 262.0); word("the", .35, 247.0)
            word("night", 2.2, 220.0, hold = 1.8)
        }
        song.lead(9.0, 10.6, 330.0)
        song.line(12.0, stampShift = -.15) {
            word("you", .4, 262.0); word("were", .4, 247.0); word("standing", .9, 220.0, 196.0); word("there", 1.2, 220.0, hold = .7)
            rest(1.1)
            word("in", .35, 196.0); word("the", .35, 220.0); word("cold", .5, 247.0); word("December", 1.2, 262.0, 247.0, 220.0)
            word("rain", 1.8, 196.0, hold = 1.4)
        }
        song.line(24.0, stampShift = .1) {
            word("never", .8, 294.0, 262.0); word("let", .4, 247.0); word("me", .4, 220.0)
            word("go", 2.7, 247.0, 220.0, 196.0, 220.0)
        }
        song.lead(29.0, 31.0, 392.0)
        val tail = song.line(33.0) {
            word("and", .4, 196.0); word("I", .4, 220.0); word("waited", .9, 247.0, 220.0); word("so", 1.7, 262.0, hold = 1.3)
            word("long", .5, 247.0); word("for", .4, 220.0); word("you", 2.3, 196.0, hold = 1.9)
        }
        song.line(tail.end + .35, stampShift = -.95) {
            word("hold", .5, 220.0); word("me", .4, 247.0); word("closer", .9, 262.0, 247.0); word("now", 2.6, 220.0, hold = 2.2)
        }
        val result = align(song)
        assertTrue("mean ${result.starts.average()}", result.starts.average() < 60)
        assertTrue("first line ends at ${result.words[0].last().endMs}", result.words[0].last().endMs in 7900L..8400L)
        assertTrue("melisma line ends at ${result.words[2].last().endMs}", result.words[2].last().endMs in 28100L..28600L)
        assertTrue("held-over line starts at ${result.words[4][0].startMs}",
            abs(result.words[4][0].startMs - (song.lines[4].start * 1000).toLong()) < 100)
    }

    // two phrases per line with a breath between them, each ending on a held note over a centred pad
    @Test
    fun phrasesAfterABreathKeepTheirWords() {
        val song = SyntheticSong(32.0, tempo = 84.0, pad = .03)
        val melody = doubleArrayOf(196.0, 220.0, 247.0, 262.0, 294.0, 262.0, 247.0, 220.0)
        var t = 3.0
        listOf(listOf("when the morning comes", "I will be there"), listOf("every little thing", "you said to me"),
            listOf("take my hand and", "walk with me tonight"), listOf("I can feel your heart", "beating next to mine"),
        ).forEachIndexed { i, phrases ->
            t = song.line(t, stampShift = listOf(0.0, -.1, .12, 0.0)[i]) {
                phrases.forEachIndexed { p, phrase ->
                    val words = phrase.split(" ")
                    words.forEachIndexed { k, w ->
                        val last = k == words.lastIndex
                        word(w, syllables(w, true) * .3 + if (last) .9 else 0.0, melody[(k + p + i) % 8], hold = if (last) .9 else 0.0)
                    }
                    if (p == 0) rest(.45)
                }
            }.end + .6
        }
        val result = align(song)
        assertTrue("mean ${result.starts.average()}", result.starts.average() < 60)
        assertTrue("line ends ${result.ends}", result.ends.max() < 200)
    }
}
