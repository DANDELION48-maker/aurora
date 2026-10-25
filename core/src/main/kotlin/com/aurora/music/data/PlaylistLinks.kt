package com.aurora.music.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader

// playlists and albums shared from other apps, read from the pages and public apis anyone can open
class PlaylistLinks(private val http: OkHttpClient) {

    enum class Service { SPOTIFY, APPLE_MUSIC, DEEZER, TIDAL, YOUTUBE, SOUNDCLOUD }

    data class Link(val service: Service, val kind: String, val id: String, val url: String)

    fun parse(input: String): Link? {
        val text = input.trim()
        Regex("^spotify:(playlist|album):([A-Za-z0-9]+)").find(text)?.let { return Link(Service.SPOTIFY, it.groupValues[1], it.groupValues[2], text) }
        val url = (if ("://" in text) text else "https://$text").toHttpUrlOrNull() ?: return null
        val host = url.host.removePrefix("www.").removePrefix("m.")
        val parts = url.pathSegments.filter { it.isNotBlank() }
        fun after(vararg kinds: String): Pair<String, String>? {
            val i = parts.indexOfFirst { it in kinds }
            return if (i >= 0) parts.getOrNull(i + 1)?.let { parts[i] to it } else null
        }
        return when {
            host == "open.spotify.com" || host == "play.spotify.com" -> after("playlist", "album")?.let { (k, id) -> Link(Service.SPOTIFY, k, id, text) }
            host.endsWith("music.apple.com") || host == "itunes.apple.com" -> {
                val i = parts.indexOfFirst { it == "playlist" || it == "album" }
                if (i < 0) null else Link(Service.APPLE_MUSIC, parts[i], parts.last(), url.toString())
            }
            host.endsWith("deezer.com") -> after("playlist", "album")?.let { (k, id) -> Link(Service.DEEZER, k, id, text) }
            host == "link.deezer.com" || host == "deezer.page.link" -> Link(Service.DEEZER, "", "", url.toString())
            host.endsWith("tidal.com") -> after("playlist", "album")?.let { (k, id) -> Link(Service.TIDAL, k, id, text) }
            host.endsWith("youtube.com") || host == "youtu.be" -> {
                val list = url.queryParameter("list")
                when {
                    list != null -> Link(Service.YOUTUBE, "playlist", list, text)
                    parts.firstOrNull() == "browse" && parts.getOrNull(1)?.startsWith("MPRE") == true -> Link(Service.YOUTUBE, "album", parts[1], text)
                    else -> null
                }
            }
            host.endsWith("soundcloud.com") -> if (host == "on.soundcloud.com" || "sets" in parts || "albums" in parts) Link(Service.SOUNDCLOUD, "playlist", "", url.newBuilder().query(null).build().toString()) else null
            else -> null
        }
    }

    // youtube and soundcloud lists are read by Aurora's own clients, the rest here
    suspend fun read(link: Link): ImportedPlaylist = withContext(Dispatchers.IO) {
        when (link.service) {
            Service.SPOTIFY -> spotify(link)
            Service.APPLE_MUSIC -> apple(link)
            Service.DEEZER -> deezer(link)
            Service.TIDAL -> tidal(link)
            else -> throw ImportException(ImportFailure.UNSUPPORTED)
        }
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en").build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ImportException(ImportFailure.UNREADABLE, "HTTP ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    private fun finalUrl(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", UA).build()
        return http.newCall(request).execute().use { it.request.url.toString() }
    }

    // the embed page carries the first hundred tracks without an account
    private fun spotify(link: Link): ImportedPlaylist {
        val html = get("https://open.spotify.com/embed/${link.kind}/${link.id}")
        val data = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw ImportException(ImportFailure.UNREADABLE)
        val entity = JsonParser.parseString(data).path("props", "pageProps", "state", "data", "entity")?.asJsonObject
            ?: throw ImportException(ImportFailure.UNREADABLE)
        val album = if (link.kind == "album") entity.text("name") else ""
        val tracks = entity.getAsJsonArray("trackList")?.mapNotNull { e ->
            val t = e.asJsonObject
            ImportTrack(t.text("title"), t.text("subtitle").replace(' ', ' '), album, (t.long("duration") / 1000).toInt())
        }.orEmpty()
        return ImportedPlaylist(entity.text("name").ifBlank { entity.text("title") }, tracks)
    }

    private fun apple(link: Link): ImportedPlaylist {
        val html = get(link.url)
        val data = Regex("<script[^>]*id=\"serialized-server-data\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw ImportException(ImportFailure.UNREADABLE)
        val tracks = ArrayList<ImportTrack>()
        fun walk(e: JsonElement) {
            when {
                e.isJsonObject -> {
                    val o = e.asJsonObject
                    if (o.has("title") && o.has("artistName") && o.has("duration")) {
                        val album = o.getAsJsonArray("tertiaryLinks")?.firstOrNull()?.asJsonObject?.text("title").orEmpty()
                        tracks += ImportTrack(o.text("title"), o.text("artistName"), album, (o.long("duration") / 1000).toInt())
                    } else o.entrySet().forEach { walk(it.value) }
                }
                e.isJsonArray -> e.asJsonArray.forEach(::walk)
            }
        }
        walk(JsonParser.parseString(data))
        val name = meta(html, "og:title")?.substringBefore(" by ")?.removeSuffix(" on Apple Music")?.trim().orEmpty()
        return ImportedPlaylist(name, tracks)
    }

    private fun deezer(link: Link): ImportedPlaylist {
        val resolved = if (link.id.isBlank()) parse(finalUrl(link.url))?.takeIf { it.service == Service.DEEZER && it.id.isNotBlank() }
            ?: throw ImportException(ImportFailure.UNREADABLE) else link
        val head = JsonParser.parseString(get("https://api.deezer.com/${resolved.kind}/${resolved.id}")).asJsonObject
        if (head.has("error")) throw ImportException(ImportFailure.UNREADABLE)
        val album = if (resolved.kind == "album") head.text("title") else ""
        val tracks = ArrayList<ImportTrack>()
        var next: String? = "https://api.deezer.com/${resolved.kind}/${resolved.id}/tracks?limit=200"
        var pages = 0
        while (next != null && pages++ < 50) {
            val page = JsonParser.parseString(get(next)).asJsonObject
            page.getAsJsonArray("data")?.forEach { e ->
                val t = e.asJsonObject
                tracks += ImportTrack(t.text("title"), t.getAsJsonObject("artist")?.text("name").orEmpty(),
                    album.ifBlank { t.getAsJsonObject("album")?.text("title").orEmpty() }, t.long("duration").toInt())
            }
            next = page.text("next").ifBlank { null }
        }
        return ImportedPlaylist(head.text("title"), tracks)
    }

    // the embed player ships each track's title, artist and album with the page, though not its length
    private fun tidal(link: Link): ImportedPlaylist {
        val html = get("https://embed.tidal.com/${link.kind}s/${link.id}")
        val start = html.indexOf("tidalMediaSessionMetadata").takeIf { it >= 0 }?.let { html.indexOf('{', it) }
            ?: throw ImportException(ImportFailure.UNREADABLE)
        val reader = JsonReader(StringReader(html.substring(start))).apply { isLenient = true }
        val tracks = JsonParser.parseReader(reader).asJsonObject.entrySet().map { (_, v) ->
            val t = v.asJsonObject
            ImportTrack(t.text("title"), t.text("artist"), t.text("album"))
        }
        val name = runCatching { meta(get("https://tidal.com/${link.kind}/${link.id}"), "og:title") }.getOrNull()
            ?: Regex("<title>(.*?)</title>").find(html)?.groupValues?.get(1).orEmpty()
        return ImportedPlaylist(name.trim(), tracks)
    }

    private fun meta(html: String, property: String) =
        Regex("<meta[^>]+property=\"$property\"[^>]+content=\"([^\"]*)\"").find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&quot;", "\"")?.replace("&#39;", "'")?.replace("&#x27;", "'")

    private fun JsonElement.path(vararg keys: String): JsonElement? =
        keys.fold(this as JsonElement?) { e, k -> e?.takeIf { it.isJsonObject }?.asJsonObject?.get(k) }

    private fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.long(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()?.toLong() ?: 0L

    private companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    }
}
