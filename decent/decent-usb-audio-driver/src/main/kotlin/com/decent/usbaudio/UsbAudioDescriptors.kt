package com.decent.usbaudio

data class UsbRateRange(val minimum: Int, val maximum: Int, val step: Int) {
    fun contains(rate: Int): Boolean = rate in minimum..maximum && (step == 0 || (rate - minimum) % step == 0)
}

data class UsbAudioFormat(
    val configuration: Int,
    val interfaceId: Int,
    val alternateSetting: Int,
    val protocol: Int,
    val channels: Int,
    val validBits: Int,
    val containerBytes: Int,
    val endpointOut: Int,
    val endpointFeedback: Int,
    val maxPacketSize: Int,
    val interval: Int,
    val synchronization: Int,
    val controlInterfaceId: Int,
    val clockSourceId: Int,
    val clockControls: Int,
    val pcm: Boolean,
    val descriptorRates: List<UsbRateRange> = emptyList(),
    val formatBitmap: Long = if (pcm) 1 else 0,
    val formatType: Int = 1,
    val fullSpeed: Boolean = false,
    val frequencyControl: Boolean = false,
    val volumeUnit: UsbVolumeUnit? = null,
) {
    val containerBits get() = containerBytes * 8
    val packetsPerSecond get() = if (fullSpeed) 1000 else 8000
    val endpointRateControl get() = protocol == 0 && frequencyControl
    val unsupportedReason: String? get() = pcmUnsupportedReason(false)
    fun pcmUnsupportedReason(experimentalDsd: Boolean): String? = when {
        transportUnsupportedReason(experimentalDsd) != null -> transportUnsupportedReason(experimentalDsd)
        !pcm -> "The USB format is not integer PCM."
        containerBytes !in 2..4 || validBits !in 16..containerBits -> "Unsupported PCM sample layout."
        else -> null
    }
    val rawUnsupportedReason: String? get() = rawUnsupportedReason(false)
    fun rawUnsupportedReason(experimentalDsd: Boolean): String? = when {
        transportUnsupportedReason(experimentalDsd) != null -> transportUnsupportedReason(experimentalDsd)
        formatType != 1 || formatBitmap != 0x80000000L -> "The USB format is not Type I raw data."
        containerBytes != 4 || validBits != 32 -> "Unsupported raw USB sample layout."
        else -> null
    }
    val transportUnsupportedReason: String? get() = transportUnsupportedReason(false)
    fun transportUnsupportedReason(experimentalDsd: Boolean): String? = when {
        protocol != 0x20 && protocol != 0 -> "Direct output requires USB Audio Class 1 or 2."
        formatType != 1 -> "The USB format is not Type I."
        channels !in 1..2 -> "Direct output supports mono or stereo."
        interval != 1 -> "The USB endpoint interval is unsupported."
        maxPacketSize !in 1..(if (experimentalDsd) 3072 else if (fullSpeed) 1023 else 1024) -> "The USB packet size exceeds the driver budget."
        protocol == 0x20 && (clockSourceId <= 0 || clockControls and 1 == 0) -> "The active USB clock cannot be verified."
        protocol == 0 && descriptorRates.isEmpty() -> "The USB sample rates are not declared."
        protocol == 0 && !frequencyControl && descriptorRates.singleOrNull()?.let { it.minimum == it.maximum } != true ->
            "The USB sample rate cannot be selected."
        synchronization == 1 && endpointFeedback <= 0 -> "Explicit asynchronous feedback is required."
        else -> null
    }
    fun fits(rate: Int, experimentalDsd: Boolean = false): Boolean = rate in 8000..(if (experimentalDsd) 2822400 else 384000) &&
        kotlin.math.ceil(rate * (if (synchronization == 1) 1.01 else 1.0) / packetsPerSecond).toInt() * channels * containerBytes <= maxPacketSize
}

data class UsbClockStatus(val requestedHz: Int, val observedHz: Int?, val clockValid: Boolean?, val failure: String?) {
    val verified get() = failure == null && requestedHz == observedHz && clockValid != false
}

// feature unit on the playback path, channel 0 is the master channel
data class UsbVolumeUnit(val unitId: Int, val volumeChannels: List<Int>, val muteChannels: List<Int>) {
    val volumeTargets get() = if (0 in volumeChannels) listOf(0) else volumeChannels
    val muteTargets get() = if (0 in muteChannels) listOf(0) else muteChannels
}

// levels are in 1/256 dB as usb audio reports them
data class UsbHardwareVolume(val minimum: Int, val maximum: Int, val step: Int, val current: Int, val muted: Boolean) {
    private val top get() = if (maximum > 0 && minimum < 0) 0 else maximum
    private val floor get() = maxOf(minimum, top - USABLE_RANGE)
    val fraction: Double get() = if (muted || top <= floor) 0.0 else ((current - floor).toDouble() / (top - floor)).coerceIn(0.0, 1.0)

    fun levelFor(fraction: Double): Int {
        val target = floor + (top - floor) * fraction.coerceIn(0.0, 1.0)
        var level = maximum - Math.round((maximum - target) / step).toInt() * step
        if (level > top) level -= step
        if (level < minimum) level += step
        return level
    }

    companion object {
        const val USABLE_RANGE = 60 * 256

        // -32768 is the spec's silence value, not a level
        fun of(minimum: Int, maximum: Int, step: Int, current: Int, muted: Boolean): UsbHardwareVolume? {
            val low = maxOf(minimum, -32767)
            val res = if (step <= 0) 1 else step
            return if (maximum - low >= res) UsbHardwareVolume(low, maximum, res, current, muted) else null
        }
    }
}

data class UsbDescriptorReport(val formats: List<UsbAudioFormat>, val malformed: Boolean)

object UsbAudioDescriptors {
    fun parse(raw: ByteArray): UsbDescriptorReport {
        data class Pending(val configuration: Int, val id: Int, val alt: Int, val protocol: Int, val control: Int,
            var channels: Int = 0, var bytes: Int = 0, var bits: Int = 0, var terminal: Int = 0,
            var pcm: Boolean = false, var out: Int = -1, var feedback: Int = -1, var packet: Int = 0,
            var interval: Int = 0, var sync: Int = 0, var rates: List<UsbRateRange> = emptyList(),
            var formats: Long = 0, var type: Int = 0, var frequency: Boolean = false)
        data class Entity(val sources: List<Int>, val volume: List<Int> = emptyList(), val mute: List<Int> = emptyList())
        val pending = mutableListOf<Pending>()
        val clocks = mutableMapOf<Triple<Int, Int, Int>, Int>()
        val terminals = mutableMapOf<Triple<Int, Int, Int>, Int>()
        val entities = mutableMapOf<Triple<Int, Int, Int>, Entity>()
        var configuration = 0; var control = -1; var interfaceClass = 0; var subclass = 0; var protocol = 0
        var stream: Pending? = null; var offset = 0; var malformed = false
        fun u(at: Int) = raw[at].toInt() and 255
        fun u24(at: Int) = u(at) or (u(at + 1) shl 8) or (u(at + 2) shl 16)
        fun ids(at: Int, count: Int, end: Int) = if (at + count <= end) List(count) { u(at + it) } else null
        // terminals and units of an audiocontrol interface, enough to walk a feature unit back to its input
        fun entity(subtype: Int, at: Int, length: Int): Pair<Int, Entity>? {
            if (length < 5) return null
            val end = at + length
            val sources = when (subtype) {
                2 -> emptyList()
                3 -> if (length >= 8) listOf(u(at + 7)) else null
                4, 5 -> ids(at + 5, u(at + 4), end)
                6 -> listOf(u(at + 4))
                7 -> if (length < 7) null else if (protocol == 0x20) listOf(u(at + 6)) else ids(at + 7, u(at + 6), end)
                8, 9 -> if (length >= 7) ids(at + 7, u(at + 6), end) else null
                0x0d -> if (protocol == 0x20) listOf(u(at + 4)) else null
                else -> null
            } ?: return null
            if (subtype != 6) return u(at + 3) to Entity(sources)
            val (start, size) = if (protocol == 0x20) 5 to 4 else 6 to u(at + 5)
            if (size == 0) return u(at + 3) to Entity(sources)
            val channels = (length - start - 1) / size
            fun bits(channel: Int) = (0 until size).fold(0L) { value, i -> value or (u(at + start + channel * size + i).toLong() shl (8 * i)) }
            // uac2 packs two bits per control and only 0b11 is host writable
            val volume = (0 until channels).filter { if (protocol == 0x20) bits(it) shr 2 and 3 == 3L else bits(it) and 2 != 0L }
            val mute = (0 until channels).filter { if (protocol == 0x20) bits(it) and 3 == 3L else bits(it) and 1 != 0L }
            return u(at + 3) to Entity(sources, volume, mute)
        }
        while (offset < raw.size) {
            if (offset + 2 > raw.size) { malformed = true; break }
            val length = u(offset); val type = u(offset + 1)
            if (length < 2 || offset + length > raw.size) { malformed = true; break }
            when (type) {
                2 -> if (length >= 9) { configuration = u(offset + 5); control = -1; stream = null } else malformed = true
                4 -> if (length >= 9) {
                    interfaceClass = u(offset + 5); subclass = u(offset + 6); protocol = u(offset + 7)
                    stream = null
                    if (interfaceClass == 1 && subclass == 1) control = u(offset + 2)
                    if (interfaceClass == 1 && subclass == 2 && u(offset + 3) > 0) {
                        stream = Pending(configuration, u(offset + 2), u(offset + 3), protocol, control)
                        pending += stream!!
                    }
                } else malformed = true
                0x24 -> if (length >= 3 && interfaceClass == 1) {
                    val subtype = u(offset + 2)
                    if (subclass == 1) entity(subtype, offset, length)?.let { (id, e) -> entities[Triple(configuration, control, id)] = e }
                    if (subclass == 1 && protocol == 0x20) {
                        if (subtype == 0x0a && length >= 8) clocks[Triple(configuration, control, u(offset + 3))] = u(offset + 5)
                        if (subtype == 2 && length >= 17) terminals[Triple(configuration, control, u(offset + 3))] = u(offset + 7)
                        if (subtype == 3 && length >= 12) terminals[Triple(configuration, control, u(offset + 3))] = u(offset + 8)
                    }
                    stream?.let { s ->
                        if (subtype == 1) {
                            if (protocol == 0x20 && length >= 16) {
                                s.terminal = u(offset + 3); s.pcm = u(offset + 5) == 1 && u(offset + 6) and 1 != 0
                                s.type = u(offset + 5)
                                s.formats = (0..3).fold(0L) { bits, i -> bits or (u(offset + 6 + i).toLong() shl (8 * i)) }
                                s.channels = u(offset + 10)
                            } else if (protocol == 0 && length >= 7) {
                                s.terminal = u(offset + 3); s.pcm = u(offset + 5) == 1 && u(offset + 6) == 0; s.type = 1; s.formats = if (s.pcm) 1 else 0
                            }
                        }
                        if (subtype == 2 && length >= 6 && u(offset + 3) == 1) {
                            if (protocol == 0x20) { s.bytes = u(offset + 4); s.bits = u(offset + 5) }
                            else if (protocol == 0 && length >= 8) {
                                s.channels = u(offset + 4); s.bytes = u(offset + 5); s.bits = u(offset + 6)
                                val count = u(offset + 7)
                                if (count == 0 && length >= 14) s.rates = listOf(UsbRateRange(u24(offset + 8), u24(offset + 11), 0))
                                else if (count > 0 && length >= 8 + count * 3) s.rates = List(count) { u24(offset + 8 + it * 3).let { rate -> UsbRateRange(rate, rate, 0) } }
                                else malformed = true
                            }
                        }
                    }
                }
                5 -> if (length >= 7) stream?.let { s ->
                    val address = u(offset + 2); val attributes = u(offset + 3)
                    if (attributes and 3 == 1) {
                        if (address and 0x80 == 0 && attributes and 0x30 == 0) {
                            s.out = address; val packet = u(offset + 4) or (u(offset + 5) shl 8)
                            val payload = packet and 0x7ff
                            val transactions = packet shr 11 and 3
                            if (packet and 0xe000 != 0 || transactions == 3 || payload > 1024) malformed = true
                            s.packet = payload * (1 + transactions)
                            s.interval = u(offset + 6); s.sync = attributes shr 2 and 3
                        } else if (address and 0x80 != 0 && (attributes and 0x30 == 0x10 || s.protocol == 0 && s.out > 0)) s.feedback = address
                    }
                } else malformed = true
                0x25 -> if (length >= 4 && interfaceClass == 1) stream?.let { s ->
                    if (s.protocol == 0 && u(offset + 2) == 1 && s.out > 0 && s.feedback < 0) s.frequency = u(offset + 3) and 1 != 0
                }
            }
            offset += length
        }
        // the writable feature unit closest downstream of the stream's input terminal
        fun volumeUnit(s: Pending): UsbVolumeUnit? {
            val graph = entities.filterKeys { it.first == s.configuration && it.second == s.control }.mapKeys { it.key.third }
            fun distance(id: Int): Int? {
                var level = setOf(id)
                val seen = mutableSetOf<Int>()
                repeat(16) { depth ->
                    if (s.terminal in level) return depth
                    seen += level
                    level = level.flatMap { graph[it]?.sources.orEmpty() }.filterTo(mutableSetOf()) { it !in seen }
                    if (level.isEmpty()) return null
                }
                return null
            }
            if (s.terminal <= 0) return null
            return graph.filterValues { it.volume.isNotEmpty() }.mapNotNull { (id, e) -> distance(id)?.let { Triple(id, e, it) } }
                .minByOrNull { it.third }?.let { (id, e) -> UsbVolumeUnit(id, e.volume, e.mute) }
        }
        val formats = pending.filter { it.out > 0 && it.bytes > 0 && it.bits > 0 && it.channels > 0 }.map { s ->
            val clock = terminals[Triple(s.configuration, s.control, s.terminal)] ?: -1
            UsbAudioFormat(s.configuration, s.id, s.alt, s.protocol, s.channels, s.bits, s.bytes, s.out, s.feedback,
                s.packet, s.interval, s.sync, s.control, clock, clocks[Triple(s.configuration, s.control, clock)] ?: 0, s.pcm, s.rates, s.formats, s.type,
                frequencyControl = s.frequency, volumeUnit = volumeUnit(s))
        }
        return UsbDescriptorReport(if (malformed) emptyList() else formats, malformed)
    }

    fun parseClockRanges(data: ByteArray): List<UsbRateRange> {
        require(data.size >= 2)
        val count = (data[0].toInt() and 255) or ((data[1].toInt() and 255) shl 8)
        require(count in 1..64 && data.size == 2 + count * 12)
        fun u32(offset: Int): Int {
            var value = 0L
            for (i in 0..3) value = value or ((data[offset + i].toLong() and 255) shl (i * 8))
            require(value <= Int.MAX_VALUE)
            return value.toInt()
        }
        return List(count) { index ->
            val offset = 2 + index * 12
            UsbRateRange(u32(offset), u32(offset + 4), u32(offset + 8)).also {
                require(it.minimum > 0 && it.maximum >= it.minimum)
            }
        }
    }

    // uac2 volume RANGE: count then min/max/res triplets, merged into one span
    fun parseVolumeRange(data: ByteArray): Triple<Int, Int, Int> {
        require(data.size >= 2)
        val count = u16(data, 0)
        require(count in 1..64 && data.size >= 2 + count * 6)
        return Triple(s16(data, 2), s16(data, 2 + (count - 1) * 6 + 2), (0 until count).minOf { u16(data, 2 + it * 6 + 4) })
    }

    fun u16(data: ByteArray, at: Int) = (data[at].toInt() and 255) or ((data[at + 1].toInt() and 255) shl 8)
    fun s16(data: ByteArray, at: Int) = u16(data, at).toShort().toInt()
}
