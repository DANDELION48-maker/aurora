package com.aurora.music.desktop.audio.mix

import com.aurora.music.desktop.audio.FakeBackend
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.wavBytes
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.mix.MixClip
import com.aurora.music.mix.MixController
import com.aurora.music.mix.MixProject
import com.aurora.music.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DesktopMixPlayerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun song(id: String, hz: Double, seconds: Int, rate: Int = 44_100): Song {
        val file = temp.newFile("$id.wav")
        file.writeBytes(wavBytes(rate, 2, 16, rate * seconds) { frame, _ -> (sin(2 * PI * hz * frame / rate) * 16_000).toInt() })
        return Song(id, "Track $id", "Aurora", "", "", seconds, streamUrl = desktopFileUri(file.path))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    @Test fun playsATimelineOfOverlappingClipsToTheEnd() {
        val a = song("a", 220.0, 6)
        val b = song("b", 330.0, 6, rate = 48_000)
        val project = MixProject(name = "Test", clips = listOf(
            MixClip(song = a, startSec = 0f, fadeOutSec = 2f),
            MixClip(song = b, startSec = 4f, fadeInSec = 2f, fadeOutSec = 0f, speed = 1.1f, pitchSemitones = 2f),
        ))
        val bus = MixController()
        val backend = FakeBackend()
        val player = DesktopMixPlayer(project, bus, { ResolvedStream(it.streamUrl) }, backend, null, 48_000)
        player.play()
        await { !bus.state.value.playing && bus.state.value.positionSec >= project.durationSec - 0.01f }
        val state = bus.state.value
        assertNull(state.error)
        assertEquals("state $state", project.durationSec, state.positionSec, 0.05f)
        val samples = backend.last.heardFloats()
        val frames = samples.size / 2
        assertTrue("frames $frames", frames > (project.durationSec * 48_000 * 0.95).toInt())
        assertTrue(samples.all { it.isFinite() && abs(it) <= 1f })
        val early = samples.copyOfRange(48_000 * 2, 48_000 * 2 + 4800)
        val late = samples.copyOfRange((frames - 48_000) * 2, (frames - 48_000) * 2 + 4800)
        assertTrue("early ${early.maxOf { abs(it) }}", early.maxOf { abs(it) } > 0.2f)
        assertTrue("late ${late.maxOf { abs(it) }}", late.maxOf { abs(it) } > 0.2f)
        player.close()
        assertFalse(bus.state.value.playing)
    }

    @Test fun stretchingKeepsTempoAndPitchSeparate() {
        val rate = 48_000
        val stretcher = TimeStretcher(rate)
        stretcher.tempo = 2.0
        val input = DoubleArray(rate * 2 * 2) { i -> sin(2 * PI * 440 * (i / 2) / rate) * 0.5 }
        stretcher.push(input, rate * 2)
        val out = DoubleArray(rate * 4)
        var made = 0
        while (true) { val step = stretcher.step(out, made); if (step == 0) break; made += step }
        assertTrue("made $made", made in (rate * 0.9).toInt()..(rate * 1.05).toInt())
        var crossings = 0
        for (i in 1 until made) if (out[(i - 1) * 2] < 0 && out[i * 2] >= 0) crossings++
        assertEquals(440.0, crossings.toDouble() * rate / made, 15.0)
    }
}
