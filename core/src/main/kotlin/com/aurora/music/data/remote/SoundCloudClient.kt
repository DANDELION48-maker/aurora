package com.aurora.music.data.remote

import com.aurora.music.data.MediaAccountExpiredException
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

fun interface SoundCloudTransport {
    // path is an api-v2 path or an absolute next_href from a previous page
    suspend fun get(path: String): JsonElement
}

/** SoundCloud's web api: the signed-in web player's oauth token plus the client id its scripts carry. */
class SoundCloudClient(
    private val session: () -> SoundCloudWebSession,
    private val onRefreshed: (SoundCloudWebSession) -> Unit = {},
    http: OkHttpClient = OkHttpClient(),
) : SoundCloudTransport {
    private val http = http.newBuilder().callTimeout(30, TimeUnit.SECONDS).build()
    private val idLock = Mutex()
    private val loginLock = Mutex()
    @Volatile private var clientId = ""
    @Volatile private var current: SoundCloudWebSession? = null
    private fun auth() = (current ?: session()).validate()

    // shortcut: the id is scraped once per process, persist it if cold starts feel slow
    private suspend fun clientId(refresh: Boolean): String = idLock.withLock {
        if (refresh || clientId.isBlank()) clientId = scrapeClientId()
        clientId
    }

    private fun scrapeClientId(): String {
        val page = fetch(Request.Builder().url(WEB).header("User-Agent", DESKTOP_UA).build())
        // the id sits in one of the last asset bundles
        val scripts = Regex("""https://a-v2\.sndcdn\.com/assets/[^"]+\.js""").findAll(page).map { it.value }.toList().asReversed()
        for (script in scripts) {
            Regex("""client_id[:=]"?([a-zA-Z0-9]{32})""").find(fetch(Request.Builder().url(script).build()))?.let { return it.groupValues[1] }
        }
        throw IOException("Could not reach SoundCloud. Please retry.")
    }

    private fun fetch(request: Request): String = http.newCall(request).execute().use {
        if (!it.isSuccessful) throw IOException("SoundCloud request failed (${it.code}). Try again later.")
        it.body?.string().orEmpty()
    }

    // a stale client id is refused like a bad login, so retry once with a fresh one before refreshing the login
    override suspend fun get(path: String): JsonElement = withContext(Dispatchers.IO) {
        val base = url(path)
        if (auth().expiresAt - System.currentTimeMillis() / 1000 < 300) refreshLogin(auth().token)
        attempt(base, false) ?: attempt(base, true)
            ?: (if (refreshLogin(auth().token)) attempt(base, false) else null)
            ?: throw MediaAccountExpiredException()
    }

    // the web player swaps its http-only refresh cookie for a new day-long token the same way
    private suspend fun refreshLogin(stale: String): Boolean = loginLock.withLock {
        val auth = auth()
        if (auth.token != stale) return@withLock true
        if (!auth.canRefresh) return@withLock false
        val request = Request.Builder().url(auth.refreshUrl).post(ByteArray(0).toRequestBody())
            .header("Cookie", auth.cookie).header("Origin", auth.origin).header("Referer", "${auth.origin}/")
            .apply { auth.userAgent.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }.build()
        val refreshed = runCatching { http.newCall(request).execute() }.getOrNull()?.use {
            if (it.isSuccessful) auth.refreshed(it.headers("Set-Cookie")) else null
        }?.takeIf { it.token.isNotBlank() && it.token != stale } ?: return@withLock false
        current = refreshed
        onRefreshed(refreshed)
        true
    }

    private suspend fun attempt(base: HttpUrl, refresh: Boolean): JsonElement? {
        val request = Request.Builder().url(base.newBuilder().setQueryParameter("client_id", clientId(refresh)).build())
            .header("Accept", "application/json").header("Origin", WEB).header("Referer", "$WEB/")
            .header("Authorization", "OAuth ${auth().token}")
            .apply { auth().userAgent.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }.build()
        val response = runCatching { http.newCall(request).execute() }
            .getOrElse { throw IOException("Could not connect to SoundCloud. Please retry.") }
        return response.use {
            if (it.code == 401 || (it.code == 403 && !refresh)) return null
            if (it.code == 403) throw IOException("SoundCloud did not allow this request. Please try again.")
            if (it.code == 404) throw IOException("SoundCloud could not find this item.")
            if (!it.isSuccessful) throw IOException("SoundCloud request failed (${it.code}). Try again later.")
            runCatching { JsonParser.parseString(it.body?.string()) }
                .getOrElse { throw IOException("SoundCloud returned an unreadable response.") }
        }
    }

    companion object {
        const val WEB = "https://soundcloud.com"
        const val API = "https://api-v2.soundcloud.com"
        // the mobile user agent is redirected to m.soundcloud.com, whose page carries no asset list
        private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

        // next_href links are followed only on the api host
        fun url(path: String): HttpUrl {
            if (!path.startsWith("https://")) {
                require(path.startsWith("/") && ".." !in path)
                return "$API$path".toHttpUrl()
            }
            val url = path.toHttpUrlOrNull()
            require(url != null && url.scheme == "https" && url.host == "api-v2.soundcloud.com" && url.port == 443)
            return url
        }
    }
}
