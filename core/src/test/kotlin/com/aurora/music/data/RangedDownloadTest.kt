package com.aurora.music.data

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RangedDownloadTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun youtubeStreamsArriveInRangesAndJoinBackTogether() {
        val audio = ByteArray(2_600_000) { (it % 251).toByte() }
        val ranges = mutableListOf<String>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val range = request.header("Range")!!.removePrefix("bytes=")
            ranges += range
            val start = range.substringBefore('-').toInt()
            val end = minOf(range.substringAfter('-').toInt(), audio.size - 1)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(206).message("Partial")
                .header("Content-Range", "bytes $start-$end/${audio.size}")
                .body(audio.copyOfRange(start, end + 1).toResponseBody()).build()
        }.build()
        val file = folder.newFile("track.audio")
        val progress = mutableListOf<Float>()
        RangedDownload.fetch(http, "https://rr1---sn-x.googlevideo.com/videoplayback?id=1", file, progress::add)
        assertArrayEquals(audio, file.readBytes())
        assertEquals(listOf("0-1048575", "1048576-2097151", "2097152-3145727"), ranges)
        assertEquals(1f, progress.last())
        assertTrue(RangedDownload.isThrottled("https://rr1---sn-x.googlevideo.com/videoplayback"))
        assertFalse(RangedDownload.isThrottled("https://navidrome.example/rest/stream"))
    }
}
