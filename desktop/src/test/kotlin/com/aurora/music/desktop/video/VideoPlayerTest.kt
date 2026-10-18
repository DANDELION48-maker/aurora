package com.aurora.music.desktop.video

import androidx.compose.ui.graphics.toAwtImage
import com.aurora.music.desktop.platform.desktopFileUri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

class VideoPlayerTest {
    @get:Rule val temp = TemporaryFolder()
    private val colors = listOf(0xff0000, 0x00ff00, 0x0000ff, 0xffff00, 0xff00ff, 0x00ffff, 0xffffff, 0x808080, 0x800000, 0x008000)

    // ten 100 ms frames of solid colour
    private fun gif(): File {
        val file = temp.newFile("clip.gif")
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(file).use { out ->
            writer.output = out
            writer.prepareWriteSequence(null)
            colors.forEach { color ->
                val image = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB).apply { for (x in 0 until 64) for (y in 0 until 48) setRGB(x, y, color) }
                val metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), null)
                val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
                val control = (0 until root.length).map { root.item(it) as IIOMetadataNode }.firstOrNull { it.nodeName == "GraphicControlExtension" }
                    ?: IIOMetadataNode("GraphicControlExtension").also { root.appendChild(it) }
                control.setAttribute("disposalMethod", "none"); control.setAttribute("userInputFlag", "FALSE")
                control.setAttribute("transparentColorFlag", "FALSE"); control.setAttribute("delayTime", "10"); control.setAttribute("transparentColorIndex", "0")
                metadata.setFromTree(metadata.nativeMetadataFormatName, root)
                writer.writeToSequence(IIOImage(image, null, metadata), null)
            }
            writer.endWriteSequence()
        }
        return file
    }

    private fun await(timeout: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeout
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    private fun VideoPlayer.color(): Int = frame.value!!.toAwtImage().getRGB(32, 24) and 0xffffff

    @Test fun decodesPacesAndSeeksFrames() {
        VideoPlayer(desktopFileUri(gif().path)).use { player ->
            assertTrue("ready", await { player.ready.value })
            assertFalse(player.failed.value)
            assertEquals(64, player.width)
            assertEquals(48, player.height)
            assertEquals("first %06x".format(player.color()), 0xff0000, player.color())
            player.play()
            val seen = mutableSetOf<Int>()
            await(3_000) { seen += player.color(); seen.size >= 4 }
            assertTrue("seen $seen", seen.size >= 4)
            player.pause()
            player.seek(650)
            assertTrue("after seek %06x".format(player.color()), await { player.color() == colors[6] })
        }
    }

    @Test fun reportsSourcesWithoutVideo() {
        val wav = temp.newFile("silence.wav")
        wav.writeBytes(com.aurora.music.desktop.audio.decode.wavBytes(8_000, 1, 16, 8_000) { _, _ -> 0 })
        VideoPlayer(desktopFileUri(wav.path)).use { player ->
            assertTrue(await { player.failed.value })
        }
    }
}
