package com.aurora.music.data

import com.aurora.music.data.remote.PresenceThrottle
import com.aurora.music.desktop.platform.HostPlatform
import com.aurora.music.util.AppLog
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

// rich presence through the local discord client's ipc pipe, no token needed
class DiscordIpc(
    private val onUser: (String?) -> Unit,
    private val open: () -> IpcPipe? = ::openDiscordPipe,
    private val retryMs: Long = 15_000,
) : AutoCloseable {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val throttle = PresenceThrottle<JsonObject>()
    private var clientId: String? = null
    private var watchJob: Job? = null
    private var sendJob: Job? = null
    private var pipe: IpcPipe? = null
    private var nonce = 0L

    @Synchronized fun start(id: String) {
        if (id == clientId) return
        clientId = id
        watchJob?.cancel()
        watchJob = scope.launch {
            disconnect()
            while (isActive) {
                if (pipe?.alive?.invoke() == false) disconnect()
                if (pipe == null && connect(id)) push(synchronized(this@DiscordIpc) { throttle.latest })
                delay(retryMs)
            }
        }
    }

    @Synchronized fun stop(): Job {
        clientId = null
        watchJob?.cancel(); watchJob = null
        sendJob?.cancel(); sendJob = null
        return scope.launch { disconnect() }
    }

    @Synchronized fun update(activity: JsonObject?) {
        throttle.offer(activity)
        if (clientId == null || sendJob != null) return
        sendJob = scope.launch {
            delay(synchronized(this@DiscordIpc) { throttle.delayMs(now()) })
            push(synchronized(this@DiscordIpc) { sendJob = null; throttle.latest })
        }
    }

    override fun close() { runBlocking { withTimeoutOrNull(1_000) { stop().join() } } }

    private fun push(activity: JsonObject?) {
        if (pipe == null) return
        if (!setActivity(activity)) {
            // discord restarted, so reconnect right away and leave further retries to the watch loop
            disconnect()
            val id = clientId
            if (id != null && connect(id) && !setActivity(activity)) disconnect()
        }
        synchronized(this) { throttle.sent(now()) }
    }

    private fun connect(id: String): Boolean {
        val p = open() ?: return false
        return try {
            val ready = p.request(OP_HANDSHAKE, JsonObject().apply { addProperty("v", 1); addProperty("client_id", id) })
            val user = ready.getAsJsonObject("data").getAsJsonObject("user")
            pipe = p
            onUser(user.text("global_name").ifBlank { user.text("username") })
            true
        } catch (e: Exception) {
            AppLog.d(TAG, "handshake failed: ${e.message}")
            p.close()
            false
        }
    }

    private fun setActivity(activity: JsonObject?): Boolean = try {
        val args = JsonObject().apply { addProperty("pid", PID); activity?.let { add("activity", it) } }
        val reply = pipe!!.request(OP_FRAME, JsonObject().apply { addProperty("cmd", "SET_ACTIVITY"); add("args", args); addProperty("nonce", (++nonce).toString()) })
        if (reply.text("evt") == "ERROR") AppLog.w(TAG, "SET_ACTIVITY rejected: ${reply.get("data")}")
        true
    } catch (e: Exception) { false }

    private fun disconnect() {
        val p = pipe ?: return
        pipe = null
        runCatching { p.write(OP_CLOSE, "{}") }
        p.close()
        onUser(null)
    }

    private fun now() = System.nanoTime() / 1_000_000

    private companion object {
        const val TAG = "DiscordIpc"
        val PID = ProcessHandle.current().pid()
    }
}

class IpcPipe(private val input: InputStream, private val output: OutputStream, val alive: () -> Boolean, private val resource: Closeable) : Closeable {
    fun write(op: Int, json: String) { output.write(encodeFrame(op, json)); output.flush() }

    fun request(op: Int, json: JsonObject): JsonObject {
        write(op, json.toString())
        while (true) {
            val (code, body) = readFrame(input)
            when (code) {
                OP_FRAME -> return JsonParser.parseString(body).asJsonObject
                OP_PING -> write(OP_PONG, body)
                OP_CLOSE -> throw IOException("discord closed ipc: $body")
            }
        }
    }

    override fun close() { runCatching { resource.close() } }
}

internal const val OP_HANDSHAKE = 0
internal const val OP_FRAME = 1
internal const val OP_CLOSE = 2
internal const val OP_PING = 3
internal const val OP_PONG = 4

internal fun encodeFrame(op: Int, json: String): ByteArray {
    val body = json.toByteArray(Charsets.UTF_8)
    return ByteBuffer.allocate(8 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).put(body).array()
}

internal fun readFrame(input: InputStream): Pair<Int, String> {
    val header = ByteBuffer.wrap(input.readExactly(8)).order(ByteOrder.LITTLE_ENDIAN)
    val op = header.int
    val length = header.int
    if (length !in 0..(1 shl 20)) throw IOException("bad ipc frame length $length")
    return op to String(input.readExactly(length), Charsets.UTF_8)
}

private fun InputStream.readExactly(n: Int): ByteArray = readNBytes(n).also { if (it.size < n) throw EOFException() }

private fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

fun openDiscordPipe(): IpcPipe? = if (HostPlatform.isWindows) windowsPipe() else unixSockets().firstNotNullOfOrNull(::unixPipe)

private fun windowsPipe(): IpcPipe? {
    // listing \\.\pipe\ never connects, unlike probing a pipe path
    fun names() = runCatching { Files.newDirectoryStream(Path.of("\\\\.\\pipe\\"), "discord-ipc-*").use { s -> s.map { it.fileName.toString() }.sorted() } }.getOrDefault(emptyList())
    for (name in names()) {
        val file = runCatching { RandomAccessFile("\\\\.\\pipe\\$name", "rw") }.getOrNull() ?: continue
        return IpcPipe(FileInputStream(file.fd), FileOutputStream(file.fd), { name in names() }, file)
    }
    return null
}

private fun unixSockets(): List<Path> {
    val roots = listOf("XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP").mapNotNull { System.getenv(it) } + "/tmp"
    return roots.distinct().flatMap { root -> listOf("", "app/com.discordapp.Discord", "snap.discord").flatMap { sub -> (0..9).map { Path.of(root, sub, "discord-ipc-$it") } } }
        .filter { Files.exists(it) }
}

// shortcut: a stale socket file left by a crashed discord still counts as alive until the next write fails
internal fun unixPipe(path: Path): IpcPipe? = runCatching {
    val channel = SocketChannel.open(UnixDomainSocketAddress.of(path))
    IpcPipe(Channels.newInputStream(channel), Channels.newOutputStream(channel), { Files.exists(path) }, channel)
}.getOrNull()
