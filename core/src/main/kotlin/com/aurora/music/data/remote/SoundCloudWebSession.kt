package com.aurora.music.data.remote

import com.google.gson.JsonParser
import java.io.IOException
import java.util.Base64

/** The signed-in web player's soundcloud.com cookies; kept in encrypted storage, never logged. */
class SoundCloudWebSession(val cookie: String, val userAgent: String = "") {
    private val cookies: Map<String, String> = cookie.split(';').mapNotNull {
        val parts = it.trim().split('=', limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank()) parts[0] to parts[1] else null
    }.toMap()

    val token: String get() = cookies["oauth_token"].orEmpty()
    val canRefresh: Boolean get() = !cookies["oauth_refresh_token"].isNullOrBlank()

    // the access token is a jwt that lives a day
    val expiresAt: Long get() = runCatching {
        JsonParser.parseString(String(Base64.getUrlDecoder().decode(token.split('.')[1]))).asJsonObject.get("exp").asLong
    }.getOrDefault(0L)

    fun validate(): SoundCloudWebSession {
        if (token.isBlank() || '\r' in cookie || '\n' in cookie || '\r' in userAgent || '\n' in userAgent)
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
        return SoundCloudWebSession(updated.entries.joinToString("; ") { "${it.key}=${it.value}" }, userAgent)
    }

    fun encode(): String = json("cookie" to cookie, "userAgent" to userAgent).toString()

    companion object {
        const val LOGIN_URL = "https://soundcloud.com/signin"

        fun decode(value: String): SoundCloudWebSession = try {
            val obj = JsonParser.parseString(value).asJsonObject
            SoundCloudWebSession(obj.string("cookie"), obj.string("userAgent")).validate()
        } catch (_: Exception) {
            throw IOException("Reconnect your SoundCloud account in Settings → Accounts.")
        }
    }
}
