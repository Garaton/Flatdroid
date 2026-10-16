package com.garathon.flatdroid.launcher

import android.annotation.SuppressLint
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * VNC passwords. VncAuth uses only the first 8 characters, and Xvnc's password file holds them
 * DES-encrypted with a fixed key, as `vncpasswd -f` writes it. That is obfuscation, not
 * protection: the file stays in private storage, and a new password is made for every launch.
 */
object VncPassword {
    const val LENGTH = 8

    // VNC's fixed key {23, 82, 107, 6, 35, 78, 88, 7}, with each byte's bits reversed as VNC's DES does.
    private val KEY = byteArrayOf(
        0xe8.toByte(), 0x4a, 0xd6.toByte(), 0x60, 0xc4.toByte(), 0x72, 0x1a, 0xe0.toByte(),
    )

    // No look-alikes (0/O, 1/l/I), since the user may have to type it into a viewer.
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun random(random: SecureRandom = SecureRandom()): String =
        (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /**
     * The 8 bytes of Xvnc's `-PasswordFile` for [password]. DES-ECB is VNC's format, not a choice;
     * Android provides DES through its bundled BouncyCastle (verify on old releases).
     */
    @SuppressLint("GetInstance")
    fun file(password: String): ByteArray {
        val plain = password.toByteArray(Charsets.ISO_8859_1).copyOf(LENGTH)
        val cipher = Cipher.getInstance("DES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "DES"))
        return cipher.doFinal(plain)
    }
}
