package com.aurora.music.desktop.updates

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class DesktopUpdaterTest {
    @get:Rule val temp = TemporaryFolder()

    private val installer = ByteArray(3 * 1024 * 1024) { (it * 31).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(installer).joinToString("") { "%02x".format(it) }

    private fun release(tag: String, digest: String = "sha256:$sha", host: String = "github.com") = """
        {"tag_name":"$tag","draft":false,"prerelease":false,"assets":[
          {"name":"Aurora-3.0.0.apk","size":10,"browser_download_url":"https://github.com/nessli420/aurora/releases/download/$tag/Aurora-3.0.0.apk"},
          {"name":"Aurora-3.0.0.exe","size":20,"browser_download_url":"https://github.com/nessli420/aurora/releases/download/$tag/Aurora-3.0.0.exe"},
          {"name":"Aurora-3.0.0.msi","size":${installer.size},"digest":"$digest",
           "browser_download_url":"https://$host/nessli420/aurora/releases/download/$tag/Aurora-3.0.0.msi"},
          {"name":"aurora-deb.zip","size":30,"browser_download_url":"https://github.com/nessli420/aurora/releases/download/$tag/aurora-deb.zip"}]}
    """.trimIndent()

    private fun client(json: String, body: ByteArray = installer) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        val url = chain.request().url.toString()
        val bytes = if (url.startsWith("https://api.github.com/")) json.toByteArray() else body
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody("application/octet-stream".toMediaType())).build()
    }).build()

    private fun updater(json: String, installed: String = "2.9.0", windows: Boolean = true, body: ByteArray = installer,
        launched: MutableList<File> = mutableListOf()) =
        DesktopUpdater(client(json, body), File(temp.root, "updates"), installed, windows, launch = { launched += it })

    private fun DesktopUpdater.awaitChecked() = runBlocking { withTimeout(10_000) { state.first { it.checked || it.error != null } } }

    @Test fun picksTheMsiFromAuroraReleasesAndComparesVersions() {
        val state = updater(release("V3.0.0")).apply { checkForUpdate() }.awaitChecked()
        assertTrue(state.updateAvailable)
        assertEquals("Aurora-3.0.0.msi", state.installer?.name)
        assertEquals(sha, state.installer?.sha256)
        assertFalse(updater(release("V3.0.0"), installed = "3.0.0").apply { checkForUpdate() }.awaitChecked().updateAvailable)
        assertNull(updater(release("V3.0.0"), windows = false).apply { checkForUpdate() }.awaitChecked().installer)
    }

    @Test fun installersHostedElsewhereAreRejected() {
        val state = updater(release("V3.0.0", host = "example.com")).apply { checkForUpdate() }.awaitChecked()
        assertNotNull(state.error)
        assertNull(state.installer)
    }

    @Test fun downloadsVerifyAndHandOverToTheInstaller() = runBlocking {
        val launched = mutableListOf<File>()
        val updater = updater(release("V3.0.0"), launched = launched)
        updater.checkForUpdate()
        updater.awaitChecked()
        updater.downloadUpdate()
        withTimeout(10_000) { updater.state.first { it.download == UpdateDownload.READY } }
        val quit = async { withTimeout(5_000) { updater.quitRequests.first() } }
        kotlinx.coroutines.yield()
        updater.install()
        quit.await()
        assertEquals("Aurora-3.0.0.msi", launched.single().name)
        assertTrue(launched.single().readBytes().contentEquals(installer))
    }

    @Test fun tamperedDownloadsAreDiscarded() = runBlocking {
        val updater = updater(release("V3.0.0"), body = installer.copyOf().also { it[100] = (it[100] + 1).toByte() })
        updater.checkForUpdate()
        updater.awaitChecked()
        updater.downloadUpdate()
        val state = withTimeout(10_000) { updater.state.first { it.download == UpdateDownload.IDLE && it.error != null } }
        assertNotNull(state.error)
        assertTrue(File(temp.root, "updates").listFiles().orEmpty().isEmpty())
    }
}
