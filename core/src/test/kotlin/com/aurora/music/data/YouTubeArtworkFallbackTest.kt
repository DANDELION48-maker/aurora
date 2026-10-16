package com.aurora.music.data

import com.aurora.music.data.remote.YouTubeArtworkFallback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class YouTubeArtworkFallbackTest {
    private val seen = mutableListOf<String>()
    private val client = OkHttpClient.Builder().addInterceptor(YouTubeArtworkFallback).addInterceptor(Interceptor { chain ->
        val url = chain.request().url.toString().also { seen += it }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("")
            .code(if (url.endsWith("maxresdefault.jpg")) 404 else 200).body("img".toResponseBody()).build()
    }).build()

    @Test fun aMissingFullSizeFallsBackToHqdefault() {
        client.newCall(Request.Builder().url("https://i.ytimg.com/vi/jNQXAC9IVRw/maxresdefault.jpg").build()).execute().use {
            assertEquals(200, it.code)
        }
        assertEquals(listOf("https://i.ytimg.com/vi/jNQXAC9IVRw/maxresdefault.jpg", "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg"), seen)
    }

    @Test fun otherHostsKeepTheir404() {
        client.newCall(Request.Builder().url("https://example.com/vi/x/maxresdefault.jpg").build()).execute().use {
            assertEquals(404, it.code)
        }
        assertEquals(1, seen.size)
    }
}
