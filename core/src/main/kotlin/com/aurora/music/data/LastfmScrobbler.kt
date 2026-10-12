package com.aurora.music.data

import com.aurora.music.data.remote.LastfmClient
import com.aurora.music.data.remote.LastfmException
import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LastfmScrobbler(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val clientFactory: (String, String) -> LastfmClient = { key, secret -> LastfmClient(key, secret) },
) {
    // rebuilt whenever the user's key+secret change
    @Volatile private var client: LastfmClient? = null

    val configured: Boolean get() = client?.configured == true

    @Volatile private var sessionKey: String? = null
    @Volatile private var enabled: Boolean = true

    init {
        scope.launch {
            store.lastfm.collect { acct ->
                sessionKey = acct.sessionKey.ifBlank { null }
                enabled = acct.enabled
            }
        }
        scope.launch {
            store.lastfmKeys.collect { client = clientFor(it) }
        }
    }

    val isConnected: Boolean get() = !sessionKey.isNullOrBlank()

    private fun clientFor(keys: Pair<String, String>): LastfmClient? =
        if (keys.first.isNotBlank() && keys.second.isNotBlank()) clientFactory(keys.first, keys.second) else null

    // returns the url to approve in the browser, the token waits in settings until finishLink
    suspend fun beginLink(): Result<String> {
        val c = clientFor(store.lastfmKeys.first()) ?: return Result.failure(IllegalStateException("Last.fm keys are missing"))
        return c.getToken().onSuccess { store.setLastfmPendingToken(it) }.map(c::authorizeUrl)
    }

    suspend fun pendingAuthorizeUrl(): String? {
        val token = store.lastfmPendingToken.first().ifBlank { return null }
        return clientFor(store.lastfmKeys.first())?.authorizeUrl(token)
    }

    suspend fun finishLink(): Result<Unit> {
        val token = store.lastfmPendingToken.first().ifBlank { return Result.failure(IllegalStateException("No pending Last.fm link")) }
        val c = clientFor(store.lastfmKeys.first()) ?: return Result.failure(IllegalStateException("Last.fm keys are missing"))
        val session = c.getSession(token).getOrElse { e ->
            if ((e as? LastfmException)?.code in EXPIRED_TOKEN) store.setLastfmPendingToken(null)
            return Result.failure(e)
        }
        store.saveLastfm(session.key, session.name, c.userInfo(session.name)?.imageUrl ?: "")
        return Result.success(Unit)
    }

    suspend fun disconnect() = store.clearLastfm()

    fun nowPlaying(song: Song) {
        val c = client ?: return
        val sk = sessionKey ?: return
        if (!enabled || song.title.isBlank() || song.artist.isBlank()) return
        scope.launch {
            val artist = replaceScrobbleArtist(
                store.artistSeparators.first().split(song.artist).first(),
                store.lastfmArtistRules.first(),
            )
            c.updateNowPlaying(sk, artist, song.title, song.album.ifBlank { null })
        }
    }

    fun scrobble(song: Song, startedAtMs: Long) {
        val c = client ?: return
        val sk = sessionKey ?: return
        if (!enabled || song.title.isBlank() || song.artist.isBlank()) return
        scope.launch {
            val artist = replaceScrobbleArtist(
                store.artistSeparators.first().split(song.artist).first(),
                store.lastfmArtistRules.first(),
            )
            c.scrobble(sk, artist, song.title, song.album.ifBlank { null }, startedAtMs / 1000)
        }
    }

    private companion object {
        // invalid or expired token, a fresh connect is needed
        val EXPIRED_TOKEN = setOf(4, 15)
    }
}
