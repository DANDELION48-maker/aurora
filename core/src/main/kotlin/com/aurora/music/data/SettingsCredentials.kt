package com.aurora.music.data

import androidx.datastore.preferences.core.Preferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

fun Preferences.withoutCredentials(): Preferences = toMutablePreferences().apply {
    if (this[SettingsKeys.SERVER_TYPE] != ServerType.LOCAL.name) {
        remove(SettingsKeys.SALT)
        remove(SettingsKeys.TOKEN)
        remove(SettingsKeys.CLIENT_TOKEN)
    }
    this[SettingsKeys.SAVED_SESSIONS]?.let { json ->
        val sessions = runCatching { Gson().fromJson<List<Session>>(json, object : TypeToken<List<Session>>() {}.type) }.getOrNull().orEmpty()
        this[SettingsKeys.SAVED_SESSIONS] = Gson().toJson(sessions.filter { it.type == ServerType.LOCAL })
    }
    with(SettingsKeys) {
        listOf(ACOUSTID_KEY, LASTFM_SK, LASTFM_API_KEY, LASTFM_SECRET, LASTFM_PENDING_TOKEN, LISTENBRAINZ_TOKEN, DISCORD_TOKEN).forEach { remove(it) }
    }
}.toPreferences()

private val ACCOUNT_KEYS = with(SettingsKeys) {
    listOf(SERVER, USERNAME, SALT, TOKEN, SERVER_TYPE, USER_ID, USER_IMAGE, CLIENT_TOKEN, CLIENT_VERSION, SAVED_SESSIONS, ACOUSTID_KEY,
        LASTFM_SK, LASTFM_USER, LASTFM_IMAGE, LASTFM_API_KEY, LASTFM_SECRET, LASTFM_PENDING_TOKEN, LISTENBRAINZ_TOKEN, LISTENBRAINZ_USER, DISCORD_TOKEN, DISCORD_USER)
}

// a backup without sign-ins keeps the accounts already on this device
fun Preferences.withAccountsFrom(device: Preferences): Preferences =
    if (this != withoutCredentials()) this else toMutablePreferences().apply {
        ACCOUNT_KEYS.forEach { key -> device[key]?.let { this[key] = it } ?: remove(key) }
    }.toPreferences()
