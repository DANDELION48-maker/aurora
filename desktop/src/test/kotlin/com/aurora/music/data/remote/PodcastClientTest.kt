package com.aurora.music.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastClientTest {
    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:media="http://search.yahoo.com/mrss/">
          <channel>
            <title>Night Signals</title>
            <itunes:author>Aurora Radio</itunes:author>
            <image><url>https://example.com/show.jpg</url><title>ignored</title></image>
            <description><![CDATA[A show about <b>sound</b>.]]></description>
            <item>
              <title>Episode 2 &amp; more</title>
              <enclosure url="https://example.com/2.mp4" type="video/mp4"/>
              <enclosure url="https://example.com/2.mp3" type="audio/mpeg"/>
              <guid>ep-2</guid>
              <pubDate>Tue, 06 Oct 2026 10:00:00 +0000</pubDate>
              <itunes:duration>1:02:03</itunes:duration>
              <itunes:image href="https://example.com/2.jpg"/>
              <description>Second</description>
            </item>
            <item>
              <title>Episode 1</title>
              <media:content url="https://example.com/1.m4a" type="audio/mp4"/>
              <itunes:duration>754</itunes:duration>
              <itunes:summary>First</itunes:summary>
            </item>
            <item><title>No audio</title></item>
          </channel>
        </rss>"""

    @Test fun parsesEpisodesFromAFeed() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(feed))
            val content = PodcastClient().episodes(server.url("/feed").toString())
            assertEquals("Night Signals", content.title)
            assertEquals("Aurora Radio", content.author)
            assertEquals("https://example.com/show.jpg", content.imageUrl)
            assertEquals(2, content.episodes.size)
            val second = content.episodes[0]
            assertEquals("Episode 2 & more", second.title)
            assertEquals("https://example.com/2.mp3", second.audioUrl)
            assertEquals("ep-2", second.id)
            assertEquals(3723, second.durationSec)
            assertEquals("https://example.com/2.jpg", second.imageUrl)
            assertTrue(second.pubDateMs > 0)
            assertEquals("Night Signals", second.podcastTitle)
            val first = content.episodes[1]
            assertEquals("https://example.com/1.m4a", first.audioUrl)
            assertEquals(754, first.durationSec)
            assertEquals("First", first.description)
        }
    }

    @Test fun refusesExternalEntities() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<?xml version="1.0"?><!DOCTYPE rss [<!ENTITY x SYSTEM "file:///C:/Windows/win.ini">]><rss><channel><title>&x;</title></channel></rss>"""))
            val content = PodcastClient().episodes(server.url("/feed").toString())
            assertEquals("", content.title)
        }
    }
}
