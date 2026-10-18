package com.aurora.music.desktop.library

import com.aurora.music.data.AudioTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.mp4.Mp4TagWriter
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import org.jaudiotagger.tag.images.StandardArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.logging.Level
import java.util.logging.Logger

class TagEditor {
    init {
        runCatching { Logger.getLogger("org.jaudiotagger").level = Level.OFF }
    }

    suspend fun read(path: String): AudioTags? = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(path)
            if (!file.isFile) return@runCatching null
            val tag = AudioFileIO.read(file).tag ?: return@runCatching AudioTags()
            AudioTags(
                title = tag.firstOrEmpty(FieldKey.TITLE),
                artist = tag.firstOrEmpty(FieldKey.ARTIST),
                album = tag.firstOrEmpty(FieldKey.ALBUM),
                albumArtist = tag.firstOrEmpty(FieldKey.ALBUM_ARTIST),
                genre = tag.firstOrEmpty(FieldKey.GENRE),
                year = tag.firstOrEmpty(FieldKey.YEAR),
                trackNumber = tag.firstOrEmpty(FieldKey.TRACK),
            )
        }.getOrNull()
    }

    // edits a copy beside the file and swaps it in, so a failed write never leaves the original half written
    suspend fun write(path: String, tags: AudioTags, artwork: ByteArray? = null): Boolean = withContext(Dispatchers.IO) {
        val original = File(path)
        if (!original.isFile) return@withContext false
        val mp4 = original.extension.lowercase() in MP4
        // the mp4 writer leaves its source open until a gc, so that copy lives in the temp folder rather than the library
        val copy = if (mp4) File.createTempFile("aurora-tags-", ".${original.extension}")
            else File(original.parentFile, ".aurora-tags-${System.nanoTime()}.${original.extension}")
        val rewritten = File(original.parentFile, ".aurora-tags-${System.nanoTime()}-out.${original.extension}")
        try {
            Files.copy(original.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val audio = AudioFileIO.read(copy)
            val tag = audio.tagOrCreateAndSetDefault
            tag.put(FieldKey.TITLE, tags.title)
            tag.put(FieldKey.ARTIST, tags.artist)
            tag.put(FieldKey.ALBUM, tags.album)
            tag.put(FieldKey.ALBUM_ARTIST, tags.albumArtist)
            tag.put(FieldKey.GENRE, tags.genre)
            tag.put(FieldKey.YEAR, tags.year)
            tag.put(FieldKey.TRACK, tags.trackNumber)
            if (artwork != null && artwork.isNotEmpty()) runCatching {
                tag.deleteArtworkField()
                tag.setField(StandardArtwork().apply {
                    binaryData = artwork
                    mimeType = "image/jpeg"
                    pictureType = PictureTypes.DEFAULT_ID
                })
            }
            // jaudiotagger's own mp4 commit renames the file while that handle is still open, which windows refuses
            val written = if (mp4) rewritten.also { output ->
                RandomAccessFile(copy, "r").use { input -> RandomAccessFile(output, "rw").use { Mp4TagWriter().write(audio, input, it) } }
            } else copy.also { audio.commit() }
            Files.move(written.toPath(), original.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (e: Exception) {
            com.aurora.music.util.AppLog.w("TagEditor", "Could not write tags to $path", e)
            false
        } finally {
            rewritten.delete()
            if (!copy.delete() && copy.exists()) {
                System.gc()
                if (!copy.delete()) copy.deleteOnExit()
            }
        }
    }

    private companion object {
        val MP4 = setOf("m4a", "m4b", "m4p", "mp4")
    }

    private fun Tag.firstOrEmpty(key: FieldKey): String = runCatching { getFirst(key) ?: "" }.getOrDefault("")

    // a blank value deletes the field so clearing actually clears the tag
    private fun Tag.put(key: FieldKey, value: String) {
        runCatching { if (value.isBlank()) deleteField(key) else setField(key, value) }
    }
}
