package com.aurora.music.data

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer

class HlsAudioDownloadTest {
    @get:Rule val folder = TemporaryFolder()

    private fun box(type: String, body: ByteArray) = ByteArrayOutputStream().apply {
        DataOutputStream(this).apply { writeInt(8 + body.size); write(type.toByteArray()) }
        write(body)
    }.toByteArray()

    private fun sidx(earliest: Int, size: Int, duration: Int) = box("sidx", ByteArrayOutputStream().apply {
        DataOutputStream(this).apply {
            writeInt(0); writeInt(1); writeInt(44100); writeInt(earliest); writeInt(0)
            writeShort(0); writeShort(1); writeInt(size); writeInt(duration); writeInt(0x90000000.toInt())
        }
    }.toByteArray())

    // a segment as soundcloud serves it: styp, its own index, then one fragment
    private fun segment(fill: Byte, earliest: Int): Pair<ByteArray, ByteArray> {
        val media = box("moof", ByteArray(12) { fill }) + box("mdat", ByteArray(30) { fill })
        return (box("styp", ByteArray(4)) + sidx(earliest, media.size, 441000) + media) to media
    }

    private fun serve(files: Map<String, ByteArray>) = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val body = files[request.url.encodedPath] ?: error(request.url.toString())
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
    }.build()

    @Test fun segmentsJoinBehindOneIndexThatCoversTheWholeFile() {
        val (first, firstMedia) = segment(1, 0)
        val (second, secondMedia) = segment(2, 441000)
        val init = box("ftyp", ByteArray(8)) + box("moov", ByteArray(16))
        val playlist = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4?sig=1\"\n#EXTINF:10,\ndata000.m4s?sig=1\n#EXTINF:10,\ndata001.m4s?sig=1\n#EXT-X-ENDLIST\n"
        val http = serve(mapOf("/a/playlist.m3u8" to playlist.toByteArray(), "/a/init.mp4" to init, "/a/data000.m4s" to first, "/a/data001.m4s" to second))
        val out = folder.newFile("song.audio")
        val progress = mutableListOf<Float>()
        HlsAudioDownload.download(http, "https://cdn.example/a/playlist.m3u8?sig=1", out, folder.root.resolve("song.part"), progress::add)

        val bytes = out.readBytes()
        assertArrayEquals(init, bytes.copyOfRange(0, init.size))
        val index = ByteBuffer.wrap(bytes, init.size, bytes.size - init.size)
        val indexSize = index.int
        assertEquals("sidx", String(ByteArray(4).also { index.get(it) }))
        assertEquals(1, index.get().toInt())
        index.position(index.position() + 3 + 4)
        assertEquals(44100, index.int)
        assertEquals(0L, index.long)
        assertEquals(0L, index.long)
        index.short
        assertEquals(2, index.short.toInt())
        assertEquals(firstMedia.size, index.int); assertEquals(441000, index.int); index.int
        assertEquals(secondMedia.size, index.int)
        assertArrayEquals(firstMedia + secondMedia, bytes.copyOfRange(init.size + indexSize, bytes.size))
        assertEquals(listOf(0.5f, 1f), progress)
        assertFalse(folder.root.resolve("song.part").exists())
    }

    @Test fun protectedStreamsAreRefused() {
        val playlist = "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\"\n#EXT-X-MAP:URI=\"init.mp4\"\ndata000.m4s\n"
        val http = serve(mapOf("/a/playlist.m3u8" to playlist.toByteArray()))
        assertThrows(IOException::class.java) {
            HlsAudioDownload.download(http, "https://cdn.example/a/playlist.m3u8", folder.newFile("x"), folder.root.resolve("x.part")) {}
        }
    }

    @Test fun anIndexThatMissesMediaIsRejected() {
        val (segment, _) = segment(1, 0)
        val media = ByteArrayOutputStream()
        val index = mutableListOf<HlsAudioDownload.Sidx>()
        HlsAudioDownload.strip(segment + box("emsg", ByteArray(4)), media, index)
        assertThrows(IOException::class.java) { HlsAudioDownload.writeSidx(index, media.size().toLong(), ByteArrayOutputStream()) }
    }
}
