package com.decent.usbaudio

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UsbAudioDescriptorsTest {
    @Test fun experimentalPacketsRequireOptInAndEnoughCapacity() {
        val pcm = UsbAudioDescriptors.parse(uac2()).formats.single().copy(maxPacketSize = 3072)
        assertNotNull(pcm.unsupportedReason)
        assertNull(pcm.pcmUnsupportedReason(true))
        assertFalse(pcm.fits(2822400))
        assertTrue(pcm.fits(2822400, true))
        assertFalse(pcm.copy(maxPacketSize = 2048).fits(2822400, true))
        assertFalse(pcm.copy(maxPacketSize = 3073).transportUnsupportedReason(true) == null)
        val raw = pcm.copy(pcm = false, formatBitmap = 0x80000000L, validBits = 32)
        assertNull(raw.rawUnsupportedReason(true))
        assertTrue(raw.copy(maxPacketSize = 2048).fits(1411200, true))
        assertFalse(raw.copy(maxPacketSize = 1024).fits(1411200, true))
        assertNotNull(raw.copy(formatBitmap = 4).rawUnsupportedReason(true))
        assertNotNull(raw.copy(containerBytes = 3).rawUnsupportedReason(true))
        assertNotNull(raw.copy(endpointFeedback = -1).rawUnsupportedReason(true))
        assertNotNull(raw.copy(interval = 2).rawUnsupportedReason(true))
    }

    @Test fun highSpeedPcmAcceptsAFullSingleTransactionPacket() {
        val pcm = UsbAudioDescriptors.parse(uac2()).formats.single()
        assertNull(pcm.copy(maxPacketSize = 776).unsupportedReason)
        assertTrue(pcm.copy(maxPacketSize = 776).fits(384000))
        assertNull(pcm.copy(maxPacketSize = 1024).unsupportedReason)
        assertNotNull(pcm.copy(maxPacketSize = 1025).unsupportedReason)
    }

    @Test fun highBandwidthDescriptorsRejectReservedTransactionCountsAndOversizedPayloads() {
        fun endpoint(packet: Int): UsbDescriptorReport {
            val bytes = uac2()
            val start = (0 until bytes.size - 7).first { bytes[it] == 7.toByte() && bytes[it + 1] == 5.toByte() }
            bytes[start + 4] = packet.toByte(); bytes[start + 5] = (packet ushr 8).toByte()
            return UsbAudioDescriptors.parse(bytes)
        }
        assertEquals(3072, endpoint(0x1400).formats.single().maxPacketSize)
        assertEquals(2048, endpoint(0x0c00).formats.single().maxPacketSize)
        assertTrue(endpoint(0x1c00).malformed)
        assertTrue(endpoint(0x1401).malformed)
        assertTrue(endpoint(0x3400).malformed)
    }

    @Test fun fullFormatBitmapPreservesRawDataWithoutMakingItPcm() {
        val bytes = uac2()
        val header = (0 until bytes.size - 16).first { bytes[it] == 16.toByte() && bytes[it + 1] == 0x24.toByte() }
        bytes[header + 6] = 0; bytes[header + 9] = 0x80.toByte()
        bytes[header + 16 + 5] = 32
        val raw = UsbAudioDescriptors.parse(bytes).formats.single()
        assertEquals(0x80000000L, raw.formatBitmap)
        assertEquals(1, raw.formatType)
        assertFalse(raw.pcm)
        assertNotNull(raw.unsupportedReason)
        assertNull(raw.transportUnsupportedReason)
        assertNull(raw.rawUnsupportedReason)
        assertNotNull(raw.copy(formatBitmap = 4).rawUnsupportedReason)
        assertNotNull(raw.copy(containerBytes = 3).rawUnsupportedReason)
        assertNotNull(raw.copy(formatType = 2).transportUnsupportedReason)
    }

    private fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private fun uac2(): ByteArray = bytes(
        9, 2, 0, 0, 2, 1, 0, 0x80, 50,
        9, 4, 3, 0, 0, 1, 1, 0x20, 0,
        8, 0x24, 0x0a, 23, 3, 7, 0, 0,
        8, 0x24, 0x0a, 24, 3, 7, 0, 0,
        17, 0x24, 2, 8, 1, 1, 0, 23, 2, 3, 0, 0, 0, 0, 0, 0, 0,
        9, 4, 4, 0, 0, 1, 2, 0x20, 0,
        9, 4, 4, 1, 2, 1, 2, 0x20, 0,
        16, 0x24, 1, 8, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0,
        6, 0x24, 2, 1, 4, 24,
        7, 5, 1, 5, 0, 2, 1,
        7, 5, 0x81, 0x11, 4, 0, 4,
    )

    @Test fun resolvesTerminalClockAndSeparatesResolutionFromContainer() {
        val result = UsbAudioDescriptors.parse(uac2())
        assertFalse(result.malformed)
        val format = result.formats.single()
        assertEquals(23, format.clockSourceId); assertEquals(3, format.controlInterfaceId)
        assertEquals(4, format.interfaceId); assertEquals(1, format.alternateSetting)
        assertEquals(24, format.validBits); assertEquals(32, format.containerBits)
        assertEquals(2, format.channels); assertEquals(0x81, format.endpointFeedback)
        assertNull(format.unsupportedReason); assertTrue(format.fits(384000))
    }

    @Test fun captureInterfaceDoesNotReplaceOutputEndpointsOrClock() {
        val capture = bytes(9, 4, 5, 1, 1, 1, 2, 0x20, 0,
            16, 0x24, 1, 8, 0, 1, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0,
            6, 0x24, 2, 1, 2, 16, 7, 5, 0x82, 5, 64, 0, 1)
        val format = UsbAudioDescriptors.parse(uac2() + capture).formats.single()
        assertEquals(4, format.interfaceId); assertEquals(1, format.endpointOut)
    }

    @Test fun malformedTailRejectsAllFormatsRatherThanKeepingPartialDiscovery() {
        assertTrue(UsbAudioDescriptors.parse(uac2() + bytes(9, 4, 1)).malformed)
        assertTrue(UsbAudioDescriptors.parse(uac2() + bytes(9, 4, 1)).formats.isEmpty())
        assertTrue(UsbAudioDescriptors.parse(uac2() + bytes(0, 0)).formats.isEmpty())
    }

    @Test fun unsupportedClockTopologyAndMissingFeedbackStayExplicit() {
        val format = UsbAudioDescriptors.parse(uac2()).formats.single()
        assertNotNull(format.copy(clockSourceId = -1).unsupportedReason)
        assertNotNull(format.copy(clockControls = 2).unsupportedReason)
        assertNotNull(format.copy(endpointFeedback = -1).unsupportedReason)
        assertNotNull(format.copy(protocol = 0).unsupportedReason)
        assertNotNull(format.copy(interval = 2).unsupportedReason)
        assertNotNull(format.copy(channels = 6).unsupportedReason)
        assertNotNull(format.copy(pcm = false).unsupportedReason)
        assertFalse(format.copy(maxPacketSize = 48).fits(48000))
        assertTrue(format.copy(maxPacketSize = 48, synchronization = 3, endpointFeedback = -1).fits(48000))
    }

    @Test fun uac1UsesItsOwnChannelAndRateOffsets() {
        val descriptors = bytes(9, 2, 0, 0, 2, 1, 0, 0x80, 50,
            9, 4, 0, 0, 0, 1, 1, 0, 0, 9, 4, 1, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 0, 1, 0, 11, 0x24, 2, 1, 2, 3, 24, 1, 0x80, 0xbb, 0,
            7, 5, 1, 9, 0x20, 1, 1)
        val format = UsbAudioDescriptors.parse(descriptors).formats.single()
        assertEquals(2, format.channels); assertEquals(24, format.validBits); assertEquals(3, format.containerBytes)
        assertEquals(listOf(UsbRateRange(48000, 48000, 0)), format.descriptorRates)
        assertNull(format.unsupportedReason)
        assertFalse(format.endpointRateControl)
        assertFalse(format.copy(fullSpeed = true).fits(96000))
        assertTrue(format.copy(fullSpeed = true).fits(48000))
    }

    @Test fun uac1SelectsRatesThroughTheEndpointAndFindsItsFeedbackEndpoint() {
        val descriptors = bytes(9, 2, 0, 0, 2, 1, 0, 0x80, 50,
            9, 4, 0, 0, 0, 1, 1, 0, 0, 9, 4, 1, 1, 2, 1, 2, 0, 0,
            7, 0x24, 1, 1, 0, 1, 0, 14, 0x24, 2, 1, 2, 3, 24, 2, 0x80, 0xbb, 0, 0, 0x77, 1,
            9, 5, 1, 5, 0x47, 2, 1, 0, 0x81, 7, 0x25, 1, 1, 0, 0, 0,
            9, 5, 0x81, 1, 3, 0, 1, 2, 0)
        val format = UsbAudioDescriptors.parse(descriptors).formats.single().copy(fullSpeed = true)
        assertEquals(listOf(UsbRateRange(48000, 48000, 0), UsbRateRange(96000, 96000, 0)), format.descriptorRates)
        assertEquals(0x81, format.endpointFeedback); assertEquals(1, format.synchronization)
        assertTrue(format.endpointRateControl)
        assertNull(format.unsupportedReason)
        assertTrue(format.fits(96000)); assertFalse(format.copy(maxPacketSize = 576).fits(96000))
        assertNotNull(format.copy(frequencyControl = false).unsupportedReason)
        assertNotNull(format.copy(endpointFeedback = -1).unsupportedReason)
        assertNotNull(format.copy(descriptorRates = emptyList()).unsupportedReason)
        assertNotNull(format.copy(maxPacketSize = 1024).unsupportedReason)
        assertNull(format.copy(fullSpeed = false).unsupportedReason)
        assertNotNull(format.copy(fullSpeed = false, maxPacketSize = 1025).unsupportedReason)
    }

    // uac2() plus input terminal 8 -> feature unit 10 -> speaker, and a microphone chain with its own feature unit
    private fun uac2WithVolume(masterControls: Int = 0x0f): ByteArray {
        val base = uac2()
        val at = (0 until base.size - 2).first { base[it] == 17.toByte() && base[it + 1] == 0x24.toByte() && base[it + 2] == 2.toByte() } + 17
        val units = bytes(
            18, 0x24, 6, 10, 8, masterControls, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            12, 0x24, 3, 11, 1, 3, 0, 10, 23, 0, 0, 0,
            17, 0x24, 2, 20, 1, 2, 0, 23, 2, 3, 0, 0, 0, 0, 0, 0, 0,
            18, 0x24, 6, 21, 20, 0x0f, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            12, 0x24, 3, 22, 1, 1, 0, 21, 23, 0, 0, 0)
        return base.copyOfRange(0, at) + units + base.copyOfRange(at, base.size)
    }

    @Test fun findsOnlyTheWritableFeatureUnitOnThePlaybackPath() {
        assertEquals(UsbVolumeUnit(10, listOf(0), listOf(0)), UsbAudioDescriptors.parse(uac2WithVolume()).formats.single().volumeUnit)
        assertNull(UsbAudioDescriptors.parse(uac2WithVolume(0x05)).formats.single().volumeUnit)
        assertNull(UsbAudioDescriptors.parse(uac2()).formats.single().volumeUnit)
    }

    @Test fun uac1FeatureUnitFallsBackToPerChannelVolume() {
        val descriptors = bytes(9, 2, 0, 0, 2, 1, 0, 0x80, 50,
            9, 4, 0, 0, 0, 1, 1, 0, 0,
            12, 0x24, 2, 1, 1, 1, 0, 2, 3, 0, 0, 0,
            10, 0x24, 6, 2, 1, 1, 1, 2, 2, 0,
            9, 0x24, 3, 3, 1, 3, 0, 2, 0,
            9, 4, 1, 1, 1, 1, 2, 0, 0,
            7, 0x24, 1, 1, 0, 1, 0, 11, 0x24, 2, 1, 2, 3, 24, 1, 0x80, 0xbb, 0,
            7, 5, 1, 9, 0x20, 1, 1)
        val unit = UsbAudioDescriptors.parse(descriptors).formats.single().volumeUnit!!
        assertEquals(2, unit.unitId)
        assertEquals(listOf(1, 2), unit.volumeTargets)
        assertEquals(listOf(0), unit.muteTargets)
    }

    @Test fun hardwareVolumeMapsPhoneStepsEvenlyInDecibelsAndRoundTrips() {
        val volume = UsbHardwareVolume.of(-59 * 256, 0, 256, -20 * 256, false)!!
        for (step in 0..15) {
            val level = volume.levelFor(step / 15.0)
            assertEquals(0, level % 256)
            assertEquals(step, Math.round(volume.copy(current = level).fraction * 15).toInt())
        }
        assertEquals(-59 * 256, volume.levelFor(0.0)); assertEquals(0, volume.levelFor(1.0))
        assertEquals(0.0, volume.copy(muted = true).fraction, 0.0)
    }

    @Test fun hardwareVolumeNeverBoostsAndUsesAPracticalSpan() {
        val wide = UsbHardwareVolume.of(-32768, 6 * 256, 128, 0, false)!!
        assertEquals(-32767, wide.minimum)
        assertEquals(0, wide.levelFor(1.0))
        assertEquals(-60 * 256, wide.levelFor(0.0))
        assertEquals(1.0, wide.copy(current = 6 * 256).fraction, 0.0)
        assertEquals(-30 * 256, wide.levelFor(0.5))
        assertNull(UsbHardwareVolume.of(0, 0, 256, 0, false))
        assertEquals(1, UsbHardwareVolume.of(-256, 0, 0, 0, false)!!.step)
    }

    @Test fun uac2VolumeRangeMergesSubranges() {
        val one = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(1)
            .putShort((-59 * 256).toShort()).putShort(0).putShort(256).array()
        assertEquals(Triple(-59 * 256, 0, 256), UsbAudioDescriptors.parseVolumeRange(one))
        val two = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN).putShort(2)
            .putShort((-100 * 256).toShort()).putShort((-50 * 256).toShort()).putShort(512)
            .putShort((-50 * 256).toShort()).putShort(0).putShort(256).array()
        assertEquals(Triple(-100 * 256, 0, 256), UsbAudioDescriptors.parseVolumeRange(two))
        assertThrows(IllegalArgumentException::class.java) { UsbAudioDescriptors.parseVolumeRange(two.copyOf(13)) }
    }

    @Test fun clockRangesValidateLengthsAndRespectDiscreteSteps() {
        val bytes = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).putShort(2)
            .putInt(44100).putInt(176400).putInt(44100).putInt(48000).putInt(48000).putInt(0).array()
        val ranges = UsbAudioDescriptors.parseClockRanges(bytes)
        assertTrue(ranges[0].contains(88200)); assertFalse(ranges[0].contains(96000))
        assertTrue(ranges[1].contains(48000)); assertFalse(ranges[1].contains(44100))
        assertThrows(IllegalArgumentException::class.java) { UsbAudioDescriptors.parseClockRanges(bytes.copyOf(25)) }
        assertThrows(IllegalArgumentException::class.java) { UsbAudioDescriptors.parseClockRanges(bytes.apply { this[0] = 65 }) }
    }
}
