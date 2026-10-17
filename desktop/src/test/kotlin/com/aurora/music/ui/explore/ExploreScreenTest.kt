package com.aurora.music.ui.explore

import androidx.compose.foundation.layout.PaddingValues
import com.aurora.music.data.ExploreCategory
import com.aurora.music.data.ExploreData
import com.aurora.music.data.ExploreGroup
import com.aurora.music.data.HomeFeedItem
import com.aurora.music.data.HomeFeedSection
import com.aurora.music.ui.home.BrowseScene
import com.aurora.music.ui.home.Sample
import com.aurora.music.ui.screens.explore.ExploreScreen
import com.aurora.music.ui.testing.distinctColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreScreenTest {
    private val explore = ExploreData(
        groups = listOf(
            ExploreGroup("Moods & moments", listOf(
                ExploreCategory("Chill", "chill", 0xFF8FB4FFL), ExploreCategory("Energize", "energize", 0xFFFFD080L),
                ExploreCategory("Focus", "focus", 0xFFA8E6CFL), ExploreCategory("Workout", "workout", 0xFFFF6E40L),
            )),
            ExploreGroup("Genres", listOf(ExploreCategory("Hip-hop", "hiphop", 0xFFFF2E7EL), ExploreCategory("Indie", "indie", 0xFF6EC6FFL))),
        ),
        sections = listOf(
            HomeFeedSection("top", "Top songs", items = Sample.songs.map { HomeFeedItem.Track(it) }),
            HomeFeedSection("releases", "New albums & singles", items = Sample.albums.map { HomeFeedItem.Record(it) }),
            HomeFeedSection("artists", "Top artists", items = Sample.artists.map { HomeFeedItem.Performer(it) }),
        ),
    )

    @Test fun rendersMoodsChartsAndReleases() {
        val opened = mutableListOf<String>()
        BrowseScene("explore", 1208, 1400) {
            ExploreScreen(PaddingValues(), onOpenDetail = { kind, id -> opened += "$kind:$id" }, onPlayAll = { _, i -> opened += "play:$i" },
                onOpenCategory = { opened += "category:${it.params}" }, load = { explore })
        }.use { scene ->
            assertTrue(scene.shot().distinctColors() > 20)
            scene.click(120f, 173f)
            scene.click(150f, 413f)
            scene.click(470f, 477f)
            scene.click(114f, 794f)
            scene.click(114f, 1090f)
            assertEquals(listOf("category:chill", "play:0", "play:5", "album:a1", "artist:ar1"), opened)
        }
    }
}
