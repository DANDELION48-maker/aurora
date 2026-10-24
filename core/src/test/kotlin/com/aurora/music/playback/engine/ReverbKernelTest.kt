package com.aurora.music.playback.engine

import com.aurora.music.data.ConcertPrefs
import com.aurora.music.data.ConcertScene
import com.aurora.music.data.ConcertSound
import com.aurora.music.data.ConcertVenue
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

    private fun concert(venue: Int) = ReverbPrefs(enabled = true, concert = ConcertPrefs(enabled = true).withVenue(venue))

    private fun runSized(kernel: ReverbKernel, frames: Int, sizes: IntArray, source: (Int, Int) -> Double): DoubleArray {
        val out = DoubleArray(frames * 2)
        val block = AudioBlock(format, 1024)
        var done = 0; var i = 0
        while (done < frames) {
            val n = minOf(sizes[i++ % sizes.size], frames - done)
            block.begin(n)
            for (f in 0 until n) { block.samples[f * 2] = source(done + f, 0); block.samples[f * 2 + 1] = source(done + f, 1) }
            kernel.process(block)
            System.arraycopy(block.samples, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    // music-like spectrum: white noise through a one-pole low-pass near 1 khz
    private fun warmNoise(seed: Long): (Int, Int) -> Double {
        val random = java.util.Random(seed)
        val values = DoubleArray(rate * 12)
        var l = 0.0; var r = 0.0
        for (i in 0 until values.size / 2) {
            l += 0.12 * (random.nextGaussian() - l); r += 0.12 * (random.nextGaussian() - r)
            values[i * 2] = l * 0.4; values[i * 2 + 1] = r * 0.4
        }
        return { frame, channel -> values[(frame * 2 + channel) % values.size] }
    }

    @Test fun concertVenuesKeepTheLevelAndIgnoreBlockSizes() {
        ConcertVenue.ALL.indices.forEach { id ->
            val source = warmNoise(20L + id)
            val whole = runSized(kernel(concert(id)), rate * 3, intArrayOf(1024), source)
            val ragged = runSized(kernel(concert(id)), rate * 3, intArrayOf(1, 77, 1024, 333), source)
            assertTrue("venue $id", whole.contentEquals(ragged))
            val dry = DoubleArray(rate * 2) { source(rate * 2 + it / 2, it % 2) }
            val ratio = rms(whole, rate * 2 * 2) / rms(dry)
            println("venue $id out/in rms ${"%.2f".format(ratio)}")
            assertTrue("venue $id ratio $ratio", whole.all { it.isFinite() } && ratio in 0.7..1.4)
        }
    }

    @Test fun everyGenreRigKeepsTheLevel() {
        ConcertSound.ALL.indices.forEach { id ->
            val source = warmNoise(40L + id)
            val out = run(kernel(ReverbPrefs(enabled = true, concert = ConcertPrefs(enabled = true, sound = id))), rate * 3, source)
            val dry = DoubleArray(rate * 2) { source(rate + it / 2, it % 2) }
            val ratio = rms(out, rate * 2) / rms(dry)
            println("sound $id out/in rms ${"%.2f".format(ratio)}")
            assertTrue("sound $id ratio $ratio", out.all { it.isFinite() } && ratio in 0.7..1.4)
        }
    }

    @Test fun everySceneKeepsTheLevelWithACrowdAndIgnoresBlockSizes() {
        ConcertScene.ALL.forEachIndexed { i, scene ->
            val prefs = ReverbPrefs(enabled = true, concert = scene.applyTo(ConcertPrefs(enabled = true)))
            val source = warmNoise(60L + i)
            val whole = runSized(kernel(prefs), rate * 3, intArrayOf(1024), source)
            val ragged = runSized(kernel(prefs), rate * 3, intArrayOf(333, 1, 1024, 77), source)
            assertTrue("scene $i", whole.contentEquals(ragged))
            val dry = DoubleArray(rate * 2) { source(rate + it / 2, it % 2) }
            val ratio = rms(whole, rate * 2) / rms(dry)
            println("scene $i out/in rms ${"%.2f".format(ratio)}, peak ${"%.2f".format(whole.maxOf { abs(it) })}")
            assertTrue("scene $i ratio $ratio", whole.all { it.isFinite() && abs(it) < 1.0 } && ratio in 0.6..1.5)
        }
    }

    @Test fun concertSwitchedOffLeavesStudioReverbAlone() {
        val source = noise(30)
        val plain = run(kernel(ReverbPrefs(enabled = true, concert = null)), rate * 2, source)
        val parked = run(kernel(ReverbPrefs(enabled = true, concert = ConcertPrefs(venue = ConcertVenue.STADIUM, openness = 1f))), rate * 2, source)
        assertTrue(plain.contentEquals(parked))
    }

    @Test fun walkingAroundAndSwitchingModesDoesNotClick() {
        val kernel = kernel(ReverbPrefs(enabled = true, mix = 0.3f))
        val tone = { frame: Int, _: Int -> 0.3 * sin(2 * Math.PI * 440 * frame / rate) }
        var last = run(kernel, rate, tone)
        var worst = 0.0
        var offset = rate
        for (step in 0 until 48) {
            val t = (step % 16) / 15f
            val venue = ConcertPrefs(enabled = step % 16 != 15, venue = step / 16 * 2, sound = step % 12, showDb = 85f + step % 30, crowd = (step % 3) * 0.3f).withSeat(step % 4)
            kernel.settings = kernel.settings.copy(concert = venue.copy(listenerX = t, size = t, openness = 1 - t))
            val start = offset
            val next = run(kernel, 2048) { frame, channel -> tone(start + frame, channel) }
            worst = maxOf(worst, abs(next[0] - last[last.size - 2]))
            for (i in 1 until 2048) worst = maxOf(worst, abs(next[i * 2] - next[i * 2 - 2]))
            last = next; offset += 2048
        }
        println("largest sample step while moving through venues ${"%.4f".format(worst)}")
        assertTrue("step $worst", worst < 0.2)
    }

    @Test fun costsFarLessThanRealtime() {
        for (prefs in listOf(ReverbPrefs(enabled = true), concert(ConcertVenue.ARENA))) {
            val kernel = kernel(prefs)
            val source = noise(6)
            run(kernel, rate * 5, source)
            val started = System.nanoTime()
            run(kernel, rate * 30, source)
            val realtime = (System.nanoTime() - started) / 1e9 / 30
            println("cpu per second of audio ${"%.2f".format(realtime * 100)}% (concert ${prefs.concertPrefs.enabled})")
            assertTrue(realtime < 0.25)
        }
    }
}
