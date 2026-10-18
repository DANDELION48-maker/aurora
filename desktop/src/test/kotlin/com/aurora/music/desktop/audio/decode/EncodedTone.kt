package com.aurora.music.desktop.audio.decode

import kotlin.math.PI
import kotlin.math.sin
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_FLAG_GLOBAL_HEADER
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_rescale_ts
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_encoder_by_name
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_from_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_packet
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_frame
import org.bytedeco.ffmpeg.global.avformat.AVFMT_GLOBALHEADER
import org.bytedeco.ffmpeg.global.avformat.AVIO_FLAG_WRITE
import org.bytedeco.ffmpeg.global.avformat.av_interleaved_write_frame
import org.bytedeco.ffmpeg.global.avformat.av_write_trailer
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_output_context2
import org.bytedeco.ffmpeg.global.avformat.avformat_free_context
import org.bytedeco.ffmpeg.global.avformat.avformat_new_stream
import org.bytedeco.ffmpeg.global.avformat.avformat_write_header
import org.bytedeco.ffmpeg.global.avformat.avio_closep
import org.bytedeco.ffmpeg.global.avformat.avio_open
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLTP
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_copy
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_get_buffer
import org.bytedeco.ffmpeg.global.avutil.av_frame_make_writable
import org.bytedeco.ffmpeg.global.avutil.av_make_q
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.Pointer
import java.io.File

// two seconds of a 440 Hz tone, encoded the way youtube and most stores serve their audio
internal fun encodedTone(file: File, muxer: String, vararg encoders: String): File {
    val codec = encoders.firstNotNullOfOrNull { avcodec_find_encoder_by_name(it) } ?: error("no encoder")
    val rate = 48_000
    val formats = codec.sample_fmts()
    val sampleFormat = generateSequence(0L) { it + 1 }.map { formats.get(it) }.takeWhile { it >= 0 }
        .firstOrNull { it == AV_SAMPLE_FMT_FLTP || it == AV_SAMPLE_FMT_FLT } ?: error("no float input")
    val output = AVFormatContext(null as Pointer?)
    check(avformat_alloc_output_context2(output, null, muxer, file.absolutePath) >= 0)
    val context: AVCodecContext = avcodec_alloc_context3(codec)
    context.sample_rate(rate)
    context.sample_fmt(sampleFormat)
    av_channel_layout_default(context.ch_layout(), 2)
    context.bit_rate(128_000)
    context.time_base(av_make_q(1, rate))
    context.strict_std_compliance(-2)
    if (output.oformat().flags() and AVFMT_GLOBALHEADER != 0) context.flags(context.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
    check(avcodec_open2(context, codec, null as AVDictionary?) >= 0) { "could not open ${codec.name().string}" }
    val stream = avformat_new_stream(output, null)
    avcodec_parameters_from_context(stream.codecpar(), context)
    stream.time_base(context.time_base())
    val io = AVIOContext(null as Pointer?)
    check(avio_open(io, file.absolutePath, AVIO_FLAG_WRITE) >= 0)
    output.pb(io)
    check(avformat_write_header(output, null as AVDictionary?) >= 0)
    val frame: AVFrame = av_frame_alloc()
    frame.nb_samples(context.frame_size().takeIf { it > 0 } ?: 960); frame.format(sampleFormat); frame.sample_rate(rate)
    av_channel_layout_copy(frame.ch_layout(), context.ch_layout())
    check(av_frame_get_buffer(frame, 0) >= 0)
    val packet = av_packet_alloc()
    fun drain() {
        while (avcodec_receive_packet(context, packet) == 0) {
            av_packet_rescale_ts(packet, context.time_base(), stream.time_base())
            packet.stream_index(0)
            av_interleaved_write_frame(output, packet)
        }
    }
    var pts = 0L
    while (pts < 2L * rate) {
        av_frame_make_writable(frame)
        val count = frame.nb_samples()
        val samples = FloatArray(count) { i -> (0.3 * sin(2 * PI * 440 * (pts + i) / rate)).toFloat() }
        if (sampleFormat == AV_SAMPLE_FMT_FLTP) for (channel in 0 until 2) FloatPointer(frame.data(channel)).put(samples, 0, count)
        else FloatPointer(frame.data(0)).put(FloatArray(count * 2) { samples[it / 2] }, 0, count * 2)
        frame.pts(pts)
        pts += count
        check(avcodec_send_frame(context, frame) >= 0)
        drain()
    }
    avcodec_send_frame(context, null)
    drain()
    av_write_trailer(output)
    av_packet_free(packet)
    av_frame_free(frame)
    avcodec_free_context(context)
    avio_closep(output.pb())
    avformat_free_context(output)
    return file
}
