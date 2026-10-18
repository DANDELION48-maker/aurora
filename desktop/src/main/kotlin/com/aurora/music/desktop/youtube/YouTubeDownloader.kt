package com.aurora.music.desktop.youtube

import com.aurora.music.data.RangedDownload
import com.aurora.music.data.YouTubeDownloadFormat
import com.aurora.music.data.YouTubeDownloadPrefs
import com.aurora.music.data.YouTubeDownloads
import com.aurora.music.data.YouTubeTrackTags
import com.aurora.music.desktop.audio.decode.FfmpegInput
import com.aurora.music.desktop.audio.decode.HttpOptions
import com.aurora.music.desktop.audio.decode.errorText
import com.aurora.music.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_MJPEG
import org.bytedeco.ffmpeg.global.avcodec.AV_PKT_FLAG_KEY
import org.bytedeco.ffmpeg.global.avcodec.av_new_packet
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_copy
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.AV_DISPOSITION_ATTACHED_PIC
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.javacpp.Pointer
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Saves YouTube audio as tagged files in a music folder so they join the local library; audio is never re-encoded. */
class YouTubeDownloader(
    private val resolver: YoutubeResolver,
    private val prefs: suspend () -> YouTubeDownloadPrefs,
    private val http: OkHttpClient,
    private val work: File,
    private val destination: suspend () -> File,
    private val onSaved: suspend (File) -> Unit = {},
    private val info: (String) -> YoutubeResolver.DownloadInfo = resolver::downloadInfo,
) {
    sealed interface State {
        data object Queued : State
        data class Downloading(val progress: Float) : State
        data object Saving : State
        data class Done(val file: File) : State
        data object Failed : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(2)
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()
    val busy: Boolean get() = _states.value.values.any { it !is State.Done && it != State.Failed }

    @Synchronized
    fun download(videoId: String) {
        when (_states.value[videoId]) {
            State.Queued, State.Saving, is State.Downloading -> return
            else -> setState(videoId, State.Queued)
        }
        scope.launch { gate.withPermit { run(videoId) } }
    }

    private suspend fun run(id: String) {
        work.mkdirs()
        val source = File(work, "$id.src")
        var target: File? = null
        try {
            val settings = prefs()
            val details = info(id)
            var remaining = details.options
            var option = YouTubeDownloads.pick(remaining, settings) ?: throw IOException("No downloadable audio")
            setState(id, State.Downloading(0f))
            // some stream urls answer 403, so fall back to the next best candidate
            while (true) {
                try {
                    RangedDownload.fetch(http, option.url, source) { setState(id, State.Downloading(it)) }
                    break
                } catch (e: IOException) {
                    remaining = remaining - option
                    option = YouTubeDownloads.pick(remaining, settings) ?: throw e
                }
            }
            setState(id, State.Saving)
            val file = File(work, "$id.${option.format.extension}").also { target = it }
            val cover = if (settings.embedArtwork) runCatching { cover(details.thumbnailUrl) }.getOrNull() else null
            remux(source, file, option.format, details.tags, cover)
            val saved = publish(file, YouTubeDownloads.fileName(details.tags, option.format))
            runCatching { onSaved(saved) }
            setState(id, State.Done(saved))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Download failed: ${e.javaClass.simpleName}: ${e.message}")
            setState(id, State.Failed)
        } finally {
            source.delete()
            target?.delete()
        }
    }

    private suspend fun publish(file: File, name: String): File {
        val directory = destination().apply { mkdirs() }
        check(directory.isDirectory) { "Could not create ${directory.path}" }
        var target = File(directory, name)
        var copy = 1
        while (target.exists()) target = File(directory, "${name.substringBeforeLast('.')} (${copy++}).${name.substringAfterLast('.')}")
        file.copyTo(target)
        return target
    }

    private fun cover(url: String): ByteArray? {
        if (url.isBlank()) return null
        val sized = url.replace(Regex("=w\\d+-h\\d+.*$"), "=w$COVER_SIZE-h$COVER_SIZE")
        val bytes = http.newCall(Request.Builder().url(sized).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes() ?: return null
        }
        return squareJpeg(bytes)
    }

    private fun setState(id: String, state: State) = _states.update { it + (id to state) }

    companion object {
        private const val TAG = "YouTubeDownloader"
        private const val COVER_SIZE = 800

        // youtube thumbnails are often 16:9 with the square cover in the middle
        internal fun squareJpeg(bytes: ByteArray): ByteArray? {
            val image = runCatching { Image.makeFromEncoded(bytes) }.getOrNull() ?: return null
            val side = minOf(image.width, image.height)
            if (side <= 0) return null
            val size = minOf(side, COVER_SIZE)
            val surface = Surface.makeRasterN32Premul(size, size)
            val left = (image.width - side) / 2f
            val top = (image.height - side) / 2f
            surface.canvas.drawImageRect(image, Rect.makeXYWH(left, top, side.toFloat(), side.toFloat()), Rect.makeWH(size.toFloat(), size.toFloat()))
            return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)?.bytes
        }

        // the cover rides along as an mp4 cover atom or an ogg picture comment, so the file is written once
        internal fun remux(source: File, target: File, format: YouTubeDownloadFormat, tags: YouTubeTrackTags, cover: ByteArray? = null) {
            FfmpegInput(source.absolutePath, HttpOptions(), AtomicBoolean()).use { input ->
                val output = AVFormatContext(null as Pointer?)
                val ogg = format == YouTubeDownloadFormat.OPUS
                val muxer = if (ogg) "ogg" else "ipod"
                check(avformat_alloc_output_context2(output, null, muxer, target.absolutePath) >= 0 && !output.isNull) { "No $muxer muxer" }
                val packet = av_packet_alloc()
                try {
                    val stream = avformat_new_stream(output, null) ?: throw IOException("Could not add a stream")
                    check(avcodec_parameters_copy(stream.codecpar(), input.parameters) >= 0) { "Could not copy the audio parameters" }
                    stream.codecpar().codec_tag(0)
                    stream.time_base(input.stream.time_base())
                    val size = cover?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() }?.let { it.width to it.height }
                    val picture = if (cover != null && size != null && !ogg) avformat_new_stream(output, null)?.apply {
                        codecpar().codec_type(AVMEDIA_TYPE_VIDEO)
                        codecpar().codec_id(AV_CODEC_ID_MJPEG)
                        codecpar().width(size.first)
                        codecpar().height(size.second)
                        disposition(AV_DISPOSITION_ATTACHED_PIC)
                        time_base(av_make_q(1, 90_000))
                    } else null
                    val metadata = AVDictionary(null as Pointer?)
                    listOf("title" to tags.title, "artist" to tags.artist, "album" to tags.album.ifBlank { tags.title }, "date" to tags.year)
                        .filter { it.second.isNotBlank() }.forEach { (key, value) -> av_dict_set(metadata, key, value, 0) }
                    if (cover != null && size != null && ogg) av_dict_set(metadata, "METADATA_BLOCK_PICTURE", pictureComment(cover, size.first, size.second), 0)
                    output.metadata(metadata)
                    val io = AVIOContext(null as Pointer?)
                    val opened = avio_open(io, "file:" + target.absolutePath, AVIO_FLAG_WRITE)
                    if (opened < 0) throw IOException("Could not create the file: ${errorText(opened)}")
                    output.pb(io)
                    val header = avformat_write_header(output, null as AVDictionary?)
                    if (header < 0) throw IOException("Could not write the header: ${errorText(header)}")
                    if (picture != null && cover != null) {
                        check(av_new_packet(packet, cover.size) >= 0) { "Could not allocate the cover" }
                        packet.data().put(cover, 0, cover.size)
                        packet.stream_index(picture.index())
                        packet.flags(packet.flags() or AV_PKT_FLAG_KEY)
                        val written = av_interleaved_write_frame(output, packet)
                        if (written < 0) throw IOException("Could not write the cover: ${errorText(written)}")
                        av_packet_unref(packet)
                    }
                    while (true) {
                        val read = av_read_frame(input.format, packet)
                        if (read < 0) {
                            if (input.endOfInput(read)) break
                            throw IOException("Could not read audio: ${errorText(read)}")
                        }
                        if (packet.stream_index() == input.streamIndex) {
                            packet.stream_index(0)
                            av_packet_rescale_ts(packet, input.stream.time_base(), stream.time_base())
                            packet.pos(-1)
                            val written = av_interleaved_write_frame(output, packet)
                            if (written < 0) throw IOException("Could not write audio: ${errorText(written)}")
                        }
                        av_packet_unref(packet)
                    }
                    val trailer = av_write_trailer(output)
                    if (trailer < 0) throw IOException("Could not finish the file: ${errorText(trailer)}")
                } finally {
                    av_packet_free(packet)
                    if (output.pb() != null && !output.pb().isNull) avio_closep(output.pb())
                    avformat_free_context(output)
                }
            }
        }

        // a flac picture block, which is how ogg files carry cover art
        internal fun pictureComment(jpeg: ByteArray, width: Int, height: Int): String {
            val mime = "image/jpeg".toByteArray(Charsets.US_ASCII)
            val block = java.nio.ByteBuffer.allocate(32 + mime.size + jpeg.size)
                .putInt(3).putInt(mime.size).put(mime).putInt(0)
                .putInt(width).putInt(height).putInt(24).putInt(0)
                .putInt(jpeg.size).put(jpeg)
            return java.util.Base64.getEncoder().encodeToString(block.array())
        }
    }
}
