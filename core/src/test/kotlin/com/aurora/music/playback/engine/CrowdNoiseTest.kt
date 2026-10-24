package com.aurora.music.playback.engine

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

    @Test fun sameCrowdEveryTime() {
        val a = run(CrowdNoise().apply { configure(rate) }, 2.0, 0.5) { 0.1 }
        val b = run(CrowdNoise().apply { configure(rate) }, 2.0, 0.5) { 0.1 }
        assertArrayEquals(a, b, 0.0)
    }
}
