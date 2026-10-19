package com.aurora.music.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.aurora.music.data.MusicRepository
import com.aurora.music.model.Song

fun songMediaItem(song: Song, repository: MusicRepository): MediaItem = MediaItem.Builder()
    .setMediaId(song.id)
    .setUri(song.streamUrl)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .apply { if (song.artworkUrl.isNotBlank()) setArtworkUri(Uri.parse(song.artworkUrl)) }
            .setExtras(PresetContextPublisher.extras(song, repository.playbackSourceIdentity(song)))
            .build()
    )
    .build()
