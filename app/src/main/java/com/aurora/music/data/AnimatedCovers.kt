package com.aurora.music.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale

data class AnimatedCover(val uri: Uri, val video: Boolean)

// named files in a picked folder, plus cover.* next to local tracks stored under it; read through the folder grant only
class AnimatedCovers(private val context: Context, private val settings: SettingsStore, scope: CoroutineScope) {
    data class Config(val enabled: Boolean, val folder: String)

    val config: StateFlow<Config> = combine(settings.animatedCoversEnabled, settings.animatedCoversFolder, ::Config)
        .stateIn(scope, SharingStarted.Eagerly, Config(false, ""))

    private val mutex = Mutex()
    private var indexedFolder = ""
    private var indexedAt = 0L
    private var named: Map<String, AnimatedCover> = emptyMap()
    private val albumFolders = HashMap<String, AnimatedCover?>()

    suspend fun setFolder(uri: Uri?) {
        val previous = config.value.folder
        if (uri != null) context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (previous.isNotBlank() && previous != uri?.toString()) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        settings.setAnimatedCoversFolder(uri?.toString().orEmpty())
    }

    suspend fun find(album: String, artist: String, path: String): AnimatedCover? {
        val (enabled, folder) = config.value
        if (!enabled || folder.isBlank() || album.isBlank()) return null
        val tree = Uri.parse(folder)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val now = System.currentTimeMillis()
                if (folder != indexedFolder || now - indexedAt > REFRESH_MS) {
                    named = runCatching { index(tree) }.getOrDefault(emptyMap())
                    albumFolders.clear()
                    indexedFolder = folder
                    indexedAt = now
                }
                val dir = path.substringBeforeLast('/', "")
                named[key("$artist $album")]
                    ?: albumFolder(tree, dir)
                    ?: named[key(dir.substringAfterLast('/'))]
                    ?: named[key(album)]
                    ?: named[key(album.replace(BRACKETS, " "))]
            }
        }
    }

    private fun albumFolder(tree: Uri, dir: String): AnimatedCover? {
        if (dir.isBlank()) return null
        if (dir in albumFolders) return albumFolders[dir]
        val found = runCatching {
            val docId = docIdFor(tree, dir) ?: return@runCatching null
            children(tree, docId)
                .filter { (_, name) -> name.substringBeforeLast('.').lowercase(Locale.ROOT) in FOLDER_NAMES }
                .mapNotNull { (id, name) -> cover(tree, id, name)?.takeIf { it.video || !name.endsWith(".webp", true) || animatedWebp(it.uri) } }
                .minByOrNull { if (it.video) 0 else 1 }
        }.getOrNull()
        albumFolders[dir] = found
        return found
    }

    private fun index(tree: Uri): Map<String, AnimatedCover> =
        children(tree, DocumentsContract.getTreeDocumentId(tree)).mapNotNull { (id, name) ->
            cover(tree, id, name)?.let { key(name.substringBeforeLast('.')) to it }
        }.toMap()

    private fun children(tree: Uri, docId: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null)?.use { c ->
            while (c.moveToNext()) out += c.getString(0) to c.getString(1).orEmpty()
        }
        return out
    }

    private fun cover(tree: Uri, id: String, name: String): AnimatedCover? {
        val video = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "mp4", "m4v", "webm" -> true
            "webp", "gif" -> false
            else -> return null
        }
        return AnimatedCover(DocumentsContract.buildDocumentUriUsingTree(tree, id), video)
    }

    // only external storage trees map back to file paths
    private fun docIdFor(tree: Uri, dir: String): String? {
        if (tree.authority != EXTERNAL_STORAGE) return null
        val root = DocumentsContract.getTreeDocumentId(tree)
        val volume = root.substringBefore(':')
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().absolutePath else "/storage/$volume"
        val rootPath = listOf(base, root.substringAfter(':', "")).filter(String::isNotEmpty).joinToString("/").trimEnd('/')
        if (dir != rootPath && !dir.startsWith("$rootPath/")) return null
        return "$volume:" + dir.removePrefix(base).trimStart('/')
    }

    // a static cover.webp next to the tracks should not replace the embedded art
    private fun animatedWebp(uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(21)
            var read = 0
            while (read < head.size) {
                val n = input.read(head, read, head.size - read)
                if (n < 0) break
                read += n
            }
            read == head.size && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" &&
                String(head, 12, 4, Charsets.US_ASCII) == "VP8X" && head[20].toInt() and 0x02 != 0
        } ?: false
    }.getOrDefault(false)

    companion object {
        private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
        private const val REFRESH_MS = 60_000L
        private val FOLDER_NAMES = setOf("cover", "folder")
        private val BRACKETS = Regex("""[(\[][^)\]]*[)\]]""")

        fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "").lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()

        fun label(folder: String): String = runCatching {
            DocumentsContract.getTreeDocumentId(Uri.parse(folder)).let { it.substringAfter(':').ifBlank { it } }
        }.getOrDefault(folder)
    }
}
