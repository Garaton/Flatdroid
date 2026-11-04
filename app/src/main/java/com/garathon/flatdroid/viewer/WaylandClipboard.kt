package com.garathon.flatdroid.viewer

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import com.garathon.flatdroid.util.DebugLog
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wayland's wire format: messages of an object, an opcode and arguments, in the machine's byte
 * order, padded to 4 bytes.
 */
object WaylandWire {
    private val ORDER: ByteOrder = ByteOrder.nativeOrder()

    /** One message; arguments are [Int]s (ids, numbers) or [String]s. */
    fun message(target: Int, opcode: Int, vararg args: Any): ByteArray {
        val body = ByteArrayOutputStream()
        for (arg in args) {
            when (arg) {
                is Int -> body.write(int(arg))
                is String -> {
                    val bytes = arg.toByteArray()
                    body.write(int(bytes.size + 1))
                    body.write(bytes)
                    repeat(4 - bytes.size % 4) { body.write(0) } // its NUL, then padding
                }
                else -> throw IllegalArgumentException("not a Wayland argument: $arg")
            }
        }
        return int(target) + int((8 + body.size()) shl 16 or opcode) + body.toByteArray()
    }

    private fun int(value: Int) = ByteBuffer.allocate(4).order(ORDER).putInt(value).array()

    /** A received message: its [body] holds the arguments. */
    class Event(val target: Int, val opcode: Int, val body: ByteBuffer) {
        fun int(): Int = body.getInt()

        fun string(): String {
            val length = body.getInt()
            if (length == 0) return ""
            val bytes = ByteArray(length - 1).also(body::get)
            body.position(body.position() + 1 + (4 - length % 4) % 4)
            return String(bytes)
        }
    }

    /** The whole messages at the start of [data]'s first [length] bytes, and how many bytes they took. */
    fun parse(data: ByteArray, length: Int): Pair<List<Event>, Int> {
        val events = mutableListOf<Event>()
        val buffer = ByteBuffer.wrap(data, 0, length).order(ORDER)
        var at = 0
        while (length - at >= 8) {
            val target = buffer.getInt(at)
            val word = buffer.getInt(at + 4)
            val size = word ushr 16
            if (size < 8) throw IOException("bad Wayland message")
            if (length - at < size) break
            events += Event(target, word and 0xffff, ByteBuffer.wrap(data.copyOfRange(at + 8, at + size)).order(ORDER))
            at += size
        }
        return events to at
    }
}

/**
 * Android's text as the Wayland session's clipboard, read from Android only when an app pastes:
 * a client of the compositor's data-control protocol that owns the clipboard ([offer]), told by
 * the compositor when an app asks for it, whether by a key or from its own menu. wayvnc could
 * only take the text ahead of any paste, and never says one happened. It offers no
 * `text/plain;charset=utf-8`, the one type wayvnc reads, so wayvnc leaves it alone (apps take
 * `text/plain` or `UTF8_STRING`). The app's own copies still reach Android through wayvnc.
 *
 * [text] gives Android's text when asked, on a thread of this class; null for none.
 */
class WaylandClipboard(private val socketPath: String, private val text: () -> String?) {
    private val socket = LocalSocket()
    private val sender: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    private var nextId = FIRST_ID
    private var seatName = 0
    private var seatVersion = 0
    private var managerName = 0
    private var managerInterface = ""
    private var manager = 0
    private var device = 0
    private var source = 0
    private var syncCallback = 0
    /** Offers the compositor made for the clipboard's content, which this client never reads. */
    private val offers = mutableListOf<Int>()
    private val fds = ArrayDeque<FileDescriptor>()
    /** [offer] came before the connection was ready. */
    private var pending = false

    fun start() {
        Thread({ run() }, "wayland-clipboard").apply { isDaemon = true }.start()
    }

    /** Android has new text: this client now owns the clipboard. Nothing is read until an app pastes. */
    fun offer() = sender.execute {
        runCatching {
            if (device == 0) {
                pending = true
                return@execute
            }
            if (source != 0) send(WaylandWire.message(source, SOURCE_DESTROY))
            source = id()
            send(WaylandWire.message(manager, MANAGER_CREATE_SOURCE, source))
            MIME_TYPES.forEach { send(WaylandWire.message(source, SOURCE_OFFER, it)) }
            send(WaylandWire.message(device, DEVICE_SET_SELECTION, source))
        }
    }

    fun close() {
        closed = true
        sender.shutdown()
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    private fun run() {
        try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            sender.execute { runCatching { hello() } }
            val data = ByteArray(BUFFER)
            var length = 0
            val input = socket.inputStream
            while (!closed) {
                val got = input.read(data, length, data.size - length)
                if (got < 0) break
                socket.ancillaryFileDescriptors?.let(fds::addAll)
                length += got
                val (events, used) = WaylandWire.parse(data, length)
                System.arraycopy(data, used, data, 0, length - used)
                length -= used
                events.forEach(::handle)
            }
        } catch (e: IOException) {
            if (!closed) DebugLog.w("Wayland clipboard: ${e.message}")
        }
    }

    private fun hello() {
        send(WaylandWire.message(DISPLAY, DISPLAY_GET_REGISTRY, REGISTRY))
        syncCallback = id()
        send(WaylandWire.message(DISPLAY, DISPLAY_SYNC, syncCallback))
    }

    private fun handle(event: WaylandWire.Event) {
        when {
            event.target == DISPLAY && event.opcode == DISPLAY_ERROR -> {
                val target = event.int()
                val code = event.int()
                DebugLog.w("Wayland clipboard: error $code on $target: ${event.string()}")
            }
            event.target == REGISTRY && event.opcode == REGISTRY_GLOBAL -> {
                val name = event.int()
                val type = event.string()
                val version = event.int()
                when {
                    type == "wl_seat" -> { seatName = name; seatVersion = version }
                    type in MANAGERS && (managerName == 0 || type == MANAGERS[0]) -> { managerName = name; managerInterface = type }
                }
            }
            event.target == syncCallback -> sender.execute { runCatching { bind() } }
            event.target == device && event.opcode == DEVICE_DATA_OFFER -> offers += event.int()
            event.target == device && (event.opcode == DEVICE_SELECTION || event.opcode == DEVICE_PRIMARY_SELECTION) -> {
                val stale = offers.toList()
                offers.clear()
                sender.execute { runCatching { stale.forEach { send(WaylandWire.message(it, OFFER_DESTROY)) } } }
            }
            event.target == source && event.opcode == SOURCE_SEND -> {
                event.string()
                val fd = fds.poll() ?: return
                // Its own thread: the app may be slow to read, and this one has events to follow.
                Thread({ write(fd) }, "wayland-clipboard-paste").apply { isDaemon = true }.start()
            }
            event.target == source && event.opcode == SOURCE_CANCELLED -> sender.execute {
                // Replaced: something else was copied. Destroyed, unless a newer offer already is the source.
                runCatching { if (source == event.target) { send(WaylandWire.message(source, SOURCE_DESTROY)); source = 0 } }
            }
        }
    }

    private fun bind() {
        if (seatName == 0 || managerName == 0) return DebugLog.w("Wayland clipboard: the compositor has no data-control")
        val seat = id()
        send(WaylandWire.message(REGISTRY, REGISTRY_BIND, seatName, "wl_seat", 1, seat))
        manager = id()
        send(WaylandWire.message(REGISTRY, REGISTRY_BIND, managerName, managerInterface, 1, manager))
        val newDevice = id()
        send(WaylandWire.message(manager, MANAGER_GET_DEVICE, newDevice, seat))
        device = newDevice
        DebugLog.i("Wayland clipboard: through $managerInterface")
        if (pending) {
            pending = false
            offer()
        }
    }

    private fun write(fd: FileDescriptor) {
        try {
            DebugLog.i("Wayland clipboard: an app pastes")
            text()?.let { FileOutputStream(fd).write(it.toByteArray()) }
        } catch (e: IOException) {
            DebugLog.w("Wayland clipboard: ${e.message}")
        } finally {
            // A stream made from a descriptor doesn't close it, and the app reads until it is closed.
            runCatching { Os.close(fd) }
        }
    }

    private fun id() = nextId++

    private fun send(bytes: ByteArray) {
        socket.outputStream.write(bytes)
    }

    private companion object {
        const val BUFFER = 64 * 1024
        const val DISPLAY = 1
        const val REGISTRY = 2
        const val FIRST_ID = 3

        // Newest first; both have the same requests and events.
        val MANAGERS = listOf("ext_data_control_manager_v1", "zwlr_data_control_manager_v1")
        val MIME_TYPES = listOf("text/plain", "UTF8_STRING", "STRING", "TEXT")

        const val DISPLAY_SYNC = 0
        const val DISPLAY_GET_REGISTRY = 1
        const val DISPLAY_ERROR = 0
        const val REGISTRY_BIND = 0
        const val REGISTRY_GLOBAL = 0
        const val MANAGER_CREATE_SOURCE = 0
        const val MANAGER_GET_DEVICE = 1
        const val DEVICE_SET_SELECTION = 0
        const val DEVICE_DATA_OFFER = 0
        const val DEVICE_SELECTION = 1
        const val DEVICE_PRIMARY_SELECTION = 3
        const val SOURCE_OFFER = 0
        const val SOURCE_DESTROY = 1
        const val SOURCE_SEND = 0
        const val SOURCE_CANCELLED = 1
        const val OFFER_DESTROY = 1
    }
}
