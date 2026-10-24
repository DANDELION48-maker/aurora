package com.aurora.music.playback.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class PaDriveTest {
    private val rate = 48_000

    private fun drive(amount: Double, level: Double, scale: Double = 1.0, hz: Double = 220.0): DoubleArray {
        val pa = PaDrive().apply { configure(rate) }
        return DoubleArray(rate) { i ->
            val x = scale * 0.3 * sin(2 * PI * hz * i / rate)
            pa.process(x, x, amount, level * scale); pa.left
        }
    }

    // energy of harmonics 2..8 relative to the fundamental, after the filters settle
    private fun harmonics(y: DoubleArray, hz: Double): Double {
        fun power(f: Double): Double {
            var re = 0.0; var im = 0.0
            for (i in rate / 2 until rate) { re += y[i] * cos(2 * PI * f * i / rate); im += y[i] * sin(2 * PI * f * i / rate) }
            return re * re + im * im
        }
        return (2..8).sumOf { power(hz * it) } / power(hz)
    }

    @Test fun noDriveIsTransparent() {
        val y = drive(0.0, 0.2, hz = 1000.0)
        for (i in rate / 2 until rate) assertEquals(0.3 * sin(2 * PI * 1000.0 * i / rate), y[i], 2e-3)
    }

    @Test fun pushingHarderBreaksItUpMore() {
        val gentle = harmonics(drive(0.2, 0.21), 220.0)
        val hard = harmonics(drive(0.9, 0.21), 220.0)
        println("harmonics re fundamental: gentle %.4f, hard %.4f".format(gentle, hard))
        assertTrue(hard > gentle * 10 && hard > 0.01)
    }

    @Test fun aQuietMasterBreaksUpLikeALoudOne() {
        val loud = drive(0.7, 0.6)
        val quiet = drive(0.7, 0.6, scale = 0.05)
        for (i in loud.indices) assertEquals(loud[i] * 0.05, quiet[i], 1e-9)
    }

    @Test fun drivenNoiseKeepsItsLevel() {
        val random = java.util.Random(3)
        var lp = 0.0
        val x = DoubleArray(rate * 2) { lp += 0.12 * (random.nextGaussian() - lp); lp * 0.4 }
        val rms = sqrt(x.sumOf { it * it } / x.size)
        for (amount in listOf(0.3, 0.6, 0.9)) {
            val pa = PaDrive().apply { configure(rate) }
            val y = DoubleArray(x.size) { pa.process(x[it], x[it], amount, rms); pa.left }
            val ratio = sqrt(y.sumOf { it * it } / y.size) / rms
            println("drive $amount keeps %.2f of the level".format(ratio))
            assertTrue(ratio in 0.85..1.15)
        }
    }
}
