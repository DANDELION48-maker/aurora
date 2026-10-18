package com.aurora.music.desktop.youtube

import com.aurora.music.data.YouTubeAudioOption
import com.aurora.music.data.YouTubeDownloadFormat
import com.aurora.music.data.YouTubeDownloadPrefs
import com.aurora.music.data.YouTubeTrackTags
import com.aurora.music.desktop.audio.decode.FfmpegInput
import com.aurora.music.desktop.audio.decode.HttpOptions
import com.aurora.music.desktop.audio.decode.encodedTone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO

class YouTubeDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    private fun thumbnail(): ByteArray {
        val image = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB).apply {
            for (x in 0 until width) for (y in 0 until height) setRGB(x, y, if (x in 70 until 250) 0x2050c0 else 0x101010)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    }

    private fun assertRemuxed(source: File, format: YouTubeDownloadFormat, codec: String) {
        val target = File(temp.root, "out.${format.extension}")
        val cover = YouTubeDownloader.squareJpeg(thumbnail())!!
        YouTubeDownloader.remux(source, target, format, YouTubeTrackTags("Song", "Artist", "Album", "2024"), cover)
        FfmpegInput(target.absolutePath, HttpOptions(), AtomicBoolean()).use { input ->
            assertEquals(codec, input.info.codec)
            assertTrue("${input.info.durationMs}", input.info.durationMs in 1_900..2_200)
            assertEquals("Song", input.tags.title)
            assertEquals("Artist", input.tags.artist)
            assertEquals("Album", input.tags.album)
            assertTrue(input.cover()!!.contentEquals(cover))
        }
        assertTrue(target.renameTo(File(temp.root, "moved.${format.extension}")))
    }

    @Test fun remuxesAacWithoutReencodingAndTagsTheFile() = assertRemuxed(encodedTone(File(temp.root, "source.mp4"), "mp4", "aac"), YouTubeDownloadFormat.M4A, "aac")

    @Test fun remuxesOpusWithoutReencodingAndTagsTheFile() {
        val source = runCatching { encodedTone(File(temp.root, "source.webm"), "webm", "libopus", "opus") }.getOrNull()
        org.junit.Assume.assumeTrue("no opus encoder in this ffmpeg build", source != null)
        assertRemuxed(source!!, YouTubeDownloadFormat.OPUS, "opus")
    }

    @Test fun coversAreCroppedToTheCentreSquare() {
        val jpeg = YouTubeDownloader.squareJpeg(thumbnail())!!
        val image = Image.makeFromEncoded(jpeg)
        assertEquals(180, image.width)
        assertEquals(180, image.height)
        val pixels = javax.imageio.ImageIO.read(jpeg.inputStream())
        val edge = pixels.getRGB(2, 90) and 0xffffff
        assertTrue("edge was ${Integer.toHexString(edge)}", (edge and 0xff) > 0x90)
    }

    @Test fun downloadsIntoTheMusicFolderWithoutClashing() = runBlocking {
        val source = encodedTone(File(temp.root, "source.mp4"), "mp4", "aac").readBytes()
        val png = thumbnail()
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val url = chain.request().url.toString()
            val body = if (url.contains("thumb")) png else source
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/octet-stream".toMediaType())).build()
        }).build()
        val music = File(temp.root, "Music/Aurora")
        val saved = mutableListOf<File>()
        val info = YoutubeResolver.DownloadInfo(YouTubeTrackTags("Song", "Artist", "Album", "2024"), "https://i.ytimg.com/thumb.jpg",
            listOf(YouTubeAudioOption("https://example.com/audio", YouTubeDownloadFormat.M4A, 128)))
        val downloader = YouTubeDownloader(YoutubeResolver(client), { YouTubeDownloadPrefs(format = YouTubeDownloadFormat.OPUS) }, client,
            File(temp.root, "work"), { music }, onSaved = { saved += it }, info = { info })
        downloader.download("aaaaaaaaaaa")
        val first = withTimeout(20_000) { downloader.states.first { it["aaaaaaaaaaa"] is YouTubeDownloader.State.Done || it["aaaaaaaaaaa"] == YouTubeDownloader.State.Failed } }
        val done = first["aaaaaaaaaaa"] as YouTubeDownloader.State.Done
        assertEquals(File(music, "Artist - Song.m4a"), done.file)
        downloader.download("bbbbbbbbbbb")
        withTimeout(20_000) { downloader.states.first { it["bbbbbbbbbbb"] is YouTubeDownloader.State.Done } }
        assertEquals(setOf("Artist - Song.m4a", "Artist - Song (1).m4a"), music.list()!!.toSet())
        assertEquals(2, saved.size)
        FfmpegInput(done.file.absolutePath, HttpOptions(), AtomicBoolean()).use { assertNotNull(it.cover()) }
        assertTrue(File(temp.root, "work").listFiles().orEmpty().isEmpty())
    }
}
