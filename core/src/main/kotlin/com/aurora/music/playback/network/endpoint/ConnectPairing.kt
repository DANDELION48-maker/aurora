package com.aurora.music.playback.network.endpoint

import java.net.URI

object ConnectPairing {
    fun hostAndPort(input: String): Pair<String, Int> {
        val trimmed = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('#')
        val colons = trimmed.count { it == ':' }
        val host = when {
            trimmed.startsWith('[') -> trimmed.substringAfter('[').substringBefore(']')
            colons == 1 -> trimmed.substringBefore(':')
            else -> trimmed
        }
        val port = when {
            trimmed.startsWith('[') -> trimmed.substringAfter("]:", "")
            colons == 1 -> trimmed.substringAfter(':')
            else -> ""
        }.ifEmpty { "$CONNECT_PORT" }.toIntOrNull()
        if (host.isBlank() || host.any { it.isWhitespace() || it in "/?#@" } || port == null || port !in 1..65535) {
            throw RendererException("invalid_address", "Enter an address like 100.101.102.103 or desktop.tailnet.ts.net.")
        }
        return host to port
    }

    fun probe(input: String): EndpointAddress {
        val (host, port) = hostAndPort(input)
        val fingerprint = EndpointTls.probe(host, port)
        return EndpointAddress.parse("https://${if (':' in host) "[$host]" else host}:$port#$fingerprint")
    }

    // the advertised id is the first half of the certificate hash, so a spoofed answer can't pass
    fun pairDiscovered(found: DiscoveredRenderer, code: String, name: String): PairedRenderer {
        val uri = URI(found.address)
        val fingerprint = EndpointTls.probe(uri.host.trim('[', ']'), uri.port)
        if (!fingerprint.startsWith(found.id)) throw RendererException("certificate_mismatch", "That device's identity changed. Search again.")
        return RendererClient(found.address, fingerprint).use { it.pair(name, code.trim()) }
    }

    fun withAddress(known: PairedRenderer, address: String): PairedRenderer =
        if (address == known.address || address in known.addresses) known else known.copy(addresses = (known.addresses + address).takeLast(8))
}
