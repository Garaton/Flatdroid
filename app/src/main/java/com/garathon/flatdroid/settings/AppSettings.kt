package com.garathon.flatdroid.settings

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
    /** Sound output (the PulseAudio socket); null follows the app's metadata ([audioAllowed]). */
    val audio: Boolean? = null,
    /**
     * Microphone input, through Android's microphone while the app records. Off by default; on,
     * the user is asked in a notification the first time the app records in a session, and the
     * answer holds for that session.
     */
    val microphone: Boolean = false,
    /** Developer setting: with [microphone] on, record whenever the app does, without asking. */
    val alwaysAllowMicrophone: Boolean = false,
    /** Developer setting: variables added to the app's environment, over Flatdroid's own. */
    val environment: Map<String, String> = emptyMap(),
    val homeMode: HomeMode = HomeMode.SHARED,
    /**
     * All of shared storage at /sdcard. Shown, and bound, only while Flatdroid has access to all
     * of it (All files access, or the storage permission on Android 10 and older).
     */
    val sdcard: Boolean = false,
) {
    /** Whether the app gets sound: its own setting, else whether its metadata asks for PulseAudio. */
    fun audioAllowed(sockets: List<String>): Boolean = audio ?: (PULSEAUDIO_SOCKET in sockets)

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
 * portrait and landscape choices follow Flatdroid's defaults.
 */
class AppSettingsStore(private val file: File) {
    fun load(): AppSettings {
        if (!file.isFile) return AppSettings()
        val props = try {
            Properties().apply { file.inputStream().use { load(it) } }
        } catch (_: Exception) {
            return AppSettings()
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
            microphone = props.getProperty(KEY_MICROPHONE) == "true",
            alwaysAllowMicrophone = props.getProperty(KEY_MICROPHONE_ALWAYS) == "true",
            environment = props.stringPropertyNames()
                .filter { it.startsWith(KEY_ENV_PREFIX) }
                .map { it.removePrefix(KEY_ENV_PREFIX) }
                .filter(LaunchEnvironment::isName)
                .sorted()
                .associateWith { props.getProperty(KEY_ENV_PREFIX + it) },
            homeMode = HomeMode.entries.firstOrNull { it.name.lowercase() == props.getProperty(KEY_HOME_MODE) } ?: defaults.homeMode,
            sdcard = props.getProperty(KEY_SDCARD) == "true",
        )
    }

    fun save(settings: AppSettings) {
        val props = Properties()
        props.setProperty(KEY_SIZE, settings.displaySize.name.lowercase())
        if (settings.displaySize == DisplaySize.CUSTOM) {
            props.setProperty(KEY_WIDTH, settings.customWidth.toString())
            props.setProperty(KEY_HEIGHT, settings.customHeight.toString())
        }
        props.setProperty(KEY_ELECTRON_NEWER, settings.newerElectronFlags.toString())
        if (settings.withoutFlatpakInfo) props.setProperty(KEY_NO_FLATPAK_INFO, "true")
        settings.lastOpened?.let { props.setProperty(KEY_LAST_OPENED, it.toString()) }
        props.setProperty(KEY_WHOLE_DEV, settings.wholeDev.toString())
        settings.audio?.let { props.setProperty(KEY_AUDIO, it.toString()) }
        if (settings.microphone) props.setProperty(KEY_MICROPHONE, "true")
        if (settings.alwaysAllowMicrophone) props.setProperty(KEY_MICROPHONE_ALWAYS, "true")
        for ((name, value) in settings.environment) props.setProperty(KEY_ENV_PREFIX + name, value)
        props.setProperty(KEY_HOME_MODE, settings.homeMode.name.lowercase())
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
         * none): whole /dev (with Binder), all of shared storage, the microphone without asking
         * and its environment. A shared settings file can't turn them on.
         */
        fun imported(imported: ByteArray, local: ByteArray?): ByteArray {
            fun widensReach(key: String) =
                key == KEY_WHOLE_DEV || key == KEY_SDCARD || key == KEY_MICROPHONE_ALWAYS || key.startsWith(KEY_ENV_PREFIX)
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
        private const val KEY_ENV_PREFIX = "env."
        private const val KEY_HOME_MODE = "home.mode"
        private const val KEY_SDCARD = "storage.sdcard"
    }
}
