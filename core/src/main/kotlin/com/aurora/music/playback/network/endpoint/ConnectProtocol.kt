package com.aurora.music.playback.network.endpoint

import com.aurora.music.data.MusicRepository
import com.aurora.music.model.Song
import com.aurora.music.playback.network.endpoint.RendererJson.bool
import com.aurora.music.playback.network.endpoint.RendererJson.int
import com.aurora.music.playback.network.endpoint.RendererJson.long
import com.aurora.music.playback.network.endpoint.RendererJson.text
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

const val CONNECT_PORT = 47801
const val CONNECT_QUEUE_WINDOW = 300

object ConnectSwitch { const val KEEP = 0; const val PAUSE = 1; const val MOVE = 2 }

// no stream or artwork urls: those can carry the host's server credentials
data class ConnectTrack(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationSec: Int = 0,
    val albumId: String = "",
    val artistId: String = "",
) {
    fun toSong(): Song = Song(id, title, artist, album, "", durationSec, albumId = albumId, artistId = artistId)

    companion object {
        fun of(song: Song) = ConnectTrack(song.id, song.title, song.artist, song.album, song.durationSec, song.albumId, song.artistId)
    }
}

data class ConnectOption(val id: String, val name: String)

data class ConnectStatus(
    val deviceId: String = "",
    val name: String = "",
    val platform: String = "",
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val index: Int = 0,
    val offset: Int = 0,
    val total: Int = 0,
    val queue: List<ConnectTrack> = emptyList(),
    val shuffle: Boolean = false,
    val repeat: Int = 0,
    val volume: Float = -1f,
    val speed: Float = 1f,
    val liked: Boolean = false,
    val sleepMinutes: Int = 0,
    val sleepEndOfTrack: Boolean = false,
    val outputs: List<ConnectOption> = emptyList(),
    val output: String = "",
    val presets: List<ConnectOption> = emptyList(),
    val error: String? = null,
) {
    val current: ConnectTrack? get() = queue.getOrNull(index - offset)

    fun windowed(full: List<ConnectTrack>): ConnectStatus {
        val start = (index - CONNECT_QUEUE_WINDOW / 3).coerceIn(0, (full.size - CONNECT_QUEUE_WINDOW).coerceAtLeast(0))
        return copy(offset = start, total = full.size, queue = full.subList(start, minOf(full.size, start + CONNECT_QUEUE_WINDOW)))
    }

    // everything except the clock, so a long poll wakes on real changes and on seeks
    fun differsFrom(other: ConnectStatus, elapsedMs: Long): Boolean {
        val drift = positionMs - other.positionMs - if (other.playing) (elapsedMs * other.speed).toLong() else 0L
        return copy(positionMs = 0) != other.copy(positionMs = 0) || kotlin.math.abs(drift) > 2_000
    }
}

sealed interface ConnectCommand {
    data class Playing(val value: Boolean) : ConnectCommand
    data class Seek(val positionMs: Long) : ConnectCommand
    data class Jump(val index: Int) : ConnectCommand
    data class Shuffle(val value: Boolean) : ConnectCommand
    data class Repeat(val mode: Int) : ConnectCommand
    data class Volume(val value: Float) : ConnectCommand
    data class Speed(val value: Float) : ConnectCommand
    data class Like(val value: Boolean) : ConnectCommand
    data class Sleep(val minutes: Int, val endOfTrack: Boolean) : ConnectCommand
    data class Output(val id: String) : ConnectCommand
    data class Preset(val id: String) : ConnectCommand
    data class Play(val tracks: List<ConnectTrack>, val index: Int, val positionMs: Long, val playing: Boolean) : ConnectCommand
    data class Add(val tracks: List<ConnectTrack>, val index: Int) : ConnectCommand
    data class Remove(val index: Int) : ConnectCommand
    data class Move(val from: Int, val to: Int) : ConnectCommand
    data object Next : ConnectCommand
    data object Previous : ConnectCommand
    data object Clear : ConnectCommand
    data object Stop : ConnectCommand
}

interface ConnectHost {
    fun status(): ConnectStatus
    fun execute(command: ConnectCommand)
    fun artwork(id: String): ByteArray? = null
}

object ConnectJson {
    private const val MAX_TRACKS = 500

    fun encode(command: ConnectCommand): JsonObject = JsonObject().apply {
        addProperty("version", RENDERER_PROTOCOL_VERSION)
        val type = when (command) {
            is ConnectCommand.Playing -> { addProperty("value", command.value); "playing" }
            is ConnectCommand.Seek -> { addProperty("positionMs", command.positionMs); "seek" }
            is ConnectCommand.Jump -> { addProperty("index", command.index); "jump" }
            is ConnectCommand.Shuffle -> { addProperty("value", command.value); "shuffle" }
            is ConnectCommand.Repeat -> { addProperty("mode", command.mode); "repeat" }
            is ConnectCommand.Volume -> { addProperty("value", command.value); "volume" }
            is ConnectCommand.Speed -> { addProperty("value", command.value); "speed" }
            is ConnectCommand.Like -> { addProperty("value", command.value); "like" }
            is ConnectCommand.Sleep -> { addProperty("minutes", command.minutes); addProperty("endOfTrack", command.endOfTrack); "sleep" }
            is ConnectCommand.Output -> { addProperty("id", command.id); "output" }
            is ConnectCommand.Preset -> { addProperty("id", command.id); "preset" }
            is ConnectCommand.Play -> {
                add("tracks", tracks(command.tracks)); addProperty("index", command.index)
                addProperty("positionMs", command.positionMs); addProperty("playing", command.playing); "play"
            }
            is ConnectCommand.Add -> { add("tracks", tracks(command.tracks)); addProperty("index", command.index); "add" }
            is ConnectCommand.Remove -> { addProperty("index", command.index); "remove" }
            is ConnectCommand.Move -> { addProperty("from", command.from); addProperty("to", command.to); "move" }
            ConnectCommand.Next -> "next"
            ConnectCommand.Previous -> "previous"
            ConnectCommand.Clear -> "clear"
            ConnectCommand.Stop -> "stop"
        }
        addProperty("type", type)
    }

    fun command(value: JsonObject): ConnectCommand {
        if (value.int("version") != RENDERER_PROTOCOL_VERSION) throw RendererException("unsupported_version", "Update both Aurora devices.", 409)
        return when (value.text("type", 24)) {
            "playing" -> ConnectCommand.Playing(value.bool("value"))
            "seek" -> ConnectCommand.Seek(value.long("positionMs").also { if (it !in 0..MAX_POSITION_MS) invalid() })
            "jump" -> ConnectCommand.Jump(index(value, "index"))
            "shuffle" -> ConnectCommand.Shuffle(value.bool("value"))
            "repeat" -> ConnectCommand.Repeat(value.int("mode").also { if (it !in 0..2) invalid() })
            "volume" -> ConnectCommand.Volume(fraction(value, 0f, 1f))
            "speed" -> ConnectCommand.Speed(fraction(value, 0.25f, 4f))
            "like" -> ConnectCommand.Like(value.bool("value"))
            "sleep" -> ConnectCommand.Sleep(value.int("minutes").also { if (it !in 0..24 * 60) invalid() }, value.bool("endOfTrack"))
            "output" -> ConnectCommand.Output(value.text("id", 256))
            "preset" -> ConnectCommand.Preset(value.text("id", 64))
            "play" -> {
                val tracks = tracks(value)
                if (tracks.isEmpty()) invalid()
                val start = index(value, "index").also { if (it !in tracks.indices) invalid() }
                ConnectCommand.Play(tracks, start, value.long("positionMs").also { if (it !in 0..MAX_POSITION_MS) invalid() }, value.bool("playing"))
            }
            "add" -> ConnectCommand.Add(tracks(value).also { if (it.isEmpty()) invalid() },
                value.int("index").also { if (it < -1 || it > 100_000) invalid() })
            "remove" -> ConnectCommand.Remove(index(value, "index"))
            "move" -> ConnectCommand.Move(index(value, "from"), index(value, "to"))
            "next" -> ConnectCommand.Next
            "previous" -> ConnectCommand.Previous
            "clear" -> ConnectCommand.Clear
            "stop" -> ConnectCommand.Stop
            else -> throw RendererException("unsupported_command", "Update both Aurora devices.")
        }
    }

    fun encode(status: ConnectStatus): JsonObject = (RendererJson.gson.toJsonTree(status) as JsonObject).apply {
        addProperty("version", RENDERER_PROTOCOL_VERSION)
    }

    fun status(value: JsonObject): ConnectStatus {
        val queue = value.getAsJsonArray("queue") ?: invalid()
        if (queue.size() > CONNECT_QUEUE_WINDOW) invalid()
        val parsed = queue.map { track(it.asJsonObject) }
        val offset = value.int("offset").coerceAtLeast(0)
        return ConnectStatus(
            deviceId = value.text("deviceId", 64), name = value.text("name", 64), platform = value.text("platform", 16),
            playing = value.bool("playing"), positionMs = value.long("positionMs").coerceAtLeast(0),
            durationMs = value.long("durationMs").coerceAtLeast(0), index = value.int("index").coerceAtLeast(0),
            offset = offset, total = value.int("total").coerceAtLeast(offset + parsed.size), queue = parsed,
            shuffle = value.bool("shuffle"), repeat = value.int("repeat").coerceIn(0, 2),
            volume = number(value, "volume")?.coerceIn(-1f, 1f) ?: -1f,
            speed = number(value, "speed")?.coerceIn(0.25f, 4f) ?: 1f,
            liked = value.bool("liked"), sleepMinutes = value.int("sleepMinutes").coerceIn(0, 24 * 60),
            sleepEndOfTrack = value.bool("sleepEndOfTrack"),
            outputs = options(value.getAsJsonArray("outputs")), output = value.text("output", 256),
            presets = options(value.getAsJsonArray("presets")),
            error = value.get("error")?.takeUnless { it.isJsonNull }?.asString?.take(160),
        )
    }

    private fun tracks(values: List<ConnectTrack>): JsonArray = RendererJson.gson.toJsonTree(values).asJsonArray

    private fun tracks(value: JsonObject): List<ConnectTrack> {
        val items = value.getAsJsonArray("tracks") ?: invalid()
        if (items.size() > MAX_TRACKS) invalid()
        return items.map { track(it.asJsonObject) }
    }

    private fun track(value: JsonObject) = ConnectTrack(
        value.text("id", 512).also { if (it.isEmpty()) invalid() }, value.text("title", 512), value.text("artist", 512),
        value.text("album", 512), value.int("durationSec").coerceIn(0, 7 * 24 * 3600),
        value.text("albumId", 512), value.text("artistId", 512),
    )

    private fun options(values: JsonArray?): List<ConnectOption> {
        if (values == null || values.size() > 64) invalid()
        return values.map { it.asJsonObject.let { option -> ConnectOption(option.text("id", 256), option.text("name", 128)) } }
    }

    private fun index(value: JsonObject, key: String): Int = value.int(key).also { if (it !in 0..100_000) invalid() }

    private fun number(value: JsonObject, key: String): Float? =
        value.get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat?.takeIf { it.isFinite() }

    private fun fraction(value: JsonObject, min: Float, max: Float): Float =
        number(value, "value")?.takeIf { it in min..max } ?: invalid()

    private fun invalid(): Nothing = throw RendererException("invalid_request", "Invalid remote control request.")
    private const val MAX_POSITION_MS = 7 * 24 * 60 * 60 * 1000L
}

// the host plays from its own sources, so a track only plays where that device can find it
suspend fun MusicRepository.resolveConnectTracks(tracks: List<ConnectTrack>): List<Song> = coroutineScope {
    val gate = Semaphore(8)
    tracks.map { track -> async { gate.withPermit { runCatching { songFor(track.id) }.getOrNull()?.takeIf { it.streamUrl.isNotBlank() } } } }
        .awaitAll().filterNotNull()
}
