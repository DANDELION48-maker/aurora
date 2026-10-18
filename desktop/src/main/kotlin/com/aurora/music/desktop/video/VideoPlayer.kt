package com.aurora.music.desktop.video

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.aurora.music.desktop.audio.decode.HttpOptions
import com.aurora.music.desktop.audio.decode.Interrupts
import com.aurora.music.desktop.audio.decode.ffmpegUrl
import com.aurora.music.desktop.audio.decode.httpOptions
import com.aurora.music.desktop.audio.decode.isHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_RGBA
import org.bytedeco.ffmpeg.global.avutil.av_dict_free
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getCachedContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.DoublePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.atomic.AtomicBoolean

/** A muted video drawn into compose: frames decode on their own thread and show when the clock reaches them. */
class VideoPlayer(private val url: String, private val http: HttpOptions = HttpOptions(), private val loop: Boolean = false,
    private val maxWidth: Int = 960) : AutoCloseable {
    private val mutableFrame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = mutableFrame.asStateFlow()
    private val mutableReady = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = mutableReady.asStateFlow()
    private val mutableFailed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = mutableFailed.asStateFlow()
    @Volatile var width = 0
        private set
    @Volatile var height = 0
        private set
    private val interrupt = AtomicBoolean()
    private val lock = Object()
    @Volatile private var playing = false
    @Volatile private var speed = 1.0
    @Volatile private var anchorMedia = 0L
    @Volatile private var anchorWall = System.nanoTime()
    @Volatile private var pendingSeek: Long? = null
    @Volatile private var closed = false
    @Volatile private var preview = true
    private val thread = Thread(::run, "aurora-video").apply { isDaemon = true; start() }

    val positionMs: Long get() = if (playing) anchorMedia + ((System.nanoTime() - anchorWall) / 1_000_000.0 * speed).toLong() else anchorMedia

    fun play() = synchronized(lock) { if (!playing) { anchorMedia = positionMs; anchorWall = System.nanoTime(); playing = true }; lock.notifyAll() }
    fun pause() = synchronized(lock) { anchorMedia = positionMs; anchorWall = System.nanoTime(); playing = false; lock.notifyAll() }
    fun setSpeed(value: Float) = synchronized(lock) { anchorMedia = positionMs; anchorWall = System.nanoTime(); speed = value.toDouble().coerceIn(0.25, 4.0) }
    fun seek(ms: Long) = synchronized(lock) { anchorMedia = ms.coerceAtLeast(0); anchorWall = System.nanoTime(); pendingSeek = ms.coerceAtLeast(0); preview = true; lock.notifyAll() }

    private fun run() {
        val format: AVFormatContext = avformat_alloc_context() ?: return fail()
        var opened = false
        var codec: AVCodecContext? = null
        var sws: SwsContext? = null
        val packet = av_packet_alloc()
        val frame = av_frame_alloc()
        Interrupts.current.set(interrupt)
        try {
            format.interrupt_callback().callback(Interrupts)
            val options = AVDictionary(null as Pointer?)
            if (isHttp(url)) httpOptions(http.copy(rangedRequests = true)).forEach { (key, value) -> av_dict_set(options, key, value, 0) }
            val target = BytePointer(ffmpegUrl(url), Charsets.UTF_8)
            val result = try { avformat_open_input(format, target, null, options) } finally { av_dict_free(options); target.close() }
            if (result < 0) return fail()
            opened = true
            if (avformat_find_stream_info(format, null as PointerPointer<*>?) < 0) return fail()
            val index = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, null as PointerPointer<*>?, 0)
            if (index < 0) return fail()
            val stream = format.streams(index)
            val decoder = avcodec_find_decoder(stream.codecpar().codec_id()) ?: return fail()
            codec = avcodec_alloc_context3(decoder)
            if (avcodec_parameters_to_context(codec, stream.codecpar()) < 0 || avcodec_open2(codec, decoder, null as AVDictionary?) < 0) return fail()
            val base = stream.time_base()
            val tick = base.num().toDouble() / base.den() * 1000.0
            val srcW = codec.width(); val srcH = codec.height()
            if (srcW <= 0 || srcH <= 0) return fail()
            val dstW = minOf(srcW, maxWidth) / 2 * 2
            val dstH = (srcH.toLong() * dstW / srcW).toInt() / 2 * 2
            width = dstW; height = dstH
            val pixels = BytePointer(dstW.toLong() * dstH * 4)
            val planes = PointerPointer<BytePointer>(1).put(pixels)
            val strides = IntPointer(1).put(dstW * 4)
            val bytes = ByteArray(dstW * dstH * 4)
            val info = ImageInfo(dstW, dstH, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
            var seekTarget = 0L
            while (!closed) {
                pendingSeek?.let { ms ->
                    pendingSeek = null
                    av_seek_frame(format, index, (ms / tick).toLong(), AVSEEK_FLAG_BACKWARD)
                    avcodec_flush_buffers(codec)
                    seekTarget = ms
                }
                synchronized(lock) { while (!playing && !preview && pendingSeek == null && !closed) lock.wait(250) }
                if (closed) break
                val read = av_read_frame(format, packet)
                if (read < 0) {
                    if (loop) { seek(0); continue }
                    synchronized(lock) { lock.wait(250) }
                    continue
                }
                if (packet.stream_index() != index) { av_packet_unref(packet); continue }
                avcodec_send_packet(codec, packet)
                av_packet_unref(packet)
                while (avcodec_receive_frame(codec, frame) >= 0) {
                    val pts = frame.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE } ?: frame.pts()
                    val ms = if (pts == AV_NOPTS_VALUE) positionMs else (pts * tick).toLong()
                    val length = (frame.duration() * tick).toLong().takeIf { it > 0 } ?: 33L
                    // after a seek, frames that end before the target are decoded but never shown
                    if (ms + length <= seekTarget) continue
                    // a frame more than a few frames late is skipped so a slow decoder catches up instead of lagging
                    val wait = ((ms - positionMs) / speed).toLong()
                    if (!preview && wait < -120 && playing) continue
                    if (wait > 0 && !preview) synchronized(lock) { if (pendingSeek == null) lock.wait(minOf(wait, 500)) }
                    if (pendingSeek != null) break
                    sws = sws_getCachedContext(sws, srcW, srcH, frame.format(), dstW, dstH, AV_PIX_FMT_RGBA, SWS_BILINEAR, null, null, null as DoublePointer?)
                    sws_scale(sws, frame.data(), frame.linesize(), 0, srcH, planes, strides)
                    pixels.get(bytes)
                    mutableFrame.value = Image.makeRaster(info, bytes, dstW * 4).toComposeImageBitmap()
                    mutableReady.value = true
                    preview = false
                    seekTarget = 0L
                }
            }
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            fail()
        } finally {
            Interrupts.current.set(null)
            sws?.let { sws_freeContext(it) }
            av_frame_free(frame)
            av_packet_free(packet)
            codec?.let { avcodec_free_context(it) }
            if (opened) avformat_close_input(format)
        }
    }

    private fun fail() { mutableFailed.value = true }

    override fun close() {
        closed = true
        interrupt.set(true)
        synchronized(lock) { lock.notifyAll() }
    }
}
