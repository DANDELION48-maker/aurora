package com.aurora.music.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

/** Joins an unencrypted fragmented-mp4 hls stream into one file that seeks like a normal download. */
internal object HlsAudioDownload {
    fun isPlaylist(url: String) = runCatching { url.toHttpUrl().encodedPath.endsWith(".m3u8") }.getOrDefault(false)

    fun download(http: OkHttpClient, url: String, file: File, temp: File, onProgress: (Float) -> Unit) {
        var base = url.toHttpUrl()
        var playlist = text(http, base.toString())
        // a multi-quality playlist points at the real one
        if ("#EXT-X-STREAM-INF" in playlist) {
            val variant = playlist.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: throw IOException("Empty stream list")
            base = base.resolve(variant) ?: throw IOException("Bad stream link")
            playlist = text(http, base.toString())
        }
        if (Regex("#EXT-X-KEY:.*METHOD=(?!NONE)").containsMatchIn(playlist)) throw IOException("This stream is protected and can't be saved")
        val init = Regex("""#EXT-X-MAP:.*URI="([^"]+)"""").find(playlist)?.groupValues?.get(1)?.let { base.resolve(it) }
            ?: throw IOException("Unsupported stream format")
        val segments = playlist.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { base.resolve(it) ?: throw IOException("Bad segment link") }
        if (segments.isEmpty()) throw IOException("Empty stream")
        val index = mutableListOf<Sidx>()
        temp.outputStream().buffered().use { media ->
            segments.forEachIndexed { i, segment ->
                strip(bytes(http, segment.toString()), media, index)
                onProgress((i + 1f) / segments.size)
            }
        }
        file.outputStream().buffered().use { out ->
            out.write(bytes(http, init.toString()))
            if (index.isNotEmpty()) writeSidx(index, temp.length(), out)
            temp.inputStream().use { it.copyTo(out) }
        }
        temp.delete()
    }

    class Sidx(val referenceId: Int, val timescale: Long, val earliest: Long, val references: List<LongArray>)

    // each segment's styp and sidx go; its own index is collected so one index can cover the whole file
    fun strip(segment: ByteArray, media: OutputStream, index: MutableList<Sidx>) {
        val buffer = ByteBuffer.wrap(segment)
        while (buffer.remaining() >= 8) {
            val start = buffer.position()
            var size = buffer.int.toLong() and 0xFFFFFFFFL
            val type = String(ByteArray(4).also { buffer.get(it) }, Charsets.US_ASCII)
            if (size == 1L) size = buffer.long else if (size == 0L) size = (segment.size - start).toLong()
            if (size < 8 || start + size > segment.size) throw IOException("Damaged stream segment")
            when (type) {
                "styp" -> Unit
                "sidx" -> index += parseSidx(ByteBuffer.wrap(segment, buffer.position(), (start + size - buffer.position()).toInt()))
                else -> media.write(segment, start, size.toInt())
            }
            buffer.position((start + size).toInt())
        }
    }

    private fun parseSidx(box: ByteBuffer): Sidx {
        val version = box.get().toInt()
        box.position(box.position() + 3)
        val referenceId = box.int
        val timescale = box.int.toLong() and 0xFFFFFFFFL
        val earliest = if (version == 0) box.int.toLong() and 0xFFFFFFFFL else box.long
        val firstOffset = if (version == 0) box.int.toLong() and 0xFFFFFFFFL else box.long
        if (firstOffset != 0L) throw IOException("Unsupported stream index")
        box.short
        val count = box.short.toInt() and 0xFFFF
        return Sidx(referenceId, timescale, earliest, List(count) {
            longArrayOf(box.int.toLong() and 0xFFFFFFFFL, box.int.toLong() and 0xFFFFFFFFL, box.int.toLong() and 0xFFFFFFFFL)
        })
    }

    // the references must cover the media exactly, or seeking would land mid-fragment
    fun writeSidx(index: List<Sidx>, mediaBytes: Long, out: OutputStream) {
        val references = index.flatMap { it.references }
        val first = index.first()
        if (references.size > 0xFFFF || index.any { it.timescale != first.timescale } ||
            references.sumOf { it[0] and 0x7FFFFFFFL } != mediaBytes) throw IOException("Stream index does not match its media")
        val body = ByteArrayOutputStream()
        DataOutputStream(body).apply {
            writeInt(1 shl 24)
            writeInt(first.referenceId)
            writeInt(first.timescale.toInt())
            writeLong(first.earliest)
            writeLong(0)
            writeShort(0)
            writeShort(references.size)
            references.forEach { writeInt(it[0].toInt()); writeInt(it[1].toInt()); writeInt(it[2].toInt()) }
        }
        DataOutputStream(out).apply { writeInt(8 + body.size()); write("sidx".toByteArray(Charsets.US_ASCII)) }
        body.writeTo(out)
    }

    private fun text(http: OkHttpClient, url: String) = String(bytes(http, url), Charsets.UTF_8)

    private fun bytes(http: OkHttpClient, url: String): ByteArray = http.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
        it.body?.bytes() ?: throw IOException("empty body")
    }
}
