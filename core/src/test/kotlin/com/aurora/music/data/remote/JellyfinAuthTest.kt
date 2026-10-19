package com.aurora.music.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class JellyfinAuthTest {

    @Test fun usesTheStandardAuthorizationHeaderAndApiKeyParam() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"AccessToken":"tok en","User":{"Id":"user-id","Name":"Listener"}}"""))
            server.enqueue(MockResponse().setResponseCode(204))
            val session = JellyfinClient.authenticate(server.url("/").toString(), "Listener", "password", testClientInfo)

            val login = server.takeRequest()
            assertEquals("/Users/AuthenticateByName", login.path)
            assertNull(login.getHeader("X-Emby-Authorization"))
            assertEquals("MediaBrowser Client=\"Aurora\", Device=\"${testClientInfo.device}\", " +
                "DeviceId=\"${session.clientToken}\", Version=\"${testClientInfo.version}\"", login.getHeader("Authorization"))

            val client = JellyfinClient(session, testClientInfo)
            client.api.publicInfo()
            val authed = server.takeRequest()
            assertNull(authed.getHeader("X-Emby-Authorization"))
            assertTrue(authed.getHeader("Authorization")!!.endsWith(", Token=\"tok en\""))

            listOf(client.streamUrl("track", 0, true), client.streamUrl("track", 128, false), client.coverArtUrl("album")).forEach {
                val url = it.toHttpUrl()
                assertEquals("tok en", url.queryParameter("ApiKey"))
                assertNull(url.queryParameter("api_key"))
            }
        }
    }
}
