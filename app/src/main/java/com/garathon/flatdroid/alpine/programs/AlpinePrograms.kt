package com.garathon.flatdroid.alpine.programs

import com.garathon.flatdroid.flatpak.FlatpakMetadata
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.flatpak.Keyfile
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.session.helpers.SessionBus
import com.garathon.flatdroid.setup.AlpineSetup
import com.garathon.flatdroid.setup.PackageGroup
import com.garathon.flatdroid.setup.rootfs.AndroidTarFs
import com.garathon.flatdroid.util.io.readTextUpTo
import java.io.File
import java.util.Locale

/**
 * A program from an Alpine package the user installed, by its desktop file in
 * `/usr/share/applications`. It runs in a root of its own, as a Flatpak does ([AlpineRoot]).
 */
data class AlpineProgram(
    /** [AlpineProgramIds.of] its desktop file's name. */
    val id: String,
    /** The package that installed its desktop file. */
    val packageName: String,
    val name: String,
    /** An absolute guest path, then its arguments. */
    val command: List<String>,
    val terminal: Boolean,
    val icon: File?,
    val display: AlpinePackages.Display,
    /** When its desktop file was written (apk keeps a package's own times, so its change time); null if unknown. */
    val installed: Long? = null,
    /**
     * What Uninstall frees, in bytes as apk records them: its package and the packages nothing else
     * in apk's world needs ([AlpinePackages.freedBy]). Programs of one package each have it.
     */
    val freedBytes: Long = 0,
) {
    /**
     * What a Flatpak's metadata would say, as a typical app asks: the network, sound, Wayland or X11
     * ([display]), the GPU's device, cameras as `/dev/video*` under Automatic video, and the whole
     * session bus (programs made for a desktop expect one; the bus has only the app and Flatdroid).
     */
    fun metadata(arch: String, branch: String): FlatpakMetadata {
        val sockets = when (display) {
            AlpinePackages.Display.WAYLAND_AND_X11 -> "wayland;fallback-x11;"
            AlpinePackages.Display.X11 -> "x11;"
        } + "${AppSettings.PULSEAUDIO_SOCKET};${SessionBus.WHOLE_BUS};"
        return FlatpakMetadata(
            Keyfile.parse(
                "[Application]\nname=$id\nruntime=${AlpineProgramIds.RUNTIME}/$arch/$branch\ncommand=${command[0]}\n\n" +
                    "[Context]\nshared=${AppSettings.NETWORK};\nsockets=$sockets\ndevices=dri;\n",
            ),
            // It opens /dev/video* itself, as on any Linux system: Automatic video gives it the device.
            readsVideoDevices = true,
        )
    }
}

/**
 * Alpine programs' IDs: `alpine+` and the desktop file's name. A Flatpak ID can't have a `+`, so
 * the two never meet, and none reaches Flatpak or Flathub; a `:` would break proot's binds.
 */
object AlpineProgramIds {
    const val PREFIX = "alpine+"
    /** The runtime name an Alpine program's metadata gives (not a Flatpak runtime). */
    const val RUNTIME = "org.alpinelinux.Alpine"
    private val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.-]{0,200}")

    /** The ID for a desktop file's name (without `.desktop`); null for a name that can't be one. */
    fun of(desktopName: String): String? = desktopName.takeIf { NAME.matches(it) && ".." !in it }?.let { PREFIX + it }

    fun isId(id: String): Boolean = id.startsWith(PREFIX) && of(id.removePrefix(PREFIX)) == id
}

/**
 * The Alpine programs installed in [rootfs]: desktop files of packages the user asked for by name
 * (apk's world), not Flatdroid's own packages, and not hidden ones (`NoDisplay`, `Hidden`,
 * `OnlyShowIn` a desktop, not `Type=Application`, or a `TryExec` that isn't there). [exists] tells
 * whether a rootfs file is there without following a link (Alpine's links are guest paths);
 * [changedAt] is a file's change time in milliseconds, when it can be read.
 */
class AlpinePrograms(
    private val rootfs: File,
    private val exists: (File) -> Boolean = { it.exists() },
    private val changedAt: (File) -> Long? = { null },
) {
    fun list(): List<AlpineProgram> {
        val db = File(rootfs, AlpinePackages.INSTALLED_DB)
        val world = File(rootfs, AlpinePackages.WORLD)
        val apps = File(rootfs, APPLICATIONS)
        val key = listOf(db, world, apps).joinToString { "${it.lastModified()}:${it.length()}" }
        val programs = synchronized(cache) { cache[rootfs.path]?.takeIf { it.first == key }?.second }
            ?: scan(db, world, apps).also { synchronized(cache) { cache[rootfs.path] = key to it } }
        // Read for each list, not cached: callers differ in how they read it.
        return programs.map { it.copy(installed = changedAt(File(apps, it.id.removePrefix(AlpineProgramIds.PREFIX) + DESKTOP))) }
    }

    /** apk's checksum of every installed file, by path relative to the rootfs; empty when its database can't be read. */
    fun installedChecksums(): Map<String, String> =
        runCatching { File(rootfs, AlpinePackages.INSTALLED_DB).readTextUpTo(MAX_DB_BYTES)?.let(AlpinePackages::parseInstalled) }.getOrNull()
            .orEmpty().fold(HashMap()) { all, p -> all.apply { putAll(p.checksums) } }

    fun program(id: String): AlpineProgram? = if (AlpineProgramIds.isId(id)) list().firstOrNull { it.id == id } else null

    private fun scan(db: File, world: File, apps: File): List<AlpineProgram> {
        val packages = runCatching { db.readTextUpTo(MAX_DB_BYTES)?.let(AlpinePackages::parseInstalled) }.getOrNull() ?: return emptyList()
        val allWorld = runCatching { world.readTextUpTo()?.let(AlpinePackages::parseWorld) }.getOrNull().orEmpty()
        val wanted = allWorld - FLATDROID_PACKAGES
        if (wanted.isEmpty()) return emptyList()
        val owners = AlpinePackages.owners(packages)
        val files = apps.listFiles { f -> f.name.endsWith(DESKTOP) && f.isFile }.orEmpty().sortedBy { it.name }
        val programs = files.mapNotNull { file ->
            val owner = owners["$APPLICATIONS/${file.name}"]?.takeIf { it in wanted } ?: return@mapNotNull null
            val id = AlpineProgramIds.of(file.name.removeSuffix(DESKTOP)) ?: return@mapNotNull null
            val desktop = runCatching { file.readTextUpTo()?.let(Keyfile::parse) }.getOrNull() ?: return@mapNotNull null
            program(id, owner, desktop, packages)
        }
        return withFreedBytes(programs, packages, allWorld)
    }

    /** [programs] with [AlpineProgram.freedBytes], worked out once per package. */
    internal fun withFreedBytes(programs: List<AlpineProgram>, packages: List<ApkPackage>, world: Set<String>): List<AlpineProgram> {
        val sizes = packages.associate { it.name to it.installedSize }
        val freed = programs.map { it.packageName }.distinct()
            .associateWith { name -> AlpinePackages.freedBy(name, packages, world).sumOf { sizes.getValue(it) } }
        return programs.map { it.copy(freedBytes = freed.getValue(it.packageName)) }
    }

    /** [desktop]'s program, or null when it isn't one to show or its command isn't installed. */
    internal fun program(id: String, packageName: String, desktop: Keyfile, packages: List<ApkPackage>): AlpineProgram? {
        fun value(key: String) = desktop.string(ENTRY, key)?.trim()
        if (value("Type") != "Application" || value("NoDisplay") == "true" || value("Hidden") == "true") return null
        if (!value("OnlyShowIn").isNullOrEmpty()) return null
        value("TryExec")?.takeIf { it.isNotEmpty() }?.let { if (resolve(it) == null) return null }
        val args = value("Exec")?.let(DesktopExec::parse) ?: return null
        val command = resolve(args[0]) ?: return null
        return AlpineProgram(
            id = id,
            packageName = packageName,
            name = InstalledApps.localizedName(desktop, Locale.getDefault()) ?: id.removePrefix(AlpineProgramIds.PREFIX),
            command = listOf(command) + args.drop(1),
            terminal = InstalledApps.runsInTerminal(desktop),
            icon = value("Icon")?.let(::icon),
            display = AlpinePackages.display(packageName, packages),
        )
    }

    /** [name]'s guest path: an absolute one as it is, else the first match in Alpine's PATH; null when not installed. */
    private fun resolve(name: String): String? {
        if (name.startsWith("/")) return name.takeIf { inRootfs(it) && exists(File(rootfs, it)) }
        if ('/' in name || name.isEmpty() || name == "." || name == "..") return null
        return PATH.map { "$it/$name" }.firstOrNull { exists(File(rootfs, it)) }
    }

    /** The icon file in the rootfs: a path, or a name in hicolor and pixmaps; a PNG before an SVG. */
    private fun icon(value: String): File? {
        if (value.isEmpty()) return null
        val candidates = if (value.startsWith("/")) listOf(value) else {
            val base = value.substringBeforeLast('.').takeIf { value.endsWith(".png") || value.endsWith(".svg") } ?: value
            InstalledApps.ICON_SIZES.map { "$ICONS/$it/apps/$base.png" } + "$ICONS/scalable/apps/$base.svg" +
                listOf("$PIXMAPS/$base.png", "$PIXMAPS/$base.svg")
        }
        return candidates.filter(::inRootfs).map { File(rootfs, it) }.firstOrNull { it.isFile && within(it) }
    }

    private fun inRootfs(path: String) = path.startsWith("/") && path.split('/').none { it == ".." }

    /** Followed links still end in the rootfs (one to a guest path would end on Android's own). */
    private fun within(file: File): Boolean = runCatching { file.canonicalPath.startsWith(rootfs.canonicalPath + "/") }.getOrDefault(false)

    companion object {
        const val APPLICATIONS = "usr/share/applications"
        private const val DESKTOP = ".desktop"
        private const val ENTRY = "Desktop Entry"
        private const val ICONS = "/usr/share/icons/hicolor"
        private const val PIXMAPS = "/usr/share/pixmaps"
        /** Alpine's PATH for a user, without /usr/local (apk installs nothing there). */
        private val PATH = listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")
        private const val MAX_DB_BYTES = 64L shl 20

        /** Flatdroid's own packages, never listed (their desktop files are the helpers'). */
        val FLATDROID_PACKAGES: Set<String> =
            (AlpineSetup.PACKAGES + PackageGroup.entries.flatMap { it.packages(true) + it.packages(false) }).toSet()

        /** The programs in [rootfs] as the app reads it: Alpine's links are guest paths, so a link counts as there. */
        fun of(rootfs: File) = AlpinePrograms(rootfs, exists = { it.exists() || AndroidTarFs.isSymlink(it) })

        /** The last list per rootfs, kept while apk's records and the desktop files are unchanged. */
        private val cache = HashMap<String, Pair<String, List<AlpineProgram>>>()
    }
}
