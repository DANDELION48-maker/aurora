package com.aurora.music.data

import android.content.Context
import android.os.Process
import com.aurora.music.model.Song
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

// one song at a time on a background-priority thread; results are tiny and cached on disk
class VocalAnalysis(private val context: Context, private val canStream: () -> Boolean) {
    private val dir = File(context.cacheDir, "vocal-activity")
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({ Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); task.run() }, "aurora-vocals")
    }.asCoroutineDispatcher()
    private val recent = object : LinkedHashMap<String, VocalActivity>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VocalActivity>) = size > 4
    }

    suspend fun activityFor(song: Song): VocalActivity? {
        if (song.durationSec !in 1..MAX_SECONDS) return null
        val key = keyFor(song)
        synchronized(recent) { recent[key] }?.let { return it }
        return withContext(worker) {
            val file = File(dir, "$key.bin")
            val cached = if (file.exists()) VocalActivity.decode(file.readBytes()) else null
            val result = cached ?: sourceOf(song)?.let { analyze(it) } ?: return@withContext null
            if (cached == null) runCatching {
                dir.mkdirs()
                file.writeBytes(result.encode())
                trim()
            }
            synchronized(recent) { recent[key] = result }
            result
        }
    }

    private suspend fun analyze(source: String): VocalActivity? {
        val context = currentCoroutineContext()
        var analyzer: VocalActivityAnalyzer? = null
        val done = AudioDecoder.decode(source,
            { rate, channels -> if (analyzer == null) analyzer = VocalActivityAnalyzer(rate, channels) },
            { pcm, length -> analyzer?.feed(pcm, length) },
            { !context.isActive }, this.context)
        return if (done) analyzer?.finish() else null
    }

    // streams cost a second download, so only on unmetered connections
    private fun sourceOf(song: Song): String? {
        if (song.suffix.lowercase() in setOf("dsf", "dff", "iso")) return null
        val url = song.streamUrl
        return when {
            url.startsWith("content://") || url.startsWith("file:") || url.startsWith("/") -> url
            url.startsWith("http://") || url.startsWith("https://") -> url.takeIf { canStream() }
            else -> null
        }
    }

    private fun keyFor(song: Song): String = MessageDigest.getInstance("SHA-1")
        .digest("${song.id}|${song.playbackSource?.providerId}|${song.durationSec}".toByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun trim() {
        val files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        files.drop(MAX_FILES).forEach { it.delete() }
    }

    private companion object {
        const val MAX_SECONDS = 20 * 60
        const val MAX_FILES = 300
    }
}
