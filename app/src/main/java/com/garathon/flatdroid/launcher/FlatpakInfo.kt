package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.flatpak.FlatpakRef
import com.garathon.flatdroid.proot.GuestPaths

/**
 * The root's `/.flatpak-info`, laid out as Flatpak writes it: `[Application]`, the app's
 * `[Context]`, `[Session Bus Policy]` and `[Environment]` as granted, and `[Instance]`. Apps read
 * it to learn they run in Flatpak, which runtime and extensions they have and what they may reach
 * (launch scripts check `flatpak-version`, `runtime-path` and the extensions).
 *
 * The context lists what the app asked for and Flatdroid gives, not everything it can reach:
 * every app gets a home, but writing `home` for an app that didn't ask would read as a
 * permission it never had (and some launch scripts refuse to start with it).
 *
 * `.Devel` and `build=true` turn off old glycin's sandbox (GTK dialogs crash with it); the file's
 * presence also makes GLib use portals, hence GDK_DEBUG=no-portals. GLib takes the network state
 * and proxies from the portal, and sees a network only where `shared` names it. KDE's frameworks
 * run processes through `flatpak-spawn --host` (here [HostCommands]) only where the session bus
 * policy lets the app talk to Flatpak.
 */
data class FlatpakInfo(
    val appId: String,
    val runtime: FlatpakRef,
    val shared: List<String> = emptyList(),
    val sockets: List<String> = emptyList(),
    val devices: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val filesystems: List<String> = emptyList(),
    val persistent: List<String> = emptyList(),
    /** Bus names to `see`, `talk` or `own`, in order. */
    val sessionBusPolicy: Map<String, String> = emptyMap(),
    val environment: Map<String, String> = emptyMap(),
    val instance: Instance? = null,
) {
    /** Where the app and runtime are deployed, as Flatpak in Alpine sees them, and which Flatpak installed them. */
    data class Instance(
        val appPath: String,
        val appCommit: String,
        val runtimePath: String,
        val runtimeCommit: String,
        /** Mounted extensions of the app's points, ID to commit. */
        val appExtensions: Map<String, String>,
        /** Mounted extensions of the runtime's points, ID to commit. */
        val runtimeExtensions: Map<String, String>,
        val branch: String,
        val arch: String,
        /** Alpine's Flatpak version (`1.16.1`); null leaves it out. */
        val flatpakVersion: String?,
        /** The app reaches the session bus through a filtering proxy. */
        val sessionBusProxy: Boolean,
    )

    /** In Flatpak's order: `[Application]`, `[Instance]`, then the context's groups. */
    fun text(): String = buildString {
        group("Application", listOf("name" to "$appId.Devel", "runtime" to "runtime/$runtime"))
        val keys = mutableListOf<Pair<String, String>>()
        instance?.run {
            keys += "app-path" to escape(appPath)
            keys += "app-commit" to appCommit
            if (appExtensions.isNotEmpty()) keys += "app-extensions" to extensions(appExtensions)
            keys += "runtime-path" to escape(runtimePath)
            keys += "runtime-commit" to runtimeCommit
            if (runtimeExtensions.isNotEmpty()) keys += "runtime-extensions" to extensions(runtimeExtensions)
            keys += "branch" to branch
            keys += "arch" to arch
            flatpakVersion?.let { keys += "flatpak-version" to it }
            if (sessionBusProxy) keys += "session-bus-proxy" to "true"
        }
        keys += "build" to "true"
        group("Instance", keys)
        group(
            "Context",
            listOfNotNull(
                list("shared", shared), list("sockets", sockets), list("devices", devices), list("features", features),
                list("filesystems", filesystems), list("persistent", persistent),
            ),
        )
        group("Session Bus Policy", sessionBusPolicy.toList())
        group("Environment", environment.map { (k, v) -> k to escape(v) })
    }.trimEnd('\n') + "\n"

    private fun StringBuilder.group(name: String, keys: List<Pair<String, String>>) {
        if (keys.isEmpty()) return
        append('[').append(name).append("]\n")
        for ((k, v) in keys) append(k).append('=').append(v).append('\n')
        append('\n')
    }

    private fun list(key: String, values: List<String>): Pair<String, String>? =
        values.takeIf { it.isNotEmpty() }?.let { key to it.joinToString("") { v -> escape(v).replace(";", "\\;") + ";" } }

    /** As Flatpak lists them: `id=commit`, separated by `;`. */
    private fun extensions(map: Map<String, String>) = map.entries.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        /**
         * The `filesystems=` entries of [asked] the app gets: those inside the home (its standard
         * folders, `~/` paths, and the `xdg-config`, `xdg-data` and `xdg-cache` paths it has its own
         * copy of), `host` as `home` (the rest of a host doesn't exist here), and with [sdcard] the
         * shared storage. Removable media, other `/` paths, `host-os`, `host-etc` and `xdg-run` are left out.
         */
        fun filesystems(asked: List<String>, sdcard: Boolean): List<String> {
            val given = LinkedHashSet<String>()
            for (entry in asked) {
                if (entry.startsWith("!")) continue
                val name = entry.substringBefore(':')
                val base = name.substringBefore('/')
                when {
                    name == "host" -> given += "home" + entry.removePrefix(name)
                    name == "home" || name == "~" || name.startsWith("~/") -> given += entry
                    base in HOME_FOLDERS -> given += entry
                }
            }
            if (sdcard) given += GuestPaths.SDCARD
            return given.toList()
        }

        /** `filesystems=` names for folders in the home. */
        private val HOME_FOLDERS = setOf(
            "xdg-desktop", "xdg-documents", "xdg-download", "xdg-music", "xdg-pictures", "xdg-public-share", "xdg-templates",
            "xdg-videos", "xdg-config", "xdg-data", "xdg-cache",
        )

        /** A value as GLib's key files escape it: backslashes and control characters, and a leading space. */
        fun escape(value: String): String = buildString {
            for ((i, c) in value.withIndex()) {
                when (c) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    '\r' -> append("\\r")
                    ' ' -> append(if (i == 0) "\\s" else " ")
                    else -> append(c)
                }
            }
        }
    }
}
