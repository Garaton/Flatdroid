package com.garathon.flatdroid.alpine.programs

import android.system.Os
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
    /**
     * The package the user added that brought it: the world's entry for its desktop file's package,
     * or the world's entry built from the same source (`libreoffice` for `libreoffice-writer`'s).
     * Removing the program removes this package.
     */
    val packageName: String,
    val name: String,
    /** An absolute guest path, then its arguments. */
    val command: List<String>,
    val terminal: Boolean,
    val icon: File?,
    val display: AlpinePackages.Display,
    /** The package that installed its desktop file. */
    val owner: String = packageName,
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
 * (apk's world) or built from the same source as one (a meta-package's parts), not Flatdroid's own
 * packages, and not hidden ones (`NoDisplay`, `Hidden`,
 * `OnlyShowIn` a desktop, not `Type=Application`, or a `TryExec` that isn't there). [exists] tells
 * whether a rootfs file is there without following a link (Alpine's links are guest paths);
 * [changedAt] is a file's change time in milliseconds, when it can be read; [readLink] a link's
 * target, null for a file that isn't one (desktop files and icons are followed inside the rootfs).
 */
class AlpinePrograms(
    private val rootfs: File,
    private val exists: (File) -> Boolean = { it.exists() },
    private val changedAt: (File) -> Long? = { null },
    private val readLink: (File) -> String? = { null },
) {
    fun list(): List<AlpineProgram> {
        val db = File(rootfs, AlpinePackages.INSTALLED_DB)
        val world = File(rootfs, AlpinePackages.WORLD)
        val apps = File(rootfs, APPLICATIONS)
        val key = listOf(db, world, apps).joinToString { "${it.lastModified()}:${it.length()}" }
        val programs = synchronized(cache) { cache[rootfs.path]?.takeIf { it.first == key }?.second }
            ?: scan(db, world, apps).also { synchronized(cache) { cache[rootfs.path] = key to it } }
        // Read for each list, not cached: callers differ in how they read it.
        return programs.map { p -> p.copy(installed = guestFile("/$APPLICATIONS/${p.id.removePrefix(AlpineProgramIds.PREFIX)}$DESKTOP")?.let(changedAt)) }
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
        val byName = packages.associateBy { it.name }
        // A package the user added, else the one they added that was built from the same source.
        val bySource = wanted.mapNotNull { w -> byName[w]?.let { it.origin to w } }.toMap()
        fun added(owner: String): String? = owner.takeIf { it in wanted }
            ?: owner.takeIf { it !in FLATDROID_PACKAGES }?.let { byName[it]?.origin }?.let(bySource::get)
        val names = apps.list { _, name -> name.endsWith(DESKTOP) }.orEmpty().sorted()
        val programs = names.mapNotNull { name ->
            val owner = owners["$APPLICATIONS/$name"] ?: return@mapNotNull null
            val packageName = added(owner) ?: return@mapNotNull null
            val id = AlpineProgramIds.of(name.removeSuffix(DESKTOP)) ?: return@mapNotNull null
            // Alpine's links are guest paths (LibreOffice's entries lead into /usr/lib).
            val file = guestFile("/$APPLICATIONS/$name")?.takeIf { it.isFile } ?: return@mapNotNull null
            val desktop = runCatching { file.readTextUpTo()?.let(Keyfile::parse) }.getOrNull() ?: return@mapNotNull null
            program(id, packageName, desktop, packages)?.copy(owner = owner)
        }
        return withFreedBytes(withoutVariants(programs), packages, allWorld)
    }

    /**
     * [programs] without an entry that runs another entry of the same package with arguments added (a
     * terminal's server mode, say): the user can add arguments to the plain one themselves. Entries
     * of a meta-package's parts (LibreOffice's Writer and its start center) come from different packages and stay.
     */
    internal fun withoutVariants(programs: List<AlpineProgram>): List<AlpineProgram> = programs.filterNot { p ->
        programs.any { q -> q !== p && q.owner == p.owner && q.command.size < p.command.size && p.command.take(q.command.size) == q.command }
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
        return candidates.filter(::inRootfs).mapNotNull(::guestFile).firstOrNull { it.isFile && within(it) }
    }

    /**
     * The rootfs file at guest [path], its links followed inside the rootfs: an absolute target is a
     * guest path, a relative one is taken from the link's folder. Null after [MAX_LINKS] links.
     */
    private fun guestFile(path: String): File? {
        val pending = ArrayDeque(path.split('/').filter { it.isNotEmpty() })
        val done = ArrayList<String>()
        var links = 0
        while (pending.isNotEmpty()) {
            val part = pending.removeFirst()
            if (part == ".") continue
            if (part == "..") { done.removeLastOrNull(); continue }
            val target = readLink(File(rootfs, (done + part).joinToString("/")))
            if (target == null) { done += part; continue }
            if (++links > MAX_LINKS) return null
            if (target.startsWith("/")) done.clear()
            target.split('/').filter { it.isNotEmpty() }.asReversed().forEach(pending::addFirst)
        }
        return File(rootfs, done.joinToString("/"))
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
        /** Links followed for one path, as the kernel's limit (ELOOP after 40). */
        private const val MAX_LINKS = 40

        /**
         * Flatdroid's own packages as Alpine's world names them, never listed (their desktop files are
         * the helpers'): setup's, and each group's virtual package. A group's package the user also
         * added by name is in the world under its own name, so it is the user's.
         */
        val FLATDROID_PACKAGES: Set<String> = (AlpineSetup.PACKAGES + PackageGroup.entries.map { it.virtualName }).toSet()

        /** The packages the user added to the Alpine at [rootfs]: its world without Flatdroid's own (Alpine's own base packages are among them, which a fresh Alpine has anyway); empty when it can't be read. */
        fun userPackages(rootfs: File): List<String> = worldOf(rootfs).filter { it !in FLATDROID_PACKAGES && AlpinePackages.isPackageName(it) }

        /** The names in the world file of the Alpine at [rootfs], without versions; empty when it can't be read. */
        fun worldOf(rootfs: File): Set<String> =
            runCatching { File(rootfs, AlpinePackages.WORLD).readTextUpTo()?.let(AlpinePackages::parseWorld) }.getOrNull().orEmpty()

        /** The programs in [rootfs] as the app reads it: Alpine's links are guest paths, so a link counts as there. */
        fun of(rootfs: File) = AlpinePrograms(
            rootfs,
            exists = { it.exists() || AndroidTarFs.isSymlink(it) },
            readLink = READ_LINK,
        )

        /** A link's target through android.system.Os; null for anything that isn't a link. */
        val READ_LINK: (File) -> String? = { file -> if (AndroidTarFs.isSymlink(file)) runCatching { Os.readlink(file.path) }.getOrNull() else null }

        /** The last list per rootfs, kept while apk's records and the desktop files are unchanged. */
        private val cache = HashMap<String, Pair<String, List<AlpineProgram>>>()
    }
}
