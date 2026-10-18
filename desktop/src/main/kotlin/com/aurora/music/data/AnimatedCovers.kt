package com.aurora.music.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer
import java.util.Locale

data class AnimatedCover(val file: File, val video: Boolean)

// named files in a chosen folder, plus cover.* or folder.* beside local tracks
class AnimatedCovers(private val settings: SettingsStore, scope: CoroutineScope) {
    data class Config(val enabled: Boolean, val folder: String)

    val config: StateFlow<Config> = combine(settings.animatedCoversEnabled, settings.animatedCoversFolder, ::Config)
        .stateIn(scope, SharingStarted.Eagerly, Config(false, ""))

    private val mutex = Mutex()
    private var indexedFolder = ""
    private var indexedAt = 0L
    private var named: Map<String, AnimatedCover> = emptyMap()
    private val albumFolders = HashMap<String, AnimatedCover?>()

    suspend fun setFolder(folder: File?) = settings.setAnimatedCoversFolder(folder?.absolutePath.orEmpty())

    suspend fun find(album: String, artist: String, path: String): AnimatedCover? {
        val (enabled, folder) = config.value
        if (!enabled || album.isBlank()) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                if (folder != indexedFolder || now - indexedAt > REFRESH_MS) {
                    named = if (folder.isBlank()) emptyMap() else runCatching { index(File(folder)) }.getOrDefault(emptyMap())
                    albumFolders.clear()
                    indexedFolder = folder
                    indexedAt = now
                }
                val dir = path.takeIf { it.isNotBlank() }?.let { File(it).parentFile }
                named[key("$artist $album")]
                    ?: albumFolder(dir)
                    ?: dir?.let { named[key(it.name)] }
                    ?: named[key(album)]
                    ?: named[key(album.replace(BRACKETS, " "))]
            }
        }
    }

    private fun albumFolder(dir: File?): AnimatedCover? {
        if (dir == null || !dir.isDirectory) return null
        val path = dir.absolutePath
        if (path in albumFolders) return albumFolders[path]
        val found = runCatching {
            dir.listFiles().orEmpty().filter { it.nameWithoutExtension.lowercase(Locale.ROOT) in FOLDER_NAMES }
                .mapNotNull { file -> cover(file)?.takeIf { it.video || file.extension.lowercase(Locale.ROOT) != "webp" || animatedWebp(file) } }
                .minByOrNull { if (it.video) 0 else 1 }
        }.getOrNull()
        albumFolders[path] = found
        return found
    }

    private fun index(folder: File): Map<String, AnimatedCover> = folder.listFiles().orEmpty().filter { it.isFile }
        .mapNotNull { file -> cover(file)?.let { key(file.nameWithoutExtension) to it } }.toMap()

    private fun cover(file: File): AnimatedCover? = when (file.extension.lowercase(Locale.ROOT)) {
        "mp4", "m4v", "webm" -> AnimatedCover(file, true)
        "webp", "gif" -> AnimatedCover(file, false)
        else -> null
    }

    // a static cover.webp next to the tracks should not replace the embedded art
    private fun animatedWebp(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(21)
            var read = 0
            while (read < head.size) { val n = input.read(head, read, head.size - read); if (n < 0) break; read += n }
            read == head.size && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" &&
                String(head, 12, 4, Charsets.US_ASCII) == "VP8X" && head[20].toInt() and 0x02 != 0
        }
    }.getOrDefault(false)

    companion object {
        private const val REFRESH_MS = 60_000L
        private val FOLDER_NAMES = setOf("cover", "folder")
        private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")

        fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

        fun label(folder: String): String = File(folder).name.ifBlank { folder }
    }
}
