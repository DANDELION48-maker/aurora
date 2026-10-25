package com.aurora.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import com.aurora.music.data.CueClip

// cue sheet tracks carry their range in the url fragment; the player clips the album file to it
fun cueClipped(item: MediaItem): MediaItem {
    val clip = CueClip.of(item.localConfiguration?.uri?.toString() ?: return item) ?: return item
    val end = clip.endMs ?: C.TIME_END_OF_SOURCE
    if (item.clippingConfiguration.startPositionMs == clip.startMs && item.clippingConfiguration.endPositionMs == end) return item
    return item.buildUpon().setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
        .setStartPositionMs(clip.startMs).setEndPositionMs(end).build()).build()
}

fun cueSource(uri: Uri): Uri = if (CueClip.of(uri.toString()) == null) uri else uri.buildUpon().fragment(null).build()
