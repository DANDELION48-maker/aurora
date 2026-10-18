package com.aurora.music.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

class BackupCredentialsTest {
    private val directory = Files.createTempDirectory("aurora-backup-credentials-").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = SettingsStore(PreferenceDataStoreFactory.create(scope = scope) { File(directory, "aurora_settings.preferences_pb") }, directory, directory)
    private val manager = BackupManager(store, LocalStore(directory), PlayHistoryStore(directory), directory, directory)
    private val secrets = mapOf(
        "server_type" to ServerType.SUBSONIC.name, "server" to "https://music.example", "username" to "alice",
        "salt" to "SECRET-salt", "token" to "SECRET-token", "sp_client_token" to "SECRET-client",
        "saved_sessions" to Gson().toJson(listOf(
            Session("https://music.example", "alice", "SECRET-saved-salt", "SECRET-saved-token"),
            Session("local", "Me", "", "local", ServerType.LOCAL))),
        "acoustid_key" to "SECRET-acoustid", "lastfm_sk" to "SECRET-lastfm-sk", "lastfm_api_key" to "SECRET-lastfm-key",
        "lastfm_secret" to "SECRET-lastfm-secret", "lastfm_pending_token" to "SECRET-lastfm-pending",
        "listenbrainz_token" to "SECRET-listenbrainz", "discord_token" to "SECRET-discord",
    )

    @After fun clean() { scope.cancel(); directory.deleteRecursively() }

    @Test fun exportedArchiveHasNoCredentials() = runBlocking {
        store.importPrefs(PrefsBackup(strings = secrets, ints = mapOf("ui_theme_mode" to 2)))
        val json = json(archive(includeSignIns = false))
        assertFalse(json, json.contains("SECRET"))
        val prefs = BackupArchive.decodeJson(json).prefs
        assertEquals(2, prefs.ints["ui_theme_mode"])
        assertEquals("https://music.example", prefs.strings["server"])
        assertTrue(prefs.strings.getValue("saved_sessions").contains("\"LOCAL\""))
    }

    @Test fun restoreWithoutSignInsKeepsThisDevicesAccounts() = runBlocking {
        store.importPrefs(PrefsBackup(strings = secrets, ints = mapOf("ui_theme_mode" to 2)))
        val backup = archive(includeSignIns = false)
        store.importPrefs(PrefsBackup(strings = mapOf("server" to "https://other.example", "token" to "DEVICE-token", "lastfm_sk" to "DEVICE-sk"),
            ints = mapOf("ui_theme_mode" to 5)))
        manager.importArchive(backup.inputStream()).getOrThrow()
        val prefs = store.exportPrefs()
        assertEquals(2, prefs.ints["ui_theme_mode"])
        assertEquals("https://other.example", prefs.strings["server"])
        assertEquals("DEVICE-token", prefs.strings["token"])
        assertEquals("DEVICE-sk", prefs.strings["lastfm_sk"])
        assertEquals("SECRET-discord", prefs.strings["discord_token"])
        assertTrue(prefs.strings.getValue("saved_sessions").contains("SECRET-saved-token"))
    }

    @Test fun restoreWithSignInsAppliesThem() = runBlocking {
        store.importPrefs(PrefsBackup(strings = secrets))
        val backup = archive(includeSignIns = true)
        assertTrue(json(backup).contains("SECRET-lastfm-secret"))
        store.importPrefs(PrefsBackup(strings = mapOf("token" to "DEVICE-token", "discord_token" to "DEVICE-discord")))
        manager.importArchive(backup.inputStream()).getOrThrow()
        val prefs = store.exportPrefs()
        assertEquals("SECRET-token", prefs.strings["token"])
        assertEquals("SECRET-discord", prefs.strings["discord_token"])
    }

    private suspend fun archive(includeSignIns: Boolean) =
        ByteArrayOutputStream().also { manager.exportArchive(1L, it, includeSignIns).getOrThrow() }.toByteArray()

    private fun json(archive: ByteArray) = ZipInputStream(archive.inputStream()).use { zip ->
        generateSequence { zip.nextEntry }.first { it.name == "backup.json" }
        zip.readBytes().toString(Charsets.UTF_8)
    }
}
