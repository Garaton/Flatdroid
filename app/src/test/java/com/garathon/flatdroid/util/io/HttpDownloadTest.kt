package com.garathon.flatdroid.util.io

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

class HttpDownloadTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: HttpServer
    private val body = ByteArray(10_000) { (it % 251).toByte() }

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/file") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
            when {
                range != null && range >= body.size -> exchange.sendResponseHeaders(416, -1)
                else -> {
                    exchange.sendResponseHeaders(200, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
            exchange.close()
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private val url get() = "http://127.0.0.1:${server.address.port}/file"

    @Test
    fun aCompleteButUnrenamedPartFileStartsOverInsteadOfFailing() {
        val dest = File(tmp.root, "out.bin")
        // Killed between the last byte and the rename: the next range starts at the end, which the server refuses.
        File(dest.path + ".part").writeBytes(body)
        httpDownload(url, dest, maxBytes = 1 shl 20)
        assertArrayEquals(body, dest.readBytes())
        assertFalse(File(dest.path + ".part").exists())
    }

    @Test
    fun aPlainDownloadWorks() {
        val dest = File(tmp.root, "plain.bin")
        httpDownload(url, dest, maxBytes = 1 shl 20)
        assertArrayEquals(body, dest.readBytes())
    }
}
