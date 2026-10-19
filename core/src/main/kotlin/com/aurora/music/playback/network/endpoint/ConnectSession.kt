package com.aurora.music.playback.network.endpoint

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConnectSession(val peer: PairedRenderer, private val scope: CoroutineScope) : AutoCloseable {
    private val clients = (listOf(peer.address) + peer.addresses).distinct().map { RendererClient(peer.copy(address = it)) }
    @Volatile private var client = clients.first()
    private val mutableStatus = MutableStateFlow<ConnectStatus?>(null)
    private val mutableError = MutableStateFlow<String?>(null)
    val status: StateFlow<ConnectStatus?> = mutableStatus.asStateFlow()
    val error: StateFlow<String?> = mutableError.asStateFlow()
    @Volatile private var receivedAt = 0L
    @Volatile private var tag: String? = null
    private val sending = Mutex()
    private val job: Job = scope.launch { poll() }

    fun positionMs(): Long {
        val current = mutableStatus.value ?: return 0
        val elapsed = if (current.playing) ((System.nanoTime() - receivedAt) / 1_000_000 * current.speed).toLong() else 0L
        val position = current.positionMs + elapsed
        return if (current.durationMs > 0) position.coerceAtMost(current.durationMs) else position
    }

    private suspend fun poll() {
        var failures = 0
        while (true) {
            try {
                val (status, next) = client.connectStatus(tag, positionMs())
                apply(status, next)
                failures = 0
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failures++
                tag = null
                val code = (e as? RendererException)?.code
                if (code !in setOf("unauthorized", "connect_disabled", "certificate_mismatch", "unsupported_version") && clients.size > 1) {
                    client = clients[(clients.indexOf(client) + 1) % clients.size]
                }
                if (failures >= clients.size) mutableError.value = e.message ?: "Lost the connection."
                delay((failures * 1_000L).coerceAtMost(5_000))
            }
        }
    }

    suspend fun send(command: ConnectCommand): Boolean = try {
        val (status, next) = sending.withLock { client.connectCommand(command) }
        apply(status, next)
        true
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { mutableError.value = e.message ?: "The command didn't reach the device."; false }

    fun post(command: ConnectCommand) { scope.launch { send(command) } }

    fun artwork(id: String): ByteArray? = client.artwork(id)

    fun clearError() { mutableError.value = null }

    private fun apply(status: ConnectStatus, next: String) {
        receivedAt = System.nanoTime()
        tag = next
        mutableError.value = null
        mutableStatus.value = status
    }

    override fun close() {
        job.cancel()
        clients.forEach { it.close() }
    }
}
