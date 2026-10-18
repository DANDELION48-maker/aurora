package com.aurora.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.sin

// the expected values come from the vendored c++ chromaprint the android app ships, run on the same signal
class ChromaprintTest {
    private fun signal(rate: Int, channels: Int, seconds: Int, seed: Int): ShortArray {
        val random = java.util.Random(seed.toLong())
        val chords = listOf(listOf(261.63, 329.63, 392.0), listOf(220.0, 261.63, 329.63), listOf(174.61, 220.0, 261.63), listOf(196.0, 246.94, 293.66))
        val pcm = ShortArray(rate * seconds * channels)
        for (i in 0 until rate * seconds) {
            val t = i.toDouble() / rate
            val chord = chords[((t / 1.7).toInt() + seed) % chords.size]
            var v = 0.0
            chord.forEachIndexed { k, f -> for (h in 1..4) v += sin(2 * PI * f * h * t + k) / (h * h) }
            val beat = if ((t * 2).rem(1.0) < 0.05) random.nextGaussian() * 0.6 else 0.0
            v = v * 0.18 + beat + random.nextGaussian() * 0.01
            for (c in 0 until channels) pcm[i * channels + c] = (v * (if (c == 1) 0.9 else 1.0) * 12000).toInt().coerceIn(-32768, 32767).toShort()
        }
        return pcm
    }

    private fun fingerprint(rate: Int, channels: Int, seed: Int): String {
        val pcm = signal(rate, channels, 40, seed)
        val fingerprinter = Chromaprint.Fingerprinter(rate, channels)
        var i = 0
        while (i < pcm.size) {
            val length = minOf(4096 * channels, pcm.size - i)
            fingerprinter.consume(pcm.copyOfRange(i, i + length), length)
            i += length
        }
        fingerprinter.finish()
        return Chromaprint.encode(fingerprinter.fingerprint.toIntArray())
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun resampledStereoMatchesTheNativeLibrary() {
        val fingerprint = fingerprint(44100, 2, 1)
        assertTrue(fingerprint.startsWith("AQABLkkSackcJcKBH8YLH8WP49Re5DeqW_BxD"))
        assertEquals("73d5c165d1fcc8ebd3cd09d20f57f26def54a8740010e6df1364af68fe725063", sha256(fingerprint))
    }

    @Test fun nativeRateMonoMatchesTheNativeLibrary() {
        val fingerprint = fingerprint(11025, 1, 3)
        assertTrue(fingerprint.startsWith("AQABLkwmRWGiRQF8XHgquD4e9MCNH-FnvIb4Y"))
        assertEquals("6bfa1f899feedada039bd57d7158fdb31d56c85ba04077ba51296334268d9db0", sha256(fingerprint))
    }
}
