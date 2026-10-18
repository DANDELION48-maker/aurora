package com.aurora.music.mix

import com.aurora.music.data.SavedTrack
import com.aurora.music.data.persistBackupFileAtomically
import com.aurora.music.data.toSavedTrack
import com.aurora.music.data.toSong
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Explicit, versioned JSON defaults avoid Gson's missing-field/null constructor bypass. */
class MixStore(root: File) {
    private val directory = File(root, "mixes").apply { mkdirs() }
    private val gson = Gson()
    private fun file(account: String) = File(directory, java.security.MessageDigest.getInstance("SHA-256").digest(account.toByteArray())
        .joinToString("") { "%02x".format(it) } + ".json")

    suspend fun list(account: String): List<MixProject> = withContext(Dispatchers.IO) {
        val f = file(account)
        if (!f.exists()) return@withContext emptyList()
        val array = runCatching { JsonParser.parseString(f.readText()).asJsonObject.getAsJsonArray("projects") }.getOrNull() ?: JsonArray()
        array.mapNotNull { runCatching { decode(it.asJsonObject) }.getOrNull() }
    }

    suspend fun save(account: String, project: MixProject) = withContext(Dispatchers.IO) {
        write(account, list(account).filter { it.id != project.id } + project.normalized())
    }

    suspend fun delete(account: String, id: String) = withContext(Dispatchers.IO) { write(account, list(account).filter { it.id != id }) }

    private fun write(account: String, projects: List<MixProject>) {
        val json = JsonObject().apply {
            addProperty("version", 1)
            add("projects", JsonArray().apply { projects.forEach { add(encode(it)) } })
        }
        persistBackupFileAtomically(file(account), json.toString().toByteArray())
    }

    private fun encode(p: MixProject) = JsonObject().apply {
        addProperty("id", p.id); addProperty("name", p.name); addProperty("masterDb", p.masterDb)
        addProperty("protect", p.protectPeaks); addProperty("loop", p.loop)
        add("clips", JsonArray().apply {
            p.clips.forEach { c ->
                add(JsonObject().apply {
                    addProperty("id", c.id)
                    // server urls are refreshed by song id on load; expiring stream credentials are never stored here
                    val saved = c.song.toSavedTrack().let { if (it.streamUrl.orEmpty().startsWith("http")) it.copy(streamUrl = "", artworkUrl = "") else it }
                    add("song", gson.toJsonTree(saved))
                    addProperty("start", c.startSec); addProperty("in", c.cueInSec); addProperty("out", c.cueOutSec)
                    addProperty("fadeIn", c.fadeInSec); addProperty("fadeOut", c.fadeOutSec); addProperty("curve", c.curve.name)
                    addProperty("inBend", c.fadeInBend); addProperty("outBend", c.fadeOutBend)
                    addProperty("note", c.transitionNote); addProperty("bassSwap", c.bassSwap); addProperty("stem", c.stem.name)
                    // stems come back from the private cache on reopen, never from a saved path
                    addProperty("gain", c.gainDb); addProperty("pan", c.pan); addProperty("bass", c.bassDb); addProperty("mid", c.midDb)
                    addProperty("treble", c.trebleDb); addProperty("speed", c.speed); addProperty("pitch", c.pitchSemitones)
                    addProperty("mute", c.muted); addProperty("solo", c.solo)
                })
            }
        })
    }

    private fun decode(p: JsonObject): MixProject {
        val clips = (p.getAsJsonArray("clips") ?: JsonArray()).map { element ->
            val c = element.asJsonObject
            val song = gson.fromJson(c.getAsJsonObject("song"), SavedTrack::class.java).toSong()
            fun f(key: String, default: Float = 0f) = c.get(key)?.takeIf { it.isJsonPrimitive }?.asFloat ?: default
            fun b(key: String) = c.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
            fun s(key: String) = c.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            MixClip(id = c.get("id").asString, song = song, startSec = f("start"), cueInSec = f("in"), cueOutSec = f("out", song.durationSec.toFloat()),
                fadeInSec = f("fadeIn"), fadeOutSec = f("fadeOut"), curve = runCatching { FadeCurve.valueOf(s("curve")) }.getOrDefault(FadeCurve.SMOOTH),
                gainDb = f("gain"), pan = f("pan"), bassDb = f("bass"), midDb = f("mid"), trebleDb = f("treble"),
                speed = f("speed", 1f), pitchSemitones = f("pitch"), muted = b("mute"), solo = b("solo"),
                fadeInBend = f("inBend", 1f), fadeOutBend = f("outBend", 1f), transitionNote = s("note"),
                bassSwap = b("bassSwap"), stem = runCatching { StemMode.valueOf(s("stem")) }.getOrDefault(StemMode.FULL))
        }
        return MixProject(p.get("id").asString, p.get("name")?.asString ?: "Untitled mix", clips,
            p.get("masterDb")?.asFloat ?: -1f, p.get("protect")?.asBoolean ?: true, p.get("loop")?.asBoolean ?: false).normalized()
    }
}
