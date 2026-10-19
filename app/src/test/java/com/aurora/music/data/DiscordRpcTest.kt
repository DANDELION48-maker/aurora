package com.aurora.music.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordRpcTest {
    @Test fun onlyPublicCdnCoversAreSentAsUrls() {
        assertTrue(isPublicArt("https://i.scdn.co/image/ab67616d0000b273"))
        assertTrue(isPublicArt("https://lh3.googleusercontent.com/abc=w544-h544"))
        assertTrue(isPublicArt("https://i1.sndcdn.com/artworks-abc-t500x500.jpg"))
        assertFalse(isPublicArt("https://jellyfin.example.com/Items/1/Images/Primary?api_key=secret"))
        assertFalse(isPublicArt("https://music.example.com/rest/getCoverArt?id=1&u=mara&t=abc&s=salt"))
        assertFalse(isPublicArt("https://plex.example.com/library/metadata/1/thumb?X-Plex-Token=secret"))
        assertFalse(isPublicArt("http://i.scdn.co/image/x"))
        assertFalse(isPublicArt("https://i.scdn.co.evil.com/x"))
        assertFalse(isPublicArt("https://evil.com/?h=i.scdn.co"))
        assertFalse(isPublicArt("https://user:pass@i.scdn.co/image/x"))
        assertFalse(isPublicArt("content://media/external/audio/albumart/1"))
    }
}
