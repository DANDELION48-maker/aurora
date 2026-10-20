package com.aurora.music.ui.components

import com.aurora.music.localization.appString
import com.aurora.music.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.model.Song
import com.aurora.music.model.accent

// one streaming feed shelf as home shows it: a header, then a sideways row of albums, playlists, artists or songs
@Composable
fun FeedSection(
    section: HomeFeedSection,
    onOpenDetail: (String, String) -> Unit,
    onPlayAll: (List<Song>, Int) -> Unit,
    gutter: Dp = 16.dp,
) {
    val tracks = remember(section) { section.items.filterIsInstance<HomeFeedItem.Track>().map { it.song } }
    Column {
        SectionHeader(section.title, Modifier.padding(horizontal = gutter))
        if (section.subtitle.isNotBlank()) Text(section.subtitle,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = gutter, vertical = 4.dp))
        Spacer(Modifier.height(10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = (gutter - 8.dp).coerceAtLeast(0.dp))) {
            items(section.items.size, key = { section.items[it].key }) { index ->
                when (val entry = section.items[index]) {
                    is HomeFeedItem.Record -> AlbumCard(entry.album, { onOpenDetail("album", entry.album.id) })
                    is HomeFeedItem.Collection -> PlaylistCard(entry.playlist, { onOpenDetail("playlist", entry.playlist.id) })
                    is HomeFeedItem.Performer -> ArtistCircle(entry.artist, { onOpenDetail("artist", entry.artist.id) })
                    is HomeFeedItem.Track -> FeedTrackCard(entry.song) {
                        onPlayAll(tracks, tracks.indexOfFirst { it.id == entry.song.id }.coerceAtLeast(0))
                    }
                }
            }
        }
    }
}

@Composable
fun FeedTrackCard(song: Song, onClick: () -> Unit) {
    Column(Modifier.width(172.dp).clip(MaterialTheme.shapes.medium).clickable(onClick = onClick).padding(8.dp)) {
        Box {
            Artwork(song.artworkUrl, song.accent, Modifier.size(156.dp), corner = 14.dp)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(34.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, appString(R.string.text_play_5d12bd), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(song.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(song.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
