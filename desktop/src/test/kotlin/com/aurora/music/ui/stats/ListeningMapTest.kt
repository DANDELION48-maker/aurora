package com.aurora.music.ui.stats

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.DetailedPlay
import com.aurora.music.data.PlayContext
import com.aurora.music.data.PlayedTrack
import com.aurora.music.ui.components.MapTiles
import com.aurora.music.ui.screens.stats.ListeningMapScreen
import com.aurora.music.ui.settings.general.GeneralSettingsScene
import com.aurora.music.ui.testing.distinctColors
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

class ListeningMapTest {
    @get:Rule val temp = TemporaryFolder()

    private val latitude = 48.8584
    private val longitude = 2.2945

    // tiles come from disk and anything that reaches for the network is refused
    private fun tiles(): MapTiles {
        val dir = temp.newFolder("tiles")
        val tile = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB).apply {
            for (x in 0 until 256) for (y in 0 until 256) setRGB(x, y, if ((x / 32 + y / 32) % 2 == 0) 0xe8e4d8 else 0xc9dff2)
        }
        val z = 16
        val n = 2.0.pow(z)
        val cx = floor((longitude + 180) / 360 * n).toInt()
        val r = Math.toRadians(latitude)
        val cy = floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * n).toInt()
        for (x in cx - 4..cx + 4) for (y in cy - 4..cy + 4) File(dir, "$z/$x/$y.png").apply { parentFile.mkdirs(); ImageIO.write(tile, "png", this) }
        val refusing = OkHttpClient.Builder().addInterceptor { throw IOException("offline") }.build()
        return MapTiles(dir, refusing)
    }

    private fun play(id: String, at: Long, lat: Double, lon: Double) = DetailedPlay(id, at, at + 120_000, 120_000,
        PlayedTrack("song-$id", "Track $id", "Aurora", "", "", "", 200), PlayContext(latitude = lat, longitude = lon, locationAccuracyM = 20f))

    @Test fun showsLocatedPlaysOnTheMap() {
        val now = System.currentTimeMillis()
        val tiles = tiles()
        GeneralSettingsScene("listening-map", height = 900, seed = {
            listeningDetails.save(play("one", now - 60_000, latitude, longitude))
            listeningDetails.save(play("two", now - 30_000, latitude + 0.0021, longitude + 0.0032))
            listeningDetails.summary()
        }) {
            ListeningMapScreen(PaddingValues(), onBack = {}, tiles = tiles)
        }.use { scene ->
            Thread.sleep(500)
            assertTrue(scene.shot().distinctColors() > 20)
        }
    }
}
