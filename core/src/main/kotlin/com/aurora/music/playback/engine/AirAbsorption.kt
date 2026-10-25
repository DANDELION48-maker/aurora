package com.aurora.music.playback.engine

import kotlin.math.exp
import kotlin.math.pow

// iso 9613-1 atmospheric absorption of a pure tone, in dB per metre
object AirAbsorption {
    fun dbPerMeter(hz: Double, tempC: Double, humidity: Double, pressureKpa: Double = 101.325): Double {
        val t = tempC + 273.15
        val ratio = t / 293.15
        val pr = pressureKpa / 101.325
        val saturation = 10.0.pow(-6.8346 * (273.16 / t).pow(1.261) + 4.6151)
        val h = humidity.coerceIn(0.0, 100.0) * saturation / pr
        val frO = pr * (24 + 4.04e4 * h * (0.02 + h) / (0.391 + h))
        val frN = pr * ratio.pow(-0.5) * (9 + 280 * h * exp(-4.170 * (ratio.pow(-1.0 / 3) - 1)))
        val f2 = hz * hz
        return 8.686 * f2 * (1.84e-11 / pr * ratio.pow(0.5) + ratio.pow(-2.5) *
            (0.01275 * exp(-2239.1 / t) / (frO + f2 / frO) + 0.1068 * exp(-3352.0 / t) / (frN + f2 / frN)))
    }
}
