package com.aurora.music.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aurora.music.data.remote.SpotifyTokenSource
import com.aurora.music.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

data class WebPlayerToken(val accessToken: String, val expiresAtMs: Long, val anonymous: Boolean)

// spotify's own web player mints the token so its rotating request signing never has to be copied here
object SpotifyWebPlayer {
    const val ORIGIN = "https://open.spotify.com"
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    private const val BRIDGE = "AuroraSpotify"

    // only reads the web player's own token response, nothing else on the page
    private val HOOK = """
        (function () {
          if (window.__auroraSpotify) return;
          window.__auroraSpotify = true;
          function report(body) {
            try {
              var j = typeof body === 'string' ? JSON.parse(body) : body;
              if (j && j.accessToken) $BRIDGE.postMessage(JSON.stringify({
                accessToken: j.accessToken,
                expiresAt: j.accessTokenExpirationTimestampMs || 0,
                anonymous: !!j.isAnonymous
              }));
            } catch (e) {}
          }
          function watched(url) {
            url = String(url || '');
            return url.indexOf('/api/token') >= 0 || url.indexOf('/get_access_token') >= 0;
          }
          var originalFetch = window.fetch;
          if (originalFetch) window.fetch = function (input) {
            var result = originalFetch.apply(this, arguments);
            try {
              if (watched(typeof input === 'string' ? input : input && (input.url || input.href) || input)) {
                result.then(function (r) { return r.clone().text(); }).then(report).catch(function () {});
              }
            } catch (e) {}
            return result;
          };
          var open = XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open = function (method, url) { this.__auroraUrl = url; return open.apply(this, arguments); };
          var send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.send = function () {
            if (watched(this.__auroraUrl)) this.addEventListener('load', function () { report(this.responseText); });
            return send.apply(this, arguments);
          };
        })();
    """.trimIndent()

    val supported: Boolean get() = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        // the desktop web player is the one that reliably boots and asks for a token
        val chrome = Regex("Chrome/([\\d.]+)").find(view.settings.userAgentString)?.groupValues?.get(1) ?: "130.0.0.0"
        view.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
        CookieManager.getInstance().setAcceptCookie(true)
    }

    // must run on the main thread before the first load
    fun installHook(view: WebView, onToken: (WebPlayerToken) -> Unit): Boolean {
        if (!supported) return false
        WebViewCompat.addWebMessageListener(view, BRIDGE, setOf(ORIGIN)) { _, message, _, _, _ ->
            parse(message.data)?.let(onToken)
        }
        WebViewCompat.addDocumentStartJavaScript(view, HOOK, setOf(ORIGIN))
        return true
    }

    fun sessionCookie(): String = CookieManager.getInstance().getCookie(ORIGIN).orEmpty()
        .split(';').map { it.trim() }.firstOrNull { it.startsWith("sp_dc=") }?.removePrefix("sp_dc=").orEmpty()

    // signing out of aurora also signs the in-app browser out of spotify
    fun clearCookies() {
        val cookies = CookieManager.getInstance()
        listOf(ORIGIN, "https://accounts.spotify.com", "https://www.spotify.com").forEach { url ->
            cookies.getCookie(url).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.forEach { name ->
                cookies.setCookie(url, "$name=; Max-Age=0; Path=/")
                cookies.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.spotify.com")
            }
        }
        cookies.flush()
    }

    private fun parse(data: String?): WebPlayerToken? = runCatching {
        val json = JSONObject(data ?: return null)
        val token = json.optString("accessToken").takeIf { it.isNotBlank() } ?: return null
        WebPlayerToken(token, json.optLong("expiresAt"), json.optBoolean("anonymous"))
    }.getOrNull()
}

// loads the web player off screen with this account's cookie whenever the token runs out
class SpotifyWebTokenSource(
    private val context: Context,
    initialToken: String,
    private val sessionCookie: () -> String,
) : SpotifyTokenSource {
    @Volatile private var token = initialToken
    @Volatile private var expiresAtMs = 0L
    @Volatile private var refreshedAt = 0L
    @Volatile private var failedAt = 0L
    private val lock = Mutex()

    override fun current(): String? = token.takeIf {
        it.isNotBlank() && (expiresAtMs == 0L || System.currentTimeMillis() < expiresAtMs - EARLY_MS)
    }

    override suspend fun refresh(): String? = lock.withLock {
        val now = SystemClock.elapsedRealtime()
        // a refresh that just finished on another request serves this one too
        if (now - refreshedAt < REUSE_MS) return@withLock token
        if (now - failedAt < RETRY_MS) return@withLock null
        val fresh = withTimeoutOrNull(TIMEOUT_MS) { withContext(Dispatchers.Main) { load() } }
        if (fresh == null) {
            failedAt = SystemClock.elapsedRealtime()
            AppLog.d("SpotifyWeb", "token refresh failed")
            return@withLock null
        }
        token = fresh.accessToken
        expiresAtMs = fresh.expiresAtMs.takeIf { it > 0 } ?: (System.currentTimeMillis() + FALLBACK_LIFETIME_MS)
        refreshedAt = SystemClock.elapsedRealtime()
        token
    }

    private suspend fun load(): WebPlayerToken? = suspendCancellableCoroutine { continuation ->
        val cookie = sessionCookie()
        if (cookie.isBlank() || !SpotifyWebPlayer.supported) { continuation.resume(null); return@suspendCancellableCoroutine }
        CookieManager.getInstance().setCookie(SpotifyWebPlayer.ORIGIN, "sp_dc=$cookie; Domain=.spotify.com; Path=/; Secure; HttpOnly")
        val view = WebView(context.applicationContext)
        var done = false
        fun finish(result: WebPlayerToken?) {
            if (done) return
            done = true
            view.stopLoading()
            view.destroy()
            if (continuation.isActive) continuation.resume(result)
        }
        SpotifyWebPlayer.configure(view)
        SpotifyWebPlayer.installHook(view) { captured -> if (!captured.anonymous) finish(captured) }
        continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { finish(null) } }
        view.loadUrl(SpotifyWebPlayer.ORIGIN)
    }

    private companion object {
        const val EARLY_MS = 2 * 60_000L
        const val REUSE_MS = 20_000L
        const val RETRY_MS = 60_000L
        const val TIMEOUT_MS = 30_000L
        const val FALLBACK_LIFETIME_MS = 50 * 60_000L
    }
}
