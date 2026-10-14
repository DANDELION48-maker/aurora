package com.aurora.music.playback.engine

import com.aurora.music.data.ReverbPreset
import com.aurora.music.data.ReverbPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class ReverbKernelTest {
    private val rate = 48_000
    private val format = AudioStreamFormat(rate, ChannelLayout.STEREO)

    private fun kernel(prefs: ReverbPrefs) = ReverbKernel().apply { configure(rate); settings = prefs }

    private fun run(kernel: ReverbKernel, frames: Int, source: (Int, Int) -> Double): DoubleArray {
        val out = DoubleArray(frames * 2)
        val block = AudioBlock(format, 1024)
        var done = 0
        while (done < frames) {
            val n = minOf(1024, frames - done)
            block.begin(n)
            for (i in 0 until n) { block.samples[i * 2] = source(done + i, 0); block.samples[i * 2 + 1] = source(done + i, 1) }
            kernel.process(block)
            System.arraycopy(block.samples, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    private fun rms(x: DoubleArray, from: Int = 0, until: Int = x.size): Double {
        var sum = 0.0
        for (i in from until until) sum += x[i] * x[i]
        return sqrt(sum / (until - from))
    }

    private fun noise(seed: Long): (Int, Int) -> Double {
        val random = java.util.Random(seed)
        val values = DoubleArray(rate * 12) { random.nextGaussian() * 0.1 }
        return { frame, channel -> values[(frame * 2 + channel) % values.size] }
    }

    @Test fun disabledLeavesSamplesUntouched() {
        val kernel = kernel(ReverbPrefs(enabled = false))
        val source = noise(1)
        val out = run(kernel, 4096, source)
        for (i in 0 until 4096) assertEquals(source(i, 0), out[i * 2], 0.0)
        assertFalse(kernel.active)
    }

    @Test fun wetLevelStaysNearDryAcrossPresets() {
        ReverbPreset.ALL.forEachIndexed { index, preset ->
            val kernel = kernel(preset.copy(enabled = true, mix = 1f))
            val out = run(kernel, rate * 6, noise(index.toLong()))
            val ratio = rms(out, rate * 2 * 2) / 0.1
            println("preset $index wet/dry rms ${"%.2f".format(ratio)} (${"%.1f".format(20 * log10(ratio))} dB)")
            assertTrue("preset $index ratio $ratio", ratio in 0.5..2.0)
        }
    }

    @Test fun decayFollowsRt60AndExtremesStayStable() {
        for (decay in listOf(0.5f, 2f, 6f)) {
            val kernel = kernel(ReverbPrefs(enabled = true, mix = 1f, decaySec = decay, damping = 0f, early = 0f, predelayMs = 0f, lowCutHz = 20f, highCutHz = 20000f))
            val seconds = decay * 1.5 + 0.5
            val out = run(kernel, (rate * seconds).toInt()) { frame, _ -> if (frame < 480) noise(9)(frame, 0) * 5 else 0.0 }
            val window = rate / 20
            fun level(at: Double) = rms(out, (at * rate).toInt() * 2, ((at * rate).toInt() + window) * 2)
            val start = level(0.15); val end = level(0.15 + decay * 0.5)
            val drop = 20 * log10(end / start)
            println("decay $decay: drop over half RT60 ${"%.1f".format(drop)} dB (ideal -30)")
            assertTrue("decay $decay drop $drop", drop in -40.0..-20.0)
        }
        val wild = kernel(ReverbPrefs(enabled = true, mix = 1f, decaySec = 12f, damping = 0f, size = 1f, modulation = 1f, width = 1.5f))
        val out = run(wild, rate * 10, noise(3))
        assertTrue(out.all { it.isFinite() } && rms(out, out.size - rate * 2) < 1.0)
    }

    @Test fun switchingOffRingsOutThenGoesIdle() {
        val kernel = kernel(ReverbPrefs(enabled = true, decaySec = 1f))
        run(kernel, rate, noise(4))
        kernel.settings = kernel.settings.copy(enabled = false)
        val tail = run(kernel, rate / 2) { _, _ -> 0.0 }
        assertTrue(rms(tail, 0, rate / 10) > 1e-4)
        run(kernel, rate * 3) { _, _ -> 0.0 }
        assertFalse(kernel.active)
        val source = noise(5)
        val after = run(kernel, 2048, source)
        for (i in 0 until 2048) assertEquals(source(i, 1), after[i * 2 + 1], 0.0)
    }

    @Test fun movingEveryControlMidSongDoesNotClick() {
        val kernel = kernel(ReverbPrefs(enabled = true, mix = 0.5f))
        val tone = { frame: Int, _: Int -> 0.3 * sin(2 * Math.PI * 440 * frame / rate) }
        var last = run(kernel, rate, tone)
        var worst = 0.0
        var offset = rate
        for (step in 0 until 40) {
            val t = step / 39f
            kernel.settings = kernel.settings.copy(size = t, predelayMs = 200 * t, mix = 0.2f + 0.6f * t, decaySec = 0.3f + 8 * t,
                damping = t, width = 1.5f * t, early = 1 - t, lowCutHz = 20f + 500 * t, highCutHz = 20000f - 15000 * t)
            val start = offset
            val next = run(kernel, 2048) { frame, channel -> tone(start + frame, channel) }
            worst = maxOf(worst, abs(next[0] - last[last.size - 2]))
            for (i in 1 until 2048) worst = maxOf(worst, abs(next[i * 2] - next[i * 2 - 2]))
            last = next; offset += 2048
        }
        println("largest sample step while sweeping controls ${"%.4f".format(worst)}")
        assertTrue("step $worst", worst < 0.2)
    }
}
