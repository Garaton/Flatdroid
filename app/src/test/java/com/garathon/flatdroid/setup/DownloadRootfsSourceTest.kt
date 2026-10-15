package com.garathon.flatdroid.setup

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.InetSocketAddress

/** Against a local HTTP server: full download, resume with Range, and a server that ignores Range. */
class DownloadRootfsSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val body = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private var honourRange = true
    private val ranges = mutableListOf<String?>()
    private lateinit var server: HttpServer

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/rootfs.tar.gz") { ex ->
            val range = ex.requestHeaders.getFirst("Range")
            synchronized(ranges) { ranges += range }
            val from = range?.takeIf { honourRange }?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            if (from > 0) {
                ex.responseHeaders.add("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                ex.sendResponseHeaders(206, (body.size - from).toLong())
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
            }
            ex.responseBody.use { it.write(body, from, body.size - from) }
        }
        server.start()
    }

    @After
    fun stop() = server.stop(0)

    private fun source(cache: File, progress: MutableList<Pair<Long, Long>> = mutableListOf()) = DownloadRootfsSource(
        version = "3.24.2", sha256 = "unused",
        url = "http://127.0.0.1:${server.address.port}/rootfs.tar.gz",
        cacheFile = cache,
    ) { done, total -> progress += done to total }

    @Test
    fun downloadsOnceAndReportsProgress() {
        val cache = File(tmp.root, "rootfs.tar.gz")
        val progress = mutableListOf<Pair<Long, Long>>()
        val source = source(cache, progress)
        assertArrayEquals(body, source.open().use { it.readBytes() })
        assertArrayEquals(body, source.open().use { it.readBytes() })
        assertEquals(1, ranges.size)
        assertEquals(body.size.toLong() to body.size.toLong(), progress.last())
        source.cleanup()
        assertFalse(cache.exists())
    }

    @Test
    fun resumesFromPartFile() {
        val cache = File(tmp.root, "rootfs.tar.gz")
        File(cache.path + ".part").writeBytes(body.copyOf(100_000))
        val progress = mutableListOf<Pair<Long, Long>>()
        assertArrayEquals(body, source(cache, progress).open().use { it.readBytes() })
        assertEquals(listOf("bytes=100000-"), ranges)
        assertEquals(body.size.toLong(), progress.last().second)
        assertFalse(File(cache.path + ".part").exists())
    }

    @Test
    fun startsOverWhenServerIgnoresRange() {
        honourRange = false
        val cache = File(tmp.root, "rootfs.tar.gz")
        File(cache.path + ".part").writeBytes(ByteArray(100_000) { 7 })
        assertArrayEquals(body, source(cache).open().use { it.readBytes() })
    }
}
