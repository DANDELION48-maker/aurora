package com.aurora.music.playback.network.endpoint

import com.aurora.music.playback.network.endpoint.RendererJson.bool
import com.aurora.music.playback.network.endpoint.RendererJson.int
import com.aurora.music.playback.network.endpoint.RendererJson.long
import com.aurora.music.playback.network.endpoint.RendererJson.text
import com.google.gson.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.Proxy
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RendererClient private constructor(
    private val endpoint: EndpointAddress,
    private val token: String?,
) : AutoCloseable {
    constructor(address: String, fingerprint: String) : this(EndpointAddress.parse("$address#$fingerprint"), null)
    constructor(address: EndpointAddress) : this(EndpointAddress.parse(address.pairingAddress), null)
    constructor(paired: PairedRenderer) : this(EndpointAddress.parse("${paired.address}#${paired.fingerprint}"), paired.token)

    private val trust = EndpointTls.pinnedTrust(endpoint.fingerprint)
    private val tls = SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf(trust), SecureRandom()) }
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(tls.socketFactory, trust)
        .hostnameVerifier { _, session ->
            runCatching { EndpointTls.fingerprint(session.peerCertificates.first() as X509Certificate) == endpoint.fingerprint }.getOrDefault(false)
        }
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    fun pair(name: String, code: String): PairedRenderer {
        val response = call("/v1/pair", JsonObject().apply {
            addProperty("version", RENDERER_PROTOCOL_VERSION)
            addProperty("name", name.trim().take(64))
            addProperty("code", code.trim())
        })
        val token = response.text("token", 43)
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) throw RendererException("invalid_response", "Invalid renderer response.")
        val addresses = response.getAsJsonArray("addresses")?.mapNotNull { item ->
            runCatching { EndpointAddress.parse("${item.asString}#${endpoint.fingerprint}").address }.getOrNull()
        }.orEmpty().filter { it != endpoint.address }.take(8)
        return PairedRenderer(response.text("id", 64), response.text("name", 64), endpoint.address,
            endpoint.fingerprint, response.text("clientId", 64), token, addresses)
    }

    fun info(): JsonObject = call("/v1/info")

    suspend fun connectStatus(tag: String? = null, positionMs: Long = 0): Pair<ConnectStatus, String> {
        val path = if (tag == null) "/v1/connect" else "/v1/connect?wait=${java.net.URLEncoder.encode(tag, "UTF-8")}&pos=$positionMs"
        return parseConnect(callCancellable(path))
    }

    suspend fun connectCommand(command: ConnectCommand, requestId: String = UUID.randomUUID().toString()): Pair<ConnectStatus, String> =
        parseConnect(callCancellable("/v1/connect", ConnectJson.encode(command).apply { addProperty("requestId", requestId) }))

    fun artwork(id: String): ByteArray? = try {
        client.newCall(request("/v1/connect/art?id=${java.net.URLEncoder.encode(id, "UTF-8")}", null)).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.byteStream()?.use { input -> bounded(input, 8_388_608) }?.takeIf { it.isNotEmpty() }
        }
    } catch (_: Exception) { null }

    private fun parseConnect(value: JsonObject): Pair<ConnectStatus, String> = try {
        ConnectJson.status(value) to value.text("tag", 16)
    } catch (error: RendererException) { throw error } catch (_: Exception) {
        throw RendererException("invalid_response", "Invalid remote control response.")
    }

    fun status(): RendererStatus = parseStatus(call("/v1/status"))

    suspend fun statusCancellable(): RendererStatus = parseStatus(callCancellable("/v1/status"))

    fun command(command: RendererCommand, requestId: String = UUID.randomUUID().toString()): RendererStatus =
        parseStatus(call("/v1/command", RendererJson.encode(command).apply { addProperty("requestId", requestId) }))

    suspend fun commandCancellable(command: RendererCommand, requestId: String = UUID.randomUUID().toString()): RendererStatus =
        parseStatus(callCancellable("/v1/command", RendererJson.encode(command).apply { addProperty("requestId", requestId) }))

    private fun request(path: String, body: JsonObject?): Request {
        val request = Request.Builder().url(endpoint.address + path)
        token?.let { request.header("Authorization", "Bearer $it") }
        body?.let { request.post(it.toString().toRequestBody("application/json".toMediaType())) }
        return request.build()
    }

    private fun call(path: String, body: JsonObject? = null): JsonObject = try {
        readResponse(client.newCall(request(path, body)).execute())
    } catch (error: Exception) { throw transportError(error) }

    private suspend fun callCancellable(path: String, body: JsonObject? = null): JsonObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request(path, body))
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(transportError(error))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = readResponse(response)
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(transportError(error))
                }
            }
        })
    }

    private fun bounded(source: java.io.InputStream, limit: Int): ByteArray = java.io.ByteArrayOutputStream().use { output ->
        val buffer = ByteArray(8192)
        while (true) {
            val count = source.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw RendererException("invalid_response", "Renderer response is too large.")
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private fun readResponse(response: Response): JsonObject = response.use {
        val source = response.body?.byteStream() ?: throw RendererException("invalid_response", "Empty renderer response.")
        val bytes = bounded(source, 1_048_576)
        val result = RendererJson.parse(bytes)
        if (!response.isSuccessful) {
            val code = runCatching { result.text("error", 64) }.getOrDefault("renderer_failed")
            val message = when (code) {
                "unauthorized" -> "Pair with this renderer again."
                "invalid_code" -> "The pairing code is incorrect."
                "pairing_closed" -> "Open a new pairing code on the renderer."
                "client_limit" -> "Remove a paired controller first."
                "renderer_busy" -> "The renderer is loading audio. Try again."
                "unsupported_version" -> "Update both Aurora devices."
                "source_unavailable" -> "The renderer could not load the audio."
                "source_too_large" -> "Queue exceeds the 512 MB limit."
                "storage_full" -> "Not enough storage on the renderer."
                "queue_changed" -> "The renderer queue changed. Reconnect."
                "unsupported_route" -> "Select an available output on the renderer."
                "connect_disabled" -> "Remote control is off on that device."
                "receiver_disabled" -> "That device only accepts remote control."
                "not_available" -> "Those songs aren't available on that device."
                else -> "The renderer could not complete this request."
            }
            throw RendererException(code, message, response.code)
        }
        if (result.get("version")?.asInt != RENDERER_PROTOCOL_VERSION &&
            result.get("protocolVersion")?.asInt != RENDERER_PROTOCOL_VERSION
        ) throw RendererException("unsupported_version", "Update both Aurora devices.", 409)
        result
    }

    private fun transportError(error: Exception): RendererException = when (error) {
        is RendererException -> error
        is javax.net.ssl.SSLException -> RendererException("certificate_mismatch", "Check the renderer's pairing address and pair again.", 502)
        else -> RendererException("renderer_unreachable", "Cannot reach the renderer. Check its address and Wi-Fi.", 503)
    }

    private fun parseStatus(value: JsonObject): RendererStatus {
        val queue = value.getAsJsonArray("queue") ?: throw RendererException("invalid_response", "Invalid renderer queue.")
        if (queue.size() > 100) throw RendererException("invalid_response", "Renderer queue is too large.")
        val route = value.getAsJsonObject("route") ?: throw RendererException("invalid_response", "Invalid renderer route.")
        return RendererStatus(
            rendererId = value.text("rendererId", 64), name = value.text("name", 64),
            role = value.text("role", 16).also { require(it == "renderer") },
            playing = value.bool("playing"), playWhenReady = value.bool("playWhenReady"),
            positionMs = value.long("positionMs").coerceAtLeast(0), durationMs = value.long("durationMs").coerceAtLeast(0),
            volume = value.get("volume").asFloat.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
            volumeSteps = value.get("volumeSteps")?.takeUnless { it.isJsonNull }?.let {
                value.int("volumeSteps").also { steps -> require(steps in 0..1000) }
            } ?: 100,
            queueIndex = value.int("queueIndex").coerceAtLeast(0),
            repeatMode = value.get("repeatMode")?.takeUnless { it.isJsonNull }?.asInt?.takeIf { it in 0..2 } ?: 0,
            queue = queue.map { item -> item.asJsonObject.let {
                EndpointQueueItem(it.text("id", 128), it.text("title", 512), it.text("artist", 512), it.long("durationMs").coerceAtLeast(0))
            } },
            route = EndpointRoute(route.text("id", 64), route.text("name", 128), route.text("state", 32),
                route.int("sampleRateHz").coerceAtLeast(0), route.int("bitDepth").coerceIn(0, 64), route.bool("processed")),
            error = value.get("error")?.takeUnless { it.isJsonNull }?.asString?.take(160),
        )
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
