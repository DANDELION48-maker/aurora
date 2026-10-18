package com.aurora.music.data.remote

import com.aurora.music.data.Podcast
import com.aurora.music.data.PodcastEpisode
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

private data class ItunesResult(val results: List<ItunesPodcast>? = emptyList())
private data class ItunesPodcast(
    @SerializedName("collectionName") val collectionName: String? = "",
    @SerializedName("artistName") val artistName: String? = "",
    @SerializedName("feedUrl") val feedUrl: String? = "",
    @SerializedName("artworkUrl600") val artworkUrl600: String? = "",
    @SerializedName("artworkUrl100") val artworkUrl100: String? = "",
)

class PodcastClient(base: OkHttpClient = OkHttpClient()) {
    private val http = base.newBuilder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // null on transport failure empty on no hits
    suspend fun search(query: String, limit: Int = 40): List<Podcast>? = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://itunes.apple.com/search".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("media", "podcast")
            .addQueryParameter("term", query)
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = getString(url.toString()) ?: return@withContext null
        runCatching {
            gson.fromJson(body, ItunesResult::class.java).results.orEmpty().mapNotNull { it.toPodcast() }
        }.getOrDefault(emptyList())
    }

    suspend fun episodes(feedUrl: String): FeedContent = withContext(Dispatchers.IO) {
        if (feedUrl.isBlank()) return@withContext FeedContent()
        val xml = getString(feedUrl) ?: return@withContext FeedContent()
        runCatching { parseFeed(xml) }.getOrDefault(FeedContent())
    }

    private fun getString(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
            .execute().use { if (it.isSuccessful) it.body?.string() else null }
    }.getOrNull()

    private fun ItunesPodcast.toPodcast(): Podcast? {
        val feed = feedUrl?.takeIf { it.isNotBlank() } ?: return null
        return Podcast(
            feedUrl = feed,
            title = collectionName,
            author = artistName,
            imageUrl = artworkUrl600?.takeIf { it.isNotBlank() } ?: artworkUrl100,
        )
    }

    data class FeedContent(
        val title: String = "",
        val author: String = "",
        val imageUrl: String = "",
        val description: String = "",
        val episodes: List<PodcastEpisode> = emptyList(),
    )

    private fun parseFeed(xml: String): FeedContent {
        val parser = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        }.createXMLStreamReader(xml.reader())

        var channelTitle = ""
        var channelAuthor = ""
        var channelImage = ""
        var channelDesc = ""
        val episodes = mutableListOf<PodcastEpisode>()
        var inItem = false

        var title = ""; var audioUrl = ""; var guid = ""; var pubDate = ""
        var duration = ""; var image = ""; var desc = ""
        // distinguish channel-level <image><url> from inside an item
        var inChannelImage = false

        var event = parser.eventType
        while (event != XMLStreamConstants.END_DOCUMENT) {
            when (event) {
                XMLStreamConstants.START_ELEMENT -> {
                    val name = parser.tagName()
                    when {
                        name.equals("item", true) -> {
                            inItem = true
                            title = ""; audioUrl = ""; guid = ""; pubDate = ""; duration = ""; image = ""; desc = ""
                        }
                        name.equals("image", true) && !inItem -> inChannelImage = true
                        name.equals("enclosure", true) && inItem -> {
                            val type = parser.attribute("type").orEmpty()
                            val url = parser.attribute("url").orEmpty()
                            // take first audio or type-less enclosure some feeds ship a video enclosure first
                            if (url.isNotBlank() && (type.isBlank() || type.startsWith("audio")) && audioUrl.isBlank()) audioUrl = url
                        }
                        name.equals("itunes:image", true) -> {
                            val href = parser.attribute("href").orEmpty()
                            if (href.isNotBlank()) { if (inItem) image = href else if (channelImage.isBlank()) channelImage = href }
                        }
                        name.equals("media:thumbnail", true) && inItem -> {
                            val url = parser.attribute("url").orEmpty()
                            if (url.isNotBlank() && image.isBlank()) image = url
                        }
                        name.equals("media:content", true) && inItem && audioUrl.isBlank() -> {
                            val url = parser.attribute("url").orEmpty()
                            val type = parser.attribute("type").orEmpty()
                            if (url.isNotBlank() && type.startsWith("audio")) audioUrl = url
                        }
                        name.equals("title", true) -> { val t = parser.elementText.trim(); if (inItem) title = t else if (!inChannelImage && channelTitle.isBlank()) channelTitle = t }
                        name.equals("itunes:author", true) && !inItem -> { val a = parser.elementText.trim(); if (channelAuthor.isBlank()) channelAuthor = a }
                        name.equals("url", true) && inChannelImage -> { val u = parser.elementText.trim(); if (channelImage.isBlank()) channelImage = u }
                        name.equals("guid", true) && inItem -> guid = parser.elementText.trim()
                        name.equals("pubDate", true) && inItem -> pubDate = parser.elementText.trim()
                        name.equals("itunes:duration", true) && inItem -> duration = parser.elementText.trim()
                        name.equals("description", true) -> { val d = parser.elementText.trim(); if (inItem) { if (desc.isBlank()) desc = d } else if (channelDesc.isBlank()) channelDesc = d }
                        name.equals("itunes:summary", true) && inItem && desc.isBlank() -> desc = parser.elementText.trim()
                    }
                }
                XMLStreamConstants.END_ELEMENT -> {
                    val name = parser.tagName()
                    when {
                        name.equals("image", true) && inChannelImage -> inChannelImage = false
                        name.equals("item", true) -> {
                            inItem = false
                            if (audioUrl.isNotBlank()) {
                                episodes += PodcastEpisode(
                                    id = guid.ifBlank { audioUrl },
                                    title = title,
                                    audioUrl = audioUrl,
                                    imageUrl = image,
                                    durationSec = parseDuration(duration),
                                    pubDateMs = parsePubDate(pubDate),
                                    description = desc,
                                    podcastTitle = channelTitle,
                                )
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        // backfill show title onto episodes parsed before channel title appeared
        val fixed = if (channelTitle.isNotBlank()) episodes.map { if (it.podcastTitle.isBlank()) it.copy(podcastTitle = channelTitle) else it } else episodes
        return FeedContent(channelTitle, channelAuthor, channelImage, channelDesc, fixed)
    }

    // feeds use the conventional itunes: and media: prefixes, so match on the prefixed name
    private fun XMLStreamReader.tagName(): String = if (prefix.isNullOrEmpty()) localName else "$prefix:$localName"

    private fun XMLStreamReader.attribute(name: String): String? =
        (0 until attributeCount).firstOrNull { getAttributeLocalName(it) == name && getAttributePrefix(it).isNullOrEmpty() }?.let(::getAttributeValue)

    // itunes:duration may be raw seconds MM:SS or HH:MM:SS
    private fun parseDuration(raw: String): Int {
        if (raw.isBlank()) return 0
        if (!raw.contains(":")) return raw.toFloatOrNull()?.toInt() ?: 0
        val parts = raw.split(":").map { it.trim().toIntOrNull() ?: 0 }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> 0
        }
    }

    private fun parsePubDate(raw: String): Long {
        if (raw.isBlank()) return 0
        for (fmt in PUB_DATE_FORMATS) {
            val ms = runCatching { SimpleDateFormat(fmt, Locale.US).parse(raw)?.time }.getOrNull()
            if (ms != null) return ms
        }
        return 0
    }

    private companion object {
        const val USER_AGENT = "Aurora/1.0 ( https://github.com/aurora-music/aurora )"
        val PUB_DATE_FORMATS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEE, dd MMM yyyy HH:mm Z",
        )
    }
}
