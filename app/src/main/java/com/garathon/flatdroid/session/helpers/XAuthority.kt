package com.garathon.flatdroid.session.helpers

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/**
 * An X authority file holding one MIT-MAGIC-COOKIE-1 for any address and display, as Xlib and
 * libxcb read it (`XAUTHORITY`) and as the X server takes it (`-auth`). For Xwayland: wlroots
 * also listens on an abstract socket, which proot doesn't keep private, and which other apps can
 * reach on Android 7 and 8 (all apps there share one SELinux level). With the cookie, a client
 * without it is refused on either socket.
 */
object XAuthority {
    const val COOKIE_BYTES = 16
    private const val FAMILY_WILD = 0xffff
    private const val NAME = "MIT-MAGIC-COOKIE-1"

    fun cookie(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(COOKIE_BYTES).also(random::nextBytes)

    /** The file's bytes: family, address, display number, name and data, each length big-endian; an empty address and number match any. */
    fun file(cookie: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        fun short(n: Int) { out.write(n shr 8 and 0xff); out.write(n and 0xff) }
        fun field(bytes: ByteArray) { short(bytes.size); out.write(bytes) }
        short(FAMILY_WILD)
        field(ByteArray(0))
        field(ByteArray(0))
        field(NAME.toByteArray(Charsets.US_ASCII))
        field(cookie)
        return out.toByteArray()
    }
}
