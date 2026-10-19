package com.aurora.music.desktop.connect

import androidx.compose.runtime.staticCompositionLocalOf
import com.aurora.music.desktop.DesktopContainer
import com.aurora.music.desktop.platform.HostPlatform
import com.aurora.music.desktop.platform.WebCredentials
import com.aurora.music.desktop.player.DesktopPlayer
import com.aurora.music.desktop.player.PlayerController
import com.aurora.music.playback.network.endpoint.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.File

val LocalConnect = staticCompositionLocalOf<DesktopConnect?> { null }

data class ProbedDevice(val address: String, val fingerprint: String, val known: Boolean)

data class DesktopConnectState(
    val enabled: Boolean = false,
    val address: EndpointAddress? = null,
    val addresses: List<String> = emptyList(),
    val pairing: PairingWindow? = null,
    val controllers: List<PairedController> = emptyList(),
    val paired: List<PairedRenderer> = emptyList(),
    val discovered: List<DiscoveredRenderer> = emptyList(),
    val scanning: Boolean = false,
    val controlling: PairedRenderer? = null,
    val probed: ProbedDevice? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

class DesktopConnect(private val container: DesktopContainer, private val local: DesktopPlayer) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val vault = WebCredentials(container.paths.local, "aurora-connect")
    private val trust = ConnectTrustStore(vault)
    private val mutable = MutableStateFlow(DesktopConnectState(paired = trust.renderers()))
    private val mutableActive = MutableStateFlow<PlayerController>(local)
    private var server: EndpointServer? = null
    private var serverJobs: List<Job> = emptyList()
    private var remote: RemotePlayerController? = null
    private var scanJob: Job? = null
    val state: StateFlow<DesktopConnectState> = mutable.asStateFlow()
    private val mutableMessages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = mutableMessages
    val active: StateFlow<PlayerController> = mutableActive.asStateFlow()
    val name = deviceName()

    init {
        scope.launch { container.desktopSettings.allowRemoteControl.collect { enable(it) } }
    }

    private suspend fun enable(on: Boolean) {
        if (on == (server != null)) return
        if (!on) {
            val old = server
            server = null
            serverJobs.forEach { it.cancel() }
            mutable.update { it.copy(enabled = false, address = null, addresses = emptyList(), pairing = null, controllers = emptyList()) }
            withContext(NonCancellable + Dispatchers.IO) { old?.stop() }
            return
        }
        val candidate = EndpointServer(name, { connectIdentity(vault) }, trust.controllers(), trust::saveControllers, MdnsAdvertiser(),
            { EndpointTls.localAddresses().firstOrNull() ?: "127.0.0.1" }, HostPlatform.name.lowercase(), connect = DesktopConnectHost(local, container))
        try {
            val address = withContext(Dispatchers.IO) { candidate.start() }
            server = candidate
            mutable.update { it.copy(enabled = true, address = address, addresses = candidate.addresses(), error = null) }
            serverJobs = listOf(
                scope.launch { candidate.clients.collect { clients -> mutable.update { it.copy(controllers = clients) } } },
                scope.launch { candidate.pairing.collect { window -> mutable.update { it.copy(pairing = window) } } },
            )
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            withContext(Dispatchers.IO) { candidate.stop() }
            mutable.update { it.copy(enabled = false, error = (e as? RendererException)?.message ?: "Remote control could not start on this network.") }
        }
    }

    fun setEnabled(on: Boolean) { scope.launch { container.desktopSettings.setAllowRemoteControl(on) } }
    fun beginPairing() { runCatching { server?.beginPairing() } }
    fun revokeController(id: String) { server?.revokeClient(id) }
    fun dismissError() = mutable.update { it.copy(error = null, probed = null) }
    fun command(command: ConnectCommand) { remote?.session?.post(command) }

    fun scan() {
        if (scanJob?.isActive == true) return
        scanJob = scope.launch {
            mutable.update { it.copy(scanning = true) }
            val own = server?.deviceId
            val found = discoverDevices().filter { it.id != own }
            val known = trust.renderers()
            known.forEach { peer -> found.firstOrNull { it.id == peer.id && it.address != peer.address }?.let { trust.saveRenderer(peer.copy(address = it.address)) } }
            mutable.update { it.copy(scanning = false, discovered = found, paired = trust.renderers()) }
        }
    }

    fun pairDiscovered(found: DiscoveredRenderer, code: String) = pairing {
        trust.saveRenderer(ConnectPairing.pairDiscovered(found, code, name))
        trust.renderers().firstOrNull { it.id == found.id }
    }

    fun probe(input: String) {
        scope.launch {
            mutable.update { it.copy(busy = true, error = null, probed = null) }
            try {
                val address = withContext(Dispatchers.IO) { ConnectPairing.probe(input) }
                val known = trust.renderers().firstOrNull { it.fingerprint == address.fingerprint }
                known?.let { trust.saveRenderer(ConnectPairing.withAddress(it, address.address)) }
                mutable.update { it.copy(probed = ProbedDevice(address.address, address.fingerprint, known != null), paired = trust.renderers()) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = (e as? RendererException)?.message ?: "Cannot reach that device.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun pairProbed(code: String) {
        val probed = mutable.value.probed ?: return
        mutable.update { it.copy(probed = null) }
        pairing {
            val paired = RendererClient(probed.address, probed.fingerprint).use { it.pair(name, code.trim()) }
            trust.saveRenderer(paired)
            paired
        }
    }

    private fun pairing(block: () -> PairedRenderer?) {
        scope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                val paired = withContext(Dispatchers.IO) { block() }
                mutable.update { it.copy(paired = trust.renderers()) }
                paired?.let(::control)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = (e as? RendererException)?.message ?: "Pairing failed. Check the code.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun forget(id: String) {
        if (mutable.value.controlling?.id == id) control(null)
        trust.forgetRenderer(id)
        mutable.update { it.copy(paired = trust.renderers()) }
    }

    fun control(peer: PairedRenderer?) {
        scope.launch {
            val behaviour = container.settingsStore.connectSwitch.first()
            val old = remote
            if (peer?.id == old?.session?.peer?.id) return@launch
            if (peer == null) {
                old ?: return@launch
                remote = null
                mutableActive.value = local
                mutable.update { it.copy(controlling = null) }
                val status = old.session.status.value
                if (behaviour == ConnectSwitch.MOVE && status != null && status.queue.isNotEmpty()) {
                    val position = old.session.positionMs()
                    val songs = container.repository.resolveConnectTracks(status.queue)
                    val start = songs.indexOfFirst { it.id == status.current?.id }.coerceAtLeast(0)
                    if (songs.isNotEmpty()) {
                        local.playAll(songs, start)
                        if (songs[start].id == status.current?.id && songs[start].durationSec > 0) local.seekTo(position / (songs[start].durationSec * 1000f))
                        if (!status.playing && local.state.value.isPlaying) local.togglePlay()
                        old.session.send(ConnectCommand.Playing(false))
                    }
                }
                old.close(); old.session.close()
                return@launch
            }
            old?.let { it.close(); it.session.close() }
            val fresh = trust.renderers().firstOrNull { it.id == peer.id } ?: peer
            val session = ConnectSession(fresh, scope)
            val controller = RemotePlayerController(session, scope, File(container.paths.cache, "connect-art")) { control(null) }
            val here = local.state.value
            if (behaviour == ConnectSwitch.MOVE && here.hasTrack && here.queue.isNotEmpty()) {
                val from = (here.currentIndex - 100).coerceIn(0, (here.queue.size - 500).coerceAtLeast(0))
                val window = here.queue.subList(from, minOf(here.queue.size, from + 500))
                session.post(ConnectCommand.Play(window.map(ConnectTrack::of), (here.currentIndex - from).coerceIn(0, window.lastIndex),
                    (here.positionSec * 1000).toLong(), here.isPlaying))
            }
            if (behaviour != ConnectSwitch.KEEP && here.isPlaying) local.togglePlay()
            remote = controller
            mutableActive.value = controller
            mutable.update { it.copy(controlling = fresh, error = null) }
            scope.launch {
                session.lost.first { it }
                if (remote !== controller) return@launch
                val message = session.error.value ?: "Lost connection to ${fresh.name}."
                control(null)
                mutable.update { it.copy(error = message) }
                mutableMessages.tryEmit(message)
            }
        }
    }

    override fun close() {
        remote?.let { it.close(); it.session.close() }
        server?.stop()
        scope.cancel()
    }
}
