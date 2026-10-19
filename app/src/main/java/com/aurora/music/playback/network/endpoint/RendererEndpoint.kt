package com.aurora.music.playback.network.endpoint

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

class RendererEndpoint(
    context: Context,
    playback: RendererPlayback?,
    name: String,
    connect: ConnectHost? = null,
) : EndpointServer(
    name = name,
    identity = RendererTls::server,
    trust = RendererTrustStore(context).controllers(),
    persist = RendererTrustStore(context)::saveControllers,
    advertiser = RendererAdvertisement(context),
    defaultHost = { localAddress(context.applicationContext) },
    platform = "android",
    playback = playback,
    connect = connect,
    reachable = { reachableAddresses(context.applicationContext) },
) {
    companion object {
        @Suppress("DEPRECATION")
        fun localAddress(context: Context): String {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val active = connectivity.activeNetwork
            val networks = connectivity.allNetworks.sortedBy { if (it == active) 0 else 1 }
            for (network in networks) {
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) continue
                val address = connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()?.firstOrNull {
                        it.isSiteLocalAddress && !it.isLinkLocalAddress && !it.isLoopbackAddress
                    }
                if (address != null) return requireNotNull(address.hostAddress)
            }
            reachableAddresses(context).firstOrNull()?.let { return it }
            throw RendererException("local_network_required", "Connect to Wi-Fi, Ethernet or Tailscale first.", 409)
        }

        // wi-fi and ethernet, then tailscale; mobile data and vowifi tunnels carry private addresses no peer can reach
        @Suppress("DEPRECATION")
        fun reachableAddresses(context: Context): List<String> = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            connectivity.allNetworks.flatMap { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@flatMap emptyList()
                val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!vpn && !local) return@flatMap emptyList()
                connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }.filterIsInstance<Inet4Address>()
                    .filter { if (vpn) EndpointTls.isTailscale(it) else it.isSiteLocalAddress && !it.isLinkLocalAddress }
            }.sortedBy { if (EndpointTls.isTailscale(it)) 1 else 0 }.mapNotNull { it.hostAddress }.distinct()
        }.getOrDefault(emptyList())
    }
}
