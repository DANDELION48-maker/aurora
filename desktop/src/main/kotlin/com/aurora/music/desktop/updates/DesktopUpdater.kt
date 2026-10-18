package com.aurora.music.desktop.updates

import com.aurora.music.R
import com.aurora.music.data.updates.AppRelease
import com.aurora.music.data.updates.AppVersion
import com.aurora.music.data.updates.GitHubRelease
import com.aurora.music.localization.appString
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class UpdateDownload { IDLE, DOWNLOADING, VERIFYING, READY }

data class InstallerAsset(val name: String, val url: String, val size: Long, val sha256: String?)

data class DesktopUpdateState(
    val release: AppRelease? = null,
    val installer: InstallerAsset? = null,
    val installed: String = "",
    val checking: Boolean = false,
    val checked: Boolean = false,
    val download: UpdateDownload = UpdateDownload.IDLE,
    val progress: Float? = null,
    val error: String? = null,
) {
    val updateAvailable: Boolean get() {
        val latest = release?.version ?: return false
        val current = AppVersion.parse(installed) ?: return false
        return latest > current
    }
}

/** Checks Aurora's GitHub releases; on Windows it downloads and verifies the installer, then hands over to it. */
class DesktopUpdater(
    base: OkHttpClient,
    private val directory: File,
    installed: String,
    private val windows: Boolean,
    private val launch: (File) -> Unit = ::startInstaller,
    private val latestApi: String = GitHubRelease.LATEST_API,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = base.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val mutableState = MutableStateFlow(DesktopUpdateState(installed = installed))
    val state: StateFlow<DesktopUpdateState> = mutableState.asStateFlow()
    private val quit = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val quitRequests: SharedFlow<Unit> = quit.asSharedFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastCheck = 0L
    private var file: File? = null

    init {
        scope.launch(Dispatchers.IO) { directory.listFiles().orEmpty().forEach { it.delete() } }
    }

    fun checkForUpdate(force: Boolean = false) {
        if (checkJob?.isActive == true || state.value.download != UpdateDownload.IDLE) return
        val now = System.currentTimeMillis()
        if (!force && lastCheck != 0L && now - lastCheck < TimeUnit.HOURS.toMillis(1)) return
        lastCheck = now
        mutableState.update { it.copy(checking = true, error = null) }
        checkJob = scope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    val request = Request.Builder().url(latestApi)
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "Aurora-Desktop/${state.value.installed}").build()
                    client.newCall(request).execute().use { response ->
                        if (response.code == 403 || response.code == 429) throw IOException(appString(R.string.text_github_is_busy_try_again_later_83f29b))
                        if (response.code == 404) throw IOException(appString(R.string.text_no_public_release_is_available_yet_54a115))
                        if (!response.isSuccessful) throw IOException(appString(R.string.text_couldn_t_check_for_updates_try_again_later_e70d9d))
                        response.body?.string() ?: throw IOException(appString(R.string.text_github_returned_an_empty_response_06f1d4))
                    }
                }
                val release = GitHubRelease.parse(json)
                val installer = if (windows) installerAsset(json, release.tag) else null
                mutableState.update { it.copy(release = release, installer = installer, checking = false, checked = true, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(checking = false, error = when (e) {
                    is java.net.UnknownHostException, is java.net.SocketTimeoutException -> appString(R.string.text_couldn_t_reach_github_check_your_connection_and_try_again_47aff9)
                    is IOException -> e.message ?: appString(R.string.text_couldn_t_check_for_updates_try_again_a15f1d)
                    else -> appString(R.string.text_couldn_t_read_the_github_release_try_again_later_309d84)
                }) }
            }
        }
    }

    fun downloadUpdate() {
        val current = state.value
        val asset = current.installer ?: return
        if (!current.updateAvailable || current.checking || current.download != UpdateDownload.IDLE || downloadJob?.isActive == true) return
        mutableState.update { it.copy(download = UpdateDownload.DOWNLOADING, progress = null, error = null) }
        downloadJob = scope.launch {
            val target = File(directory, asset.name)
            val partial = File(directory, asset.name + ".part")
            try {
                withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    val request = Request.Builder().url(asset.url).header("User-Agent", "Aurora-Desktop/${current.installed}").build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException(appString(R.string.text_download_failed_check_your_connection_and_storage_then_try_again_3e9e00))
                        val body = response.body ?: throw IOException(appString(R.string.text_download_failed_check_your_connection_and_storage_then_try_again_3e9e00))
                        body.byteStream().use { input -> partial.outputStream().use { output ->
                            val buffer = ByteArray(256 * 1024)
                            var copied = 0L
                            var reported = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                copied += count
                                check(copied <= asset.size) { appString(R.string.text_the_download_could_not_be_verified_please_try_again_95c71d) }
                                if (copied - reported >= 1 shl 20) {
                                    reported = copied
                                    mutableState.update { it.copy(progress = (copied.toFloat() / asset.size).coerceIn(0f, 1f)) }
                                }
                            }
                        } }
                    }
                    mutableState.update { it.copy(download = UpdateDownload.VERIFYING, progress = 1f) }
                    verify(partial, asset)
                    target.delete()
                    check(partial.renameTo(target)) { appString(R.string.text_couldn_t_download_the_update_try_again_5d009e) }
                }
                file = target
                mutableState.update { it.copy(download = UpdateDownload.READY) }
            } catch (e: CancellationException) {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { partial.delete() }
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { partial.delete(); target.delete() }
                mutableState.update { it.copy(download = UpdateDownload.IDLE, progress = null,
                    error = e.message ?: appString(R.string.text_couldn_t_download_the_update_try_again_5d009e)) }
            }
        }
    }

    fun cancelDownload() {
        if (state.value.download == UpdateDownload.VERIFYING) return
        val previous = downloadJob
        previous?.cancel()
        mutableState.update { it.copy(download = UpdateDownload.VERIFYING) }
        downloadJob = scope.launch {
            previous?.join()
            withContext(Dispatchers.IO) { file?.delete(); file = null }
            mutableState.update { it.copy(download = UpdateDownload.IDLE, progress = null, error = null) }
        }
    }

    // the installer replaces files Aurora holds open, so Aurora quits once it has started
    fun install() {
        val ready = file
        if (state.value.download != UpdateDownload.READY || ready == null || !ready.isFile) {
            mutableState.update { it.copy(download = UpdateDownload.IDLE, error = appString(R.string.text_the_downloaded_update_is_missing_download_it_again_8a84fc)) }
            return
        }
        try {
            launch(ready)
            quit.tryEmit(Unit)
        } catch (_: Exception) {
            mutableState.update { it.copy(error = appString(R.string.update_desktop_install_failed)) }
        }
    }

    fun close() { scope.coroutineContext[Job]?.cancel() }

    companion object {
        internal fun verify(file: File, asset: InstallerAsset) {
            check(file.isFile && file.length() == asset.size) { appString(R.string.text_the_download_is_incomplete_please_try_again_96c9cd) }
            asset.sha256?.let { expected ->
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                check(actual.equals(expected, ignoreCase = true)) { appString(R.string.text_the_download_could_not_be_verified_please_try_again_95c71d) }
            }
        }

        // the msi upgrades the per-user install in place; the exe is the fallback
        internal fun installerAsset(json: String, tag: String): InstallerAsset? {
            val assets = JsonParser.parseString(json).asJsonObject.getAsJsonArray("assets")
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()
            fun named(extension: String) = assets.firstOrNull { it.string("name")?.endsWith(extension, ignoreCase = true) == true }
            val asset = named(".msi") ?: named(".exe") ?: return null
            val name = asset.string("name") ?: return null
            require(name.matches(Regex("[A-Za-z0-9._-]{1,120}"))) { "Unexpected installer name." }
            val url = asset.string("browser_download_url") ?: return null
            val uri = URI(url)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
                uri.path.startsWith("/nessli420/aurora/releases/download/$tag/")) { "The installer is not from Aurora's releases." }
            val size = asset.get("size")?.asLong ?: 0
            require(size in 1..(1L shl 31)) { "The release installer is empty." }
            val digest = asset.string("digest")
            val sha256 = digest?.removePrefix("sha256:")?.also { hash ->
                require(digest.startsWith("sha256:") && hash.matches(Regex("[a-fA-F0-9]{64}"))) { "Invalid installer checksum." }
            }
            return InstallerAsset(name, url, size, sha256)
        }

        private fun startInstaller(file: File) {
            val command = if (file.extension.equals("msi", ignoreCase = true)) listOf("msiexec", "/i", file.absolutePath) else listOf(file.absolutePath)
            ProcessBuilder(command).directory(file.parentFile).start()
        }

        private fun JsonObject.string(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
    }
}
