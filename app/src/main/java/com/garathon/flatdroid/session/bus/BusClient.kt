package com.garathon.flatdroid.session.bus

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.system.Os
import com.garathon.flatdroid.util.debug.DebugLog
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Flatdroid's own connection to an app's session bus: straight to `dbus-daemon`'s socket among the
 * helpers (the app reaches Flatdroid only through its filtering proxy). [open] authenticates,
 * owns [names] and then answers calls with [handler] on one thread of its own, asleep in `read()`
 * until a message comes. Nothing is started for this but that thread.
 *
 * File descriptors that come with a call (an app's file to open, say) are the handler's only
 * while it runs: they are closed after it, so a handler that keeps one duplicates it.
 */
class BusClient private constructor(
    private val socket: LocalSocket,
    private val input: InputStream,
    private val output: OutputStream,
    private val handler: (Message, List<FileDescriptor>) -> List<Message>,
    private val log: (String) -> Unit,
) : Closeable {
    private val serial = AtomicInteger(0)
    @Volatile private var closed = false
    private val reader = Thread(::readLoop, "flatdroid-bus").apply { isDaemon = true }

    /** Sends [message] (a signal, say); false once the connection is gone. */
    fun send(message: Message): Boolean {
        if (closed) return false
        val bytes = DBusWire.encode(message, nextSerial())
        return try {
            synchronized(output) { output.write(bytes); output.flush() }
            true
        } catch (e: IOException) {
            false
        }
    }

    private fun nextSerial(): UInt = serial.incrementAndGet().toUInt().let { if (it == 0u) nextSerial() else it }

    private fun readLoop() {
        try {
            while (!closed) {
                val (message, fds) = readMessage(socket, input) ?: continue
                try {
                    // Calls, and the bus's own signals Flatdroid subscribed to (connections leaving).
                    if (message.type != DBusWire.METHOD_CALL && message.type != DBusWire.SIGNAL) continue
                    if (message.type == DBusWire.METHOD_CALL) {
                        DebugLog.i { "Bus: ${message.sender} called ${message.iface}.${message.member}(${message.signature}) ${message.body.toString().take(240)}" }
                    }
                    val replies = try {
                        handler(message, fds)
                    } catch (e: Exception) {
                        DebugLog.w { "Bus: ${message.iface}.${message.member} failed: $e" }
                        listOfNotNull(message.error("org.freedesktop.DBus.Error.Failed", "Failed").takeIf { message.expectsReply })
                    }
                    for (reply in replies) send(reply)
                } finally {
                    closeAll(fds)
                }
            }
        } catch (e: IOException) {
            if (!closed) log("[flatdroid] the session bus closed Flatdroid's connection: $e")
        } catch (e: WireException) {
            // Only a header that isn't D-Bus's gets here: the stream can't be followed after it.
            log("[flatdroid] the session bus sent Flatdroid a broken message ($e); it left the bus")
        } finally {
            closeQuietly()
        }
    }

    override fun close() {
        closeQuietly()
        if (Thread.currentThread() != reader) reader.join(JOIN_MS)
    }

    private fun closeQuietly() {
        if (closed) return
        closed = true
        // shutdownInput wakes the reader from read(); close alone may not.
        runCatching { socket.shutdownInput() }
        runCatching { socket.close() }
    }

    companion object {
        private const val JOIN_MS = 2_000L
        private const val MAX_AUTH_LINE = 512
        private const val BUS = "org.freedesktop.DBus"
        private const val BUS_PATH = "/org/freedesktop/DBus"
        /** RequestName's flag: fail instead of waiting in line for the name. */
        private const val DO_NOT_QUEUE = 4u
        private const val PRIMARY_OWNER = 1u
        private const val SETUP_TIMEOUT_MS = 5_000
        /** Connections leaving the bus (their unique name losing its owner), from the bus itself. */
        private const val LEFT_THE_BUS = "type='signal',sender='$BUS',path='$BUS_PATH',interface='$BUS',member='NameOwnerChanged',arg2=''"

        /**
         * Connects to the daemon's socket [path], authenticates as Flatdroid's user, and owns
         * [names]. Null, logged, when any of that fails: the app then runs without Flatdroid's
         * services on its bus.
         */
        fun open(
            path: File, names: List<String>, handler: (Message, List<FileDescriptor>) -> List<Message>, log: (String) -> Unit,
        ): BusClient? {
            val socket = LocalSocket()
            try {
                socket.connect(LocalSocketAddress(path.path, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = SETUP_TIMEOUT_MS
                val input = socket.inputStream
                val output = socket.outputStream
                if (!authenticate(input, output)) log("[flatdroid] the session bus passes no files to Flatdroid; apps can't open files through it")
                val client = BusClient(socket, input, output, handler, log)
                client.call(Message.call(BUS, BUS_PATH, BUS, "Hello"))
                client.call(Message.call(BUS, BUS_PATH, BUS, "AddMatch", "s", LEFT_THE_BUS))
                for (name in names) {
                    val reply = client.call(Message.call(BUS, BUS_PATH, BUS, "RequestName", "su", name, DO_NOT_QUEUE))
                    if (reply.body.firstOrNull() != PRIMARY_OWNER) throw IOException("$name is taken (${reply.body.firstOrNull()})")
                }
                socket.soTimeout = 0
                client.reader.start()
                return client
            } catch (e: Exception) {
                log("[flatdroid] Flatdroid could not join the session bus: $e")
                runCatching { socket.close() }
                return null
            }
        }

        /**
         * EXTERNAL as Flatdroid's own user (the helpers run as the same Android user), anonymous if
         * the daemon refuses that, then file descriptors asked for. Whether the daemon agreed to them.
         */
        private fun authenticate(input: InputStream, output: OutputStream): Boolean {
            output.write(byteArrayOf(0))
            output.write(BusAuth.external(Os.getuid()).toByteArray())
            output.flush()
            var line = readLine(input)
            if (line.startsWith("REJECTED")) {
                output.write(BusAuth.ANONYMOUS.toByteArray())
                output.flush()
                line = readLine(input)
            }
            if (!line.startsWith("OK ")) throw IOException("authentication: $line")
            output.write(BusAuth.NEGOTIATE_UNIX_FD.toByteArray())
            output.flush()
            val fds = readLine(input) == BusAuth.AGREE_UNIX_FD
            output.write(BusAuth.BEGIN.toByteArray())
            output.flush()
            return fds
        }

        private fun readLine(input: InputStream): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) throw EOFException("authentication")
                if (c == '\n'.code) return line.toString().removeSuffix("\r")
                if (line.length >= MAX_AUTH_LINE) throw IOException("authentication line too long")
                line.append(c.toChar())
            }
        }

        /**
         * The next message and the file descriptors that came with it, or null for one Flatdroid
         * skipped (larger than [DBusWire.MAX_MESSAGE], with a body it can't read, or whose
         * descriptors don't match its header); a skipped message's descriptors are closed.
         * Throws at the end of the stream.
         */
        private fun readMessage(socket: LocalSocket, input: InputStream): Pair<Message, List<FileDescriptor>>? {
            val fds = ArrayList<FileDescriptor>()
            try {
                val head = ByteArray(DBusWire.FIXED_HEADER)
                readFully(socket, input, head, 0, head.size, fds)
                val length = DBusWire.messageLength(head)
                if (length > DBusWire.MAX_MESSAGE) {
                    skipFully(socket, input, length - head.size, fds)
                    DebugLog.w { "Bus: skipped a message of $length bytes" }
                    closeAll(fds)
                    return null
                }
                val bytes = head.copyOf(length.toInt())
                readFully(socket, input, bytes, head.size, bytes.size - head.size, fds)
                val message = try {
                    DBusWire.decode(bytes)
                } catch (e: WireException) {
                    DebugLog.w { "Bus: skipped a message: ${e.message}" }
                    null
                }
                if (message == null || message.unixFds != fds.size) {
                    if (message != null) DebugLog.w { "Bus: skipped a message with ${fds.size} files for ${message.unixFds}" }
                    closeAll(fds)
                    return null
                }
                return message to fds
            } catch (e: Exception) {
                closeAll(fds)
                throw e
            }
        }

        /** Reads [count] bytes, collecting the descriptors any read brought. */
        private fun readFully(socket: LocalSocket, input: InputStream, into: ByteArray, offset: Int, count: Int, fds: MutableList<FileDescriptor>) {
            var done = 0
            while (done < count) {
                val n = input.read(into, offset + done, count - done)
                takeFds(socket, fds)
                if (n < 0) throw EOFException()
                done += n
            }
        }

        private fun skipFully(socket: LocalSocket, input: InputStream, count: Long, fds: MutableList<FileDescriptor>) {
            val scratch = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
                takeFds(socket, fds)
                if (n < 0) throw EOFException()
                left -= n
            }
        }

        private fun takeFds(socket: LocalSocket, into: MutableList<FileDescriptor>) {
            socket.ancillaryFileDescriptors?.let { into.addAll(it) }
        }

        private fun closeAll(fds: List<FileDescriptor>) {
            for (fd in fds) runCatching { Os.close(fd) }
        }
    }

    /** During [open], before the reader runs: sends [call] and waits for its reply, skipping signals. */
    private fun call(call: Message): Message {
        val serial = nextSerial()
        val bytes = DBusWire.encode(call, serial)
        synchronized(output) { output.write(bytes); output.flush() }
        while (true) {
            val (message, fds) = readMessage(socket, input) ?: continue
            closeAll(fds)
            if (message.replySerial != serial) continue
            if (message.type == DBusWire.ERROR) throw IOException("${call.member}: ${message.errorName} ${message.body.firstOrNull()}")
            return message
        }
    }
}

/** The authentication lines Flatdroid sends and the one it hopes for. */
object BusAuth {
    /** EXTERNAL for [uid]: the user ID in decimal, hex-encoded. */
    fun external(uid: Int): String = "AUTH EXTERNAL ${hex(uid.toString())}\r\n"

    val ANONYMOUS = "AUTH ANONYMOUS ${hex("flatdroid")}\r\n"
    const val NEGOTIATE_UNIX_FD = "NEGOTIATE_UNIX_FD\r\n"
    const val AGREE_UNIX_FD = "AGREE_UNIX_FD"
    const val BEGIN = "BEGIN\r\n"

    private fun hex(text: String) = text.toByteArray().joinToString("") { "%02x".format(it) }
}
