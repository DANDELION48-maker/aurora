package com.aurora.music.data.remote

import com.aurora.music.data.ServerType
import com.aurora.music.data.Session
import com.aurora.music.util.AppLog
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

// where web-player sessions get fresh tokens since they have no oauth refresh token
interface SpotifyTokenSource {
    fun current(): String?
    suspend fun refresh(): String?
}

// refresh token stashed in session salt; web-player sessions mark the salt and use a token source instead
class SpotifyClient(
    val session: Session,
    private val clientId: String,
    private val onTokenRefreshed: (String) -> Unit = {},
    private val tokenSource: SpotifyTokenSource? = null,
) {
    @Volatile private var accessToken: String = session.token
    private val refreshToken: String = session.salt

    // back off for the retry-after window so we dont extend the throttle
    @Volatile private var blockedUntilMs: Long = 0L
    private val rateLimitInterceptor = Interceptor { chain ->
        if (System.currentTimeMillis() < blockedUntilMs) {
            return@Interceptor Response.Builder()
                .request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(429).message("rate-limited (cooldown)")
                .body("".toResponseBody(null)).build()
        }
        val resp = chain.proceed(chain.request())
        if (resp.code == 429) {
            val header = resp.header("Retry-After")
            val retry = (header?.toIntOrNull() ?: 8).coerceIn(1, 3600)
            AppLog.d("SpotifyRL", "429 on ${chain.request().url.encodedPath} Retry-After=$header → cooldown ${retry}s")
            blockedUntilMs = System.currentTimeMillis() + retry * 1000L
        }
        resp
    }

    private val authInterceptor = Interceptor { chain ->
        val token = tokenSource?.let { it.current() ?: refreshed() } ?: accessToken
        val first = chain.request().newBuilder().header("Authorization", "Bearer $token").build()
        val resp = chain.proceed(first)
        if (resp.code != 401) return@Interceptor resp
        val newTok = refreshed() ?: return@Interceptor resp
        resp.close()
        chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $newTok").build())
    }

    private fun refreshed(): String? {
        val newTok = when {
            tokenSource != null -> runBlocking { tokenSource.refresh() }
            refreshToken.isNotBlank() && clientId.isNotBlank() -> runBlocking { SpotifyAuth.refresh(clientId, refreshToken) }?.accessToken
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        accessToken = newTok
        onTokenRefreshed(newTok)
        return newTok
    }

    private val okhttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(rateLimitInterceptor)
        .addInterceptor(authInterceptor)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val api: SpotifyApi = buildApi(okhttp)

    companion object {
        const val API_BASE = "https://api.spotify.com"
        const val WEB_PLAYER = "web-player"

        private fun buildApi(client: OkHttpClient): SpotifyApi = Retrofit.Builder()
            .baseUrl("$API_BASE/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SpotifyApi::class.java)

        private fun apiFor(token: String): SpotifyApi {
            val ok = OkHttpClient.Builder()
                .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }
                .build()
            return buildApi(ok)
        }

        // a token minted by the signed-in web player; credential is the opaque reference to its saved cookie
        suspend fun loginWithWebToken(accessToken: String, credential: String): Session? {
            val me = runCatching { apiFor(accessToken).me() }.getOrElse { e ->
                AppLog.d("SpotifyWeb", "login: me() failed code=${(e as? retrofit2.HttpException)?.code()}"); null
            } ?: return null
            val id = me.id ?: return null
            return Session(
                server = API_BASE,
                username = me.displayName ?: id,
                salt = WEB_PLAYER,
                token = accessToken,
                type = ServerType.SPOTIFY,
                userId = id,
                imageUrl = me.images?.firstOrNull()?.url ?: "",
                clientToken = credential,
            )
        }

        suspend fun login(clientId: String, code: String, verifier: String): Session? {
            val tok = SpotifyAuth.exchangeCode(clientId, code, verifier)
            if (tok == null) { AppLog.d("SpotifyOAuth", "login: token exchange failed"); return null }
            val access = tok.accessToken ?: return null
            val me = runCatching { apiFor(access).me() }.getOrElse { e ->
                val c = (e as? retrofit2.HttpException)?.code()
                AppLog.d("SpotifyOAuth", "login: me() failed code=$c ${e.message}"); null
            } ?: return null
            val id = me.id ?: return null
            AppLog.d("SpotifyOAuth", "login: SUCCESS user=$id")
            return Session(
                server = API_BASE,
                username = me.displayName ?: id,
                salt = tok.refreshToken ?: "",
                token = access,
                type = ServerType.SPOTIFY,
                userId = id,
                imageUrl = me.images?.firstOrNull()?.url ?: "",
            )
        }
    }
}
