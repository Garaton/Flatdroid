package com.garathon.flatdroid.session.bus

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * D-Bus's wire format, only the parts Flatdroid's own services need. Values are Kotlin types by
 * signature: `y` [UByte], `b` [Boolean], `n` [Short], `q` [UShort], `i` [Int], `u` [UInt], `x`
 * [Long], `t` [ULong], `d` [Double], `s`, `o` and `g` [String], `h` [UnixFd], `ay` [ByteArray],
 * any other `a` [List] (a dict [Map]), `(…)` [Struct], `v` [Variant]. Messages reach Flatdroid through `dbus-daemon`, which
 * checks them, but their bodies are an app's: every read here is bounded by the message's own
 * length, nesting and the limits below, and anything else is a [WireException].
 */
object DBusWire {
    /** Flatdroid takes no message larger than this; the specification allows 128 MiB. */
    const val MAX_MESSAGE = 1 shl 20
    const val MAX_SIGNATURE = 255
    /** The specification's limits: 32 arrays and 32 structs deep. */
    const val MAX_DEPTH = 64

    const val METHOD_CALL = 1
    const val METHOD_RETURN = 2
    const val ERROR = 3
    const val SIGNAL = 4

    const val NO_REPLY_EXPECTED = 0x1

    /** The fixed part of a message's header: up to and including the header fields' length. */
    const val FIXED_HEADER = 16

    private const val FIELD_PATH = 1
    private const val FIELD_INTERFACE = 2
    private const val FIELD_MEMBER = 3
    private const val FIELD_ERROR_NAME = 4
    private const val FIELD_REPLY_SERIAL = 5
    private const val FIELD_DESTINATION = 6
    private const val FIELD_SENDER = 7
    private const val FIELD_SIGNATURE = 8
    private const val FIELD_UNIX_FDS = 9

    /**
     * The whole length of the message whose first [FIXED_HEADER] bytes are [head], to read the
     * rest before [decode] (or skip it, past [MAX_MESSAGE]). Throws for a header that isn't D-Bus's.
     */
    fun messageLength(head: ByteArray): Long {
        if (head.size < FIXED_HEADER) throw WireException("short header")
        val buffer = ByteBuffer.wrap(head).order(order(head[0]))
        if (head[3].toInt() != 1) throw WireException("protocol version ${head[3]}")
        val body = buffer.getInt(4).toUInt().toLong()
        val fields = buffer.getInt(12).toUInt().toLong()
        return FIXED_HEADER + fields + pad(FIXED_HEADER + fields, 8) + body
    }

    /** The message in [bytes] (exactly one, as [messageLength] measured). */
    fun decode(bytes: ByteArray): Message {
        val length = messageLength(bytes)
        if (length > MAX_MESSAGE) throw WireException("message of $length bytes")
        if (bytes.size.toLong() != length) throw WireException("message length")
        val reader = Reader(bytes, order(bytes[0]))
        reader.skip(1)
        val type = reader.byte().toInt()
        val flags = reader.byte().toInt()
        reader.skip(1)
        val bodyLength = reader.uint().toInt()
        val serial = reader.uint()
        if (serial == 0u) throw WireException("serial 0")
        @Suppress("UNCHECKED_CAST")
        val fields = reader.read("a(yv)") as List<Struct>
        reader.align(8)
        val bodyStart = reader.position
        if (bodyStart + bodyLength != bytes.size) throw WireException("body length")
        var path: String? = null
        var iface: String? = null
        var member: String? = null
        var errorName: String? = null
        var replySerial: UInt? = null
        var destination: String? = null
        var sender: String? = null
        var signature = ""
        var unixFds = 0
        for (field in fields) {
            val code = (field.values[0] as UByte).toInt()
            val variant = field.values[1] as Variant
            fun expect(sig: String) = if (variant.signature == sig) variant.value else throw WireException("header field $code type")
            when (code) {
                FIELD_PATH -> path = expect("o") as String
                FIELD_INTERFACE -> iface = expect("s") as String
                FIELD_MEMBER -> member = expect("s") as String
                FIELD_ERROR_NAME -> errorName = expect("s") as String
                FIELD_REPLY_SERIAL -> replySerial = expect("u") as UInt
                FIELD_DESTINATION -> destination = expect("s") as String
                FIELD_SENDER -> sender = expect("s") as String
                FIELD_SIGNATURE -> signature = expect("g") as String
                FIELD_UNIX_FDS -> unixFds = (expect("u") as UInt).toInt()
                else -> {} // Unknown fields are ignored, as the specification asks.
            }
        }
        val body = Reader(bytes, order(bytes[0]), start = bodyStart).readAll(signature)
        return Message(type, flags, serial, path, iface, member, errorName, replySerial, destination, sender, signature, body, unixFds)
    }

    /** [message] as bytes, little-endian, numbered [serial]. */
    fun encode(message: Message, serial: UInt): ByteArray {
        val body = Writer()
        val types = Signature.split(message.signature)
        if (types.size != message.body.size) throw WireException("body doesn't match ${message.signature}")
        types.forEachIndexed { index, type -> body.write(type, message.body[index]) }
        val bodyBytes = body.bytes()
        val fields = buildList {
            message.path?.let { add(Struct(FIELD_PATH.toUByte(), Variant("o", it))) }
            message.iface?.let { add(Struct(FIELD_INTERFACE.toUByte(), Variant("s", it))) }
            message.member?.let { add(Struct(FIELD_MEMBER.toUByte(), Variant("s", it))) }
            message.errorName?.let { add(Struct(FIELD_ERROR_NAME.toUByte(), Variant("s", it))) }
            message.replySerial?.let { add(Struct(FIELD_REPLY_SERIAL.toUByte(), Variant("u", it))) }
            message.destination?.let { add(Struct(FIELD_DESTINATION.toUByte(), Variant("s", it))) }
            message.sender?.let { add(Struct(FIELD_SENDER.toUByte(), Variant("s", it))) }
            if (message.signature.isNotEmpty()) add(Struct(FIELD_SIGNATURE.toUByte(), Variant("g", message.signature)))
            if (message.unixFds > 0) add(Struct(FIELD_UNIX_FDS.toUByte(), Variant("u", message.unixFds.toUInt())))
        }
        val header = Writer()
        header.write("y", 'l'.code.toUByte())
        header.write("y", message.type.toUByte())
        header.write("y", message.flags.toUByte())
        header.write("y", 1.toUByte())
        header.write("u", bodyBytes.size.toUInt())
        header.write("u", serial)
        header.write("a(yv)", fields)
        header.align(8)
        val out = header.bytes() + bodyBytes
        if (out.size > MAX_MESSAGE) throw WireException("message of ${out.size} bytes")
        return out
    }

    private fun order(endian: Byte) = when (endian.toInt().toChar()) {
        'l' -> ByteOrder.LITTLE_ENDIAN
        'B' -> ByteOrder.BIG_ENDIAN
        else -> throw WireException("endianness")
    }

    internal fun pad(position: Long, alignment: Int): Long = (alignment - position % alignment) % alignment

    internal fun alignment(type: Char): Int = when (type) {
        'y', 'g', 'v' -> 1
        'n', 'q' -> 2
        'b', 'i', 'u', 's', 'o', 'a', 'h' -> 4
        'x', 't', 'd', '(', '{' -> 8
        else -> throw WireException("type $type")
    }

    /** Reads values from [bytes], aligned as from the message's start. */
    internal class Reader(private val bytes: ByteArray, order: ByteOrder, start: Int = 0) {
        private val buffer = ByteBuffer.wrap(bytes).order(order)
        var position = start
            private set
        private var depth = 0

        fun skip(count: Int) = need(count).also { position += count }

        fun align(alignment: Int) {
            val padding = pad(position.toLong(), alignment).toInt()
            need(padding)
            for (i in 0 until padding) if (bytes[position + i].toInt() != 0) throw WireException("non-zero padding")
            position += padding
        }

        private fun need(count: Int) {
            if (count < 0 || position + count > bytes.size) throw WireException("past the end")
        }

        fun byte(): Byte = need(1).let { bytes[position++] }
        fun uint(): UInt { align(4); need(4); return buffer.getInt(position).toUInt().also { position += 4 } }

        /** Every value of [signature] up to the end of the bytes, which must be the end of the last. */
        fun readAll(signature: String): List<Any?> {
            val values = Signature.split(signature).map { read(it) }
            if (position != bytes.size) throw WireException("bytes after the body")
            return values
        }

        fun read(type: String): Any? {
            if (++depth > MAX_DEPTH) throw WireException("nested too deep")
            try {
                return readType(type)
            } finally {
                depth--
            }
        }

        private fun readType(type: String): Any? = when (type[0]) {
            'y' -> byte().toUByte()
            'b' -> when (uint()) { 0u -> false; 1u -> true; else -> throw WireException("boolean") }
            'n' -> { align(2); need(2); buffer.getShort(position).also { position += 2 } }
            'q' -> { align(2); need(2); buffer.getShort(position).toUShort().also { position += 2 } }
            'i' -> uint().toInt()
            'u' -> uint()
            'h' -> UnixFd(uint().toInt())
            'x' -> { align(8); need(8); buffer.getLong(position).also { position += 8 } }
            't' -> { align(8); need(8); buffer.getLong(position).toULong().also { position += 8 } }
            'd' -> { align(8); need(8); buffer.getDouble(position).also { position += 8 } }
            's' -> string(uint().toLong())
            'o' -> string(uint().toLong()).also { if (!Signature.isObjectPath(it)) throw WireException("object path") }
            'g' -> string(byte().toUByte().toLong()).also { Signature.split(it) }
            'v' -> {
                val signature = string(byte().toUByte().toLong())
                val types = Signature.split(signature)
                if (types.size != 1) throw WireException("variant of $signature")
                Variant(signature, read(signature))
            }
            '(' -> {
                align(8)
                Struct(Signature.split(type.substring(1, type.length - 1)).map { read(it) })
            }
            'a' -> array(type.substring(1))
            else -> throw WireException("type $type")
        }

        private fun array(element: String): Any {
            val length = uint().toLong()
            if (length > MAX_MESSAGE) throw WireException("array of $length bytes")
            align(alignment(element[0]))
            val end = position + length
            if (end > bytes.size) throw WireException("past the end")
            // Bytes stay bytes: a list of boxed ones would take about 20 times the message's size.
            if (element == "y") return bytes.copyOfRange(position, end.toInt()).also { position = end.toInt() }
            return if (element[0] == '{') {
                val (keyType, valueType) = Signature.split(element.substring(1, element.length - 1))
                val map = LinkedHashMap<Any?, Any?>()
                while (position < end) {
                    align(8)
                    val key = read(keyType)
                    map[key] = read(valueType)
                }
                if (position != end.toInt()) throw WireException("array length")
                map
            } else {
                val list = ArrayList<Any?>()
                while (position < end) list += read(element)
                if (position != end.toInt()) throw WireException("array length")
                list
            }
        }

        private fun string(length: Long): String {
            if (length > MAX_MESSAGE) throw WireException("string of $length bytes")
            need(length.toInt() + 1)
            val start = position
            for (i in start until start + length.toInt()) if (bytes[i].toInt() == 0) throw WireException("NUL in a string")
            if (bytes[start + length.toInt()].toInt() != 0) throw WireException("string not ended by NUL")
            position += length.toInt() + 1
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, length.toInt())).toString()
            } catch (e: CharacterCodingException) {
                throw WireException("string not UTF-8")
            }
        }
    }

    /** Writes values little-endian, aligned as from the message's start (body and header both start at 0 mod 8). */
    internal class Writer {
        private val out = ByteArrayOutputStream()
        private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        private var depth = 0

        fun bytes(): ByteArray = out.toByteArray()

        fun align(alignment: Int) {
            repeat(pad(out.size().toLong(), alignment).toInt()) { out.write(0) }
        }

        private fun put(alignment: Int, fill: (ByteBuffer) -> Unit) {
            align(alignment)
            scratch.clear()
            fill(scratch)
            out.write(scratch.array(), 0, scratch.position())
        }

        private fun string(value: String, byteLength: Boolean) {
            val encoded = value.toByteArray(Charsets.UTF_8)
            if (encoded.any { it.toInt() == 0 }) throw WireException("NUL in a string")
            if (byteLength) {
                if (encoded.size > 255) throw WireException("signature too long")
                out.write(encoded.size)
            } else {
                put(4) { it.putInt(encoded.size) }
            }
            out.write(encoded)
            out.write(0)
        }

        fun write(type: String, value: Any?) {
            if (++depth > MAX_DEPTH) throw WireException("nested too deep")
            try {
                writeType(type, value)
            } finally {
                depth--
            }
        }

        private fun writeType(type: String, value: Any?) {
            fun mismatch(): Nothing = throw WireException("$value isn't of type $type")
            when (type[0]) {
                'y' -> out.write((value as? UByte ?: mismatch()).toInt())
                'b' -> put(4) { it.putInt(if (value as? Boolean ?: mismatch()) 1 else 0) }
                'n' -> put(2) { it.putShort(value as? Short ?: mismatch()) }
                'q' -> put(2) { it.putShort((value as? UShort ?: mismatch()).toShort()) }
                'i' -> put(4) { it.putInt(value as? Int ?: mismatch()) }
                'u' -> put(4) { it.putInt((value as? UInt ?: mismatch()).toInt()) }
                'h' -> put(4) { it.putInt((value as? UnixFd ?: mismatch()).index) }
                'x' -> put(8) { it.putLong(value as? Long ?: mismatch()) }
                't' -> put(8) { it.putLong((value as? ULong ?: mismatch()).toLong()) }
                'd' -> put(8) { it.putDouble(value as? Double ?: mismatch()) }
                's' -> string(value as? String ?: mismatch(), byteLength = false)
                'o' -> (value as? String ?: mismatch()).let {
                    if (!Signature.isObjectPath(it)) throw WireException("object path $it")
                    string(it, byteLength = false)
                }
                'g' -> (value as? String ?: mismatch()).let { Signature.split(it); string(it, byteLength = true) }
                'v' -> {
                    val variant = value as? Variant ?: mismatch()
                    if (Signature.split(variant.signature).size != 1) throw WireException("variant of ${variant.signature}")
                    string(variant.signature, byteLength = true)
                    write(variant.signature, variant.value)
                }
                '(' -> {
                    val struct = value as? Struct ?: mismatch()
                    val types = Signature.split(type.substring(1, type.length - 1))
                    if (types.size != struct.values.size) mismatch()
                    align(8)
                    types.forEachIndexed { index, member -> write(member, struct.values[index]) }
                }
                'a' -> array(type.substring(1), value)
                else -> mismatch()
            }
        }

        private fun array(element: String, value: Any?) {
            put(4) { it.putInt(0) }
            val lengthAt = out.size() - 4
            align(alignment(element[0]))
            val start = out.size()
            if (element[0] == '{') {
                val (keyType, valueType) = Signature.split(element.substring(1, element.length - 1))
                val map = value as? Map<*, *> ?: throw WireException("$value isn't a dict")
                for ((key, item) in map) {
                    align(8)
                    write(keyType, key)
                    write(valueType, item)
                }
            } else if (element == "y" && value is ByteArray) {
                out.write(value)
            } else {
                val list = value as? List<*> ?: throw WireException("$value isn't a list")
                for (item in list) write(element, item)
            }
            val length = out.size() - start
            val bytes = out.toByteArray()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(lengthAt, length)
            out.reset()
            out.write(bytes)
        }
    }
}

/** A message's header and body. [body] holds one value per complete type of [signature]. */
data class Message(
    val type: Int,
    val flags: Int = 0,
    val serial: UInt = 0u,
    val path: String? = null,
    val iface: String? = null,
    val member: String? = null,
    val errorName: String? = null,
    val replySerial: UInt? = null,
    val destination: String? = null,
    val sender: String? = null,
    val signature: String = "",
    val body: List<Any?> = emptyList(),
    val unixFds: Int = 0,
) {
    val expectsReply get() = type == DBusWire.METHOD_CALL && flags and DBusWire.NO_REPLY_EXPECTED == 0

    /** A reply to this call carrying [body] of [signature]. */
    fun reply(signature: String = "", vararg body: Any?) = Message(
        DBusWire.METHOD_RETURN, DBusWire.NO_REPLY_EXPECTED, replySerial = serial, destination = sender,
        signature = signature, body = body.toList(),
    )

    /** An error reply to this call. */
    fun error(name: String, text: String) = Message(
        DBusWire.ERROR, DBusWire.NO_REPLY_EXPECTED, errorName = name, replySerial = serial, destination = sender,
        signature = "s", body = listOf(text),
    )

    companion object {
        fun call(destination: String, path: String, iface: String, member: String, signature: String = "", vararg body: Any?) = Message(
            DBusWire.METHOD_CALL, path = path, iface = iface, member = member, destination = destination,
            signature = signature, body = body.toList(),
        )

        fun signal(path: String, iface: String, member: String, signature: String = "", vararg body: Any?) = Message(
            DBusWire.SIGNAL, DBusWire.NO_REPLY_EXPECTED, path = path, iface = iface, member = member,
            signature = signature, body = body.toList(),
        )
    }
}

data class Variant(val signature: String, val value: Any?)

data class Struct(val values: List<Any?>) {
    constructor(vararg values: Any?) : this(values.toList())
}

/** An index into the file descriptors that came with a message. */
data class UnixFd(val index: Int)

class WireException(message: String) : Exception(message)

/** Signatures: split into complete types and checked. */
object Signature {
    /** The complete types of [signature], in order; throws for an invalid one. */
    fun split(signature: String): List<String> {
        if (signature.length > DBusWire.MAX_SIGNATURE) throw WireException("signature too long")
        val types = ArrayList<String>()
        var index = 0
        while (index < signature.length) {
            val end = completeType(signature, index, arrays = 0, structs = 0)
            types += signature.substring(index, end)
            index = end
        }
        return types
    }

    /** Where the complete type starting at [start] ends. */
    private fun completeType(signature: String, start: Int, arrays: Int, structs: Int): Int {
        if (arrays > 32 || structs > 32) throw WireException("signature nested too deep")
        if (start >= signature.length) throw WireException("signature ends early")
        return when (val c = signature[start]) {
            in BASIC, 'v' -> start + 1
            'a' -> {
                if (start + 1 < signature.length && signature[start + 1] == '{') {
                    if (start + 2 >= signature.length || signature[start + 2] !in BASIC) throw WireException("dict key")
                    val valueEnd = completeType(signature, start + 3, arrays + 1, structs)
                    if (valueEnd >= signature.length || signature[valueEnd] != '}') throw WireException("dict entry")
                    valueEnd + 1
                } else {
                    completeType(signature, start + 1, arrays + 1, structs)
                }
            }
            '(' -> {
                var index = start + 1
                if (index < signature.length && signature[index] == ')') throw WireException("empty struct")
                while (index < signature.length && signature[index] != ')') index = completeType(signature, index, arrays, structs + 1)
                if (index >= signature.length) throw WireException("struct not closed")
                index + 1
            }
            else -> throw WireException("type $c")
        }
    }

    fun isObjectPath(path: String): Boolean =
        path == "/" || (path.startsWith("/") && !path.endsWith("/") && path.substring(1).split('/').all { it.isNotEmpty() && it.all(::isPathChar) })

    private fun isPathChar(c: Char) = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_'

    private const val BASIC = "ybnqiuxtdsogh"
}
