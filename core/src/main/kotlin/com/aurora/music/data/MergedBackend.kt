package com.aurora.music.data

import com.aurora.music.model.Album
import com.aurora.music.model.Artist
import com.aurora.music.model.DetailInfo
import com.aurora.music.model.Playlist
import com.aurora.music.model.Song
import com.aurora.music.util.TrackMatch
import com.aurora.music.util.accentArgbFor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// composite backend merging several live backends ids namespaced per source so calls route back
class MergedBackend(
    private val sources: List<MediaBackend>,
    override val session: Session,
    private val priority: () -> List<String> = { DEFAULT_SOURCE_PRIORITY },
    private val downloads: () -> List<Song> = { emptyList() },
    private val catalogueMatch: () -> CatalogueMatchPrefs = { CatalogueMatchPrefs() },
    // a library view over some of the sources; it offers no further views or catalogue feeds
    private val nested: Boolean = false,
) : MediaBackend {

    private fun catalogueOf(type: ServerType) = sources.indexOfFirst { it.session.type == type && it.session.accountKey() == session.accountKey() }
        .takeIf { it >= 0 } ?: sources.indexOfFirst { it.session.type == type }
    private val youtubeIndex = catalogueOf(ServerType.YOUTUBE_MUSIC)
    private val spotifyIndex = catalogueOf(ServerType.SPOTIFY)
    private val soundcloudIndex = catalogueOf(ServerType.SOUNDCLOUD)
    private fun isCatalogue(source: MediaBackend) = source.session.type.isCatalogue

    private fun catalogueFor(id: String): Int = when (id) {
        "discovery", "youtube" -> youtubeIndex
        "spotify" -> spotifyIndex
        "soundcloud" -> soundcloudIndex
        else -> -1
    }

    // library first, then each streaming catalogue on its own; a lone choice is no choice
    private fun <T> choices(make: (String, String) -> T, libraryId: String, libraryLabel: String, youtubeId: String): List<T> =
        if (nested) emptyList() else buildList {
            add(make(libraryId, libraryLabel))
            if (youtubeIndex >= 0) add(make(youtubeId, "YouTube Music"))
            if (spotifyIndex >= 0) add(make("spotify", "Spotify"))
            if (soundcloudIndex >= 0) add(make("soundcloud", "SoundCloud"))
        }.takeIf { it.size > 1 }.orEmpty()

    override val homeFeeds: List<HomeFeedChoice> get() = choices(::HomeFeedChoice, "library", "Library", "discovery")
    override val searchSources: List<SearchSourceChoice> get() = choices(::SearchSourceChoice, "library", "Local & servers", "discovery")
    override val libraryViews: List<LibraryViewChoice> get() = choices(::LibraryViewChoice, "library", "Local & servers", "youtube")

    private val views = HashMap<String, MediaBackend>()

    // views reuse the same namespaced ids, so anything they hand out still routes through this backend
    override fun libraryView(view: String): MediaBackend {
        if (nested) return this
        return synchronized(views) {
            views.getOrPut(view) {
                val picked = when (view) {
                    "library" -> sources.filterNot(::isCatalogue)
                    else -> listOfNotNull(sources.getOrNull(catalogueFor(view)))
                }
                if (picked.isEmpty()) this else MergedBackend(picked, session, priority, downloads, catalogueMatch, nested = true)
            }
        }
    }

    override suspend fun home(feed: String): HomeData {
        val index = catalogueFor(feed)
        return if (index >= 0) sources[index].home().wrapHome(index) else home()
    }

    override suspend fun homePage(feed: String, continuation: String): HomeData {
        val index = catalogueFor(feed)
        return if (index >= 0) sources[index].homePage(continuation).wrapHome(index) else HomeData()
    }

    private fun HomeData.wrapHome(i: Int) = copy(
        newReleases = newReleases.map { it.wrap(i) }, recentlyPlayed = recentlyPlayed.map { it.wrap(i) },
        mostPlayed = mostPlayed.map { it.wrap(i) }, random = random.map { it.wrap(i) },
        playlists = playlists.map { it.wrap(i) }, artists = artists.map { it.wrap(i) }, starred = starred.map { it.wrap(i) },
        sections = sections.map { section -> section.copy(id = wrapId(i, section.id), items = section.items.map { entry -> when (entry) {
            is HomeFeedItem.Track -> HomeFeedItem.Track(entry.song.wrap(i))
            is HomeFeedItem.Record -> HomeFeedItem.Record(entry.album.wrap(i))
            is HomeFeedItem.Performer -> HomeFeedItem.Performer(entry.artist.wrap(i))
            is HomeFeedItem.Collection -> HomeFeedItem.Collection(entry.playlist.wrap(i))
        } }) },
    )

    private val matchMutex = Mutex()
    private val matchCache = LinkedHashMap<String, Pair<Long, List<Song>>>()

    override suspend fun playbackCandidates(song: Song): List<Song> {
        if (SEP in song.id && unwrap(song.id) == null) return emptyList()
        val index = unwrap(song.id)?.first ?: sources.indexOfFirst { it.session.accountKey() == session.accountKey() }
        if (index !in sources.indices) return emptyList()
        if (!isCatalogue(sources[index])) return emptyList()
        val rules = catalogueMatch()
        if (!rules.useLibrary) return emptyList()
        val order = priority()
        val copies = matchMutex.withLock {
            val title = recordingTitle(song.title)
            val key = order.joinToString(",") + "\u0000" + title + "\u0000" + song.artist
            val cached = matchCache[key]?.takeIf { System.nanoTime() - it.first < 60_000_000_000L }
            cached?.second ?: coroutineScope {
                sources.mapIndexedNotNull { i, source ->
                    if (isCatalogue(source)) null else async<List<Song>> {
                        try {
                            withTimeoutOrNull(3_000) { source.matchingSongs(song).map { it.wrap(i) } }.orEmpty()
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { emptyList<Song>() }
                    }
                }.awaitAll().flatten()
            }.also {
                matchCache[key] = System.nanoTime() to it
                while (matchCache.size > 128) matchCache.remove(matchCache.keys.first())
            }
        }
        fun tier(copy: Song) = when {
            copy.playbackSource?.source == com.aurora.music.data.rules.RuleSource.DOWNLOAD -> "downloaded"
            copy.streamUrl.startsWith("file:") || copy.streamUrl.startsWith("content:") -> "local"
            else -> "stream"
        }
        return (copies + downloads()).filter { copy ->
            copy.streamUrl.isNotBlank() && (recordingMatches(song, copy) || rules.relaxed && sameLength(song, copy)) &&
                (!rules.losslessOnly || copy.suffix.lowercase() in LOSSLESS)
        }
            .distinctBy { it.streamUrl }
            .sortedWith(compareBy<Song> { order.indexOf(tier(it)).let { rank -> if (rank < 0) Int.MAX_VALUE else rank } }
                .thenBy { if (song.durationSec > 0 && it.durationSec > 0 && kotlin.math.abs(song.durationSec - it.durationSec) > TrackMatch.DURATION_TOLERANCE_SEC) 1 else 0 }
                .thenByDescending { qualityScore(it) })
            .map { song.withPlaybackFrom(it) }
    }

    // a differently spelled artist still counts when the title and the length agree closely
    private fun sameLength(song: Song, copy: Song) = recordingTitlesMatch(song, copy) && song.durationSec > 0 && copy.durationSec > 0 &&
        kotlin.math.abs(song.durationSec - copy.durationSec) <= RELAXED_DURATION_SEC

    private val primary: MediaBackend? = sources.firstOrNull { it.session.accountKey() == session.accountKey() }
        ?: sources.firstOrNull { it.session.type != ServerType.LOCAL } ?: sources.firstOrNull()

    private val namespaces = sources.map { "s" + PlaybackSourceIdentity.fromSession(it.session, "").providerId!!.removePrefix("provider:") }
    private fun wrapId(idx: Int, id: String): String = if (id.isBlank()) "" else "${namespaces[idx]}$SEP$id"
    private fun unwrap(wrapped: String): Pair<Int, String>? {
        val i = wrapped.indexOf(SEP)
        if (i <= 0) return null
        val namespace = wrapped.substring(0, i)
        val idx = namespace.toIntOrNull() ?: namespaces.indexOf(namespace)
        if (idx !in sources.indices) return null
        return idx to wrapped.substring(i + 1)
    }

    private fun Song.wrap(idx: Int) = copy(
        id = wrapId(idx, id),
        albumId = wrapId(idx, albumId),
        artistId = wrapId(idx, artistId),
        playbackSource = playbackSource ?: sources[idx].playbackSourceIdentity(this),
    )
    private fun Album.wrap(idx: Int) = copy(id = wrapId(idx, id))
    private fun Artist.wrap(idx: Int) = copy(id = wrapId(idx, id))
    private fun Playlist.wrap(idx: Int) = copy(id = wrapId(idx, id))

    override fun playbackSourceIdentity(song: Song): PlaybackSourceIdentity? {
        song.playbackSource?.let { return it }
        val (index, id) = unwrap(song.id) ?: return null
        val album = unwrap(song.albumId)?.takeIf { it.first == index }?.second.orEmpty()
        return sources[index].playbackSourceIdentity(song.copy(id = id, albumId = album))
    }

    override fun playbackCollectionIdentity(kind: String, id: String, name: String?): PlaybackCollectionIdentity? {
        val (index, original) = unwrap(id) ?: return null
        return sources[index].playbackCollectionIdentity(kind, original, name)
    }

    private suspend fun <T> fanOut(timeoutMs: (MediaBackend) -> Long = { SOURCE_TIMEOUT_MS }, block: suspend (MediaBackend) -> List<T>): List<List<T>> = coroutineScope {
        sources.mapIndexed { i, src -> async { reach(i) { withTimeoutOrNull(timeoutMs(src)) { block(src) } } ?: emptyList() } }
            .awaitAll()
    }

    // a source that just failed, timed out or answered empty only after a connect timeout is skipped for a while
    // instead of stalling every call; servers swallow connection errors into empty lists, hence the slow-empty rule
    private val downUntil = java.util.concurrent.ConcurrentHashMap<Int, Long>()

    private suspend fun <R> reach(index: Int, call: suspend () -> R?): R? {
        if ((downUntil[index] ?: 0L) > System.currentTimeMillis()) return null
        val started = System.currentTimeMillis()
        val result = try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val slowEmpty = (result as? Collection<*>)?.isEmpty() == true && System.currentTimeMillis() - started >= SLOW_EMPTY_MS
        if (result == null || slowEmpty) downUntil[index] = System.currentTimeMillis() + DOWN_MS else downUntil.remove(index)
        return result
    }

    private fun dedupAlbums(all: List<Album>): List<Album> =
        all.distinctBy { TrackMatch.key(it.artist, it.title) }

    private fun dedupArtists(all: List<Artist>): List<Artist> =
        all.distinctBy { TrackMatch.identity(it.name) }.filter { it.name.isNotBlank() }

    // cluster by real duration window not fixed bins so near-equal durations dont split across a boundary
    private fun dedupSongs(all: List<Song>): List<Song> =
        all.groupBy { TrackMatch.key(it.artist, it.title) }
            .values
            .flatMap { group ->
                val clusters = ArrayList<MutableList<Song>>()
                for (s in group.sortedBy { it.durationSec }) {
                    val cur = clusters.lastOrNull()
                    if (cur != null && s.durationSec - cur.first().durationSec <= TrackMatch.DURATION_TOLERANCE_SEC) cur.add(s)
                    else clusters.add(mutableListOf(s))
                }
                clusters.map { c -> c.maxByOrNull { qualityScore(it) } ?: c.first() }
            }

    private fun qualityScore(s: Song): Long {
        var score = 0L
        if (s.suffix.lowercase() in LOSSLESS) score += 2_000_000
        score += s.bitDepth.toLong() * 100_000
        score += s.sampleRateHz.toLong() / 100
        score += s.bitrateKbps.toLong()
        return score
    }

    override suspend fun ping(): Boolean = fanOut { listOf(it.ping()) }.any { it.firstOrNull() == true }

    override suspend fun home(): HomeData {
        val homes = coroutineScope {
            sources.mapIndexed { idx, src -> async { idx to if (isCatalogue(src)) null
                else reach(idx) { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { src.home() } } } }.awaitAll()
        }
        val albums = ArrayList<Album>(); val recent = ArrayList<Album>(); val most = ArrayList<Album>(); val random = ArrayList<Album>()
        val playlists = ArrayList<Playlist>(); val artists = ArrayList<Artist>(); val starred = ArrayList<Song>()
        val sections = ArrayList<HomeFeedSection>()
        for ((idx, h) in homes) {
            if (h == null) continue
            albums += h.newReleases.map { it.wrap(idx) }
            recent += h.recentlyPlayed.map { it.wrap(idx) }
            most += h.mostPlayed.map { it.wrap(idx) }
            random += h.random.map { it.wrap(idx) }
            playlists += h.playlists.map { it.wrap(idx) }
            artists += h.artists.map { it.wrap(idx) }
            starred += h.starred.map { it.wrap(idx) }
            sections += h.wrapHome(idx).sections
        }
        return HomeData(
            newReleases = dedupAlbums(albums),
            recentlyPlayed = dedupAlbums(recent),
            mostPlayed = dedupAlbums(most),
            random = dedupAlbums(random),
            playlists = playlists,
            artists = dedupArtists(artists),
            starred = dedupSongs(starred),
            sections = sections,
        )
    }

    override suspend fun allAlbums(): List<Album> = dedupAlbums(wrapAll(fanOut { it.allAlbums() }) { a, i -> a.wrap(i) })
    override suspend fun allArtists(): List<Artist> = dedupArtists(wrapAll(fanOut { it.allArtists() }) { a, i -> a.wrap(i) })
    override suspend fun allPlaylists(): List<Playlist> = wrapAll(fanOut { it.allPlaylists() }) { p, i -> p.wrap(i) }
    override suspend fun allSongs(): List<Song> = dedupSongs(wrapAll(fanOut { it.allSongs() }) { s, i -> s.wrap(i) })
    override suspend fun librarySongs(limit: Int): List<Song> = dedupSongs(wrapAll(fanOut { it.librarySongs(limit) }) { s, i -> s.wrap(i) })
    // each source pages independently so a page is only locally deduped; cross-page dupes are rare and harmless
    override suspend fun songsPage(offset: Int, count: Int): List<Song> =
        dedupSongs(wrapAll(fanOut { it.songsPage(offset, count) }) { s, i -> s.wrap(i) }).sortedBy { it.title.lowercase() }
    // a whole streaming library of likes takes a while to page in; servers keep the usual limit
    override suspend fun starredSongs(): List<Song> =
        dedupSongs(wrapAll(fanOut({ if (isCatalogue(it)) LIKED_TIMEOUT_MS else SOURCE_TIMEOUT_MS }) { it.starredSongs() }) { s, i -> s.wrap(i) })
    // the count matches the deduped liked list; a single-source view asks its source directly
    override suspend fun starredCount(): Int = sources.singleOrNull()?.starredCount() ?: starredSongs().size
    override suspend fun starredIds(): Set<String> =
        sources.indices.zip(fanOut { it.starredIds().toList() }).flatMap { (i, ids) -> ids.map { wrapId(i, it) } }.toSet()

    override suspend fun search(query: String): SearchResults = search(query, "discovery")

    override suspend fun search(query: String, source: String): SearchResults {
        // a missing catalogue falls back to the other one before the merged library
        val index = when (source) {
            "library" -> -1
            "spotify" -> spotifyIndex.takeIf { it >= 0 } ?: youtubeIndex
            "soundcloud" -> soundcloudIndex.takeIf { it >= 0 } ?: youtubeIndex
            else -> youtubeIndex.takeIf { it >= 0 } ?: spotifyIndex.takeIf { it >= 0 } ?: soundcloudIndex
        }
        if (index >= 0) {
            val result = sources[index].search(query)
            return result.copy(songs = result.songs.map { it.wrap(index) },
                albums = result.albums.map { it.wrap(index) }, artists = result.artists.map { it.wrap(index) },
                playlists = result.playlists.map { it.wrap(index) })
        }
        val results = coroutineScope {
            sources.mapIndexed { idx, src -> async { idx to if (isCatalogue(src)) null
                else reach(idx) { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { src.search(query) } } } }.awaitAll()
        }
        val songs = ArrayList<Song>(); val albums = ArrayList<Album>(); val artists = ArrayList<Artist>(); val playlists = ArrayList<Playlist>()
        for ((idx, r) in results) {
            if (r == null) continue
            songs += r.songs.map { it.wrap(idx) }
            albums += r.albums.map { it.wrap(idx) }
            artists += r.artists.map { it.wrap(idx) }
            playlists += r.playlists.map { it.wrap(idx) }
        }
        return SearchResults(dedupSongs(songs), dedupAlbums(albums), dedupArtists(artists), playlists)
    }

    override suspend fun likedSongIds(ids: List<String>): Set<String> {
        val bySource = ids.mapNotNull { unwrap(it) }.groupBy({ it.first }, { it.second })
        val liked = HashSet<String>()
        for ((idx, origIds) in bySource) {
            val src = sources.getOrNull(idx) ?: continue
            runCatching { src.likedSongIds(origIds) }.getOrNull()?.forEach { liked += wrapId(idx, it) }
        }
        return liked
    }

    override val supportsFolders: Boolean get() = sources.any { it.supportsFolders }

    override suspend fun browseFolder(folderId: String): FolderContent? {
        if (folderId.isBlank()) {
            val perSource = coroutineScope {
                sources.mapIndexed { i, s ->
                    async { i to if (s.supportsFolders) runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { s.browseFolder("") } }.getOrNull() else null }
                }.awaitAll()
            }
            val folders = ArrayList<FolderNode>(); val songs = ArrayList<Song>()
            for ((i, c) in perSource) {
                if (c == null) continue
                folders += c.folders.map { FolderNode(wrapId(i, it.id), it.name) }
                songs += c.songs.map { it.wrap(i) }
            }
            return FolderContent(id = "", title = "Folders", folders = folders, songs = songs)
        }
        val (i, oid) = unwrap(folderId) ?: return null
        val c = runCatching { sources.getOrNull(i)?.browseFolder(oid) }.getOrNull() ?: return null
        return c.copy(
            id = wrapId(i, c.id),
            folders = c.folders.map { FolderNode(wrapId(i, it.id), it.name) },
            songs = c.songs.map { it.wrap(i) },
        )
    }

    override val supportsServerTagEdit: Boolean get() = sources.any { it.supportsServerTagEdit }
    override suspend fun readMetadata(songId: String): AudioTags? = route(songId) { src, _, oid -> src.readMetadata(oid) }
    override suspend fun updateMetadata(songId: String, tags: AudioTags): Boolean =
        route(songId) { src, _, oid -> src.updateMetadata(oid, tags) } ?: false

    override suspend fun songFor(id: String): Song? = route(id) { src, i, oid -> src.songFor(oid)?.wrap(i) }
    override suspend fun radio(seedId: String): List<Song> = route(seedId) { src, i, oid -> src.radio(oid).map { it.wrap(i) } } ?: emptyList()
    override fun prefersServerRadio(seedId: String): Boolean {
        val (index, original) = unwrap(seedId) ?: return if (SEP in seedId) false
            else primary?.prefersServerRadio(seedId) ?: false
        return sources[index].prefersServerRadio(original)
    }
    override suspend fun scrobble(id: String) { route(id) { src, _, oid -> src.scrobble(oid) } }

    override fun playbackReportTarget(song: Song): PlaybackReportTarget? {
        val provider = song.playbackSource?.providerId ?: return null
        val source = sources.singleOrNull { PlaybackSourceIdentity.fromSession(it.session, "").providerId == provider } ?: return null
        val id = song.playbackSource.songId ?: song.id
        val original = if (SEP in id) {
            val (index, raw) = unwrap(id) ?: return null
            if (sources[index] !== source) return null
            raw
        } else id
        return source.playbackReportTarget(song.copy(id = original, playbackSource = song.playbackSource.copy(songId = original)))
    }
    override suspend fun setStarred(id: String, starred: Boolean, kind: String): Boolean =
        route(id) { src, _, oid -> src.setStarred(oid, starred, kind) } ?: false

    // liked songs follow the library view they were opened from; any other id is every source together
    private fun likedScope(id: String): MediaBackend = if (!nested && id in VIEW_IDS) libraryView(id) else this

    override suspend fun detail(kind: String, id: String): DetailData? {
        if (kind == "liked") {
            val songs = likedScope(id).starredSongs()
            val subtitle = if (id == ALL_LIKED_ID) "${songs.size} songs from every source" else "${songs.size} songs you love"
            return DetailData(
                DetailInfo("Liked Songs", subtitle, songs.firstOrNull()?.artworkUrl ?: "", accentArgbFor("liked"), false, songs.size, "Liked"),
                songs,
            )
        }
        return route(id) { src, i, oid ->
            src.detail(kind, oid)?.let { d -> d.copy(tracks = d.tracks.map { it.wrap(i) }, albums = d.albums.map { it.wrap(i) }) }
        }
    }
    override suspend fun detailPage(kind: String, id: String, offset: Int): List<Song> =
        route(id) { src, i, oid -> src.detailPage(kind, oid, offset).map { it.wrap(i) } } ?: emptyList()

    override suspend fun collectionTracks(kind: String, id: String): List<Song> =
        if (kind == "liked") likedScope(id).starredSongs()
        else route(id) { src, i, oid -> src.collectionTracks(kind, oid).map { it.wrap(i) } }
            ?: error("This collection's source is unavailable.")

    // a song from another source borrows the canvas or comments of its twin on a merged catalogue
    private val twins = object : LinkedHashMap<String, Song?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song?>?) = size > 128
    }

    private suspend fun twinOn(index: Int, song: Song): Song? {
        val source = sources.getOrNull(index) ?: return null
        val key = "$index\u0000${song.id}"
        synchronized(twins) { if (twins.containsKey(key)) return twins[key] }
        // a failed search isn't remembered, so the next try asks again
        val results = try { source.search("${recordingTitle(song.title)} ${song.artist}".trim()).songs }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        val twin = results.firstOrNull { twinMatches(song, it) }
        synchronized(twins) { twins[key] = twin }
        return twin
    }

    // uploads often carry "artist - title" in the title, posted by a label or fan account
    private fun twinMatches(song: Song, candidate: Song): Boolean {
        val parts = candidate.title.split(" - ", " \u2013 ", limit = 2)
        val split = if (parts.size == 2) candidate.copy(artist = parts[0], title = parts[1]) else candidate
        return listOf(candidate, split).any { recordingMatches(song, it) || sameLength(song, it) }
    }

    override fun sourceType(song: Song): ServerType = unwrap(song.id)?.let { sources[it.first].session.type } ?: session.type

    private fun borrows(catalogue: Int, owner: Int, song: Song) = catalogue >= 0 && owner != catalogue && !song.isRadio() && !song.isPodcast()

    override fun supportsCanvas(song: Song): Boolean {
        val (i, oid) = unwrap(song.id) ?: return false
        return sources[i].supportsCanvas(song.copy(id = oid)) || borrows(spotifyIndex, i, song)
    }

    override suspend fun canvasUrl(song: Song): String? {
        val (i, oid) = unwrap(song.id) ?: return null
        val original = song.copy(id = oid)
        if (sources[i].supportsCanvas(original)) return sources[i].canvasUrl(original)
        if (!borrows(spotifyIndex, i, song)) return null
        val twin = twinOn(spotifyIndex, song) ?: return null
        return sources[spotifyIndex].takeIf { it.supportsCanvas(twin) }?.canvasUrl(twin)
    }

    override suspend fun catalogueLyrics(song: Song): Lyrics? {
        val (i, _) = unwrap(song.id) ?: return null
        if (!borrows(spotifyIndex, i, song)) return null
        return twinOn(spotifyIndex, song)?.let { sources[spotifyIndex].serverLyrics(it) }
    }

    override fun supportsComments(song: Song): Boolean {
        val (i, oid) = unwrap(song.id) ?: return false
        return sources[i].supportsComments(song.copy(id = oid)) || borrows(soundcloudIndex, i, song)
    }

    override suspend fun comments(song: Song): List<com.aurora.music.model.TrackComment>? {
        val (i, oid) = unwrap(song.id) ?: return null
        val original = song.copy(id = oid)
        if (sources[i].supportsComments(original)) return sources[i].comments(original)
        if (!borrows(soundcloudIndex, i, song)) return null
        return twinOn(soundcloudIndex, song)?.let { sources[soundcloudIndex].comments(it) }
    }

    override suspend fun postComment(song: Song, body: String, timestampMs: Long): com.aurora.music.model.TrackComment? {
        val (i, oid) = unwrap(song.id) ?: return null
        val original = song.copy(id = oid)
        if (sources[i].supportsComments(original)) return sources[i].postComment(original, body, timestampMs)
        if (!borrows(soundcloudIndex, i, song)) return null
        return twinOn(soundcloudIndex, song)?.let { sources[soundcloudIndex].postComment(it, body, timestampMs) }
    }

    override suspend fun serverLyrics(song: Song): Lyrics? {
        val (i, oid) = unwrap(song.id) ?: return primary?.serverLyrics(song)
        return sources.getOrNull(i)?.serverLyrics(song.copy(id = oid))
    }

    override fun streamUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        val (i, oid) = unwrap(songId) ?: return if (SEP in songId) "" else primary?.streamUrl(songId, maxBitrate, lossless) ?: ""
        return sources.getOrNull(i)?.streamUrl(oid, maxBitrate, lossless) ?: ""
    }
    // each source resolves its own download link, such as soundcloud's hls stream behind its play link
    override suspend fun downloadUrl(songId: String, maxBitrate: Int, lossless: Boolean): String {
        val (i, oid) = unwrap(songId) ?: return if (SEP in songId) "" else primary?.downloadUrl(songId, maxBitrate, lossless) ?: ""
        return sources.getOrNull(i)?.downloadUrl(oid, maxBitrate, lossless) ?: ""
    }
    override fun coverArtUrl(id: String, size: Int): String {
        val (i, oid) = unwrap(id) ?: return if (SEP in id) "" else primary?.coverArtUrl(id, size) ?: ""
        return sources.getOrNull(i)?.coverArtUrl(oid, size) ?: ""
    }

    override suspend fun createPlaylist(name: String): Boolean = primary?.createPlaylist(name) ?: false
    override suspend fun createPlaylistWithId(name: String): String? {
        val pIdx = sources.indexOfFirst { it === primary }
        val pid = primary?.createPlaylistWithId(name) ?: return null
        return if (pIdx >= 0) wrapId(pIdx, pid) else pid
    }
    override suspend fun createPlaylistWithId(name: String, trackIds: List<String>): String? {
        if (name.isBlank()) return null
        val source = primary ?: return null
        val index = sources.indexOfFirst { it === source }
        if (index < 0) return null
        val ids = trackIds.map { unwrap(it) ?: return null }
        if (ids.any { it.first != index || it.second.isBlank() }) return null
        val id = source.createPlaylistWithId(name.trim(), ids.map { it.second }) ?: return null
        return wrapId(index, id)
    }
    override suspend fun updatePlaylist(id: String, name: String?, comment: String?): Boolean =
        route(id) { src, _, oid -> src.updatePlaylist(oid, name, comment) } ?: false
    override suspend fun deletePlaylist(id: String): Boolean =
        route(id) { src, _, oid -> src.deletePlaylist(oid) } ?: false

    override suspend fun addToPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        val (i, oPid) = unwrap(playlistId) ?: return false
        val src = sources.getOrNull(i) ?: return false
        val ids = trackIds.map { unwrap(it) ?: return false }
        if (ids.any { it.first != i }) return false
        return src.addToPlaylist(oPid, ids.map { it.second })
    }

    override suspend fun playlistsForSong(songId: String): List<Playlist> {
        val (index, original) = unwrap(songId) ?: return emptyList()
        return sources[index].playlistsForSong(original).map { it.wrap(index) }
    }

    override suspend fun removeFromPlaylist(playlistId: String, trackIds: List<String>): Boolean {
        val (index, original) = unwrap(playlistId) ?: return false
        val ids = trackIds.map { unwrap(it) ?: return false }
        if (ids.any { it.first != index }) return false
        return sources[index].removeFromPlaylist(original, ids.map { it.second })
    }

    override suspend fun profileImageUrl(): String = primary?.profileImageUrl() ?: ""

    private fun <T> wrapAll(perSource: List<List<T>>, wrap: (T, Int) -> T): List<T> =
        perSource.flatMapIndexed { i, list -> list.map { wrap(it, i) } }

    private suspend fun <R> route(id: String, block: suspend (MediaBackend, Int, String) -> R): R? {
        return try {
            val (i, oid) = unwrap(id) ?: return if (SEP in id) null else primary?.let { p -> block(p, sources.indexOf(p), id) }
            val src = sources.getOrNull(i) ?: return null
            block(src, i, oid)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private companion object {
        const val SEP = '\u0001'   // control char that never appears in a real backend id
        const val SOURCE_TIMEOUT_MS = 20_000L
        const val LIKED_TIMEOUT_MS = 90_000L
        const val DOWN_MS = 60_000L
        const val SLOW_EMPTY_MS = 8_000L
        val VIEW_IDS = setOf("library", "youtube", "spotify", "soundcloud")
        val LOSSLESS = setOf("flac", "alac", "wav", "aiff", "aif", "ape", "wv", "dsf", "dff")
        const val RELAXED_DURATION_SEC = 2
    }
}

const val MERGE_NAMESPACE_SEP = '\u0001'   // same control char as MergedBackend.SEP

// liked songs id for every source's likes together, beside the per-view ids
const val ALL_LIKED_ID = "all"

private val MERGE_NAMESPACE_PATTERN = Regex("s[0-9a-f]{64}")

fun stripMergeNamespace(id: String): String {
    val i = id.indexOf(MERGE_NAMESPACE_SEP)
    if (i <= 0) return id
    val prefix = id.substring(0, i)
    return if (prefix.all { it.isDigit() } || prefix.matches(MERGE_NAMESPACE_PATTERN)) id.substring(i + 1) else id
}
