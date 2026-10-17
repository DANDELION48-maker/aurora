package com.aurora.music.desktop.youtube

import com.aurora.music.util.AppLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import org.schabi.newpipe.extractor.downloader.Request as NpRequest
import org.schabi.newpipe.extractor.downloader.Response as NpResponse

// youtube audio for spotify, youtube music and soundcloud fallbacks; every call blocks on the network
class YoutubeResolver(http: OkHttpClient) {
    private val http = http.newBuilder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    private data class Cached(val url: String, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    @Volatile private var initialized = false

    private fun ensureInit() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(Fetcher(http))
                initialized = true
            }
        }
    }

    fun signatureTimestamp(videoId: String): Int? {
        ensureInit()
        return runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
    }

    // aurora-yt://video/<id> plays that video; aurora-yt://<key>?q=..&dur=.. plays the best search match
    fun resolveSentinel(sentinel: String): String? {
        val uri = runCatching { URI(sentinel) }.getOrNull() ?: return null
        if (uri.scheme != "aurora-yt") return null
        val query = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            val name = part.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            name to URLDecoder.decode(part.substringAfter('='), "UTF-8")
        }.toMap()
        return if (uri.host == "video") resolveVideo(uri.path.orEmpty().trim('/').substringAfterLast('/'))
        else resolveSearch(uri.host.orEmpty(), query["q"].orEmpty(), query["dur"]?.toIntOrNull() ?: 0)
    }

    fun resolveSearch(key: String, query: String, durationSec: Int): String? {
        cached("search:$key")?.let { return it }
        if (query.isBlank()) return null
        return runCatching {
            ensureInit()
            val search = ServiceList.YouTube.getSearchExtractor(query, listOf("music_songs"), "").apply { fetchPage() }
            val items = search.initialPage.items.filterIsInstance<StreamInfoItem>()
            val pick = (if (durationSec > 0) items.minByOrNull { abs(it.duration - durationSec) } else null) ?: items.firstOrNull()
                ?: return@runCatching null
            val id = pick.url.substringAfter("v=", "").take(11)
            resolveVideo(id)?.also { remember("search:$key", it) }
        }.getOrElse { AppLog.d(TAG, "search failed: ${it.javaClass.simpleName}"); null }
    }

    fun resolveVideo(videoId: String): String? {
        if (!videoId.matches(VIDEO_ID)) return null
        cached("video:$videoId")?.let { return it }
        return runCatching {
            ensureInit()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val live = info.streamType in setOf(StreamType.LIVE_STREAM, StreamType.AUDIO_LIVE_STREAM)
            val audio = info.audioStreams.filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .sortedByDescending { it.averageBitrate }.distinctBy { it.content }.take(6)
                .firstOrNull { canOpen(it.content) }?.content
            // when the audio-only streams are withheld, the smallest muxed stream still carries the sound
            val muxed = if (audio != null || live) null else info.videoStreams
                .filter { it.isUrl && it.content.isNotBlank() && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .sortedBy { it.resolution.orEmpty().takeWhile(Char::isDigit).toIntOrNull() ?: 9999 }
                .distinctBy { it.content }.take(6).firstOrNull { canOpen(it.content) }?.content
            val url = audio ?: muxed ?: info.hlsUrl.takeIf { it.isNotBlank() } ?: info.dashMpdUrl.takeIf { it.isNotBlank() }
            url?.also { if (!live) remember("video:$videoId", it) }
        }.getOrElse { AppLog.w(TAG, "stream extraction failed for $videoId: ${it.javaClass.simpleName}"); null }
    }

    private fun canOpen(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", USER_AGENT).build()).execute()
            .use { it.code in 200..299 }
    }.getOrDefault(false)

    private fun cached(key: String): String? = cache[key]?.let {
        if (it.expiresAt > System.currentTimeMillis()) it.url else { cache.remove(key, it); null }
    }

    private fun remember(key: String, url: String) {
        val now = System.currentTimeMillis()
        val expires = runCatching {
            URI(url).rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("expire=") }?.substringAfter('=')?.toLongOrNull()?.times(1000)
        }.getOrNull()
        cache.entries.removeIf { it.value.expiresAt <= now }
        if (cache.size >= 256) cache.clear()
        cache[key] = Cached(url, minOf(expires?.minus(60_000) ?: Long.MAX_VALUE, now + 10 * 60_000))
    }

    private class Fetcher(private val client: OkHttpClient) : Downloader() {
        override fun execute(request: NpRequest): NpResponse {
            val builder = Request.Builder().url(request.url())
            request.headers().forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { builder.addHeader(name, it) }
            }
            if (request.headers()["User-Agent"].isNullOrEmpty()) builder.header("User-Agent", USER_AGENT)
            val data = request.dataToSend()
            builder.method(request.httpMethod(), data?.toRequestBody(null, 0, data.size))
            client.newCall(builder.build()).execute().use { response ->
                return NpResponse(response.code, response.message, response.headers.toMultimap(), response.body?.string(), response.request.url.toString())
            }
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private const val TAG = "YtResolver"
        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    }
}
