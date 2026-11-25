package com.garathon.flatdroid.launcher.electron

import com.garathon.flatdroid.flatpak.Keyfile
import java.io.File
import java.io.InputStream
import com.garathon.flatdroid.util.WHITESPACE

/**
 * Electron apps on Flathub start through zypak (a Chromium sandbox helper that needs Flatpak's
 * sandbox), and some Chromium browsers through cobalt, which asks Flatpak's portal before
 * handing the browser to zypak. Where the app has zypak in `/app/bin`, Flatdroid's own stand-in
 * ([zypakStandIn]) is bound over it and the app starts through its own command, so its launch
 * script still sets things up; otherwise, and for cobalt, the launcher runs the binary itself.
 * Either way Chromium's sandboxes and GPU are off.
 */
object Electron {
    /**
     * Every Electron app's. The GPU work runs in the main process (`--in-process-gpu`): a separate
     * GPU process fails a /proc check in newer Electron, and on x86_64 Android 10 and later it dies
     * at start in Electron 9 too; in the main process both open, with one process fewer.
     */
    val BASE_FLAGS = listOf("--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage", "--in-process-gpu")

    /**
     * Tried when an app still reports [GPU_FAILURE] with the base flags; they break Electron 9
     * (no window).
     */
    val NEWER_FLAGS = listOf("--no-zygote", "--disable-gpu-sandbox")
    const val OZONE_X11 = "--ozone-platform=x11"
    const val OZONE_WAYLAND = "--ozone-platform=wayland"
    /** Chromium's switch for pages that follow `prefers-reduced-motion`. */
    const val REDUCED_MOTION = "--force-prefers-reduced-motion"

    /**
     * The flags for a launch. On Wayland the session is named, as an Electron that defaults to X11
     * finds none there; X11 is named only for newer Electron ([NEWER_FLAGS]), which needs it.
     */
    fun flags(newer: Boolean, wayland: Boolean, reduceMotion: Boolean = false): List<String> =
        BASE_FLAGS + (if (newer) NEWER_FLAGS else emptyList()) +
            listOfNotNull(if (wayland) OZONE_WAYLAND else OZONE_X11.takeIf { newer }, REDUCED_MOTION.takeIf { reduceMotion })

    /** The log line that triggers a relaunch with [NEWER_FLAGS]. */
    const val GPU_FAILURE = "GPU process isn't usable"

    /** zypak's entry points: the wrapper script, and its helper, which IDE wrappers call directly. */
    private val ZYPAK = listOf("zypak-wrapper", "zypak-helper")

    /** zypak's files in `/app/bin` the stand-in is bound over (`zypak-wrapper` is a link to the script). */
    val ZYPAK_FILES = listOf("zypak-wrapper.sh", "zypak-wrapper", "zypak-helper")

    /**
     * Flatdroid's zypak, bound over each of [ZYPAK_FILES]: starts the binary it is handed with
     * [flags] before the app's own arguments, without zypak's sandbox. Takes zypak's forms:
     * `zypak-wrapper[.sh] <binary> [args]` and `zypak-helper host|child [descriptors] - <binary> [args]`
     * (a child is started as it is), `host-latest`/`exec-latest <binary> [args]`, `version`;
     * `spawn-strategy-test` says no. Electron run as Node (`ELECTRON_RUN_AS_NODE`, an app's CLI
     * script that starts the window itself with its own arguments) gets [nodeFlags] after the
     * script instead: Node refuses them before it, and the script passes them on to the window.
     * That window's output is the script's to keep or drop (some drop it), so a failure
     * Flatdroid would relaunch for can't be seen: [nodeFlags] are the newer Electron's from the start.
     */
    fun zypakStandIn(flags: List<String>, nodeFlags: List<String> = flags): String {
        require((flags + nodeFlags).all { FLAG.matches(it) }) { "Unusable flag in $flags $nodeFlags" }
        val quoted = flags.joinToString(" ") { "'$it'" }
        val nodeQuoted = nodeFlags.joinToString(" ") { "'$it'" }
        return """
            |#!/bin/sh
            |# Flatdroid's zypak: starts the binary itself, without zypak's sandbox.
            |me=${'$'}{0##*/}
            |case ${'$'}me in
            |  zypak-helper*)
            |    mode=${'$'}{1-}
            |    [ ${'$'}# -gt 0 ] && shift
            |    case ${'$'}mode in
            |      version) echo "Zypak (Flatdroid's stand-in)"; exit 0;;
            |      spawn-strategy-test) exit 1;;
            |      host|child)
            |        while [ ${'$'}# -gt 0 ] && [ "${'$'}1" != - ]; do shift; done
            |        [ ${'$'}# -gt 0 ] || { echo "zypak-helper: FD map ended too soon" >&2; exit 1; }
            |        shift
            |        if [ "${'$'}mode" = child ]; then
            |          [ ${'$'}# -gt 0 ] || { echo "zypak-helper: Expected a command" >&2; exit 1; }
            |          exec "${'$'}@"
            |        fi;;
            |      host-latest|exec-latest) ;;
            |      *) echo "zypak-helper: Invalid mode: ${'$'}mode" >&2; exit 1;;
            |    esac;;
            |esac
            |[ ${'$'}# -gt 0 ] || { echo "${'$'}me: Expected a command" >&2; exit 1; }
            |binary=${'$'}1
            |shift
            |if [ -n "${'$'}{ELECTRON_RUN_AS_NODE-}" ]; then
            |  # After the script, the first argument not starting with '-' (none: Node's prompt, no flags).
            |  n=${'$'}# placed=0
            |  while [ "${'$'}n" -gt 0 ]; do
            |    a=${'$'}1
            |    shift
            |    n=${'$'}((n - 1))
            |    set -- "${'$'}@" "${'$'}a"
            |    if [ "${'$'}placed" = 0 ]; then
            |      case ${'$'}a in -*) ;; *) set -- "${'$'}@" $nodeQuoted; placed=1;; esac
            |    fi
            |  done
            |  exec "${'$'}binary" "${'$'}@"
            |fi
            |exec "${'$'}binary" $quoted "${'$'}@"
            |""".trimMargin()
    }

    private val FLAG = Regex("--[a-z0-9-]+(=[A-Za-z0-9_,.-]*)?")
    private const val COBALT = "cobalt"

    /**
     * The binary a script hands to zypak, e.g. `exec zypak-wrapper.sh /app/editor "$@"` or
     * `exec /app/bin/zypak-helper host - /app/share/editor/editor "$@"` gives the binary's path.
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
        .map { code -> code.split(WHITESPACE).map { it.trim('"', '\'') } }

    /** cobalt's configuration in the app: `[Application]` with `EntryPoint`, the browser it starts when not its default. */
    const val COBALT_CONFIG = "/app/etc/cobalt.ini"

    /**
     * The browser a command script hands to cobalt (`exec cobalt "$@"`), when [exists] finds it:
     * the `EntryPoint` of cobalt's configuration ([config], the text of [COBALT_CONFIG], read only
     * for a script that uses cobalt), as a browser built from source sets it; else cobalt's default,
     * `/app/extra/<name>` with the name from the app ID's last part (`browser` for `org.example.Browser`).
     * Null if the script doesn't use cobalt.
     */
    fun cobaltTarget(script: String, appId: String, config: () -> String? = { null }, exists: (String) -> Boolean): String? {
        val usesCobalt = script.lineSequence().any { line ->
            line.substringBefore('#').trim().split(WHITESPACE).map { it.trim('"', '\'') }
                .any { it.substringAfterLast('/') == COBALT }
        }
        if (!usesCobalt) return null
        val entryPoint = config()?.let { Keyfile.parse(it).string("Application", "EntryPoint") }?.trim()
            ?.takeIf { it.startsWith("/app/") && ".." !in it.split('/') }
        val name = appId.substringAfterLast('.').lowercase()
        return (listOfNotNull(entryPoint) + listOf("/app/extra/$name", "/app/extra/$name/$name")).firstOrNull(exists)
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
        val parts = line.trim().split(WHITESPACE, limit = 2)
        if (parts.size == 2 && parts[1].removePrefix("*") == file && SHA256_HEX.matches(parts[0])) {
            parts[0].lowercase()
        } else {
            null
        }
    }

    private const val MAX_VERSION_LENGTH = 16
    private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
    private val VERSION = Regex("\\d+\\.\\d+\\.\\d+")

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
