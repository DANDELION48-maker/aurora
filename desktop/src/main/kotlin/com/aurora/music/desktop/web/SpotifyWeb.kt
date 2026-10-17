package com.aurora.music.desktop.web

import com.aurora.music.data.remote.SpotifyGraphql
import com.aurora.music.data.remote.SpotifyGraphqlException
import com.aurora.music.util.AppLog
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.cef.network.CefCookie
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// spotify only answers its private api from inside its own page, so aurora's queries run there
object SpotifyWebPlayer {
    const val ORIGIN = "https://open.spotify.com"
    const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    internal const val BRIDGE = "AuroraSpotify"
    val HOSTS = listOf("open.spotify.com", "accounts.spotify.com", "www.spotify.com", "spotify.com")

    // copies the headers the page sends with its own queries and runs aurora's with them; never reports tokens back
    internal val SCRIPT = """
        (function () {
          if (window.__aurora) return;
          var nativeFetch = window.fetch.bind(window);
          var state = { headers: null, token: null, clientToken: null, ops: null, announced: false };
          window.__aurora = state;
          function post(message) { try { window.$BRIDGE.postMessage(JSON.stringify(message)); } catch (e) {} }
          function urlOf(input) { return String(typeof input === 'string' ? input : (input && (input.url || input.href)) || input || ''); }
          function isToken(url) { return url.indexOf('/api/token') >= 0 || url.indexOf('/get_access_token') >= 0; }
          function lower(headers) {
            var out = {};
            if (!headers) return out;
            if (Array.isArray(headers)) headers.forEach(function (p) { out[String(p[0]).toLowerCase()] = p[1]; });
            else if (typeof headers.forEach === 'function') headers.forEach(function (v, k) { out[String(k).toLowerCase()] = v; });
            else Object.keys(headers).forEach(function (k) { out[k.toLowerCase()] = headers[k]; });
            return out;
          }
          function announce() {
            if (state.announced) return;
            state.announced = true;
            post({ type: 'ready' });
          }
          function sawQuery(headers) {
            if (!headers.authorization) return;
            var picked = {};
            ['authorization', 'client-token', 'app-platform', 'spotify-app-version'].forEach(function (k) { if (headers[k]) picked[k] = headers[k]; });
            state.headers = picked;
            announce();
          }
          // only used when the page never queries on its own, e.g. while it sits in the background
          function fallback() {
            if (state.headers || !state.token || !state.clientToken) return;
            setTimeout(function () {
              if (state.headers) return;
              state.headers = { authorization: 'Bearer ' + state.token, 'client-token': state.clientToken, 'app-platform': 'WebPlayer' };
              announce();
            }, 4000);
          }
          function sawToken(body) {
            try {
              var j = JSON.parse(body);
              if (!j || !j.accessToken) return;
              state.token = j.accessToken;
              if (state.headers) state.headers.authorization = 'Bearer ' + j.accessToken;
              post({ type: 'token', anonymous: !!j.isAnonymous });
              fallback();
            } catch (e) {}
          }
          function sawClientToken(body) {
            try {
              var j = JSON.parse(body);
              var token = j && j.granted_token && j.granted_token.token;
              if (!token) return;
              state.clientToken = token;
              if (state.headers) state.headers['client-token'] = token;
              fallback();
            } catch (e) {}
          }
          window.fetch = function (input, init) {
            var result = nativeFetch.apply(null, arguments);
            try {
              var url = urlOf(input);
              if (url.indexOf('/pathfinder/') >= 0) {
                var headers = lower(init && init.headers);
                if (!headers.authorization && input && typeof input === 'object' && input.headers) headers = lower(input.headers);
                sawQuery(headers);
              } else if (isToken(url)) {
                result.then(function (r) { return r.clone().text(); }).then(sawToken).catch(function () {});
              } else if (url.indexOf('clienttoken.spotify.com') >= 0) {
                result.then(function (r) { return r.clone().text(); }).then(sawClientToken).catch(function () {});
              }
            } catch (e) {}
            return result;
          };
          var open = XMLHttpRequest.prototype.open;
          var setHeader = XMLHttpRequest.prototype.setRequestHeader;
          var send = XMLHttpRequest.prototype.send;
          XMLHttpRequest.prototype.open = function (method, url) { this.__auroraUrl = String(url); this.__auroraHeaders = {}; return open.apply(this, arguments); };
          XMLHttpRequest.prototype.setRequestHeader = function (name, value) {
            if (this.__auroraHeaders) this.__auroraHeaders[String(name).toLowerCase()] = value;
            return setHeader.apply(this, arguments);
          };
          XMLHttpRequest.prototype.send = function () {
            var url = this.__auroraUrl || '';
            if (url.indexOf('/pathfinder/') >= 0) sawQuery(this.__auroraHeaders || {});
            else if (isToken(url)) this.addEventListener('load', function () { sawToken(this.responseText); });
            else if (url.indexOf('clienttoken.spotify.com') >= 0) this.addEventListener('load', function () { sawClientToken(this.responseText); });
            return send.apply(this, arguments);
          };
          // operation hashes live in the page's scripts, including ones the cut-down player never loads
          function scan() {
            var urls = [];
            try {
              var manifest = JSON.parse(atob(document.getElementById('__CDN_FILE_URLS__').textContent.trim()));
              Object.keys(manifest).forEach(function (k) { if (/\.js$/.test(k)) urls.push(manifest[k]); });
            } catch (e) {}
            document.querySelectorAll('script[src]').forEach(function (s) { urls.push(s.src); });
            urls = urls.filter(function (u, i) { return u && urls.indexOf(u) === i; });
            var ops = {};
            return Promise.all(urls.map(function (u) {
              return nativeFetch(u).then(function (r) { return r.text(); }).then(function (text) {
                for (var m of text.matchAll(/"([A-Za-z0-9_]+)","(?:query|mutation)","([0-9a-f]{64})"/g)) ops[m[1]] = m[2];
              }).catch(function () {});
            })).then(function () {
              if (Object.keys(ops).length) post({ type: 'ops', ops: ops });
              return ops;
            });
          }
          function hashFor(operation, hash, rescan) {
            if (hash) return Promise.resolve(hash);
            if (rescan || !state.ops) state.ops = scan();
            return state.ops.then(function (ops) { return ops[operation] || ''; });
          }
          state.query = function (id, operation, variables, hash, rescan) {
            hashFor(operation, hash, rescan).then(function (h) {
              if (!h) return { status: -1, body: '', hash: '' };
              return nativeFetch('https://api-partner.spotify.com/pathfinder/v2/query', {
                method: 'POST',
                headers: Object.assign({ 'content-type': 'application/json;charset=UTF-8', accept: 'application/json' }, state.headers),
                body: JSON.stringify({ variables: variables, operationName: operation, extensions: { persistedQuery: { version: 1, sha256Hash: h } } })
              }).then(function (r) { return r.text().then(function (t) { return { status: r.status, body: t, hash: h }; }); });
            }).then(function (r) { post({ type: 'result', id: id, status: r.status, body: r.body, hash: r.hash }); },
              function (e) { post({ type: 'result', id: id, status: 0, body: String(e && e.message || e), hash: '' }); });
          };
          state.spclient = function (id, path, body) {
            if (!/^\/(playlist\/v2\/|color-lyrics\/v2\/track\/)/.test(path) || path.indexOf('..') >= 0) { post({ type: 'result', id: id, status: -1, body: '', hash: '' }); return; }
            var headers = Object.assign({ accept: 'application/json' }, state.headers);
            if (!headers['spotify-app-version']) {
              try { headers['spotify-app-version'] = JSON.parse(atob(document.getElementById('appServerConfig').textContent.trim())).clientVersion || ''; } catch (e) {}
              if (!headers['spotify-app-version']) delete headers['spotify-app-version'];
            }
            // lyrics only answer the desktop platform, which the mobile player itself overrides for them
            if (path.indexOf('/color-lyrics/') === 0) headers['app-platform'] = 'WebPlayer';
            if (body) headers['content-type'] = 'application/json;charset=UTF-8';
            nativeFetch('https://spclient.wg.spotify.com' + path, {
              method: body ? 'POST' : 'GET',
              headers: headers,
              body: body ? JSON.stringify(body) : undefined
            }).then(function (r) { return r.text().then(function (t) { post({ type: 'result', id: id, status: r.status, body: t, hash: '' }); }); },
              function (e) { post({ type: 'result', id: id, status: 0, body: String(e && e.message || e), hash: '' }); });
          };
        })();
    """.trimIndent()

    suspend fun sessionCookie(): String = Chromium.cookies(ORIGIN).firstOrNull { it.name == "sp_dc" }?.value.orEmpty()

    // signing out of aurora also signs the embedded browser out of spotify
    suspend fun clearCookies() = Chromium.clearCookies(HOSTS)
}

// operation hashes change only with a new web player build, so a page start rarely has to rescan
class SpotifyOperations(private val file: File) {
    private val gson = Gson()
    private val hashes = ConcurrentHashMap<String, String>().apply {
        runCatching { gson.fromJson<Map<String, String>>(file.readText(), object : TypeToken<Map<String, String>>() {}.type) }
            .getOrNull()?.let(::putAll)
    }

    fun hash(operation: String): String? = hashes[operation]

    fun remember(found: Map<String, String>) {
        if (found.isEmpty()) return
        hashes.putAll(found)
        save()
    }

    fun forget() {
        hashes.clear()
        save()
    }

    @Synchronized private fun save() {
        runCatching { file.parentFile.mkdirs(); file.writeText(gson.toJson(HashMap(hashes))) }
    }
}

// one open.spotify.com page
class SpotifyPage(val page: WebPage, private val operations: SpotifyOperations) : SpotifyGraphql {
    private class Reply(val status: Int, val body: String, val hash: String)

    private val ready = CompletableDeferred<Unit>()
    private val calls = ConcurrentHashMap<Int, CompletableDeferred<Reply>>()
    private val ids = AtomicInteger()
    @Volatile var signedIn: Boolean? = null
        private set
    @Volatile var onSignedIn: () -> Unit = {}

    init {
        page.startScript = page.bridgeScript(SpotifyWebPlayer.BRIDGE) + "\n" + SpotifyWebPlayer.SCRIPT
        page.onMessage = { bridge, data -> if (bridge == SpotifyWebPlayer.BRIDGE) receive(data) }
    }

    private fun receive(data: String) {
        val json = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: return
        when (json.text("type")) {
            "ready" -> ready.complete(Unit)
            "token" -> {
                val now = json.get("anonymous")?.takeIf { it.isJsonPrimitive }?.asBoolean != true
                signedIn = now
                if (now) onSignedIn()
            }
            "ops" -> json.get("ops")?.takeIf { it.isJsonObject }?.asJsonObject?.let { ops ->
                operations.remember(ops.keySet().associateWith { ops.text(it) }.filterValues { it.length == 64 })
            }
            "result" -> calls.remove(json.get("id")?.asInt ?: return)
                ?.complete(Reply(json.get("status")?.asInt ?: 0, json.text("body"), json.text("hash")))
        }
    }

    suspend fun awaitReady(timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) { ready.await() } != null

    override suspend fun query(operation: String, variables: JsonObject): JsonObject {
        if (!awaitReady(READY_TIMEOUT_MS)) throw SpotifyGraphqlException("web player did not start")
        if (signedIn == false) throw SpotifyGraphqlException("signed out", 401)
        val cached = operations.hash(operation)
        var reply = send(operation, variables, cached.orEmpty(), rescan = false)
        if (cached != null && "PersistedQueryNotFound" in reply.body) {
            // a new web player build retired the saved hashes
            operations.forget()
            reply = send(operation, variables, "", rescan = true)
        }
        if (reply.status == -1) throw SpotifyGraphqlException("unknown operation $operation", -1)
        if (reply.hash.isNotBlank()) operations.remember(mapOf(operation to reply.hash))
        val root = runCatching { JsonParser.parseString(reply.body).asJsonObject }.getOrNull()
        if (reply.status !in 200..299 || root == null) throw SpotifyGraphqlException("$operation failed with ${reply.status}", reply.status)
        val data = root.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
        if (data == null) {
            val message = root.get("errors")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.joinToString("; ") { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.get("message")?.asString.orEmpty() }
            throw SpotifyGraphqlException("$operation failed: ${message.orEmpty().take(200)}", reply.status)
        }
        return data
    }

    override suspend fun spclient(path: String, body: JsonObject?): JsonObject {
        if (!SPCLIENT_PATH.containsMatchIn(path) || ".." in path) throw SpotifyGraphqlException("path not allowed", -1)
        if (!awaitReady(READY_TIMEOUT_MS)) throw SpotifyGraphqlException("web player did not start")
        if (signedIn == false) throw SpotifyGraphqlException("signed out", 401)
        val reply = call(path) { id -> "window.__aurora && window.__aurora.spclient($id, ${quote(path)}, ${body ?: "null"})" }
        if (reply.status !in 200..299) throw SpotifyGraphqlException("service call failed with ${reply.status}: ${reply.body.take(300)}", reply.status)
        return runCatching { JsonParser.parseString(reply.body).asJsonObject }.getOrNull() ?: JsonObject()
    }

    private suspend fun send(operation: String, variables: JsonObject, hash: String, rescan: Boolean): Reply =
        call(operation) { id -> "window.__aurora && window.__aurora.query($id, ${quote(operation)}, $variables, ${quote(hash)}, $rescan)" }

    private suspend fun call(label: String, script: (Int) -> String): Reply {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<Reply>()
        calls[id] = reply
        try {
            page.run(script(id))
            return withTimeoutOrNull(QUERY_TIMEOUT_MS) { reply.await() } ?: throw SpotifyGraphqlException("$label timed out")
        } finally {
            calls.remove(id)
        }
    }

    fun close() {
        val closed = SpotifyGraphqlException("web player closed")
        ready.completeExceptionally(closed)
        calls.values.forEach { it.completeExceptionally(closed) }
        calls.clear()
        page.close()
    }

    private companion object {
        const val READY_TIMEOUT_MS = 30_000L
        const val QUERY_TIMEOUT_MS = 30_000L
        val SPCLIENT_PATH = Regex("^/(playlist/v2/|color-lyrics/v2/track/)")
        private val gson = Gson()
        fun quote(value: String): String = gson.toJson(value)
        fun JsonObject.text(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    }
}

// a hidden web player signed in as the active account, closed again once it sits idle
class SpotifyWebBridge(root: File) {
    val operations = SpotifyOperations(File(root, "spotify-operations.json"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var page: SpotifyPage? = null
    private var pageCookie = ""
    @Volatile private var failedAt = 0L
    private val inFlight = AtomicInteger()
    @Volatile private var idle: Job? = null

    fun account(cookie: () -> String): SpotifyGraphql = object : SpotifyGraphql {
        override suspend fun query(operation: String, variables: JsonObject): JsonObject =
            run(cookie()) { it.query(operation, variables) }

        override suspend fun spclient(path: String, body: JsonObject?): JsonObject =
            run(cookie()) { it.spclient(path, body) }
    }

    // spotify rate limits bursts such as a whole liked library; a short back-off keeps pages from going missing
    private suspend fun run(cookie: String, request: suspend (SpotifyPage) -> JsonObject): JsonObject {
        var attempt = 0
        while (true) {
            try {
                return runOnce(cookie, request)
            } catch (e: SpotifyGraphqlException) {
                if (e.status != 429 || ++attempt > RATE_RETRIES) throw e
                delay(RATE_BACKOFF_MS * attempt)
            }
        }
    }

    private suspend fun runOnce(cookie: String, request: suspend (SpotifyPage) -> JsonObject): JsonObject {
        if (cookie.isBlank()) throw SpotifyGraphqlException("signed out", 401)
        inFlight.incrementAndGet()
        try {
            val current = open(cookie)
            return try {
                request(current)
            } catch (e: SpotifyGraphqlException) {
                if (e.status != 401) throw e
                // the page's token ran out; a fresh start mints a new one
                lock.withLock { close(current) }
                try {
                    request(open(cookie))
                } catch (again: SpotifyGraphqlException) {
                    // still refused, so the saved login itself expired; stop restarting the page for every call
                    if (again.status == 401) failedAt = System.currentTimeMillis()
                    throw again
                }
            }
        } finally {
            inFlight.decrementAndGet()
            scheduleIdle()
        }
    }

    private suspend fun open(cookie: String): SpotifyPage = lock.withLock {
        page?.takeIf { pageCookie == cookie }?.let { return@withLock it }
        if (System.currentTimeMillis() - failedAt < RETRY_MS) throw SpotifyGraphqlException("web player unavailable")
        val startedAt = System.currentTimeMillis()
        page?.let(::close)
        Chromium.setCookie(SpotifyWebPlayer.ORIGIN, CefCookie("sp_dc", cookie, ".spotify.com", "/", true, true, Date(), Date(), false, null))
        val fresh = SpotifyPage(Chromium.open(SpotifyWebPlayer.ORIGIN, hidden = true, origins = setOf(SpotifyWebPlayer.ORIGIN)), operations)
        page = fresh
        pageCookie = cookie
        if (!fresh.awaitReady(BOOT_TIMEOUT_MS)) {
            failedAt = System.currentTimeMillis()
            close(fresh)
            AppLog.d(TAG, "hidden web player did not start")
            throw SpotifyGraphqlException("web player did not start")
        }
        AppLog.d(TAG, "hidden web player ready in ${System.currentTimeMillis() - startedAt} ms, signed in=${fresh.signedIn}")
        fresh
    }

    private fun close(target: SpotifyPage) {
        if (page === target) {
            page = null
            pageCookie = ""
        }
        target.close()
    }

    private fun scheduleIdle() {
        idle?.cancel()
        idle = scope.launch {
            delay(IDLE_MS)
            if (inFlight.get() == 0) reset() else scheduleIdle()
        }
    }

    // next query starts a fresh page
    fun reset() {
        failedAt = 0L
        scope.launch { lock.withLock { page?.let(::close) } }
    }

    private companion object {
        const val TAG = "SpotifyWeb"
        const val BOOT_TIMEOUT_MS = 30_000L
        const val RETRY_MS = 30_000L
        const val IDLE_MS = 3 * 60_000L
        const val RATE_RETRIES = 3
        const val RATE_BACKOFF_MS = 2_000L
    }
}
