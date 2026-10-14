package com.aurora.music.playback.haptics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

class MusicHapticsAnalyzerTest {
    private class Song(val rate: Int, seconds: Double) {
        val samples = FloatArray((rate * seconds).toInt())
        private val random = Random(7)

        fun kick(at: Double, amplitude: Double = 0.8) {
            var phase = 0.0
            val start = (at * rate).toInt()
            for (i in 0 until (0.35 * rate).toInt()) {
                val t = i.toDouble() / rate
                phase += 2 * PI * (50 + 100 * exp(-t / 0.03)) / rate
                val attack = minOf(1.0, t / 0.001)
                add(start + i, amplitude * attack * exp(-t / 0.12) * sin(phase))
            }
        }

        fun snare(at: Double, amplitude: Double = 0.6) {
            val start = (at * rate).toInt()
            for (i in 0 until (0.25 * rate).toInt()) {
                val t = i.toDouble() / rate
                val noise = random.nextDouble(-1.0, 1.0) * exp(-t / 0.08)
                val body = 0.3 * sin(2 * PI * 180 * t) * exp(-t / 0.05)
                add(start + i, amplitude * (noise + body))
            }
        }

        fun hat(at: Double, amplitude: Double = 0.25) {
            val start = (at * rate).toInt()
            var previous = 0.0
            for (i in 0 until (0.08 * rate).toInt()) {
                val t = i.toDouble() / rate
                val noise = random.nextDouble(-1.0, 1.0)
                add(start + i, amplitude * (noise - previous) * exp(-t / 0.02))
                previous = noise
            }
        }

        fun tone(from: Double, to: Double, hz: Double, amplitude: Double, tremolo: Double = 0.0) {
            for (i in (from * rate).toInt() until minOf(samples.size, (to * rate).toInt())) {
                val t = i.toDouble() / rate
                val fade = minOf(1.0, (t - from) / 0.05, (to - t) / 0.05)
                add(i, amplitude * fade * (1 - tremolo * (0.5 + 0.5 * sin(2 * PI * 0.5 * t))) * sin(2 * PI * hz * t))
            }
        }

        private fun add(index: Int, value: Double) {
            if (index in samples.indices) samples[index] += value.toFloat()
        }
    }

    private class Result : MusicHapticsAnalyzer.Sink {
        val taps = mutableListOf<HapticTap>()
        val texture = mutableListOf<Pair<Long, Float>>()
        override fun onTap(timeUs: Long, kind: HapticKind, strength: Float) {
            taps += HapticTap(timeUs, kind, strength)
        }
        override fun onTexture(timeUs: Long, level: Float) {
            texture += timeUs to level
        }
        fun of(kind: HapticKind) = taps.filter { it.kind == kind }
    }

    private fun analyse(song: Song, startUs: Long = 0, chunks: Random = Random(3)): Result {
        val result = Result()
        val analyzer = MusicHapticsAnalyzer(result)
        var at = 0
        while (at < song.samples.size) {
            val count = minOf(song.samples.size - at, chunks.nextInt(200, 5_000))
            analyzer.feed(song.samples, at, count, song.rate, startUs + at * 1_000_000L / song.rate)
            at += count
        }
        return result
    }

    private fun errorsMs(found: List<HapticTap>, truth: List<Double>, startUs: Long = 0): List<Double> = truth.mapNotNull { t ->
        found.minByOrNull { abs(it.timeUs - startUs - t * 1e6) }?.let { (it.timeUs - startUs - t * 1e6) / 1000 }?.takeIf { abs(it) <= 25 }
    }

    @Test fun kicksLandOnTheBeat() {
        val song = Song(48_000, 8.5)
        val beats = (0 until 16).map { 0.25 + it * 0.5 }
        beats.forEach { song.kick(it) }
        val result = analyse(song)
        val errors = errorsMs(result.of(HapticKind.KICK), beats)
        assertEquals(16, errors.size)
        assertEquals(16, result.of(HapticKind.KICK).size)
        assertTrue("kick timing $errors", errors.all { abs(it) <= 8 })
        assertTrue("mean ${errors.average()}", abs(errors.average()) <= 4)
    }

    @Test fun snaresAndHatsAreTheirOwnKinds() {
        val song = Song(44_100, 8.5)
        val kicks = (0 until 8).map { 0.25 + it * 1.0 }
        val snares = kicks.map { it + 0.5 }
        val hats = (0 until 32).map { 0.25 + it * 0.25 }.filter { h -> (kicks + snares).none { abs(it - h) < 0.01 } }
        kicks.forEach { song.kick(it) }
        snares.forEach { song.snare(it) }
        hats.forEach { song.hat(it) }
        listOf(196.0, 246.9, 392.0).forEach { song.tone(0.0, 8.5, it, 0.02) }
        val result = analyse(song)
        val kickErrors = errorsMs(result.of(HapticKind.KICK), kicks)
        val snareErrors = errorsMs(result.of(HapticKind.SNARE), snares)
        val hatErrors = errorsMs(result.of(HapticKind.HAT), hats)
        assertEquals(kicks.size, kickErrors.size)
        assertTrue("snares ${snareErrors.size}", snareErrors.size >= snares.size - 1)
        assertTrue("hats ${hatErrors.size}/${hats.size}", hatErrors.size >= hats.size * 0.8)
        assertTrue("kick $kickErrors snare $snareErrors hat $hatErrors", (kickErrors + snareErrors + hatErrors).all { abs(it) <= 10 })
        val strayKicks = result.of(HapticKind.KICK).filter { tap -> kicks.none { abs(tap.timeUs - it * 1e6) <= 25_000 } }
        assertTrue("stray kicks $strayKicks", strayKicks.isEmpty())
        // a kick's click reaches the snare band too, the timeline folds it into the kick
        val straySnares = result.of(HapticKind.SNARE).filter { tap -> tap.timeUs > 100_000 && (kicks + snares + hats).none { abs(tap.timeUs - it * 1e6) <= 25_000 } }
        assertTrue("stray snares $straySnares", straySnares.isEmpty())
    }

    @Test fun louderKicksTapHarder() {
        val song = Song(48_000, 8.5)
        val beats = (0 until 16).map { 0.25 + it * 0.5 }
        beats.forEachIndexed { i, at -> song.kick(at, if (i % 2 == 0) 0.8 else 0.2) }
        val kicks = analyse(song).of(HapticKind.KICK)
        fun near(at: Double) = kicks.firstOrNull { abs(it.timeUs - at * 1e6) <= 25_000 }
        val loud = beats.filterIndexed { i, _ -> i % 2 == 0 }.drop(2).mapNotNull(::near).map { it.strength }
        val quiet = beats.filterIndexed { i, _ -> i % 2 == 1 }.drop(2).mapNotNull(::near).map { it.strength }
        assertTrue("quiet kicks found ${quiet.size}", quiet.size >= 5)
        assertTrue("loud ${loud.average()} quiet ${quiet.average()}", loud.average() > quiet.average() + 0.15)
    }

    @Test fun heldBassRumblesWithoutTapping() {
        val song = Song(48_000, 4.5)
        song.tone(0.5, 3.5, 55.0, 0.5)
        val result = analyse(song)
        assertTrue("taps ${result.taps}", result.of(HapticKind.KICK).count { it.timeUs > 700_000 } == 0)
        val held = result.texture.filter { it.first in 1_000_000..3_000_000 }.map { it.second }
        assertTrue(held.isNotEmpty() && held.all { it >= 0.6f })
        assertTrue(result.texture.filter { it.first >= 3_800_000 }.all { it.second == 0f })
        assertTrue(result.texture.filter { it.first <= 400_000 }.all { it.second == 0f })
    }

    @Test fun kicksAloneDoNotRumble() {
        val song = Song(48_000, 6.0)
        (0 until 10).forEach { song.kick(0.25 + it * 0.5) }
        val result = analyse(song)
        assertTrue("${result.texture.map { it.second }}", result.texture.filter { it.first > 1_000_000 }.all { it.second <= 0.2f })
    }

    @Test fun aSteadyPadStaysQuiet() {
        val song = Song(44_100, 5.0)
        listOf(220.0, 277.2, 329.6, 1_320.0, 2_640.0).forEach { song.tone(0.2, 4.8, it, 0.12, tremolo = 0.4) }
        val result = analyse(song)
        assertTrue("taps ${result.taps}", result.taps.none { it.timeUs > 400_000 && it.timeUs < 4_700_000 })
    }

    @Test fun timesFollowTheStreamClockAtHiResRates() {
        val beats = (0 until 8).map { 0.25 + it * 0.5 }
        val startUs = 12_345_678L
        listOf(44_100, 96_000, 192_000).forEach { rate ->
            val song = Song(rate, 4.5)
            beats.forEach { song.kick(it) }
            val errors = errorsMs(analyse(song, startUs).of(HapticKind.KICK), beats, startUs)
            assertEquals("rate $rate", beats.size, errors.size)
            assertTrue("rate $rate $errors", errors.all { abs(it) <= 8 })
        }
    }

    @Test fun resetForgetsThePreviousStream() {
        val result = Result()
        val analyzer = MusicHapticsAnalyzer(result)
        val song = Song(48_000, 2.0)
        song.kick(0.5)
        song.kick(1.5)
        analyzer.feed(song.samples, 0, 30_000, 48_000, 0)
        analyzer.reset()
        analyzer.feed(song.samples, 0, song.samples.size, 48_000, 5_000_000)
        val kicks = result.of(HapticKind.KICK).filter { it.timeUs >= 5_000_000 }
        assertEquals(2, kicks.size)
        assertTrue(abs(kicks[0].timeUs - 5_500_000) <= 8_000)
    }
}
