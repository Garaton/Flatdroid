package com.garathon.flatdroid.alpine.programs

import com.garathon.flatdroid.util.base64

/** An installed Alpine package as apk's database lists it (`/lib/apk/db/installed`). */
data class ApkPackage(
    val name: String,
    /** The source package it was built from (`o:`); packages split from one source share it. */
    val origin: String,
    /** Its files, relative to the rootfs. */
    val files: List<String>,
    /** What it depends on (`D:`): package names and `so:`, `cmd:` or `pc:` names, versions dropped, conflicts left out. */
    val depends: List<String>,
    /** What it provides (`p:`), versions dropped. */
    val provides: List<String>,
    /** Each file's content checksum as apk records it (`Z:`, e.g. `Q1` and a base64 SHA-1), by path relative to the rootfs. */
    val checksums: Map<String, String> = emptyMap(),
    /** Its installed size in bytes as apk records it (`I:`). */
    val installedSize: Long = 0,
    /** The packages that, all installed, make apk install this one too (`i:`), versions dropped. */
    val installIf: List<String> = emptyList(),
)

/**
 * Reads apk's own records of what is installed: the database (which package owns which file and
 * depends on what) and the world file (the packages asked for by name, so not only as a
 * dependency). Pure text; nothing is run.
 */
object AlpinePackages {
    /** The database, relative to the rootfs. */
    const val INSTALLED_DB = "lib/apk/db/installed"
    /** The packages asked for by name, relative to the rootfs. */
    const val WORLD = "etc/apk/world"

    /** An Alpine package name as the install dialog takes it: no option, no path, no version. */
    private val NAME = Regex("[A-Za-z0-9_][A-Za-z0-9_.+-]{0,127}")

    fun isPackageName(name: String): Boolean = NAME.matches(name)

    /** The package names in [text] (separated by whitespace), each once; null if there are none or any isn't a name. */
    fun parseNames(text: String): List<String>? =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() }.distinct().takeIf { names -> names.isNotEmpty() && names.all(::isPackageName) }

    fun parseInstalled(text: String): List<ApkPackage> {
        val packages = mutableListOf<ApkPackage>()
        var name: String? = null
        var origin: String? = null
        var dir = ""
        val files = mutableListOf<String>()
        val checksums = HashMap<String, String>()
        var depends = emptyList<String>()
        var provides = emptyList<String>()
        var size = 0L
        var installIf = emptyList<String>()
        fun flush() {
            name?.let { packages += ApkPackage(it, origin ?: it, files.toList(), depends, provides, HashMap(checksums), size, installIf) }
            name = null; origin = null; dir = ""; files.clear(); checksums.clear(); depends = emptyList(); provides = emptyList()
            size = 0L; installIf = emptyList()
        }
        for (line in text.lineSequence()) {
            if (line.isEmpty()) { flush(); continue }
            if (line.length < 2 || line[1] != ':') continue
            val value = line.substring(2)
            when (line[0]) {
                'P' -> name = value
                'o' -> origin = value
                'F' -> dir = value
                'R' -> files += if (dir.isEmpty()) value else "$dir/$value"
                // The file just named (a checksum follows its R: line).
                'Z' -> files.lastOrNull()?.let { checksums[it] = value }
                'D' -> depends = value.split(' ').filter { it.isNotEmpty() && !it.startsWith('!') }.map(::withoutVersion)
                'p' -> provides = value.split(' ').filter { it.isNotEmpty() }.map(::withoutVersion)
                'I' -> size = value.toLongOrNull()?.coerceAtLeast(0) ?: 0L
                'i' -> installIf = value.split(' ').filter { it.isNotEmpty() && !it.startsWith('!') }.map(::withoutVersion)
            }
        }
        flush()
        return packages
    }

    /** The names in the world file: each line's package, without its version or repository tag. */
    fun parseWorld(text: String): Set<String> =
        text.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.startsWith('!') }.map { withoutVersion(it).substringBefore('@') }.toSet()

    /**
     * Whether [content] is what apk recorded as [checksum]: `Q1` and a base64 SHA-1, or `Q2` and a
     * base64 SHA-256; false for any other form.
     */
    fun matches(checksum: String, content: java.io.InputStream): Boolean {
        val algorithm = when {
            checksum.startsWith("Q1") -> "SHA-1"
            checksum.startsWith("Q2") -> "SHA-256"
            else -> return false
        }
        val digest = java.security.MessageDigest.getInstance(algorithm)
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = content.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return base64(digest.digest()) == checksum.substring(2)
    }

    /** Which package owns each file (relative to the rootfs). */
    fun owners(packages: List<ApkPackage>): Map<String, String> {
        val owners = HashMap<String, String>()
        for (p in packages) for (file in p.files) owners[file] = p.name
        return owners
    }

    /**
     * The installed packages that removing [packageName] from [world] frees, itself included, as
     * `apk del` works it out: everything no longer needed by the rest of [world], through
     * dependencies and `install_if` (a package installed because others are). Empty when the rest
     * still needs it, or [packageName] isn't in [world] (`apk del` removes only what world names).
     */
    fun freedBy(packageName: String, packages: List<ApkPackage>, world: Set<String>): Set<String> {
        if (packageName !in world || packages.none { it.name == packageName }) return emptySet()
        // Not installed - needed: anything world doesn't need (none, after any apk run) isn't the program's.
        return needed(world, packages) - needed(world - packageName, packages)
    }

    /** The installed packages [world] needs: its packages, their dependencies, and what `install_if` adds, to a fixed point. */
    internal fun needed(world: Set<String>, packages: List<ApkPackage>): Set<String> {
        val byName = packages.associateBy { it.name }
        // A name (package, `so:`, `cmd:`, `pc:` and so on) to the installed packages providing it.
        val providers = HashMap<String, MutableList<String>>()
        for (p in packages) for (name in listOf(p.name) + p.provides) providers.getOrPut(name) { mutableListOf() } += p.name
        val needed = HashSet<String>()
        fun add(roots: Collection<String>) {
            val stack = ArrayDeque(roots.flatMap { providers[it].orEmpty() })
            while (stack.isNotEmpty()) {
                val name = stack.removeLast()
                if (!needed.add(name)) continue
                for (dep in byName.getValue(name).depends) stack.addAll(providers[dep].orEmpty())
            }
        }
        add(world)
        val conditional = packages.filter { it.installIf.isNotEmpty() }
        while (true) {
            val more = conditional.filter { p -> p.name !in needed && p.installIf.all { c -> providers[c].orEmpty().any(needed::contains) } }
            if (more.isEmpty()) return needed
            add(more.map { it.name })
        }
    }

    /** What an Alpine program's own packages (those of its source) say about the display it takes. */
    enum class Display {
        /** Wayland, with X11 as Force X11. */
        WAYLAND_AND_X11,
        X11,
    }

    /**
     * The display [packageName]'s program takes, from what the packages of its source depend on
     * (abuild lists the shared libraries each package links): GTK 3 and 4 and SDL take both (they
     * pick Wayland at run time); Qt takes Wayland only with its Wayland plugin installed; a program
     * that links only X11's libraries takes X11. Anything else gets Wayland, with Force X11 (verify:
     * read from dependencies only, not from the program itself).
     */
    fun display(packageName: String, packages: List<ApkPackage>): Display {
        val byName = packages.associateBy { it.name }
        val origin = byName[packageName]?.origin ?: return Display.WAYLAND_AND_X11
        val deps = packages.filter { it.origin == origin }.flatMap { it.depends }.toSet()
        fun installed(name: String) = name in byName
        val qt6 = QT6.any { it in deps }
        val qt5 = QT5.any { it in deps }
        // Qt first: it takes Wayland through its plugin only, whatever else the program links.
        return when {
            qt6 -> if (installed(QT6_WAYLAND)) Display.WAYLAND_AND_X11 else Display.X11
            qt5 -> if (installed(QT5_WAYLAND)) Display.WAYLAND_AND_X11 else Display.X11
            GTK_OR_SDL.any { it in deps } || WAYLAND_CLIENT in deps -> Display.WAYLAND_AND_X11
            X11_CLIENT.any { it in deps } -> Display.X11
            else -> Display.WAYLAND_AND_X11
        }
    }

    private fun withoutVersion(token: String): String = token.substringBefore('=').substringBefore('<').substringBefore('>').substringBefore('~')

    private const val WAYLAND_CLIENT = "so:libwayland-client.so.0"
    private val X11_CLIENT = setOf("so:libX11.so.6", "so:libxcb.so.1")
    /** GTK 3 and 4 and SDL by their libraries, and by their package names for programs in Python and other languages. */
    private val GTK_OR_SDL = setOf(
        "so:libgtk-3.so.0", "so:libgtk-4.so.1", "so:libSDL2-2.0.so.0", "so:libSDL3.so.0",
        "gtk+3.0", "gtk4.0", "libadwaita", "py3-gobject3", "sdl2", "sdl3",
    )
    private val QT6 = setOf("so:libQt6Gui.so.6", "py3-pyqt6", "py3-pyside6", "qt6-qtbase")
    private val QT5 = setOf("so:libQt5Gui.so.5", "py3-qt5", "qt5-qtbase")
    private const val QT6_WAYLAND = "qt6-qtwayland"
    private const val QT5_WAYLAND = "qt5-qtwayland"
}
