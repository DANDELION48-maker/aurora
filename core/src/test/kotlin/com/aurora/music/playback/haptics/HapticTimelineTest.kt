package com.aurora.music.playback.haptics

import com.aurora.music.playback.PcmConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HapticTimelineTest {
    private fun HapticTimeline.playAll(): List<HapticTap> = buildList {
        while (true) {
            val next = peek() ?: break
            due(next.timeUs, 0) { add(it) }
        }
    }

    @Test fun nearbyHitsBecomeOneTapAtTheStrongerDrum() {
        val timeline = HapticTimeline()
        timeline.addTap(1_010_000, HapticKind.SNARE, 0.9f, HapticDetail.DETAILED)
        timeline.addTap(1_000_000, HapticKind.KICK, 0.5f, HapticDetail.DETAILED)
        timeline.addTap(1_020_000, HapticKind.HAT, 0.3f, HapticDetail.DETAILED)
        timeline.addTap(1_200_000, HapticKind.HAT, 0.3f, HapticDetail.DETAILED)
        assertEquals(listOf(HapticTap(1_000_000, HapticKind.KICK, 0.9f), HapticTap(1_200_000, HapticKind.HAT, 0.3f)), timeline.playAll())
    }

    @Test fun detailChoosesWhichDrumsAreFelt() {
        fun kinds(detail: Int): List<HapticKind> {
            val timeline = HapticTimeline()
            timeline.addTap(0, HapticKind.KICK, 1f, detail)
            timeline.addTap(250_000, HapticKind.HAT, 1f, detail)
            timeline.addTap(500_000, HapticKind.SNARE, 1f, detail)
            timeline.addTap(3_000_000, HapticKind.SNARE, 1f, detail)
            return timeline.playAll().map { it.kind }
        }
        assertEquals(listOf(HapticKind.KICK, HapticKind.SNARE), kinds(HapticDetail.BEAT))
        assertEquals(listOf(HapticKind.KICK, HapticKind.SNARE, HapticKind.SNARE), kinds(HapticDetail.RHYTHM))
        assertEquals(listOf(HapticKind.KICK, HapticKind.HAT, HapticKind.SNARE, HapticKind.SNARE), kinds(HapticDetail.DETAILED))
    }

    @Test fun tapsFireAheadByTheLeadAndLateOnesAreDropped() {
        val timeline = HapticTimeline()
        timeline.addTap(1_000_000, HapticKind.KICK, 1f, HapticDetail.RHYTHM)
        timeline.addTap(1_500_000, HapticKind.KICK, 1f, HapticDetail.RHYTHM)
        val played = mutableListOf<Long>()
        timeline.due(970_000, 20_000) { played += it.timeUs }
        assertTrue(played.isEmpty())
        assertEquals(980_000L, timeline.nextDueUs(20_000))
        timeline.due(985_000, 20_000) { played += it.timeUs }
        assertEquals(listOf(1_000_000L), played)
        timeline.due(1_600_000, 20_000) { played += it.timeUs }
        assertEquals(listOf(1_000_000L), played)
        assertNull(timeline.nextDueUs(20_000))
    }

    @Test fun textureHoldsUntilTheNextPoint() {
        val timeline = HapticTimeline()
        timeline.addTexture(1_000_000, 0.5f)
        timeline.addTexture(1_020_000, 0.8f)
        assertEquals(0f, timeline.textureAt(990_000))
        assertEquals(0.5f, timeline.textureAt(1_010_000))
        assertEquals(0.8f, timeline.textureAt(1_050_000))
        assertEquals(0f, timeline.textureAt(1_200_000))
        timeline.clear()
        assertEquals(0f, timeline.textureAt(1_010_000))
    }

    @Test fun clockCarriesThePositionForwardOnlyWhilePlaying() {
        val clock = HapticClock(maxExtrapolateUs = 100_000)
        assertNull(clock.audibleUs(0))
        clock.update(5_000_000, 1_000_000_000, 1f)
        clock.playing(true, 1_000_000_000)
        assertEquals(5_010_000L, clock.audibleUs(1_010_000_000))
        assertEquals(5_100_000L, clock.audibleUs(2_000_000_000))
        clock.update(6_000_000, 3_000_000_000, 2f)
        assertEquals(6_020_000L, clock.audibleUs(3_010_000_000))
        clock.playing(false, 3_010_000_000)
        assertNull(clock.audibleUs(3_020_000_000))
        clock.playing(true, 4_000_000_000)
        assertEquals(6_040_000L, clock.audibleUs(4_010_000_000))
    }

    @Test fun ringHandsOverMonoSamplesWithTheirMediaTime() {
        val ring = HapticPcmRing(bits = 12)
        val cursor = HapticPcmRing.Cursor()
        val buffer = ByteBuffer.allocate(4 * 100).order(ByteOrder.LITTLE_ENDIAN)
        repeat(100) { buffer.putShort((it * 100).toShort()).putShort((it * 100 + 200).toShort()) }
        buffer.flip()
        ring.push(buffer, PcmConstants.ENCODING_PCM_16BIT, 2, 50_000, 0, 400, 1_000_000)
        ring.push(buffer, PcmConstants.ENCODING_PCM_16BIT, 2, 50_000, 200, 400, 9_000_000)
        val got = mutableListOf<Pair<Long, Float>>()
        val scratch = FloatArray(32)
        var restarts = 0
        ring.drain(cursor, scratch, { restarts++ }) { samples, offset, count, rate, firstUs ->
            for (k in 0 until count) got += (firstUs + k * 1_000_000L / rate) to samples[offset + k]
        }
        assertEquals(1, restarts)
        assertEquals(150, got.size)
        assertEquals(1_000_000L to (100f / 32768f), got[0])
        assertEquals(1_000_020L to (200f / 32768f), got[1])
        assertEquals(9_000_000L to (5_100f / 32768f), got[100])
        assertEquals(0, buffer.position())
        got.clear()
        ring.drain(cursor, scratch, { restarts++ }) { _, _, count, _, _ -> repeat(count) { got += 0L to 0f } }
        assertTrue(got.isEmpty())
        assertEquals(1, restarts)
        ring.flush()
        ring.push(buffer, PcmConstants.ENCODING_PCM_16BIT, 2, 50_000, 0, 8, 20_000_000)
        ring.drain(cursor, scratch, { restarts++ }) { samples, offset, _, _, firstUs -> got += firstUs to samples[offset] }
        assertEquals(listOf(20_000_000L to (100f / 32768f)), got)
        assertEquals(2, restarts)
    }

    @Test fun ringReadsTwentyFourBitAndFloat() {
        val ring = HapticPcmRing(bits = 10)
        val cursor = HapticPcmRing.Cursor()
        val packed = ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0.toByte())).order(ByteOrder.LITTLE_ENDIAN)
        ring.push(packed, PcmConstants.ENCODING_PCM_24BIT, 1, 48_000, 0, 6, 0)
        val floats = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putFloat(0.25f).putFloat(0.75f).also { it.flip() }
        ring.push(floats, PcmConstants.ENCODING_PCM_FLOAT, 2, 48_000, 0, 8, 1_000)
        val got = mutableListOf<Float>()
        ring.drain(cursor, FloatArray(16), {}) { samples, offset, count, _, _ -> for (k in 0 until count) got += samples[offset + k] }
        assertEquals(listOf(0.5f, -0.5f, 0.5f), got)
    }
}
