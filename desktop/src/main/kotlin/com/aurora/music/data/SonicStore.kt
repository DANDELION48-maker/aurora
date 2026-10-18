package com.aurora.music.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

internal fun File.diskBytes(): Long =
    if (!exists()) 0L else walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }

/** Account-scoped index into shared stream analysis, one small file per track. */
class SonicStore(root: File) {
    private val directory = File(root, "sonic-index-v4").apply { mkdirs() }
    private val lock = Any()
    private val accounts = ConcurrentHashMap<String, MutableMap<String, FloatArray>>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var active = ""
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> get() { preload(active); return _count }
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun folder(account: String) = File(directory, hash(account)).apply { mkdirs() }
    private fun file(account: String, id: String) = File(folder(account), hash(id) + ".json")
    private fun record(f: File): Pair<String, FloatArray>? = runCatching {
        val json = JsonParser.parseString(f.readText()).asJsonObject
        val a = json.getAsJsonArray("vector")
        if (a.size() != SonicFeatures.DIMS) null else json.get("id").asString to FloatArray(a.size()) { a[it].asFloat }
    }.getOrNull()
    private fun data(account: String): MutableMap<String, FloatArray> = accounts.getOrPut(account) {
        runCatching { folder(account).listFiles().orEmpty().filter { it.extension == "json" }.mapNotNull(::record).toMap().toMutableMap() }
            .getOrDefault(mutableMapOf())
    }

    // vectors load on first radio or analysis use, not on every launch
    fun selectAccount(account: String) = synchronized(lock) {
        active = account
        _count.value = if (accounts.containsKey(account) || _count.subscriptionCount.value > 0) data(account).size else 0
    }
    private fun load(account: String) = synchronized(lock) { val size = data(account).size; if (account == active) _count.value = size }
    private fun preload(account: String) {
        if (accounts.containsKey(account)) return
        if (!java.awt.EventQueue.isDispatchThread()) { load(account); return }
        if (!pending.add(account)) return
        thread(isDaemon = true, name = "sonic-index") {
            try { load(account) } finally { pending.remove(account) }
        }
    }
    fun has(id: String, account: String = active) = synchronized(lock) { data(account).containsKey(id) }
    fun get(id: String, account: String = active): FloatArray? {
        if (!accounts.containsKey(account)) {
            val f = File(File(directory, hash(account)), hash(id) + ".json")
            return if (f.isFile) record(f)?.takeIf { it.first == id }?.second else null
        }
        return synchronized(lock) { data(account)[id]?.copyOf() }
    }
    fun put(id: String, vec: FloatArray, account: String = active) = synchronized(lock) {
        require(vec.size == SonicFeatures.DIMS && vec.all { it.isFinite() })
        if (data(account)[id]?.contentEquals(vec) == true) return@synchronized
        data(account)[id] = vec.copyOf()
        val json = JsonObject().apply {
            addProperty("id", id)
            add("vector", JsonArray().apply { vec.forEach { add(it) } })
        }
        persistBackupFileAtomically(file(account, id), json.toString().toByteArray())
        if (account == active) _count.value = data(account).size
    }
    fun snapshot(account: String = active): Map<String, FloatArray> = synchronized(lock) { HashMap(data(account)) }
    fun bytes(): Long = directory.diskBytes()
    fun clear() = synchronized(lock) {
        accounts.clear()
        directory.deleteRecursively()
        directory.mkdirs()
        _count.value = 0
    }
}
