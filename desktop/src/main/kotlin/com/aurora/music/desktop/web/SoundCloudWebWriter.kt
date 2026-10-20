package com.aurora.music.desktop.web

import com.aurora.music.data.remote.SoundCloudBrowser
import com.aurora.music.data.remote.SoundCloudWebSession
import com.aurora.music.util.AppLog
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A hidden page on soundcloud's own site; its fetches carry the browser identity and bot-check cookie the login earned. */
class SoundCloudWebWriter : SoundCloudBrowser {
    data class Captcha(val origin: String, val url: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, String>>>()
    private val ids = AtomicInteger()
    private var page: WebPage? = null
    private var pageOrigin = ""
    private var idle: Job? = null

    // the check the user solves in a window of its own, while one is waiting
    private val mutableCaptcha = MutableStateFlow<Captcha?>(null)
    val captcha: StateFlow<Captcha?> = mutableCaptcha.asStateFlow()
    @Volatile private var solved: CompletableDeferred<Boolean>? = null

    override suspend fun fetch(origin: String, method: String, url: String, token: String, body: String?, contentType: String, prompt: Boolean): Pair<Int, String> = lock.withLock {
        require(origin in SoundCloudWebSession.ORIGINS && url.startsWith("https://api-v2.soundcloud.com/"))
        idle?.cancel()
        try {
            val current = open(origin)
            val first = request(current, method, url, token, body, contentType)
            val challenge = captchaUrl(first) ?: return@withLock first
            if (!prompt || !solve(origin, challenge)) return@withLock first
            // the check was solved in another page; this one's bot-check script still holds the refused session
            close()
            request(open(origin), method, url, token, body, contentType)
        } finally {
            idle = scope.launch { delay(IDLE_MS); lock.withLock { close() } }
        }
    }

    // a solved check stays with the shared cookie jar, so later writes go straight through
    private suspend fun solve(origin: String, challenge: String): Boolean {
        val done = CompletableDeferred<Boolean>().also { solved = it }
        return try {
            mutableCaptcha.value = Captcha(origin, challenge)
            withTimeoutOrNull(CAPTCHA_TIMEOUT_MS) { done.await() } == true
        } finally {
            solved = null
            mutableCaptcha.value = null
        }
    }

    fun captchaSolved(ok: Boolean) {
        solved?.complete(ok)
    }

    private suspend fun request(page: WebPage, method: String, url: String, token: String, body: String?, contentType: String): Pair<Int, String> {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<Pair<Int, String>>()
        replies[id] = reply
        return try {
            // the bot check's own script tags protected calls with the session it keeps in this cookie
            val script = """var dd = (document.cookie.match(/(?:^|; )datadome=([^;]+)/) || [])[1];
                var headers = {'Authorization': ${quote("OAuth $token")}, 'Content-Type': ${quote(contentType)}, 'Accept': 'application/json'};
                if (dd) headers['x-datadome-clientid'] = dd;
                fetch(${quote(url)}, {method: ${quote(method)}, credentials: 'include', headers: headers,
                body: ${body?.let(::quote) ?: "undefined"}})
                .then(r => r.text().then(t => $BRIDGE.postMessage(JSON.stringify({id: $id, status: r.status, body: t}))))
                .catch(e => $BRIDGE.postMessage(JSON.stringify({id: $id, status: 0, body: String(e)})))"""
            page.run(script)
            withTimeoutOrNull(TIMEOUT_MS) { reply.await() }?.takeIf { it.first != 0 }
                ?: throw IOException("Could not connect to SoundCloud. Please retry.").also { AppLog.d("SoundCloud", "$method got no answer from the page") }
        } finally {
            replies.remove(id)
        }
    }

    private suspend fun open(origin: String): WebPage {
        page?.takeIf { pageOrigin == origin }?.let { return it }
        close()
        val created = Chromium.open("$origin/", hidden = true, origins = setOf(origin))
        created.startScript = created.bridgeScript(BRIDGE)
        created.onMessage = { bridge, data ->
            if (bridge == BRIDGE) runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()?.let { json ->
                replies[json.get("id")?.asInt ?: return@let]?.complete((json.get("status")?.asInt ?: 0) to (json.get("body")?.asString.orEmpty()))
            }
        }
        page = created
        pageOrigin = origin
        // the real page runs soundcloud's bot check script, which vouches for this browser
        withTimeoutOrNull(TIMEOUT_MS) { created.loading.first { !it } } ?: throw IOException("Could not connect to SoundCloud. Please retry.")
        return created
    }

    private fun close() {
        page?.close()
        page = null
        pageOrigin = ""
    }

    companion object {
        const val BRIDGE = "__auroraSoundCloud"
        private const val CAPTCHA_TIMEOUT_MS = 5 * 60_000L
        private const val TIMEOUT_MS = 20_000L
        private const val IDLE_MS = 2 * 60_000L
        private val gson = Gson()
        private fun quote(value: String): String = gson.toJson(value)

        fun captchaUrl(reply: Pair<Int, String>): String? {
            if (reply.first != 403) return null
            val url = runCatching { URI(JsonParser.parseString(reply.second).asJsonObject.get("url").asString) }.getOrNull() ?: return null
            return url.toString().takeIf { url.scheme == "https" && url.host.orEmpty().let { it == "captcha-delivery.com" || it.endsWith(".captcha-delivery.com") } }
        }

        // the same hand-off the bot check's own script does: the captcha frame posts back the cookie it earned
        fun captchaScript(url: String) = """(function() {
            var f = document.createElement('iframe'); f.id = 'aurora-captcha'; f.src = ${quote(url)};
            f.style.cssText = 'position:fixed;top:0;left:0;width:100vw;height:100vh;border:0;z-index:2147483647;background:#fff';
            window.addEventListener('message', function(e) {
                var host = ''; try { host = new URL(e.origin).hostname; } catch (x) { return; }
                if (host !== 'captcha-delivery.com' && !host.endsWith('.captcha-delivery.com')) return;
                var d = e.data; try { if (typeof d === 'string') d = JSON.parse(d); } catch (x) { return; }
                if (!d || !d.cookie) return;
                document.cookie = d.cookie; f.remove();
                $BRIDGE.postMessage(JSON.stringify({captcha: true}));
            });
            document.body.appendChild(f);
        })()"""
    }
}
