package com.aurora.music.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** YouTube throttles one long transfer to playback speed but serves short ranges at full speed. */
object RangedDownload {
    private const val CHUNK = 1_048_576L

    fun isThrottled(url: String) = url.toHttpUrlOrNull()?.host?.let { it == "googlevideo.com" || it.endsWith(".googlevideo.com") } == true

    fun fetch(http: OkHttpClient, url: String, file: File, onProgress: (Float) -> Unit) {
        file.outputStream().use { output ->
            var position = 0L
            var total = -1L
            while (total < 0 || position < total) {
                val request = Request.Builder().url(url).header("Range", "bytes=$position-${position + CHUNK - 1}").build()
                http.newCall(request).execute().use { response ->
                    val body = response.body ?: throw IOException("Empty body")
                    when (response.code) {
                        206 -> total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: throw IOException("Missing length")
                        200 -> total = 0
                        else -> throw IOException("HTTP ${response.code}")
                    }
                    val copied = body.byteStream().copyTo(output)
                    if (copied == 0L && total > 0) throw IOException("Truncated download")
                    position += copied
                }
                if (total > 0) onProgress((position.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        if (file.length() == 0L) throw IOException("Empty download")
    }
}
