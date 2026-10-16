package com.aurora.music.data

import com.aurora.music.data.remote.YouTubeMusicParser
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeMusicArtworkTest {
    @Test fun rowArtIsAskedForAtFullSize() {
        assertEquals("https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            YouTubeMusicParser.fullSize("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj"))
        assertEquals("https://yt3.ggpht.com/def=s1200-c-k-c0x00ffffff-no-rj",
            YouTubeMusicParser.fullSize("https://yt3.ggpht.com/def=s88-c-k-c0x00ffffff-no-rj"))
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg",
            YouTubeMusicParser.fullSize("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg?sqp=x&rs=y"))
        val thumbnails = JsonParser.parseString("""{"thumbnails":[{"url":"https://lh3.googleusercontent.com/a=w60-h60-l90-rj"},
            {"url":"https://lh3.googleusercontent.com/a=w120-h120-l90-rj"}]}""").asJsonObject
        assertEquals("https://lh3.googleusercontent.com/a=w1200-h1200-l90-rj", YouTubeMusicParser.thumbnail(thumbnails))
    }
}
