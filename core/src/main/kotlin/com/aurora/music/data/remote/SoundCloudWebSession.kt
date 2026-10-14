package com.aurora.music.data.remote

import com.google.gson.JsonParser
import java.io.IOException
import java.util.Base64

/** The signed-in web player's soundcloud.com cookies; kept in encrypted storage, never logged. */
class SoundCloudWebSession(val cookie: String, val userAgent: String = "", val origin: String = SoundCloudClient.WEB) {
    private val cookies: Map<String, String> = cookie.split(';').mapNotNull {
        val parts = it.trim().split('=', limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
    }.toMap()

    val token: String get() = cookies["oauth_token"].orEmpty()
    val canRefresh: Boolean get() = !cookies["oauth_refresh_token"].isNullOrBlank()

    // phones get the mobile site, which keeps its own cookies and refresh route
    val refreshUrl: String get() = if (origin == MOBILE) "$MOBILE/-/refresh-token" else "$origin/n/api/refresh-token"

    // the access token is a jwt that lives a day
    val expiresAt: Long get() = runCatching {
        JsonParser.parseString(String(Base64.getUrlDecoder().decode(token.split('.')[1]))).asJsonObject.get("exp").asLong
    }.getOrDefault(0L)

    fun validate(): SoundCloudWebSession {
        if (token.isBlank() || origin !in ORIGINS || '\r' in cookie || '\n' in cookie || '\r' in userAgent || '\n' in userAgent)
            throw IOException("Finish signing in to SoundCloud, then try connecting again.")
        return this
    }

    // applies the set-cookie headers of a token refresh, which rotates both tokens
    fun refreshed(setCookies: List<String>): SoundCloudWebSession {
        val updated = cookies.toMutableMap()
        setCookies.forEach { header ->
            val pair = header.substringBefore(';').split('=', limit = 2)
            if (pair.size != 2 || pair[0].isBlank()) return@forEach
            val name = pair[0].trim()
            if (pair[1].isBlank() || header.contains("Max-Age=0", ignoreCase = true)) updated.remove(name) else updated[name] = pair[1]
        }
        return SoundCloudWebSession(updated.entries.joinToString("; ") { "${it.key}=${it.value}" }, userAgent, origin)
    }

    fun encode(): String = json("cookie" to cookie, "userAgent" to userAgent, "origin" to origin).toString()

    companion object {
        const val LOGIN_URL = "https://soundcloud.com/signin"
        const val MOBILE = "https://m.soundcloud.com"
        val ORIGINS = listOf(MOBILE, SoundCloudClient.WEB)

        fun decode(value: String): SoundCloudWebSession = try {
            val obj = JsonParser.parseString(value).asJsonObject
            SoundCloudWebSession(obj.string("cookie"), obj.string("userAgent"), obj.string("origin").ifBlank { SoundCloudClient.WEB }).validate()
        } catch (_: Exception) {
            throw IOException("Reconnect your SoundCloud account in Settings → Accounts.")
        }
    }
}
