package com.aurora.music.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class PendingLike(val liked: Boolean = true, val sentAt: Long = 0L)

// likes soundcloud hasn't taken yet; liked songs shows them until soundcloud's own list agrees
class SoundCloudPendingLikes(filesDir: File? = null) {
    private val file = filesDir?.let { File(it, "soundcloud_pending_likes.json") }
    private val gson = Gson()
    private val lock = Any()
    private val syncedAt = HashMap<String, Long>()

    @Volatile private var map: Map<String, Map<String, PendingLike>> = load()

    private fun load(): Map<String, Map<String, PendingLike>> = runCatching {
        if (file == null || !file.exists()) emptyMap()
        else gson.fromJson<Map<String, LinkedHashMap<String, PendingLike>>>(file.readText(),
            object : TypeToken<Map<String, LinkedHashMap<String, PendingLike>>>() {}.type) ?: emptyMap()
    }.getOrDefault(emptyMap())

    private fun persist() = runCatching { file?.writeText(gson.toJson(map)) }

    fun of(user: String): Map<String, PendingLike> = map[user].orEmpty()

    fun set(user: String, track: String, like: PendingLike) = edit(user) { it.remove(track); it[track] = like }

    fun clear(user: String, track: String) = edit(user) { it.remove(track) }

    // the bot check refuses every write until the user passes it, so retrying more often only wastes requests
    fun dueForSync(user: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (now - (syncedAt[user] ?: 0L) < SYNC_GAP_MS) false else { syncedAt[user] = now; true }
    }

    private fun edit(user: String, change: (LinkedHashMap<String, PendingLike>) -> Unit) = synchronized(lock) {
        val likes = LinkedHashMap(of(user)).also(change)
        map = if (likes.isEmpty()) map - user else map + (user to likes)
        persist()
    }

    companion object {
        const val SYNC_GAP_MS = 10 * 60_000L
        const val SENT_GRACE_MS = 5 * 60_000L
    }
}
