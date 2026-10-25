package com.aurora.music.playback.engine

import com.aurora.music.data.ConcertSound
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

class CrowdNoiseTest {
    private val rate = 48_000

    private fun run(crowd: CrowdNoise, seconds: Double, amount: Double, music: (Int) -> Double): DoubleArray {
        val n = (seconds * rate).toInt()
        return DoubleArray(n * 2) { i ->
            if (i % 2 == 0) { val x = music(i / 2); crowd.next(x, x, amount); crowd.left } else crowd.right
        }
    }

    private fun db(x: DoubleArray, from: Int, until: Int) = 20 * log10(sqrt((from until until).sumOf { x[it] * x[it] } / (until - from)))

    @Test fun theMurmurSitsWellUnderTheBand() {
        val random = java.util.Random(1)
        val crowd = CrowdNoise().apply { configure(rate) }
        val out = run(crowd, 8.0, 1.0) { random.nextGaussian() * 0.1 }
        val level = db(out, rate * 6 * 2, out.size) - 20 * log10(0.1)
        println("murmur %.1f dB under the music".format(level))
        assertTrue(level in -34.0..-16.0)
        assertTrue(out.all { it.isFinite() })
    }

    @Test fun aLoudSongThatStopsGetsCheered() {
        val random = java.util.Random(2)
        val crowd = CrowdNoise().apply { configure(rate) }
        val out = run(crowd, 30.0, 1.0) { i -> if (i < rate * 25) random.nextGaussian() * 0.2 else 0.0 }
        val during = db(out, rate * 20 * 2, rate * 24 * 2)
        val after = db(out, rate * 26 * 2, rate * 29 * 2)
        println("crowd during the song %.1f dB, after it stops %.1f dB".format(during, after))
        assertTrue(after > during + 8)
    }

    @Test fun aChorusThatLiftsGetsCheeredAndSungAlongToByAPopCrowd() {
        // crowd level against the band, in the verse and once the chorus has lifted
        fun lift(sound: Int): Pair<Double, Double> {
            val random = java.util.Random(3)
            val crowd = CrowdNoise().apply { configure(rate); personality(ConcertSound.ALL[sound]) }
            val out = run(crowd, 18.0, 1.0) { i -> random.nextGaussian() * if (i < rate * 12) 0.06 else 0.25 }
            return db(out, rate * 8 * 2, rate * 11 * 2) - 20 * log10(0.06) to db(out, rate * 13 * 2, rate * 16 * 2) - 20 * log10(0.25)
        }
        val (popVerse, popChorus) = lift(ConcertSound.POP); val (_, gazeChorus) = lift(ConcertSound.SHOEGAZE)
        println("crowd against the band: pop verse %.1f dB, pop chorus %.1f dB, shoegaze chorus %.1f dB".format(popVerse, popChorus, gazeChorus))
        assertTrue(popChorus > popVerse + 3 && popChorus > gazeChorus + 6)
    }

    @Test fun aDropAfterABuildGetsARoar() {
        fun drop(sound: Int): Double {
            val random = java.util.Random(4)
            val crowd = CrowdNoise().apply { configure(rate); personality(ConcertSound.ALL[sound]) }
            var hp = 0.0; var prev = 0.0
            val out = run(crowd, 16.0, 1.0) { i ->
                val n = random.nextGaussian() * 0.15
                hp = 0.95 * (hp + n - prev); prev = n
                if (i < rate * 12) hp else hp + 0.6 * kotlin.math.sin(2 * Math.PI * 50 * i / rate)
            }
            return db(out, (rate * 12.5).toInt() * 2, (rate * 14.5).toInt() * 2) - db(out, rate * 9 * 2, rate * 11 * 2)
        }
        val edm = drop(ConcertSound.ELECTRONIC); val jazz = drop(ConcertSound.JAZZ)
        println("drop: electronic crowd +%.1f dB, jazz crowd +%.1f dB".format(edm, jazz))
        assertTrue(edm > 6 && edm > jazz + 3)
    }

    @Test fun musicStartingAfterSilenceGetsACheer() {
        val random = java.util.Random(6)
        val crowd = CrowdNoise().apply { configure(rate); personality(ConcertSound.ALL[ConcertSound.ROCK]) }
        val warm = run(crowd, 25.0, 1.0) { random.nextGaussian() * 0.1 }
        val out = run(crowd, 14.0, 1.0) { i -> if (i < rate * 3) 0.0 else random.nextGaussian() * 0.1 }
        val opening = db(out, (rate * 3.5).toInt() * 2, (rate * 5.0).toInt() * 2)
        val later = db(out, rate * 11 * 2, rate * 13 * 2)
        println("song starts: crowd %.1f dB, settled %.1f dB".format(opening, later))
        assertTrue(warm.all { it.isFinite() } && opening > later + 6)
    }

    @Test fun sameCrowdEveryTime() {
        val a = run(CrowdNoise().apply { configure(rate) }, 2.0, 0.5) { 0.1 }
        val b = run(CrowdNoise().apply { configure(rate) }, 2.0, 0.5) { 0.1 }
        assertArrayEquals(a, b, 0.0)
    }
}
