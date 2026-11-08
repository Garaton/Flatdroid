package com.garathon.flatdroid.session

/**
 * wayvnc's login for a Wayland session. wayvnc has no classic VNC password: it offers RSA-AES and
 * Apple's Diffie-Hellman login (both a username and a password), which needs `relax_encryption`
 * (the session itself isn't encrypted, as with Xvnc). AVNC and bVNC use the Apple login. The
 * password can be longer than VncAuth's 8 characters; a new one is made for every launch.
 */
object WaylandVnc {
    const val USERNAME = "flatdroid"
    const val PASSWORD_LENGTH = 16

    /** The config file's text ([SessionScript.GUEST_WAYVNC_CONFIG]). [password] must be a [com.garathon.flatdroid.launcher.VncPassword] one: no spaces or newlines. */
    fun config(password: String): String {
        require(password.all { it.isLetterOrDigit() }) { "Unusable password" }
        return "enable_auth=true\nusername=$USERNAME\npassword=$password\nrelax_encryption=true\n"
    }
}
