package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class DiscordRpcTest {
    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until width) for (y in 0 until height) image.setRGB(x, y, if (x < width / 2) 0xff2e7e else 0x2e7eff)
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    @Test fun artworkIsCroppedToASquareJpeg() = runBlocking {
        val jpeg = loadDiscordArtwork({ png(1280, 720) }, "https://example.com/wide.png")
        assertNotNull(jpeg)
        val decoded = ImageIO.read(jpeg!!.inputStream())
        assertEquals(512, decoded.width)
        assertEquals(512, decoded.height)
        assertEquals(0xff, (decoded.getRGB(5, 256) shr 16) and 0xff)
        assertEquals(0xff, decoded.getRGB(506, 256) and 0xff)
    }

    @Test fun unreadableArtworkIsSkipped() = runBlocking {
        assertNull(loadDiscordArtwork({ byteArrayOf(1, 2, 3) }, "x"))
        assertNull(loadDiscordArtwork({ null }, "x"))
    }

    @Test fun activityShowsTheTrackAndItsTimeline() {
        val song = Song("s", "Midnight Drive", "Aurora", "Afterglow", "", 238)
        val activity = discordActivity(song, showAlbum = true, activityName = "aurora", positionSec = 102f, now = 1_000_000L)
        assertEquals("Aurora", activity["name"].asString)
        assertEquals("Midnight Drive", activity["details"].asString)
        assertEquals("Aurora · Afterglow", activity["state"].asString)
        assertEquals(1_000_000L - 102_000L, activity["timestamps"].asJsonObject["start"].asLong)
        assertEquals(1_000_000L - 102_000L + 238_000L, activity["timestamps"].asJsonObject["end"].asLong)
        val byArtist = discordActivity(song, showAlbum = false, activityName = "artist", positionSec = 0f)
        assertEquals("Aurora", byArtist["name"].asString)
        assertEquals("Midnight Drive", byArtist["details"].asString)
    }
}
