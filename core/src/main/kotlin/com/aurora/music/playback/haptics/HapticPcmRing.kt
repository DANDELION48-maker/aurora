package com.aurora.music.playback.haptics

import com.aurora.music.playback.PcmConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

// mono samples handed from the audio thread to the analyser with the media time of each buffer; one writer, one reader
class HapticPcmRing(bits: Int = 16) {
    private val size = 1 shl bits
    private val mask = size - 1
    private val ring = FloatArray(size)
    private val anchorStart = LongArray(ANCHORS)
    private val anchorTime = LongArray(ANCHORS)
    private val anchorRate = IntArray(ANCHORS)
    @Volatile private var written = 0L
    @Volatile private var anchors = 0L
    @Volatile private var flushedAt = 0L
    @Volatile private var generation = 0

    class Cursor {
        internal var read = 0L
        internal var anchor = -1L
        internal var generation = -1
    }

    fun flush() {
        flushedAt = written
        generation++
    }

    fun push(buffer: ByteBuffer, encoding: Int, channels: Int, sampleRate: Int, start: Int, end: Int, timeUs: Long) {
        if (channels <= 0 || sampleRate <= 0 || end <= start || timeUs == PcmConstants.TIME_UNSET) return
        val bytes = when (encoding) {
            PcmConstants.ENCODING_PCM_16BIT -> 2
            PcmConstants.ENCODING_PCM_24BIT -> 3
            PcmConstants.ENCODING_PCM_32BIT, PcmConstants.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytes * channels
        val frames = (end - start) / frameBytes
        if (frames <= 0) return
        val base = written
        val slot = (anchors % ANCHORS).toInt()
        anchorStart[slot] = base
        anchorTime[slot] = timeUs
        anchorRate[slot] = sampleRate
        anchors = anchors + 1
        val little = buffer.order() == ByteOrder.LITTLE_ENDIAN
        var p = start
        var w = base
        repeat(frames) {
            var sum = 0f
            for (c in 0 until channels) sum += sample(buffer, p + c * bytes, encoding, little)
            ring[(w and mask.toLong()).toInt()] = sum / channels
            w++
            p += frameBytes
        }
        written = w
    }

    // hands every new sample to the analyser in order; onRestart runs first when the stream restarted or the reader fell behind
    fun drain(cursor: Cursor, scratch: FloatArray, onRestart: () -> Unit, feed: (FloatArray, Int, Int, Int, Long) -> Unit) {
        val end = written
        val known = anchors
        if (cursor.generation != generation) {
            cursor.generation = generation
            cursor.read = maxOf(flushedAt, end - size / 2)
            seek(cursor, known, end)
            onRestart()
        } else if (end - cursor.read > size - LAG_MARGIN || cursor.anchor >= 0 && known - cursor.anchor >= ANCHORS - 1) {
            cursor.read = end
            seek(cursor, known, end)
            onRestart()
            return
        } else if (cursor.anchor < 0) {
            seek(cursor, known, end)
        }
        while (cursor.read < end && cursor.anchor >= 0) {
            while (cursor.anchor + 1 < known && startOf(cursor.anchor + 1) <= cursor.read) cursor.anchor++
            val slot = slotOf(cursor.anchor)
            val next = if (cursor.anchor + 1 < known) minOf(end, startOf(cursor.anchor + 1)) else end
            val rate = anchorRate[slot]
            var at = cursor.read
            while (at < next) {
                val count = minOf(scratch.size.toLong(), next - at).toInt()
                for (k in 0 until count) scratch[k] = ring[((at + k) and mask.toLong()).toInt()]
                feed(scratch, 0, count, rate, anchorTime[slot] + (at - anchorStart[slot]) * 1_000_000L / rate)
                at += count
            }
            cursor.read = next
        }
    }

    // the newest buffer that starts at or before the read point, or the oldest one still held after it
    private fun seek(cursor: Cursor, known: Long, end: Long) {
        var a = known - 1
        val oldest = maxOf(0L, known - ANCHORS + 2)
        while (a > oldest && startOf(a) > cursor.read) a--
        if (a < 0) {
            cursor.anchor = -1
            cursor.read = end
            return
        }
        if (startOf(a) > cursor.read) cursor.read = minOf(startOf(a), end)
        cursor.anchor = a
    }

    private fun slotOf(anchor: Long) = (anchor % ANCHORS).toInt()
    private fun startOf(anchor: Long) = anchorStart[slotOf(anchor)]

    private fun sample(buffer: ByteBuffer, at: Int, encoding: Int, little: Boolean): Float = when (encoding) {
        PcmConstants.ENCODING_PCM_16BIT -> {
            val lo = buffer.get(at).toInt() and 0xFF
            val hi = buffer.get(at + 1).toInt()
            (if (little) (hi shl 8) or lo else (lo shl 8) or (hi and 0xFF)).toShort() / 32768f
        }
        PcmConstants.ENCODING_PCM_24BIT -> {
            val b0 = buffer.get(at).toInt() and 0xFF
            val b1 = buffer.get(at + 1).toInt() and 0xFF
            val b2 = buffer.get(at + 2).toInt() and 0xFF
            val v = if (little) (b2 shl 16) or (b1 shl 8) or b0 else (b0 shl 16) or (b1 shl 8) or b2
            ((v shl 8) shr 8) / 8_388_608f
        }
        PcmConstants.ENCODING_PCM_32BIT -> buffer.getInt(at) / 2_147_483_648f
        else -> buffer.getFloat(at)
    }

    private companion object {
        const val ANCHORS = 256
        const val LAG_MARGIN = 4_096
    }
}
