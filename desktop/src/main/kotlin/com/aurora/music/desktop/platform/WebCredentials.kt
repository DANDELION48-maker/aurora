package com.aurora.music.desktop.platform

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// browser logins for streaming services; windows keeps them under the user's DPAPI key, linux under a private key file
class WebCredentials(root: File, name: String) {
    private val directory = File(root, "credentials/$name")
    private val keyFile = File(root, "credentials/.key")

    fun save(session: String): String = UUID.randomUUID().toString().also { update(it, session) }

    // rewrites a saved login in place, for sessions whose cookies rotate
    @Synchronized fun update(id: String, session: String) {
        directory.mkdirs()
        val target = file(id)
        val temp = File(directory, "$id.tmp")
        temp.writeBytes(seal(session.toByteArray(Charsets.UTF_8)))
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun read(id: String): String = runCatching { String(open(file(id).readBytes()), Charsets.UTF_8) }.getOrDefault("")

    fun remove(id: String) {
        file(id).delete()
    }

    private fun file(id: String): File {
        require(id.matches(ID)) { "bad credential id" }
        return File(directory, "$id.bin")
    }

    private fun seal(plain: ByteArray): ByteArray =
        if (HostPlatform.isWindows) Crypt32Util.cryptProtectData(plain)
        else {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            iv + cipher.doFinal(plain)
        }

    private fun open(sealed: ByteArray): ByteArray =
        if (HostPlatform.isWindows) Crypt32Util.cryptUnprotectData(sealed)
        else Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12))) }
            .doFinal(sealed, 12, sealed.size - 12)

    @Synchronized private fun key(): SecretKeySpec {
        if (!keyFile.isFile) {
            keyFile.parentFile.mkdirs()
            keyFile.writeBytes(ByteArray(32).also(random::nextBytes))
            runCatching { Files.setPosixFilePermissions(keyFile.toPath(), PosixFilePermissions.fromString("rw-------")) }
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }

    companion object {
        private val ID = Regex("[0-9a-fA-F-]{36}")
        private val random = SecureRandom()
        fun youtubeMusic(root: File) = WebCredentials(root, "youtube-music")
        fun spotify(root: File) = WebCredentials(root, "spotify-web")
        fun soundcloud(root: File) = WebCredentials(root, "soundcloud-web")
    }
}
