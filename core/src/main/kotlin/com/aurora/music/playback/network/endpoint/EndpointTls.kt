package com.aurora.music.playback.network.endpoint

import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

object EndpointTls {
    fun fingerprint(certificate: X509Certificate): String = MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    fun pinnedTrust(fingerprint: String): X509TrustManager = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw java.security.cert.CertificateException("Client certificate authentication is unsupported.")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull()
                ?: throw java.security.cert.CertificateException("Missing renderer certificate.")
            certificate.checkValidity()
            if (!MessageDigest.isEqual(fingerprint.toByteArray(), fingerprint(certificate).toByteArray())) {
                throw java.security.cert.CertificateException("Renderer certificate changed. Pair again.")
            }
        }
    }

    // trust on first use for typed addresses; the user compares the short key shown on both devices
    fun probe(host: String, port: Int): String {
        var seen: X509Certificate? = null
        val capture = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                throw java.security.cert.CertificateException("Client certificate authentication is unsupported.")
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                seen = chain?.firstOrNull()?.also { it.checkValidity() }
                    ?: throw java.security.cert.CertificateException("Missing renderer certificate.")
            }
        }
        val context = SSLContext.getInstance("TLSv1.2").apply { init(null, arrayOf(capture), SecureRandom()) }
        try {
            (context.socketFactory.createSocket() as SSLSocket).use { socket ->
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 5_000
                socket.startHandshake()
            }
        } catch (_: Exception) {
            throw RendererException("renderer_unreachable", "Cannot reach that device. Check the address and that remote control is on.", 503)
        }
        return fingerprint(seen ?: throw RendererException("renderer_unreachable", "Cannot reach that device.", 503))
    }

    fun shortKey(fingerprint: String): String = fingerprint.take(8).uppercase().chunked(4).joinToString("-")

    // lan first, then tailscale (100.64.0.0/10), which mdns never reaches
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress || isTailscale(it) }
            .sortedBy { if (isTailscale(it)) 1 else 0 }
            .mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())

    fun isTailscale(address: Inet4Address): Boolean {
        val bytes = address.address
        return (bytes[0].toInt() and 0xff) == 100 && (bytes[1].toInt() and 0xc0) == 64
    }
}
