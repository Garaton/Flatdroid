package com.garathon.flatdroid.settings

import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.launcher.LaunchEnvironment
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.util.Properties

/** The display size an app starts with: one of the defaults, or the user's own. */
enum class DisplaySize { PORTRAIT, LANDSCAPE, CUSTOM }

/** What an app gets as its home. None of them isolates the app; they change what it can reach. */
enum class HomeMode {
    /** The shared home (`Documents/Flatdroid` unless the user chose another folder). */
    SHARED,
    /** The app's own folder in Flatdroid's private storage; files come in by import. */
    PRIVATE,
    /** A new home, settings included, at every launch; nothing is kept after the app closes. */
    TEMPORARY,
}

/**
 * What a Flatpak's own settings start as, from the security level ([SecuritySettings.appDefaults]):
 * the settings below that the user hasn't changed follow these.
 */
data class AppDefaults(
    val homeMode: HomeMode = HomeMode.SHARED,
    /** Network and sound are on when the app's metadata asks for them; otherwise off until the user turns them on. */
    val permissionsFromMetadata: Boolean = true,
    val androidFonts: Boolean = true,
    val clipboard: Boolean = true,
    val timeZone: Boolean = true,
    val language: Boolean = true,
)

/** Flatdroid's choices for one Flatpak. */
data class AppSettings(
    /** Xvnc's size at launch; the viewer's resize option can change it afterwards. */
    val displaySize: DisplaySize = DisplaySize.PORTRAIT,
    /** Used with [DisplaySize.CUSTOM] only. */
    val customWidth: Int = PORTRAIT_WIDTH,
    val customHeight: Int = PORTRAIT_HEIGHT,
    /** Electron needed the flags for newer versions ("GPU process isn't usable" without them). Managed by the launcher. */
    val newerElectronFlags: Boolean = false,
    /**
     * The app's root has no /.flatpak-info: KDE's frameworks reported that they would run
     * processes on the host. Managed by the launcher.
     */
    val withoutFlatpakInfo: Boolean = false,
    /** When the app was last launched, in milliseconds since the epoch; null if never. */
    val lastOpened: Long? = null,
    /**
     * For an app asking for all devices: bind the host's whole /dev, Binder included, instead of
     * the device groups Flatpak names. Shown only with developer options.
     */
    val wholeDev: Boolean = false,
    /** Sound output (the PulseAudio socket); null follows [defaults] ([audioAllowed]). */
    val audio: Boolean? = null,
    /**
     * Internet access: off, the app gets no resolv.conf and can't open Internet sockets. Null
     * follows [defaults] ([networkAllowed]).
     */
    val network: Boolean? = null,
    /**
     * Android's fonts at /run/host/fonts. Off, the app has only its runtime's own fonts, so the
     * font list (which apps and the websites they show can read) doesn't reveal the device; text in
     * scripts the runtime has no font for may show as boxes. Null follows [defaults] ([usesAndroidFonts]).
     */
    val androidFonts: Boolean? = null,
    /**
     * Clipboard text goes both ways between the app and Android, through the VNC viewer. Off,
     * Xvnc neither takes the viewer's clipboard nor sends its own, so the app can't read what was
     * copied in Android. Null follows [defaults] ([sharesClipboard]).
     */
    val clipboard: Boolean? = null,
    /**
     * Android's time zone, as Flatpak gives the host's (`/etc/localtime`, `/etc/timezone`). Off,
     * the app gets UTC, so its clock doesn't hint at where the device is. Null follows [defaults]
     * ([sharesTimeZone]).
     */
    val timeZone: Boolean? = null,
    /**
     * Android's language and region as the app's locale (`LANG`), as Flatpak passes the host's.
     * Off, the generic C.UTF-8: English, and nothing about the device's language or region. Null
     * follows [defaults] ([sharesLanguage]).
     */
    val language: Boolean? = null,
    /** Developer setting: the security level's extra system calls stay allowed for this app. */
    val allowExtraSyscalls: Boolean = false,
    /**
     * Developer setting: the app's hidden config and key files (.ssh, .gnupg, shell start-up,
     * .gitconfig, …) come from a location shared with the other apps that opt in, instead of the
     * app's own, in any home mode. Off by default, so apps don't share keys unless asked.
     */
    val shareHiddenFiles: Boolean = false,
    /**
     * Microphone input, through Android's microphone while the app records. Off by default; on,
     * the user is asked in a notification the first time the app records in a session, and the
     * answer holds for that session.
     */
    val microphone: Boolean = false,
    /** With [microphone] on, record whenever the app does, without asking. */
    val alwaysAllowMicrophone: Boolean = false,
    /**
     * Android's cameras through Flatpak's camera portal. Off by default; on, the app may use them
     * whenever it runs (Flatdroid can't ask at the moment of use), and Android's camera runs only
     * while the app takes its picture.
     */
    val camera: Boolean = false,
    /**
     * With [camera] on, the frames' short side, one of [AndroidCameras.RESOLUTIONS] or
     * [AndroidCameras.FULL_RESOLUTION] (each camera's largest size); a camera without it takes
     * its largest size below.
     */
    val cameraResolution: Int = AndroidCameras.DEFAULT_RESOLUTION,
    /** With [camera] on, frames per second, as the device's cameras offer them; a camera that can't takes the nearest it can. */
    val cameraFrameRate: Int = AndroidCameras.DEFAULT_FRAME_RATE,
    /** Developer setting: variables added to the app's environment, over Flatdroid's own. */
    val environment: Map<String, String> = emptyMap(),
    /** The user's choice of home; null follows [defaults] ([homeMode]). */
    val homeModeChoice: HomeMode? = null,
    /**
     * All of shared storage at /sdcard. Shown, and bound, only while Flatdroid has access to all
     * of it (All files access, or the storage permission on Android 10 and older).
     */
    val sdcard: Boolean = false,
    /** What the settings the user hasn't changed follow; not saved (it comes from the security level). */
    val defaults: AppDefaults = AppDefaults(),
) {
    val homeMode: HomeMode get() = homeModeChoice ?: defaults.homeMode
    val usesAndroidFonts: Boolean get() = androidFonts ?: defaults.androidFonts
    val sharesClipboard: Boolean get() = clipboard ?: defaults.clipboard
    val sharesTimeZone: Boolean get() = timeZone ?: defaults.timeZone
    val sharesLanguage: Boolean get() = language ?: defaults.language

    /** Whether the app gets sound: its own setting, else [defaults] and whether its metadata asks for PulseAudio. */
    fun audioAllowed(sockets: List<String>): Boolean = audio ?: (defaults.permissionsFromMetadata && PULSEAUDIO_SOCKET in sockets)

    /** Whether the app gets the Internet: its own setting, else [defaults] and whether its metadata asks for the network. */
    fun networkAllowed(shared: List<String>): Boolean = network ?: (defaults.permissionsFromMetadata && NETWORK in shared)

    val width: Int get() = when (displaySize) {
        DisplaySize.PORTRAIT -> PORTRAIT_WIDTH
        DisplaySize.LANDSCAPE -> LANDSCAPE_WIDTH
        DisplaySize.CUSTOM -> customWidth
    }
    val height: Int get() = when (displaySize) {
        DisplaySize.PORTRAIT -> PORTRAIT_HEIGHT
        DisplaySize.LANDSCAPE -> LANDSCAPE_HEIGHT
        DisplaySize.CUSTOM -> customHeight
    }
    val geometry: String get() = "${width}x$height"

    companion object {
        const val PORTRAIT_WIDTH = 1000
        const val PORTRAIT_HEIGHT = 1400
        const val LANDSCAPE_WIDTH = 1600
        const val LANDSCAPE_HEIGHT = 1000
        const val MIN_SIZE = 320
        const val MAX_SIZE = 4096
        /** Flatpak's `sockets=` name for sound. */
        const val PULSEAUDIO_SOCKET = "pulseaudio"
        /** Flatpak's `shared=` name for the network. */
        const val NETWORK = "network"
    }
}

/**
 * [AppSettings.environment] as the user edits it: one `NAME=value` per line; blank lines and lines
 * starting with `#` are skipped.
 */
object EnvironmentText {
    /** The variables in [text], in order; null if a line isn't `NAME=value` with a usable name. */
    fun parse(text: String): Map<String, String>? {
        val variables = linkedMapOf<String, String>()
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val name = line.substringBefore('=', missingDelimiterValue = "")
            if (!LaunchEnvironment.isName(name)) return null
            variables[name] = line.substringAfter('=')
        }
        return variables
    }

    fun format(variables: Map<String, String>): String = variables.entries.joinToString("\n") { "${it.key}=${it.value}" }
}

/**
 * Keeps [AppSettings] in a properties file. Only a custom size is stored as numbers, so the
 * portrait and landscape choices follow Flatdroid's defaults; settings left at [appDefaults]
 * aren't stored, so they follow the security level.
 */
class AppSettingsStore(private val file: File, private val appDefaults: AppDefaults = AppDefaults()) {
    fun load(): AppSettings {
        if (!file.isFile) return AppSettings(defaults = appDefaults)
        val props = try {
            Properties().apply { file.inputStream().use { load(it) } }
        } catch (_: Exception) {
            return AppSettings(defaults = appDefaults)
        }
        val defaults = AppSettings()
        fun size(key: String, default: Int) =
            props.getProperty(key)?.toIntOrNull()?.takeIf { it in AppSettings.MIN_SIZE..AppSettings.MAX_SIZE } ?: default
        return AppSettings(
            displaySize = DisplaySize.entries.firstOrNull { it.name.lowercase() == props.getProperty(KEY_SIZE) } ?: defaults.displaySize,
            customWidth = size(KEY_WIDTH, defaults.customWidth),
            customHeight = size(KEY_HEIGHT, defaults.customHeight),
            newerElectronFlags = props.getProperty(KEY_ELECTRON_NEWER) == "true",
            withoutFlatpakInfo = props.getProperty(KEY_NO_FLATPAK_INFO) == "true",
            lastOpened = props.getProperty(KEY_LAST_OPENED)?.toLongOrNull(),
            wholeDev = props.getProperty(KEY_WHOLE_DEV) == "true",
            audio = props.getProperty(KEY_AUDIO)?.toBooleanStrictOrNull(),
            network = props.getProperty(KEY_NETWORK)?.toBooleanStrictOrNull(),
            androidFonts = props.getProperty(KEY_ANDROID_FONTS)?.toBooleanStrictOrNull(),
            clipboard = props.getProperty(KEY_CLIPBOARD)?.toBooleanStrictOrNull(),
            timeZone = props.getProperty(KEY_TIME_ZONE)?.toBooleanStrictOrNull(),
            language = props.getProperty(KEY_LANGUAGE)?.toBooleanStrictOrNull(),
            allowExtraSyscalls = props.getProperty(KEY_EXTRA_SYSCALLS) == "true",
            shareHiddenFiles = props.getProperty(KEY_SHARE_HIDDEN_FILES) == "true",
            microphone = props.getProperty(KEY_MICROPHONE) == "true",
            camera = props.getProperty(KEY_CAMERA) == "true",
            cameraResolution = props.getProperty(KEY_CAMERA_RESOLUTION)?.toIntOrNull()
                ?.takeIf { it in AndroidCameras.RESOLUTIONS || it == AndroidCameras.FULL_RESOLUTION }
                ?: AndroidCameras.DEFAULT_RESOLUTION,
            cameraFrameRate = props.getProperty(KEY_CAMERA_FRAME_RATE)?.toIntOrNull()?.takeIf { it in 1..AndroidCameras.MAX_FRAME_RATE }
                ?: AndroidCameras.DEFAULT_FRAME_RATE,
            alwaysAllowMicrophone = props.getProperty(KEY_MICROPHONE_ALWAYS) == "true",
            environment = props.stringPropertyNames()
                .filter { it.startsWith(KEY_ENV_PREFIX) }
                .map { it.removePrefix(KEY_ENV_PREFIX) }
                .filter(LaunchEnvironment::isName)
                .sorted()
                .associateWith { props.getProperty(KEY_ENV_PREFIX + it) },
            homeModeChoice = HomeMode.entries.firstOrNull { it.name.lowercase() == props.getProperty(KEY_HOME_MODE) },
            sdcard = props.getProperty(KEY_SDCARD) == "true",
            defaults = appDefaults,
        )
    }

    fun save(settings: AppSettings) {
        val props = Properties()
        props.setProperty(KEY_SIZE, settings.displaySize.name.lowercase())
        // Kept while another size is chosen, so switching back finds them.
        props.setProperty(KEY_WIDTH, settings.customWidth.toString())
        props.setProperty(KEY_HEIGHT, settings.customHeight.toString())
        props.setProperty(KEY_ELECTRON_NEWER, settings.newerElectronFlags.toString())
        if (settings.withoutFlatpakInfo) props.setProperty(KEY_NO_FLATPAK_INFO, "true")
        settings.lastOpened?.let { props.setProperty(KEY_LAST_OPENED, it.toString()) }
        props.setProperty(KEY_WHOLE_DEV, settings.wholeDev.toString())
        settings.audio?.let { props.setProperty(KEY_AUDIO, it.toString()) }
        settings.network?.let { props.setProperty(KEY_NETWORK, it.toString()) }
        settings.androidFonts?.let { props.setProperty(KEY_ANDROID_FONTS, it.toString()) }
        settings.clipboard?.let { props.setProperty(KEY_CLIPBOARD, it.toString()) }
        settings.timeZone?.let { props.setProperty(KEY_TIME_ZONE, it.toString()) }
        settings.language?.let { props.setProperty(KEY_LANGUAGE, it.toString()) }
        if (settings.allowExtraSyscalls) props.setProperty(KEY_EXTRA_SYSCALLS, "true")
        if (settings.shareHiddenFiles) props.setProperty(KEY_SHARE_HIDDEN_FILES, "true")
        if (settings.microphone) props.setProperty(KEY_MICROPHONE, "true")
        if (settings.alwaysAllowMicrophone) props.setProperty(KEY_MICROPHONE_ALWAYS, "true")
        if (settings.camera) props.setProperty(KEY_CAMERA, "true")
        props.setProperty(KEY_CAMERA_RESOLUTION, settings.cameraResolution.toString())
        props.setProperty(KEY_CAMERA_FRAME_RATE, settings.cameraFrameRate.toString())
        for ((name, value) in settings.environment) props.setProperty(KEY_ENV_PREFIX + name, value)
        settings.homeModeChoice?.let { props.setProperty(KEY_HOME_MODE, it.name.lowercase()) }
        if (settings.sdcard) props.setProperty(KEY_SDCARD, "true")
        file.writeTextAtomically(StringWriter().also { props.store(it, null) }.toString())
    }

    fun update(change: (AppSettings) -> AppSettings) = save(change(load()))

    fun delete() {
        file.delete()
    }

    companion object {
        /**
         * An app's settings file from an import, [imported], with the settings that widen what the
         * app can reach left as they are on this device ([local], its current file, or null for
         * none): whole /dev (with Binder), all of shared storage, the microphone without asking,
         * the camera, the network, Android's fonts, the clipboard, the time zone, the language, the
         * extra system calls, its home and its environment. A shared
         * settings file can't turn them on.
         */
        fun imported(imported: ByteArray, local: ByteArray?): ByteArray {
            fun widensReach(key: String) =
                key in WIDENING_KEYS || key.startsWith(KEY_ENV_PREFIX)
            val props = Properties().apply { load(imported.inputStream()) }
            props.stringPropertyNames().filter(::widensReach).forEach { props.remove(it) }
            if (local != null) {
                val current = Properties().apply { load(local.inputStream()) }
                current.stringPropertyNames().filter(::widensReach).forEach { props.setProperty(it, current.getProperty(it)) }
            }
            return ByteArrayOutputStream().also { props.store(it, null) }.toByteArray()
        }

        private const val KEY_SIZE = "display.size"
        private const val KEY_WIDTH = "display.width"
        private const val KEY_HEIGHT = "display.height"
        private const val KEY_ELECTRON_NEWER = "electron.newerFlags"
        private const val KEY_NO_FLATPAK_INFO = "flatpakInfo.none"
        private const val KEY_LAST_OPENED = "lastOpened"
        private const val KEY_WHOLE_DEV = "dev.whole"
        private const val KEY_AUDIO = "permission.audio"
        private const val KEY_MICROPHONE = "permission.microphone"
        private const val KEY_MICROPHONE_ALWAYS = "permission.microphone.always"
        private const val KEY_CAMERA = "permission.camera"
        private const val KEY_CAMERA_RESOLUTION = "camera.resolution"
        private const val KEY_CAMERA_FRAME_RATE = "camera.frameRate"
        private const val KEY_ENV_PREFIX = "env."
        private const val KEY_HOME_MODE = "home.mode"
        private const val KEY_SDCARD = "storage.sdcard"
        private const val KEY_NETWORK = "permission.network"
        private const val KEY_ANDROID_FONTS = "privacy.androidFonts"
        private const val KEY_CLIPBOARD = "permission.clipboard"
        private const val KEY_TIME_ZONE = "privacy.timeZone"
        private const val KEY_LANGUAGE = "privacy.language"
        private const val KEY_EXTRA_SYSCALLS = "filter.allowExtraSyscalls"
        private const val KEY_SHARE_HIDDEN_FILES = "home.shareHiddenFiles"
        private val WIDENING_KEYS = setOf(
            KEY_WHOLE_DEV, KEY_SDCARD, KEY_MICROPHONE_ALWAYS, KEY_CAMERA, KEY_NETWORK, KEY_EXTRA_SYSCALLS, KEY_HOME_MODE,
            KEY_SHARE_HIDDEN_FILES, KEY_ANDROID_FONTS, KEY_CLIPBOARD, KEY_TIME_ZONE, KEY_LANGUAGE,
        )
    }
}
