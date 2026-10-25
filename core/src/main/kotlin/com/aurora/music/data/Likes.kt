package com.aurora.music.data

import com.aurora.music.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// the hearts: a song is liked when its own source likes it or any other active account likes the same recording
class Likes(
    private val scope: CoroutineScope,
    private val repository: MusicRepository,
    private val savePlaylistLike: suspend (String, Boolean) -> Unit,
) {
    val ledger = LikeLedger(repository::likeSources) { repository.activeSession }

    private val _likedIds = MutableStateFlow<Set<String>>(emptySet())
    val likedIds: StateFlow<Set<String>> = _likedIds.asStateFlow()
    private val _failures = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val failures: SharedFlow<Unit> = _failures.asSharedFlow()

    private var serverLikedIds: Set<String> = emptySet()
    private var playlistIds: Set<String> = emptySet()
    private val checked = HashSet<String>()
    private var edits = 0
    private val inFlight = mutableListOf<String>()
    private var ledgerLoad: Job? = null
    private val seen = object : LinkedHashMap<String, Pair<Song, String>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Song, String>>?) = size > SEEN_CAP
    }

    init {
        scope.launch { ledger.revision.collect { publish() } }
    }

    private fun publish() {
        val elsewhere = seen.entries.mapNotNullTo(HashSet()) { (id, entry) -> id.takeIf { ledger.likedAnywhere(entry.first, entry.second) } }
        _likedIds.value = serverLikedIds + elsewhere + playlistIds
    }

    fun setLikedPlaylists(ids: Set<String>) {
        playlistIds = ids
        publish()
    }

    // likes belong to the account, so the incoming one starts from its own
    fun reset() {
        ledgerLoad?.cancel()
        serverLikedIds = emptySet()
        checked.clear()
        seen.clear()
        publish()
        refresh()
    }

    fun refresh() {
        scope.launch {
            val before = edits
            val fetched = attempt(null) { repository.starredIds() } ?: return@launch
            // a like toggled mid-fetch makes the fetched set stale
            if (before != edits) return@launch
            serverLikedIds = fetched - inFlight.toSet() + inFlight.filter { it in serverLikedIds }
            publish()
            attempt(Unit) { repository.markDownloadsLiked(fetched, true) }
        }
        // a streaming library of likes can take a while; a resume meanwhile doesn't start another
        if (ledgerLoad?.isActive != true) ledgerLoad = scope.launch { ledger.refresh() }
    }

    // the songs on screen; a starred set can be partial, so ones it doesn't have are asked about one by one
    fun check(songs: List<Song>) {
        val fresh = songs.filter { s -> s.id.isNotEmpty() && seen[s.id]?.first?.let { it.title != s.title || it.artist != s.artist } ?: true }.distinctBy { it.id }
        val toCheck = songs.map { it.id }.filter { it.isNotEmpty() && it !in serverLikedIds && it !in checked }.distinct()
        if (fresh.isEmpty() && toCheck.isEmpty()) return
        scope.launch {
            if (fresh.isNotEmpty()) {
                val keyed = withContext(Dispatchers.Default) { fresh.map { it to recordingKey(it.title) } }
                keyed.forEach { seen[it.first.id] = it }
                publish()
            }
            if (toCheck.isEmpty()) return@launch
            val liked = attempt(null) { repository.likedSongIds(toCheck) } ?: return@launch
            checked.addAll(toCheck)
            attempt(Unit) { repository.markDownloadsLiked(liked, true) }
            if (liked.isNotEmpty()) {
                serverLikedIds = serverLikedIds + liked
                publish()
            }
        }
    }

    // what a connected device reports for the song it plays
    fun remote(id: String, liked: Boolean) {
        serverLikedIds = if (liked) serverLikedIds + id else serverLikedIds - id
        publish()
    }

    // songs toggle on the source they play from; album, artist and playlist likes stay with the active source
    fun toggle(id: String, kind: String = "song") {
        if (id.isEmpty() || id.startsWith("aurora-mix:") || !repository.canLike(id)) return
        when (kind) {
            "song" -> toggle(seen[id]?.first ?: Song(id, "", "", "", "", 0))
            "playlist" -> {
                val now = id !in _likedIds.value
                playlistIds = if (now) playlistIds + id else playlistIds - id
                scope.launch { attempt(Unit) { savePlaylistLike(id, now) } }
                // backends that can't star playlists keep the local like
                launchChange(id, now, report = false) { repository.setStarred(id, now, "playlist") }
            }
            else -> {
                val now = id !in serverLikedIds
                launchChange(id, now) { repository.setStarred(id, now, kind) }
            }
        }
    }

    fun toggle(song: Song) {
        if (song.id.isEmpty() || song.id.startsWith("aurora-mix:") || !repository.canLike(song.id)) return
        val now = !ownLiked(song)
        launchChange(song.id, now) { setOwn(song, now) }
    }

    suspend fun options(song: Song): List<LikeOption> = ledger.options(song, song.id in serverLikedIds)

    // the menu's choice for one account; the playing one is the heart's own toggle
    suspend fun set(song: Song, option: LikeOption, liked: Boolean): List<LikeOption> {
        if (option.playing) change(song.id, liked) { setOwn(song, liked) }
        else if (option.available) change("${option.key}$MERGE_NAMESPACE_SEP${option.id}", liked) { ledger.set(song, option.key, liked) ?: false }
        return options(song)
    }

    private fun ownLiked(song: Song): Boolean =
        song.id in serverLikedIds || ledger.owner(song.id)?.let { ledger.likedOn(it.first, song) } == true

    private suspend fun setOwn(song: Song, liked: Boolean): Boolean {
        val key = ledger.owner(song.id)?.first?.takeUnless { song.id.startsWith("cached:") }
        return key?.let { ledger.set(song, it, liked) } ?: repository.setStarred(song.id, liked)
    }

    private fun launchChange(id: String, liked: Boolean, report: Boolean = true, call: suspend () -> Boolean) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { change(id, liked, report, call) }
    }

    private suspend fun change(id: String, liked: Boolean, report: Boolean = true, call: suspend () -> Boolean) {
        serverLikedIds = if (liked) serverLikedIds + id else serverLikedIds - id
        edits++
        inFlight += id
        publish()
        attempt(Unit) { repository.markDownloadsLiked(listOf(id), liked) }
        val ok = attempt(false) { call() }
        if (!ok && (id in serverLikedIds) == liked) {
            serverLikedIds = if (liked) serverLikedIds - id else serverLikedIds + id
            attempt(Unit) { repository.markDownloadsLiked(listOf(id), !liked) }
        }
        edits++
        inFlight -= id
        publish()
        if (!ok && report) _failures.tryEmit(Unit)
    }

    private companion object {
        const val SEEN_CAP = 5000
    }
}
