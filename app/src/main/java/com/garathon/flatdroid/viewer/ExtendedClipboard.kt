package com.garathon.flatdroid.viewer

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * TigerVNC's extended clipboard, text only: UTF-8 instead of Latin-1, sent only when the other
 * side asks for it. A message is a cut-text message with a negated length; its payload starts
 * with flags (formats in the low bits, the action in the high byte). Text travels null-terminated
 * with CRLF line ends, compressed with zlib (a new stream for each message).
 */
object ExtendedClipboard {
    private const val TEXT = 1
    private const val CAPS = 1 shl 24
    private const val REQUEST = 1 shl 25
    private const val PEEK = 1 shl 26
    private const val NOTIFY = 1 shl 27
    private const val PROVIDE = 1 shl 28
    private const val ACTIONS = CAPS or REQUEST or PEEK or NOTIFY or PROVIDE

    sealed interface Message {
        data object Caps : Message
        data class Notify(val text: Boolean) : Message
        data class Request(val text: Boolean) : Message
        data class Provide(val text: String?) : Message
    }

    /** Our capabilities: text, up to [RfbClient.MAX_TEXT], and every action but peek. */
    fun caps(): ByteArray = message(CAPS or REQUEST or NOTIFY or PROVIDE or TEXT) { writeInt(RfbClient.MAX_TEXT) }

    /** Tells the server there is new text on our side (it asks for it with a request when needed). */
    fun notify(): ByteArray = message(NOTIFY or TEXT) {}

    /** Asks the server for its text. */
    fun request(): ByteArray = message(REQUEST or TEXT) {}

    fun provide(text: String): ByteArray {
        val bytes = text.replace("\r\n", "\n").replace("\n", "\r\n").toByteArray(Charsets.UTF_8) + 0
        val packed = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(packed)).use { it.writeInt(bytes.size); it.write(bytes) }
        return message(PROVIDE or TEXT) { write(packed.toByteArray()) }
    }

    /** The message in a payload, or null for one without a single known action. */
    fun parse(payload: ByteArray): Message? {
        if (payload.size < 4) return null
        val input = DataInputStream(payload.inputStream())
        val flags = input.readInt()
        val text = flags and TEXT != 0
        // Caps lists the actions the sender supports in the same flags; any other message has one action.
        if (flags and CAPS != 0) return Message.Caps
        return when (flags and ACTIONS) {
            NOTIFY -> Message.Notify(text)
            REQUEST -> Message.Request(text)
            PROVIDE -> Message.Provide(if (text) readText(payload) else null)
            else -> null
        }
    }

    /** The text in a provide message: the first format's data (text is the lowest format bit, so it comes first). */
    private fun readText(payload: ByteArray): String? = runCatching {
        DataInputStream(InflaterInputStream(payload.inputStream(4, payload.size - 4))).use { input ->
            val length = input.readInt()
            if (length < 0 || length > RfbClient.MAX_TEXT) return null
            val bytes = ByteArray(length).also(input::readFully)
            String(bytes, Charsets.UTF_8).substringBefore('\u0000').replace("\r\n", "\n")
        }
    }.getOrNull()

    private fun message(flags: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val payload = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(flags); body() } }.toByteArray()
        return ByteArrayOutputStream().also {
            DataOutputStream(it).apply { writeByte(6); write(ByteArray(3)); writeInt(-payload.size); write(payload) }
        }.toByteArray()
    }
}
