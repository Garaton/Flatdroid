package com.garathon.flatdroid.flatpak

import android.content.Context
import android.system.Os
import com.garathon.flatdroid.BuildConfig
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.programs.AlpineProgram
import com.garathon.flatdroid.alpine.programs.AlpineProgramIds
import com.garathon.flatdroid.alpine.programs.AlpinePrograms
import com.garathon.flatdroid.launcher.electron.Electron
import com.garathon.flatdroid.launcher.Executable
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.AppSettingsStore
import com.garathon.flatdroid.setup.rootfs.AndroidTarFs
import com.garathon.flatdroid.storage.AppData
import com.garathon.flatdroid.util.WHITESPACE
import com.garathon.flatdroid.util.io.readTextUpTo
import java.io.File
import java.util.Locale

/** An installed app as the home list shows it. */
data class InstalledApp(
    val id: String,
    val name: String,
    val icon: File?,
    val lastOpened: Long?,
    /** When it was installed; null if unknown. */
    val installed: Long? = null,
    /** Its metadata asks for all devices (`devices=all`). */
    val requestsAllDevices: Boolean = false,
    /** Its metadata asks for sound (`sockets=pulseaudio`). */
    val requestsAudio: Boolean = false,
    /** Its metadata asks for the network (`shared=network`). */
    val requestsNetwork: Boolean = false,
    /** Its metadata asks for Wayland as well as X11, so X11 can be forced ([FlatpakContext.waylandAndX11]). */
    val waylandAndX11: Boolean = false,
    /** Its metadata asks for Wayland, so it runs there unless X11 is forced. */
    val wayland: Boolean = false,
    /** Its desktop file says it runs in a terminal: its session is Wayland whatever it asks for. */
    val terminal: Boolean = false,
    /** How many times it was launched, and for how long it ran in all (milliseconds). */
    val launches: Int = 0,
    val usedMillis: Long = 0,
    /** A hint that it uses a camera ([FlatpakContext.mayUseCamera]). */
    val mayUseCamera: Boolean = false,
    /** Its metadata asks for the home or the whole host file system (`filesystems=home`, `host` or `host-os`). */
    val requestsFiles: Boolean = false,
    /** An Alpine program's package ([AlpineProgram]); null for a Flatpak. */
    val alpinePackage: String? = null,
) {
    /** Automatic video gives it `/dev/video*` ([FlatpakContext.readsVideoDevices]): it asks for all devices, or reads them itself. */
    val takesVideoDevices: Boolean get() = requestsAllDevices || alpinePackage != null
}

/** What an app's metadata says (see [InstalledApps.details]). */
data class AppDetails(
    /** The runtime's name and branch, e.g. `org.gnome.Platform 47`. */
    val runtime: String?,
    val display: Display,
    val sockets: List<String>,
    val devices: List<String>,
    val filesystems: List<String>,
    val shared: List<String>,
    /** When it was installed; null if unknown. */
    val installed: Long?,
) {
    /** Which display protocols the app asks for. */
    enum class Display { WAYLAND_AND_X11, WAYLAND, X11, NONE }
}

/**
 * Installed apps with their names and icons, read from what each app exports, and Alpine
 * programs ([AlpinePrograms]) with what their metadata would say.
 */
class InstalledApps(private val context: Context) {
    private val env = AlpineEnvironment.get(context)
    private val installation = FlatpakInstallation(env.flatpakDir, env.arch)
    private val alpine = AlpinePrograms(
        File(env.paths().rootfs),
        exists = { it.exists() || AndroidTarFs.isSymlink(it) },
        changedAt = { file -> runCatching { Os.lstat(file.path).st_ctime * 1000 }.getOrNull() },
    )

    private fun alpineProgram(id: String): AlpineProgram? = if (AlpineProgramIds.isId(id)) alpine.program(id) else null

    private fun alpineMetadata(program: AlpineProgram) = program.metadata(env.arch, BuildConfig.ALPINE_BRANCH)

    fun list(): List<InstalledApp> = flatpaks() + alpine.list().map { program ->
        val settings = AppSettingsStore(AppData.settingsFile(context, program.id)).load()
        val metadata = alpineMetadata(program).context
        InstalledApp(
            id = program.id,
            name = program.name,
            icon = program.icon,
            lastOpened = settings.lastOpened,
            launches = settings.launches,
            usedMillis = settings.usedMillis,
            // As a Flatpak's: never opened, it sorts by when it came.
            installed = program.installed,
            requestsAudio = true,
            requestsNetwork = true,
            waylandAndX11 = metadata.waylandAndX11,
            wayland = metadata.wayland,
            terminal = program.terminal,
            alpinePackage = program.packageName,
        )
    }

    private fun flatpaks(): List<InstalledApp> = installation.appIds().mapNotNull { id ->
        val app = installation.app(id) ?: return@mapNotNull null
        // Only its usage is read here, which follows no defaults: Flatdroid's settings aren't read again for each app.
        val settings = AppSettingsStore(AppData.settingsFile(context, id)).load()
        val desktop = desktop(app, id)
        InstalledApp(
            id = id,
            name = desktop?.let { localizedName(it, Locale.getDefault()) } ?: id,
            icon = icon(app, id),
            lastOpened = settings.lastOpened,
            launches = settings.launches,
            usedMillis = settings.usedMillis,
            installed = installation.installed(id),
            requestsAllDevices = DevNodes.ALL in app.metadata.context.devices,
            requestsAudio = AppSettings.PULSEAUDIO_SOCKET in app.metadata.context.sockets,
            requestsNetwork = AppSettings.NETWORK in app.metadata.context.shared,
            waylandAndX11 = app.metadata.context.waylandAndX11,
            wayland = app.metadata.context.wayland,
            terminal = desktop?.let(::runsInTerminal) == true,
            mayUseCamera = app.metadata.context.mayUseCamera,
            requestsFiles = app.metadata.context.filesystems.any { it.substringBefore(':') in FILE_ACCESS },
        )
    }

    /**
     * Whether the app's command starts Electron or a Chromium browser, as the launcher sees it
     * (through zypak or cobalt): it shows web content, without Chromium's sandbox here. Reads the
     * command's script, so it is asked for one app (its settings), not for the whole list.
     * WebKitGTK apps can't be told this way (a script may load it).
     */
    fun showsWebContent(id: String): Boolean {
        if (AlpineProgramIds.isId(id)) return false
        val app = installation.app(id) ?: return false
        val command = app.metadata.command?.takeIf { it.isNotBlank() && !it.startsWith("/") } ?: return false
        val script = (Executable.of(File(app.files, "bin/$command")) as? Executable.Script)?.text ?: return false
        return Electron.zypakTarget(script) != null || Electron.cobaltTarget(script, id) { true } != null
    }

    /** What the app's metadata says, for its Overview; null if it isn't installed. */
    fun details(id: String): AppDetails? {
        alpineProgram(id)?.let { program ->
            val context = alpineMetadata(program).context
            return AppDetails(
                runtime = "Alpine ${BuildConfig.ALPINE_VERSION}",
                display = if (context.waylandAndX11) AppDetails.Display.WAYLAND_AND_X11 else AppDetails.Display.X11,
                sockets = context.sockets, devices = context.devices, filesystems = context.filesystems, shared = context.shared,
                installed = program.installed,
            )
        }
        val app = installation.app(id) ?: return null
        val context = app.metadata.context
        val display = when {
            context.waylandAndX11 -> AppDetails.Display.WAYLAND_AND_X11
            context.wayland -> AppDetails.Display.WAYLAND
            "x11" in context.sockets || "fallback-x11" in context.sockets -> AppDetails.Display.X11
            else -> AppDetails.Display.NONE
        }
        return AppDetails(
            runtime = app.metadata.runtime?.let { "${it.name} ${it.branch}" },
            display = display,
            sockets = context.sockets,
            devices = context.devices,
            filesystems = context.filesystems,
            shared = context.shared,
            installed = installation.installed(id),
        )
    }

    fun name(id: String): String? = alpineProgram(id)?.name ?: installation.app(id)?.let { desktopName(it, id) }

    /** [id]'s name and icon, as the list has them (without its use and metadata); null if it isn't installed. */
    fun brief(id: String): InstalledApp? = alpineProgram(id)?.let { InstalledApp(id, it.name, it.icon, lastOpened = null, alpinePackage = it.packageName) }
        ?: installation.app(id)?.let { InstalledApp(id, desktopName(it, id) ?: id, icon(it, id), lastOpened = null) }

    /** The app's metadata permissions; null if it isn't installed. */
    fun context(id: String): FlatpakContext? = alpineProgram(id)?.let { alpineMetadata(it).context } ?: installation.app(id)?.metadata?.context

    /** The `sockets=` the app's metadata asks for; empty if it isn't installed. */
    fun sockets(id: String): List<String> = context(id)?.sockets.orEmpty()

    /** The app is made for a terminal (its desktop file says `Terminal=true`). */
    fun runsInTerminal(id: String): Boolean =
        alpineProgram(id)?.terminal ?: (installation.app(id)?.let { desktop(it, id) }?.let(::runsInTerminal) == true)

    private fun desktopName(app: Deployment, id: String): String? = desktop(app, id)?.let { localizedName(it, Locale.getDefault()) }

    private fun desktop(app: Deployment, id: String): Keyfile? = try {
        File(app.export, "share/applications/$id.desktop").readTextUpTo()?.let(Keyfile::parse)
    } catch (_: Exception) {
        null
    }

    /** A PNG when the app exports one, else its scalable SVG. */
    private fun icon(app: Deployment, id: String): File? {
        val icons = File(app.export, "share/icons/hicolor")
        return (ICON_SIZES.map { File(icons, "$it/apps/$id.png") } + File(icons, "scalable/apps/$id.svg"))
            .firstOrNull { it.isFile }
    }

    companion object {
        /** The `filesystems=` entries that give an app the home or everything. */
        private val FILE_ACCESS = setOf("home", "host", "host-os")

        /** PNG sizes to try, best first. */
        val ICON_SIZES = listOf("128x128", "256x256", "96x96", "64x64", "512x512", "48x48")

        /**
         * An app ID Flatdroid keeps data for: a Flatpak's ([isFlatpakId]) or an Alpine program's
         * ([AlpineProgramIds]). Flatpak, Flathub and the catalog take only [isFlatpakId].
         */
        fun isValidId(id: String): Boolean = isFlatpakId(id) || AlpineProgramIds.isId(id)

        /** A Flatpak app ID: at least two dot-separated parts of letters, digits, `_` and `-`. */
        fun isFlatpakId(id: String): Boolean =
            id.length <= 255 && ID.matches(id)

        private val ID = Regex("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)+")

        /** The app IDs in [text], separated by whitespace, each once; null if there are none or any isn't valid. */
        fun parseIds(text: String): List<String>? = splitIds(text).takeIf { ids -> ids.isNotEmpty() && ids.all(::isFlatpakId) }

        /** The words of [text] (IDs separated by spaces), each once, valid or not. */
        fun splitIds(text: String): List<String> = text.split(WHITESPACE).filter { it.isNotEmpty() }.distinct()

        fun runsInTerminal(desktop: Keyfile): Boolean = desktop.string("Desktop Entry", "Terminal")?.trim() == "true"

        /** `Name[ll_CC]`, then `Name[ll]`, then `Name`, from a desktop file's `[Desktop Entry]`. */
        fun localizedName(desktop: Keyfile, locale: Locale): String? {
            val group = "Desktop Entry"
            val keys = listOfNotNull(
                locale.country.takeIf { it.isNotEmpty() }?.let { "Name[${locale.language}_$it]" },
                "Name[${locale.language}]",
                "Name",
            )
            return keys.firstNotNullOfOrNull { key -> desktop.string(group, key)?.takeIf { it.isNotBlank() } }
        }
    }
}
