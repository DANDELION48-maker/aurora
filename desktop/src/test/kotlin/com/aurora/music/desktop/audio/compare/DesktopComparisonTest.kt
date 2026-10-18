package com.aurora.music.desktop.audio.compare

import com.aurora.music.data.AudioPrefs
import com.aurora.music.data.ProcessingPlaybackPrefs
import com.aurora.music.data.ProcessingPreset
import com.aurora.music.data.ProcessingRack
import com.aurora.music.data.ProcessingRackNode
import com.aurora.music.data.RackNodeKind
import com.aurora.music.desktop.audio.FakeBackend
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.wavBytes
import com.aurora.music.desktop.natives.OutputEncoding
import com.aurora.music.desktop.platform.desktopFileUri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DesktopComparisonTest {
    @get:Rule val temp = TemporaryFolder()

    private fun preset(name: String, kind: RackNodeKind, node: AudioPrefs = AudioPrefs()) = ProcessingPreset(java.util.UUID.randomUUID().toString(), name,
        createdAtMs = 1, audio = AudioPrefs(), playback = ProcessingPlaybackPrefs(),
        rack = ProcessingRack(enabled = true, nodes = listOf(ProcessingRackNode(java.util.UUID.randomUUID().toString(), name, kind, audio = node))))

    private fun source(): ResolvedStream {
        val rate = 44_100
        val file = temp.newFile("selection.wav")
        file.writeBytes(wavBytes(rate, 2, 16, rate * 20) { frame, channel ->
            val t = frame.toDouble() / rate
            ((sin(2 * PI * 220 * t) * 0.5 + sin(2 * PI * 330 * t + channel) * 0.3) * 20_000).toInt()
        })
        return ResolvedStream(desktopFileUri(file.path))
    }

    @Test fun rendersBothPresetsAtTheOutputRateAndLevelMatchesThem() = runBlocking {
        val audio = ComparisonRenderer.prepare(source(), 3, preset("plain", RackNodeKind.GAIN), preset("warm", RackNodeKind.SATURATION, AudioPrefs(dspSaturation = 0.8f)), 48_000)
        assertEquals(48_000, audio.rate)
        assertTrue("frames ${audio.frames}", audio.frames in 48_000 * 14..48_000 * 16)
        assertTrue("residual ${audio.levels}", abs(audio.levels.residualDb) < 0.2)
        assertFalse("identical renders", audio.a.contentEquals(audio.b))
    }

    @Test fun playsTheSelectionAndSwitchesBetweenRenders() = runBlocking {
        val audio = ComparisonRenderer.prepare(source(), 0, preset("plain", RackNodeKind.GAIN), preset("warm", RackNodeKind.SATURATION, AudioPrefs(dspSaturation = 0.8f)), 48_000)
        val backend = FakeBackend()
        var invalidated: String? = null
        val playback = ComparisonPlayback(backend, null, audio) { invalidated = it }
        playback.start()
        playback.awaitReady()
        playback.select(false)
        Thread.sleep(200)
        val closed = java.util.concurrent.CountDownLatch(1)
        playback.whenClosed { closed.countDown() }
        playback.close()
        assertTrue(closed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, backend.opened.size)
        assertEquals(48_000, backend.last.sampleRate)
        assertEquals(OutputEncoding.F32, backend.last.encoding)
        assertFalse(backend.last.exclusive)
        assertEquals(null, invalidated)
    }
}
