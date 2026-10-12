package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class VocalActivityTest {
    private val rate = 44100
    private val sung = listOf(2.0..5.0, 7.0..9.5, 12.0..16.0)

    // centred harmonic voice with vibrato over wide chords, centred drums and bass, and stereo noise
    private fun mix(seconds: Int): ShortArray {
        val random = Random(7)
        val out = ShortArray(rate * seconds * 2)
        var phase = 0.0
        for (i in 0 until rate * seconds) {
            val t = i.toDouble() / rate
            val gate = sung.maxOf { r -> ((minOf(t - r.start, r.endInclusive - t)) / .03).coerceIn(0.0, 1.0) }
            phase += 2 * PI * 220 * (1 + .02 * sin(2 * PI * 5 * t)) / rate
            var voice = 0.0
            for (h in 1..8) voice += sin(phase * h) / h
            voice *= .09 * gate
            val beat = t % .5
            val drum = .3 * exp(-beat / .04) * (random.nextDouble() * 2 - 1)
            val bass = .15 * sin(2 * PI * 82.4 * t)
            val left = .06 * (sin(2 * PI * 261.6 * t) + sin(2 * PI * 392.0 * t))
            val right = .06 * (sin(2 * PI * 329.6 * t) + sin(2 * PI * 493.9 * t))
            val l = voice + drum + bass + left + .01 * random.nextGaussian()
            val r = voice + drum + bass + right + .01 * random.nextGaussian()
            out[i * 2] = (l.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
            out[i * 2 + 1] = (r.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
        return out
    }

    private fun analyze(pcm: ShortArray): VocalActivity {
        val analyzer = VocalActivityAnalyzer(rate, 2)
        var offset = 0
        while (offset < pcm.size) {
            val n = minOf(4096, pcm.size - offset)
            analyzer.feed(pcm.copyOfRange(offset, offset + n), n)
            offset += n
        }
        return analyzer.finish()
    }

    @Test
    fun findsTheCentredVoiceButNotDrumsBassOrWideChords() {
        val activity = analyze(mix(18))
        fun mean(from: Double, to: Double) = (from * 1000..to * 1000).let { r ->
            generateSequence(r.start.toLong()) { it + 20 }.takeWhile { it <= r.endInclusive }.map { activity.at(it) }.average()
        }
        sung.forEach { assertTrue("inside $it", mean(it.start + .2, it.endInclusive - .2) > .7) }
        listOf(0.3..1.7, 5.3..6.7, 9.8..11.7, 16.3..17.7).forEach { assertTrue("outside $it", mean(it.start, it.endInclusive) < .3) }
        val onset = generateSequence(1000L) { it + 20 }.first { activity.at(it) >= .5f }
        assertEquals(2000.0, onset.toDouble(), 100.0)
        assertTrue(activity.reliable)
        val restored = VocalActivity.decode(activity.encode())
        assertNotNull(restored)
        assertEquals(activity.levels.toList(), restored!!.levels.toList())
        assertEquals(activity.onsets.toList(), restored.onsets.toList())
    }

    // 22.05k and 44.1k don't split into whole 20ms hops, which used to drift a quarter second over two minutes
    @Test
    fun framesStayOnTheClock() {
        val rate = 22050
        val bursts = listOf(2.0..4.0, 6.0..8.0, 120.0..122.0)
        val random = Random(3)
        val out = ShortArray(rate * 125 * 2)
        for (i in 0 until rate * 125) {
            val t = i.toDouble() / rate
            var voice = 0.0
            if (bursts.any { t in it }) for (h in 1..8) voice += .09 * sin(2 * PI * 220 * h * t) / h
            out[i * 2] = ((voice + .01 * random.nextGaussian() + .05 * sin(2 * PI * 330 * t)) * 32767).toInt().toShort()
            out[i * 2 + 1] = ((voice + .01 * random.nextGaussian() + .05 * sin(2 * PI * 415 * t)) * 32767).toInt().toShort()
        }
        val analyzer = VocalActivityAnalyzer(rate, 2)
        analyzer.feed(out, out.size)
        val activity = analyzer.finish()
        val onset = generateSequence(118_000L) { it + 20 }.first { activity.at(it) >= .5f }
        assertEquals(120_000.0, onset.toDouble(), 60.0)
    }

    @Test
    fun marksTheStartOfEverySungNote() {
        val song = SyntheticSong(30.0, tempo = 70.0)
        var t = 2.0
        repeat(5) { i ->
            t = song.line(t) {
                word("never", .8, 294.0, 262.0); word("let", .4, 247.0); word("me", .4, 220.0 + 10 * i)
                word("go", 2.4, 247.0, hold = 1.9)
            }.end + 1.0
        }
        val activity = song.analyze()
        val notes = song.lines.flatMap { l -> l.words.flatMap { w -> w.notes } }
        val found = notes.count { n -> ((n.start * 1000).toInt() / 20 - 2..(n.start * 1000).toInt() / 20 + 2).any { activity.onset(it) >= .75f } }
        assertTrue("found $found of ${notes.size}", found >= notes.size * .85)
        val holds = notes.filter { it.end - it.start > 1.0 }
        val spurious = holds.sumOf { n -> ((n.start * 1000).toInt() / 20 + 15 until (n.end * 1000).toInt() / 20 - 5).count { activity.onset(it) >= .75f } }
        assertTrue("$spurious strong onsets inside held notes", spurious <= 1)
    }

    private fun activity(vararg spans: LongRange): VocalActivity {
        val levels = ByteArray(1000) { i -> if (spans.any { i * 20L in it }) 255.toByte() else 0 }
        return VocalActivity(20, levels, 5f, .4f)
    }

    private val line = Lyrics(listOf(LyricsRepository.timedLine(10_000, "la la la la la la la la"), LyricsRepository.timedLine(16_000, "la")), true, "test")

    @Test
    fun longSilencesDontStretchTheLastWord() {
        val lyrics = Lyrics(listOf(LyricsRepository.timedLine(10_000, "la la la la la la"), LyricsRepository.timedLine(16_000, "la")), true, "test")
        val words = lyrics.withWordTiming(activity(10_000L until 12_200L, 15_000L until 15_400L)).lines[0].words
        assertEquals(12_200.0, words.last().endMs.toDouble(), 40.0)
    }

    @Test
    fun wordsFollowTheDetectedSinging() {
        val words = line.withWordTiming(activity(10_600L until 13_000L, 13_400L until 14_200L)).lines[0].words
        assertEquals(10_600.0, words.first().startMs.toDouble(), 40.0)
        assertEquals(14_200.0, words.last().endMs.toDouble(), 40.0)
        assertTrue(words.none { it.startMs in 13_050L..13_350L })
    }

    @Test
    fun unreliableOrSilentAnalysisKeepsTheEstimate() {
        val estimate = line.withWordTiming().lines[0].words
        val unreliable = VocalActivity(20, ByteArray(1000) { 255.toByte() }, .5f, 1f)
        assertFalse(unreliable.reliable)
        assertEquals(estimate, line.withWordTiming(unreliable).lines[0].words)
        assertEquals(estimate, line.withWordTiming(activity(30_000L until 31_000L)).lines[0].words)
    }
}
