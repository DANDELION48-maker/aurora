package com.aurora.music.mix

import com.aurora.music.data.SonicEngine
import com.aurora.music.data.SonicFeatures
import com.aurora.music.data.SonicStore
import com.aurora.music.desktop.audio.ResolvedStream
import com.aurora.music.desktop.audio.decode.wavBytes
import com.aurora.music.desktop.platform.desktopFileUri
import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MixAnalyzerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun song(id: String, file: File) = Song(id, "Song $id", "Artist", "Album", "", 40, streamUrl = desktopFileUri(file.path))

    // forty seconds of a chord pulsing at its own tempo, so each track has a key, a beat and a sound of its own
    private fun track(name: String, bpm: Double, chord: List<Double>): File {
        val rate = 22050
        val beat = rate * 60.0 / bpm
        val random = java.util.Random(name.hashCode().toLong())
        return temp.newFile("$name.wav").apply {
            writeBytes(wavBytes(rate, 1, 16, rate * 40) { frame, _ ->
                val t = frame.toDouble() / rate
                val pulse = kotlin.math.exp(-(frame % beat) / (rate * 0.08))
                val tone = chord.sumOf { f -> kotlin.math.sin(2 * Math.PI * f * t) + 0.3 * kotlin.math.sin(4 * Math.PI * f * t) } / chord.size
                ((tone * (0.35 + 0.65 * pulse) + random.nextGaussian() * 0.01) * 12000).toInt()
            })
        }
    }

    @Test fun analyzesAndCachesRealAudio() = runBlocking {
        val store = SonicStore(temp.root)
        var resolved = 0
        val analyzer = MixAnalyzer(temp.root, { song -> resolved++; ResolvedStream(song.streamUrl) }) { account, id, vector -> store.put(id, vector, account) }
        store.selectAccount("acct")
        val flac = song("flac", track("pulse", 120.0, listOf(261.63, 329.63, 392.0)))
        val progress = mutableListOf<Float>()
        val result = analyzer.analyze("acct", flac) { progress += it }
        assertTrue(result.seconds > 1f)
        assertEquals(SonicFeatures.DIMS, result.sonic.size)
        assertTrue(result.peaks.isNotEmpty())
        assertTrue(result.audibleEnd > result.audibleStart)
        assertEquals(1f, progress.last())
        assertTrue(store.has("flac", "acct"))

        val again = analyzer.analyze("acct", flac) {}
        assertEquals(result.peaks, again.peaks)
        assertEquals(1, resolved)
        assertNotNull(analyzer.cached("acct", flac))

        val reopened = SonicStore(temp.root)
        reopened.selectAccount("acct")
        assertTrue(reopened.has("flac", "acct"))
        assertEquals(SonicFeatures.DIMS, reopened.get("flac", "acct")!!.size)
    }

    @Test fun radioAndKeysComeFromAnalyzedTracks() = runBlocking {
        val store = SonicStore(temp.root)
        store.selectAccount("acct")
        val analyzer = MixAnalyzer(temp.root, { ResolvedStream(it.streamUrl) }) { account, id, vector -> store.put(id, vector, account) }
        val library = listOf(song("a", track("a", 120.0, listOf(261.63, 329.63, 392.0))), song("b", track("b", 124.0, listOf(261.63, 329.63, 392.0))),
            song("c", track("c", 90.0, listOf(233.08, 277.18, 349.23))))
        library.forEach { analyzer.analyze("acct", it) {} }
        val engine = SonicEngine(store, { library }, { "acct" }, analyzer)
        assertNotNull(engine.keyInfo("a"))
        val radio = engine.buildRadio(library.first())
        assertEquals("a", radio.first().id)
        assertTrue(radio.size >= 2)
    }
}
