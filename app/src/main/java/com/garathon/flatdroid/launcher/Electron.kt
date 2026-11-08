package com.garathon.flatdroid.launcher

import java.io.File
import java.io.InputStream

/**
 * Electron apps on Flathub start through zypak (a Chromium sandbox helper that needs Flatpak's
 * sandbox), and some Chromium browsers through cobalt, which asks Flatpak's portal before
 * handing the browser to zypak. The launcher skips both and runs the binary itself, with
 * Chromium's sandboxes and GPU off.
 */
object Electron {
    /** Works for Electron 9 (Homestuck). */
    val BASE_FLAGS = listOf("--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage")

    /**
     * Newer Electron (Obsidian) also needs these, or its GPU process fails a /proc check and it
     * exits; they break Electron 9 (no window). Added only after [GPU_FAILURE] shows up.
     */
    val NEWER_FLAGS = listOf("--no-zygote", "--disable-gpu-sandbox", "--in-process-gpu")
    const val OZONE_X11 = "--ozone-platform=x11"
    const val OZONE_WAYLAND = "--ozone-platform=wayland"

    /**
     * The flags for a launch. On Wayland the session is named, as an Electron that defaults to X11
     * finds none there; X11 is named only for newer Electron ([NEWER_FLAGS]), which needs it.
     */
    fun flags(newer: Boolean, wayland: Boolean): List<String> =
        BASE_FLAGS + (if (newer) NEWER_FLAGS else emptyList()) +
            listOfNotNull(if (wayland) OZONE_WAYLAND else OZONE_X11.takeIf { newer })

    /** The log line that triggers a relaunch with [NEWER_FLAGS]. */
    const val GPU_FAILURE = "GPU process isn't usable"

    /** zypak's entry points: the wrapper script, and its helper, which IDE wrappers call directly. */
    private val ZYPAK = listOf("zypak-wrapper", "zypak-helper")
    private const val COBALT = "cobalt"

    /**
     * The binary a script hands to zypak, e.g. `exec zypak-wrapper.sh /app/obsidian "$@"` or
     * `exec /app/bin/zypak-helper host - /app/share/codium/codium "$@"` gives the binary's path.
     * Null if the script doesn't use zypak or names no absolute path.
     */
    fun zypakTarget(script: String): String? {
        for (words in commands(script)) {
            val at = words.indexOfFirst { word -> ZYPAK.any { word.substringAfterLast('/').startsWith(it) } }
            if (at < 0) continue
            return words.drop(at + 1).firstOrNull { it.startsWith("/") }
        }
        return null
    }

    /**
     * The script under /app a launch script hands over to with `exec` (an IDE's wrapper that sets
     * up its tools, then calls zypak), e.g. `exec "/app/bin/<ID>-wrapper" …`; null if it hands
     * over to none. Only an /app path: it is read from the app's own files.
     */
    fun execTarget(script: String): String? = commands(script).firstNotNullOfOrNull { words ->
        words.getOrNull(1)?.takeIf { words[0] == "exec" && it.startsWith("/app/") }
    }

    /** Each line of [script] as words, comments and quotes removed. */
    private fun commands(script: String): Sequence<List<String>> = script.lineSequence()
        .map { line -> line.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .map { code -> code.split(Regex("\\s+")).map { it.trim('"', '\'') } }

    /**
     * The browser a command script hands to cobalt (`exec cobalt "$@"`): cobalt's own entry
     * point, `/app/extra/<name>` with the name from the app ID's last part (`chrome` for
     * `com.google.Chrome`), when [exists] finds it. Null if the script doesn't use cobalt.
     */
    fun cobaltTarget(script: String, appId: String, exists: (String) -> Boolean): String? {
        val usesCobalt = script.lineSequence().any { line ->
            line.substringBefore('#').trim().split(Regex("\\s+")).map { it.trim('"', '\'') }
                .any { it.substringAfterLast('/') == COBALT }
        }
        if (!usesCobalt) return null
        val name = appId.substringAfterLast('.').lowercase()
        return listOf("/app/extra/$name", "/app/extra/$name/$name").firstOrNull(exists)
    }

    /** The Electron version a binary was built as (its user agent's `Electron/<version>`). */
    fun version(binary: InputStream): String? {
        val marker = "Electron/".toByteArray()
        val buffer = ByteArray(1 shl 16)
        // Keep a tail across reads so a marker split between two reads is still found.
        val keep = marker.size + MAX_VERSION_LENGTH
        var carry = ByteArray(0)
        while (true) {
            val read = binary.read(buffer)
            if (read <= 0) return null
            val chunk = carry + buffer.copyOf(read)
            var i = indexOf(chunk, marker, 0)
            while (i >= 0) {
                val start = i + marker.size
                var end = start
                while (end < chunk.size && end - start < MAX_VERSION_LENGTH &&
                    (chunk[end] in '0'.code.toByte()..'9'.code.toByte() || chunk[end] == '.'.code.toByte())
                ) end++
                val candidate = String(chunk, start, end - start).trimEnd('.')
                // Ignore a match cut off at the end of this chunk; the next read sees it whole.
                val complete = end < chunk.size || end - start >= MAX_VERSION_LENGTH
                if (complete && VERSION.matches(candidate)) return candidate
                i = indexOf(chunk, marker, i + 1)
            }
            carry = chunk.copyOfRange(maxOf(0, chunk.size - keep), chunk.size)
        }
    }

    /**
     * True if [resources] holds native Node modules, which a swapped-in Electron couldn't load.
     * Linked directories aren't entered (a link cycle would never end).
     */
    fun hasNativeModules(resources: File): Boolean =
        resources.walkTopDown()
            .onEnter { it == resources || it.canonicalPath == it.absolutePath }
            .any { it.name.endsWith(".node") }

    /** Electron's name for a Flatpak architecture. */
    fun arch(flatpakArch: String): String = when (flatpakArch) {
        "aarch64" -> "arm64"
        "x86_64" -> "x64"
        else -> error("No Electron build for $flatpakArch")
    }

    fun zipName(version: String, flatpakArch: String) = "electron-v$version-linux-${arch(flatpakArch)}.zip"

    fun releaseUrl(version: String, file: String) =
        "https://github.com/electron/electron/releases/download/v$version/$file"

    /** The SHA-256 for [file] in a release's SHASUMS256.txt (`<hex> *<name>` lines). */
    fun sha256(shasums: String, file: String): String? = shasums.lineSequence().firstNotNullOfOrNull { line ->
        val parts = line.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size == 2 && parts[1].removePrefix("*") == file && Regex("[0-9a-fA-F]{64}").matches(parts[0])) {
            parts[0].lowercase()
        } else {
            null
        }
    }

    private const val MAX_VERSION_LENGTH = 16
    private val VERSION = Regex("\\d+\\.\\d+\\.\\d+")

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
