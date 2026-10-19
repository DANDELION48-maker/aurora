package com.aurora.music.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue

class DiscordIpcTest {
    @Test fun framesAreLittleEndianOpcodeLengthAndUtf8Json() {
        val frame = encodeFrame(OP_FRAME, """{"a":"é"}""")
        assertArrayEquals(byteArrayOf(1, 0, 0, 0, 10, 0, 0, 0), frame.copyOf(8))
        assertEquals(OP_FRAME to """{"a":"é"}""", readFrame(frame.inputStream()))
        assertThrows(EOFException::class.java) { readFrame(frame.copyOf(frame.size - 1).inputStream()) }
    }

    @Test(timeout = 20_000) fun talksToDiscordAndReconnectsAfterItRestarts() {
        val dir = Files.createTempDirectory("ipc")
        val path = dir.resolve("discord-ipc-0")
        val users = LinkedBlockingQueue<String>()
        val ipc = DiscordIpc(onUser = { users.put(it ?: "") }, open = { unixPipe(path) }, retryMs = 100)
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path)).use { server ->
                ipc.update(activity("First"))
                ipc.start("42")
                server.accept().use { discord ->
                    val (op, hello) = discord.read()
                    assertEquals(OP_HANDSHAKE, op)
                    assertEquals("42", JsonParser.parseString(hello).asJsonObject["client_id"].asString)
                    discord.send(OP_FRAME, READY)
                    assertEquals("mara", users.take())
                    val args = JsonParser.parseString(discord.read().second).asJsonObject["args"].asJsonObject
                    assertTrue(args.has("pid"))
                    assertEquals("First", args["activity"].asJsonObject["details"].asString)
                    discord.send(OP_PING, """{"x":1}""")
                    assertEquals(OP_PONG to """{"x":1}""", discord.read())
                    discord.send(OP_FRAME, """{"cmd":"SET_ACTIVITY","evt":null}""")
                }
                ipc.update(activity("Second"))
                server.accept().use { discord ->
                    assertEquals("", users.take())
                    assertEquals(OP_HANDSHAKE, discord.read().first)
                    discord.send(OP_FRAME, READY)
                    assertEquals("mara", users.take())
                    assertEquals("Second", details(discord.read().second))
                    discord.send(OP_FRAME, """{"cmd":"SET_ACTIVITY","evt":null}""")
                    ipc.close()
                    assertEquals(OP_CLOSE, discord.read().first)
                }
            }
        } finally {
            ipc.close()
            Files.deleteIfExists(path)
            Files.deleteIfExists(dir)
        }
    }

    private fun SocketChannel.read() = readFrame(Channels.newInputStream(this))
    private fun SocketChannel.send(op: Int, json: String) = Channels.newOutputStream(this).write(encodeFrame(op, json))
    private fun activity(details: String) = JsonObject().apply { addProperty("details", details) }
    private fun details(frame: String) = JsonParser.parseString(frame).asJsonObject["args"].asJsonObject["activity"].asJsonObject["details"].asString

    private companion object {
        const val READY = """{"cmd":"DISPATCH","evt":"READY","data":{"v":1,"user":{"username":"mara","global_name":null}}}"""
    }
}
