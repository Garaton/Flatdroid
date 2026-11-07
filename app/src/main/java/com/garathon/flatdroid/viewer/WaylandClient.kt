package com.garathon.flatdroid.viewer

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.Os
import com.garathon.flatdroid.util.DebugLog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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

    /** An XKB keymap with [keysyms], each on its own key: key code 9 (Wayland's 1) for the first. */
    fun keymap(keysyms: List<Int>): String {
        val codes = keysyms.indices.joinToString("") { "<K${it + 1}> = ${it + 9}; " }
        val symbols = keysyms.withIndex().joinToString("") { (i, keysym) -> "key <K${i + 1}> {[ 0x${keysym.toString(16)} ]}; " }
        return "xkb_keymap {\n" +
            "xkb_keycodes \"flatdroid\" { minimum = 8; maximum = 255; $codes};\n" +
            "xkb_types \"flatdroid\" { include \"complete\" };\n" +
            "xkb_compatibility \"flatdroid\" { include \"complete\" };\n" +
            "xkb_symbols \"flatdroid\" { $symbols};\n" +
            "};\n"
    }

    /** A received message: its [body] holds the arguments. */
    class Event(val target: Int, val opcode: Int, val body: ByteBuffer) {
        fun int(): Int = body.getInt()

        fun string(): String {
            val length = body.getInt()
            if (length == 0) return ""
            if (length < 0 || length > body.remaining()) throw IOException("bad Wayland string")
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
 * Flatdroid's own client of the session's compositor, through which it does what wayvnc can't.
 * All of this runs on [sender], so nothing here is shared between threads; the reading thread only
 * reads and waits for its events to be handled, which also keeps a flood of them from piling up.
 * What comes from the compositor isn't trusted (the socket's directory is the app's to change too):
 * a message that makes no sense ends this connection, and nothing else.
 *
 * - **Android's text, read only when an app pastes:** [text] set, a client of the data-control
 *   protocol owns the clipboard ([offer]) and is told when an app asks for it, whether by a key or
 *   from its own menu. wayvnc could only take the text ahead of any paste, and never says one
 *   happened. It offers no `text/plain;charset=utf-8`, the one type wayvnc reads, so wayvnc leaves it
 *   alone (apps take `text/plain` or `UTF8_STRING`). The app's own copies still reach Android through
 *   wayvnc. [text] gives the text when asked, on a thread of its own; null for none.
 * - **The output's refresh:** wayvnc's resize sets none, which the compositor takes as its 60 Hz;
 *   after one, [outputResized] sets [refresh] (mHz) again. While nobody looks ([hidden]) it is 1 Hz:
 *   apps that draw when the compositor asks then draw about once a second.
 * - **Characters outside wayvnc's keymap:** it drops those. [key] presses them on a virtual keyboard
 *   of its own, whose keymap holds what has been typed so far.
 */
class WaylandClient(
    private val socketPath: String,
    private val text: (() -> String?)?,
    private val refresh: Int?,
    private val keymapDir: File,
) {
    private val socket = LocalSocket()
    private val sender: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    /** A virtual keyboard can be had. */
    @Volatile var canType = false
        private set
    private val pastes = AtomicInteger()

    // All on the sender's thread.
    private var nextId = FIRST_ID
    private var syncCallback = 0
    private var seatName = 0
    private var clipboardName = 0
    private var clipboardInterface = ""
    private var outputName = 0
    private var keyboardName = 0
    private var seat = 0
    private var clipboard = 0
    private var device = 0
    private var source = 0
    /** Every source made: the compositor's messages about an old one may still be on their way. */
    private val sources = HashSet<Int>()
    /** Offers the compositor made for the clipboard's content, which this client never reads. */
    private val offers = mutableListOf<Int>()
    private val fds = ArrayDeque<FileDescriptor>()
    /** [offer] came before the connection was ready. */
    private var pending = false

    private var output = 0
    private var head = 0
    private var serial = 0
    /** The configuration being applied. */
    private var configuration = 0
    /** The size to keep at [rate], until that has been applied. */
    private var wanted: Pair<Int, Int>? = null
    /** [refresh], or [HIDDEN_REFRESH] while hidden. */
    private var rate = refresh
    private var attempts = 0

    private var keyboards = 0
    private var keyboard = 0
    /** The keymap's keysyms: each on the key code of its index + 1. */
    private var mapped = emptyList<Int>()

    fun start() {
        Thread({ run() }, "wayland-client").apply { isDaemon = true }.start()
    }

    /** Android has new text: this client now owns the clipboard. Nothing is read until an app pastes. */
    fun offer() = post {
        if (text == null) return@post
        if (device == 0) {
            pending = true
            return@post
        }
        if (source != 0) send(WaylandWire.message(source, SOURCE_DESTROY))
        source = id()
        sources += source
        send(WaylandWire.message(clipboard, MANAGER_CREATE_SOURCE, source))
        MIME_TYPES.forEach { send(WaylandWire.message(source, SOURCE_OFFER, it)) }
        send(WaylandWire.message(device, DEVICE_SET_SELECTION, source))
    }

    /** The output is now [width]×[height]: back to [rate]. */
    fun outputResized(width: Int, height: Int) = post {
        wanted = (width to height).takeIf { refresh != null }
        attempts = 0
        applyMode()
    }

    /** The viewer was [hidden] or shown again, the output being [width]×[height]. */
    fun hidden(hidden: Boolean, width: Int, height: Int) = post {
        val refresh = refresh ?: return@post
        rate = if (hidden) HIDDEN_REFRESH else refresh
        wanted = width to height
        attempts = 0
        applyMode()
    }

    /** [keysym] is pressed or released on the virtual keyboard. */
    fun key(down: Boolean, keysym: Int) = post {
        if (keyboards == 0 || (!down && keysym !in mapped)) return@post
        if (keyboard == 0) {
            keyboard = id()
            send(WaylandWire.message(keyboards, KEYBOARDS_CREATE, seat, keyboard))
        }
        if (keysym !in mapped) {
            mapped = (if (mapped.size == MAX_KEYS) emptyList() else mapped) + keysym
            sendKeymap()
        }
        val code = mapped.indexOf(keysym) + 1
        send(WaylandWire.message(keyboard, KEYBOARD_KEY, SystemClock.uptimeMillis().toInt(), code, if (down) 1 else 0))
    }

    fun close() {
        closed = true
        post { while (true) closeFd(fds.poll() ?: break) }
        sender.shutdown()
        runCatching { socket.shutdownInput() }
        runCatching { socket.shutdownOutput() }
        runCatching { socket.close() }
    }

    private fun run() {
        try {
            socket.connect(LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
            post(::hello)
            val data = ByteArray(BUFFER)
            var length = 0
            val input = socket.inputStream
            while (!closed) {
                val got = input.read(data, length, data.size - length)
                if (got < 0) break
                val received = socket.ancillaryFileDescriptors
                length += got
                val (events, used) = WaylandWire.parse(data, length)
                System.arraycopy(data, used, data, 0, length - used)
                length -= used
                sender.submit {
                    received?.forEach(::keep)
                    events.forEach(::handle)
                }.get()
            }
        } catch (e: Exception) {
            if (!closed) DebugLog.w("Wayland client: $e")
        } finally {
            canType = false
            runCatching { socket.close() }
        }
    }

    /** Sender's thread, unless closed; a failure only ends what it was doing. */
    private fun post(action: () -> Unit) {
        runCatching {
            sender.execute { runCatching(action).onFailure { if (!closed) DebugLog.w("Wayland client: $it") } }
        }
    }

    private fun keep(fd: FileDescriptor) {
        if (fds.size < MAX_FDS) fds += fd
        else {
            closeFd(fd)
            throw IOException("too many descriptors")
        }
    }

    private fun closeFd(fd: FileDescriptor) {
        runCatching { Os.close(fd) }
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
                DebugLog.w("Wayland client: error $code on $target: ${event.string()}")
            }
            event.target == REGISTRY && event.opcode == REGISTRY_GLOBAL -> {
                val name = event.int()
                val type = event.string()
                when {
                    type == "wl_seat" -> seatName = name
                    type == OUTPUT_MANAGER -> outputName = name
                    type == KEYBOARD_MANAGER -> keyboardName = name
                    type in CLIPBOARD_MANAGERS && (clipboardName == 0 || type == CLIPBOARD_MANAGERS[0]) -> {
                        clipboardName = name
                        clipboardInterface = type
                    }
                }
            }
            event.target == syncCallback -> bind()
            event.target == device && event.opcode == DEVICE_DATA_OFFER -> offers += event.int()
            event.target == device && (event.opcode == DEVICE_SELECTION || event.opcode == DEVICE_PRIMARY_SELECTION) -> {
                offers.forEach { send(WaylandWire.message(it, OFFER_DESTROY)) }
                offers.clear()
            }
            event.target in sources && event.opcode == SOURCE_SEND -> paste(event.target)
            event.target == source && event.opcode == SOURCE_CANCELLED -> {
                // Replaced: something else was copied.
                send(WaylandWire.message(source, SOURCE_DESTROY))
                source = 0
            }
            event.target == output && event.opcode == OUTPUT_HEAD -> if (head == 0) head = event.int()
            event.target == output && event.opcode == OUTPUT_DONE -> {
                serial = event.int()
                applyMode()
            }
            event.target == configuration -> {
                send(WaylandWire.message(configuration, CONFIGURATION_DESTROY))
                configuration = 0
                // Cancelled: the output changed meanwhile, tried again on its new serial.
                if (event.opcode != CONFIGURATION_CANCELLED || ++attempts >= MAX_ATTEMPTS) wanted = null
                applyMode()
            }
        }
    }

    private fun bind() {
        if (seatName != 0) {
            seat = id()
            bind(seatName, "wl_seat", seat)
        }
        if (text != null && seat != 0 && clipboardName != 0) {
            clipboard = id()
            bind(clipboardName, clipboardInterface, clipboard)
            device = id()
            send(WaylandWire.message(clipboard, MANAGER_GET_DEVICE, device, seat))
            DebugLog.i("Wayland client: clipboard through $clipboardInterface")
        }
        if (refresh != null && outputName != 0) {
            output = id()
            bind(outputName, OUTPUT_MANAGER, output)
        }
        if (seat != 0 && keyboardName != 0) {
            keyboards = id()
            bind(keyboardName, KEYBOARD_MANAGER, keyboards)
            canType = true
        }
        if (pending) {
            pending = false
            offer()
        }
    }

    private fun bind(name: Int, type: String, id: Int) = send(WaylandWire.message(REGISTRY, REGISTRY_BIND, name, type, 1, id))

    /** An app asks for the text of [from], on the next descriptor received; from an old source it gets none. */
    private fun paste(from: Int) {
        val fd = fds.poll() ?: return
        val text = text
        if (text == null || from != source || pastes.get() >= MAX_PASTES) return closeFd(fd)
        pastes.incrementAndGet()
        // Its own thread: the app may be slow to read, and this one has events to follow.
        Thread({ write(fd, text) }, "wayland-paste").apply { isDaemon = true }.start()
    }

    private fun write(fd: FileDescriptor, text: () -> String?) {
        try {
            DebugLog.i("Wayland client: an app pastes")
            text()?.let { FileOutputStream(fd).write(it.toByteArray()) }
        } catch (e: IOException) {
            DebugLog.w("Wayland client: ${e.message}")
        } finally {
            // A stream made from a descriptor doesn't close it, and the app reads until it is closed.
            closeFd(fd)
            pastes.decrementAndGet()
        }
    }

    private fun applyMode() {
        val (width, height) = wanted ?: return
        val refresh = rate ?: return
        if (configuration != 0 || head == 0 || serial == 0) return
        configuration = id()
        val headConfiguration = id()
        send(WaylandWire.message(output, OUTPUT_CREATE_CONFIGURATION, configuration, serial))
        send(WaylandWire.message(configuration, CONFIGURATION_ENABLE_HEAD, headConfiguration, head))
        send(WaylandWire.message(headConfiguration, HEAD_SET_CUSTOM_MODE, width, height, refresh))
        send(WaylandWire.message(configuration, CONFIGURATION_APPLY))
    }

    private fun sendKeymap() {
        val bytes = WaylandWire.keymap(mapped).toByteArray() + 0
        val file = File.createTempFile("keymap", null, keymapDir)
        try {
            file.writeBytes(bytes)
            RandomAccessFile(file, "r").use {
                socket.setFileDescriptorsForSend(arrayOf(it.fd))
                send(WaylandWire.message(keyboard, KEYBOARD_KEYMAP, XKB_V1, bytes.size))
                // It would go with every later message too, closed by then.
                socket.setFileDescriptorsForSend(null)
            }
        } finally {
            file.delete()
        }
    }

    private fun id() = nextId++

    private fun send(bytes: ByteArray) {
        socket.outputStream.write(bytes)
    }

    private companion object {
        const val BUFFER = 64 * 1024
        /** No paste needs more; beyond these the other end is misbehaving. */
        const val MAX_FDS = 16
        const val MAX_PASTES = 4
        /** Key codes the keymap can hold; a full one starts again. */
        const val MAX_KEYS = 240
        const val MAX_ATTEMPTS = 3
        /** The output's refresh while hidden, in mHz. */
        const val HIDDEN_REFRESH = 1000
        const val DISPLAY = 1
        const val REGISTRY = 2
        const val FIRST_ID = 3

        // Newest first; both have the same requests and events.
        val CLIPBOARD_MANAGERS = listOf("ext_data_control_manager_v1", "zwlr_data_control_manager_v1")
        val MIME_TYPES = listOf("text/plain", "UTF8_STRING", "STRING", "TEXT")
        const val OUTPUT_MANAGER = "zwlr_output_manager_v1"
        const val KEYBOARD_MANAGER = "zwp_virtual_keyboard_manager_v1"

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
        const val OUTPUT_CREATE_CONFIGURATION = 0
        const val OUTPUT_HEAD = 0
        const val OUTPUT_DONE = 1
        const val CONFIGURATION_ENABLE_HEAD = 0
        const val CONFIGURATION_APPLY = 2
        const val CONFIGURATION_DESTROY = 4
        const val CONFIGURATION_CANCELLED = 2
        const val HEAD_SET_CUSTOM_MODE = 1
        const val KEYBOARDS_CREATE = 0
        const val KEYBOARD_KEYMAP = 0
        const val KEYBOARD_KEY = 1
        /** The keymap format: XKB's text. */
        const val XKB_V1 = 1
    }
}
