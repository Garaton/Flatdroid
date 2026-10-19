package com.garathon.flatdroid.flatpak

/** A Flatpak ref without its kind: `name/arch/branch`, e.g. `org.gnome.Platform/aarch64/50`. */
data class FlatpakRef(val name: String, val arch: String, val branch: String) {
    override fun toString() = "$name/$arch/$branch"

    companion object {
        /** Parses `name/arch/branch`, with or without a leading `app/` or `runtime/`. */
        fun parse(text: String): FlatpakRef? {
            val parts = text.trim().removePrefix("app/").removePrefix("runtime/").split('/')
            if (parts.size != 3 || parts.any { it.isEmpty() || it == "." || it == ".." }) return null
            return FlatpakRef(parts[0], parts[1], parts[2])
        }
    }
}

/**
 * An `[Extension <name>]` group: where installed extensions named [name] (or, with
 * [subdirectories], `<name>.<suffix>`) are mounted, relative to /usr for a runtime's extension
 * points and /app for an app's.
 */
data class ExtensionPoint(
    val name: String,
    val directory: String,
    /** Acceptable branches, most preferred first; empty means the parent's branch. */
    val versions: List<String>,
    val subdirectories: Boolean,
    /** Added to LD_LIBRARY_PATH, relative to the mounted extension. */
    val addLdPath: String?,
)

/** The `[Context]` permissions an app asks for. Read now; enforced per app later. */
data class FlatpakContext(
    val shared: List<String> = emptyList(),
    val sockets: List<String> = emptyList(),
    val devices: List<String> = emptyList(),
    val filesystems: List<String> = emptyList(),
    val persistent: List<String> = emptyList(),
) {
    /**
     * Asks for Wayland and not for X11 (`x11` or `fallback-x11`). Flatdroid only has X11; such an
     * app may still work through its toolkit (GTK, Qt), but one with no X11 support (foot) can't.
     */
    val waylandOnly: Boolean
        get() = "wayland" in sockets && "x11" !in sockets && "fallback-x11" !in sockets
}

/** The metadata file of an installed app or runtime. */
class FlatpakMetadata(private val keyfile: Keyfile) {
    /** The app or runtime ID. */
    val name: String? get() = keyfile.string(APPLICATION, "name") ?: keyfile.string(RUNTIME, "name")

    /** The runtime an app runs on. */
    val runtime: FlatpakRef? get() = keyfile.string(APPLICATION, "runtime")?.let(FlatpakRef::parse)

    /** The command in /app/bin the app starts with. */
    val command: String? get() = keyfile.string(APPLICATION, "command")

    /** `[Environment]`, unescaped, in file order. */
    val environment: Map<String, String>
        get() = keyfile.group(ENVIRONMENT).keys.associateWith { keyfile.string(ENVIRONMENT, it)!! }

    val context: FlatpakContext
        get() = FlatpakContext(
            shared = keyfile.list(CONTEXT, "shared"),
            sockets = keyfile.list(CONTEXT, "sockets"),
            devices = keyfile.list(CONTEXT, "devices"),
            filesystems = keyfile.list(CONTEXT, "filesystems"),
            persistent = keyfile.list(CONTEXT, "persistent"),
        )

    val extensionPoints: List<ExtensionPoint>
        get() = keyfile.groupNames.filter { it.startsWith(EXTENSION) }.mapNotNull { group ->
            val directory = keyfile.string(group, "directory")?.trim('/') ?: return@mapNotNull null
            if (directory.isEmpty() || directory.split('/').any { it == ".." }) return@mapNotNull null
            ExtensionPoint(
                name = group.removePrefix(EXTENSION).trim(),
                directory = directory,
                versions = keyfile.list(group, "versions").ifEmpty { listOfNotNull(keyfile.string(group, "version")) },
                subdirectories = keyfile.boolean(group, "subdirectories") ?: false,
                addLdPath = keyfile.string(group, "add-ld-path")?.trim('/')?.takeIf { it.isNotEmpty() },
            )
        }

    companion object {
        private const val APPLICATION = "Application"
        private const val RUNTIME = "Runtime"
        private const val ENVIRONMENT = "Environment"
        private const val CONTEXT = "Context"
        private const val EXTENSION = "Extension "

        fun parse(text: String) = FlatpakMetadata(Keyfile.parse(text))
    }
}
