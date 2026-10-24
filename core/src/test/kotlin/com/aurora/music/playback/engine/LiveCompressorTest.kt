package com.aurora.music.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class LiveCompressorTest {
    private val rate = 48_000

    // quiet noise bed with a loud 40 ms hit every quarter second
    private fun hits(scale: Double): (Int) -> Double {
        val random = java.util.Random(7)
        val bed = DoubleArray(rate) { random.nextGaussian() * 0.05 }
        return { i -> scale * (bed[i % rate] + if (i % (rate / 4) < rate / 25) 0.6 * sin(2 * Math.PI * 120 * i / rate) else 0.0) }
    }

    @Test fun hitsGetSquashedAndTheBedIsLeftAlone() {
        val c = LiveCompressor().apply { configure(rate, 5.0, 0.0, 0.1, 0.0) }
        val x = hits(1.0)
        var onHit = 0.0; var onBed = 0.0
        for (i in 0 until rate * 3) {
            val gr = c.next(x(i), x(i))
            if (i < rate) continue
            val phase = i % (rate / 4)
            if (phase < rate / 25) onHit = minOf(onHit, gr) else if (phase > rate / 4 - rate / 50) onBed = minOf(onBed, gr)
        }
        println("deepest squash on hits %.1f dB, on the bed %.2f dB".format(onHit, onBed))
        assertTrue(onHit < -3)
        assertTrue(onBed > -0.5)
    }

    @Test fun quietAndLoudMastersGetTheSameSquash() {
        val loud = LiveCompressor().apply { configure(rate, 4.0, 2.0, 0.15, 1.5) }
        val quiet = LiveCompressor().apply { configure(rate, 4.0, 2.0, 0.15, 1.5) }
        val x = hits(1.0)
        for (i in 0 until rate * 3) {
            val a = loud.next(x(i), x(i)); val b = quiet.next(x(i) * 0.05, x(i) * 0.05)
            if (i > rate) assertEquals(a, b, 1e-6)
        }
    }

    @Test fun ratioOneOnlyAddsMakeup() {
        val c = LiveCompressor().apply { configure(rate, 1.0, 0.0, 0.1, 0.7) }
        val x = hits(1.0)
        for (i in 0 until rate) assertEquals(0.7, c.next(x(i), x(i)), 1e-12)
    }
}
