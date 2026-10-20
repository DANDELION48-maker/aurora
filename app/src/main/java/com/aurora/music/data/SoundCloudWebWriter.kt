package com.aurora.music.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aurora.music.data.remote.SoundCloudBrowser
import com.aurora.music.data.remote.SoundCloudWebSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** A hidden page on soundcloud's own site; its fetches carry the browser identity and bot-check cookie the login earned. */
class SoundCloudWebWriter(context: Context) : SoundCloudBrowser {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Mutex()
    private val replies = ConcurrentHashMap<Int, CompletableDeferred<Pair<Int, String>>>()
    private val ids = AtomicInteger()
    private var view: WebView? = null
    private var viewOrigin = ""
    private val idle = Runnable { close() }

    // the page the user solves soundcloud's captcha in, while one is waiting
    val captcha = MutableStateFlow<WebView?>(null)
    @Volatile private var solved: CompletableDeferred<Boolean>? = null

    override suspend fun fetch(origin: String, method: String, url: String, token: String, body: String?, contentType: String, prompt: Boolean): Pair<Int, String> = lock.withLock {
        require(origin in SoundCloudWebSession.ORIGINS && url.startsWith("https://api-v2.soundcloud.com/"))
        main.removeCallbacks(idle)
        try {
            val page = open(origin)
            val first = request(page, method, url, token, body, contentType)
            val challenge = captchaUrl(first) ?: return@withLock first
            if (prompt && solve(page, challenge)) request(page, method, url, token, body, contentType) else first
        } finally {
            main.removeCallbacks(idle)
            main.postDelayed(idle, IDLE_MS)
        }
    }

    // a solved check stays with this browser, so later writes go straight through
    private suspend fun solve(page: WebView, challenge: String): Boolean {
        val done = CompletableDeferred<Boolean>().also { solved = it }
        try {
            withContext(Dispatchers.Main) {
                page.evaluateJavascript(captchaScript(challenge), null)
                captcha.value = page
            }
            return withTimeoutOrNull(CAPTCHA_TIMEOUT_MS) { done.await() } == true
        } finally {
            solved = null
            withContext(NonCancellable + Dispatchers.Main) {
                captcha.value = null
                page.evaluateJavascript("var f = document.getElementById('aurora-captcha'); if (f) f.remove();", null)
            }
        }
    }

    fun cancelCaptcha() { solved?.complete(false) }

    private suspend fun request(page: WebView, method: String, url: String, token: String, body: String?, contentType: String): Pair<Int, String> {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<Pair<Int, String>>()
        replies[id] = reply
        return try {
            // the bot check's own script tags protected calls with the session it keeps in this cookie
            val script = """var dd = (document.cookie.match(/(?:^|; )datadome=([^;]+)/) || [])[1];
                var headers = {'Authorization': ${JSONObject.quote("OAuth $token")}, 'Content-Type': ${JSONObject.quote(contentType)}, 'Accept': 'application/json'};
                if (dd) headers['x-datadome-clientid'] = dd;
                fetch(${JSONObject.quote(url)}, {method: ${JSONObject.quote(method)}, credentials: 'include', headers: headers,
                body: ${body?.let(JSONObject::quote) ?: "undefined"}})
                .then(r => r.text().then(t => $BRIDGE.postMessage(JSON.stringify({id: $id, status: r.status, body: t}))))
                .catch(e => $BRIDGE.postMessage(JSON.stringify({id: $id, status: 0, body: String(e)})))"""
            withContext(Dispatchers.Main) { page.evaluateJavascript(script, null) }
            withTimeoutOrNull(TIMEOUT_MS) { reply.await() }?.takeIf { it.first != 0 }
                ?: throw IOException("Could not connect to SoundCloud. Please retry.").also { com.aurora.music.util.AppLog.d("SoundCloud", "$method got no answer from the page") }
        } finally {
            replies.remove(id)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun open(origin: String): WebView {
        val loaded = CompletableDeferred<Unit>()
        val page = withContext(Dispatchers.Main) {
            view?.takeIf { viewOrigin == origin }?.let { return@withContext it.also { loaded.complete(Unit) } }
            close()
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) throw IOException("This WebView is too old for SoundCloud changes.")
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                WebViewCompat.addWebMessageListener(this, BRIDGE, setOf(origin)) { _, message, _, _, _ ->
                    val json = runCatching { JSONObject(message.data ?: "") }.getOrNull() ?: return@addWebMessageListener
                    if (json.optBoolean("captcha")) solved?.complete(true)
                    replies[json.optInt("id")]?.complete(json.optInt("status") to json.optString("body"))
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) { loaded.complete(Unit) }
                }
                // the real page runs soundcloud's bot check script, which vouches for this browser
                settings.domStorageEnabled = true
                loadUrl("$origin/")
                view = this
                viewOrigin = origin
            }
        }
        withTimeoutOrNull(TIMEOUT_MS) { loaded.await() } ?: throw IOException("Could not connect to SoundCloud. Please retry.")
        return page
    }

    // main thread
    private fun close() {
        view?.apply { stopLoading(); destroy() }
        view = null
        viewOrigin = ""
    }

    private companion object {
        const val BRIDGE = "__auroraSoundCloud"
        const val CAPTCHA_TIMEOUT_MS = 5 * 60_000L

        fun captchaUrl(reply: Pair<Int, String>): String? {
            if (reply.first != 403) return null
            val url = runCatching { java.net.URI(JSONObject(reply.second).optString("url")) }.getOrNull() ?: return null
            return url.toString().takeIf { url.scheme == "https" && url.host.orEmpty().let { it == "captcha-delivery.com" || it.endsWith(".captcha-delivery.com") } }
        }

        // the same hand-off the bot check's own script does: the captcha frame posts back the cookie it earned
        fun captchaScript(url: String) = """(function() {
            var f = document.createElement('iframe'); f.id = 'aurora-captcha'; f.src = ${JSONObject.quote(url)};
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
        const val TIMEOUT_MS = 20_000L
        const val IDLE_MS = 2 * 60_000L
    }
}
