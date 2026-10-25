package com.aurora.music.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

data class CueFolder(val uri: String, val name: String)

// sidecar .cue files: read straight from disk where android allows it, otherwise through folders the user granted
class CueLibrary(private val context: Context) {
    private val prefs = context.getSharedPreferences("cue_folders", Context.MODE_PRIVATE)

    fun folders(): List<CueFolder> = saved().sorted().map { CueFolder(it, label(Uri.parse(it))) }

    fun add(tree: Uri) = synchronized(lock) {
        require(tree.authority == STORAGE)
        check(prefs.edit().putStringSet(KEY, saved() + tree.toString()).commit())
    }

    fun remove(uri: String) = synchronized(lock) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        check(prefs.edit().putStringSet(KEY, saved() - uri).commit())
    }

    // dirs are absolute folder paths of the audio files worth checking
    fun sheets(dirs: Collection<String>): Map<String, List<CueSheet>> {
        val trees = saved().map(Uri::parse).filter { it.authority == STORAGE }
            .mapNotNull { uri -> runCatching { pathOf(DocumentsContract.getTreeDocumentId(uri)) }.getOrNull()?.let { uri to it } }
        val out = HashMap<String, List<CueSheet>>()
        for (dir in dirs.distinct()) {
            val found = direct(dir) ?: trees.firstOrNull { (_, root) -> dir == root || dir.startsWith("$root/") }
                ?.let { (tree, _) -> granted(tree, dir) }.orEmpty()
            if (found.isNotEmpty()) out[CueSheets.dirKey(dir)] = found
        }
        return out
    }

    private fun direct(dir: String): List<CueSheet>? = runCatching {
        File(dir).listFiles { file -> file.isFile && CueSheets.isCue(file.name) }
            ?.map { CueSheets.parse(it.readBytes()) }?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun granted(tree: Uri, dir: String): List<CueSheet> = runCatching {
        val folder = documentIdOf(dir) ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE)
        val ids = ArrayList<String>()
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (CueSheets.isCue(cursor.getString(1).orEmpty()) && cursor.getLong(2) in 1..MAX_BYTES) ids += cursor.getString(0)
            }
        }
        ids.mapNotNull { id ->
            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { CueSheets.parse(it.readBytes()) }
        }
    }.getOrDefault(emptyList())

    private fun label(tree: Uri): String = runCatching {
        DocumentsContract.getTreeDocumentId(tree).let { id -> pathOf(id)?.substringAfterLast('/')?.ifBlank { null } ?: id }
    }.getOrDefault(tree.toString())

    private fun saved() = prefs.getStringSet(KEY, emptySet()).orEmpty().toSet()

    private companion object {
        val lock = Any()
        const val KEY = "trees"
        const val STORAGE = "com.android.externalstorage.documents"
        const val MAX_BYTES = 1L shl 20
        @Suppress("DEPRECATION")
        val primary: String get() = Environment.getExternalStorageDirectory().absolutePath

        // external storage documents are "<volume>:<relative path>"
        fun pathOf(documentId: String): String? {
            val volume = documentId.substringBefore(':', "").ifBlank { return null }
            val root = if (volume == "primary") primary else "/storage/$volume"
            return (root + "/" + documentId.substringAfter(':')).trimEnd('/')
        }

        fun documentIdOf(path: String): String? = when {
            path == primary || path.startsWith("$primary/") -> "primary:" + path.removePrefix(primary).trimStart('/')
            path.startsWith("/storage/") -> path.removePrefix("/storage/").let { it.substringBefore('/') + ":" + it.substringAfter('/', "") }
            else -> null
        }
    }
}
