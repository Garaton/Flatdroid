package com.garathon.flatdroid.settings

import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.StringWriter
import java.util.Properties

/** The display size an app starts with: one of the defaults, or the user's own. */
enum class DisplaySize { PORTRAIT, LANDSCAPE, CUSTOM }

/** Flatdroid's choices for one Flatpak. */
data class AppSettings(
    /** Xvnc's size at launch; the viewer's resize option can change it afterwards. */
    val displaySize: DisplaySize = DisplaySize.PORTRAIT,
    /** Used with [DisplaySize.CUSTOM] only. */
    val customWidth: Int = PORTRAIT_WIDTH,
    val customHeight: Int = PORTRAIT_HEIGHT,
    /** Electron needed the flags for newer versions ("GPU process isn't usable" without them). Managed by the launcher. */
    val newerElectronFlags: Boolean = false,
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
            lastOpened = props.getProperty(KEY_LAST_OPENED)?.toLongOrNull(),
            wholeDev = props.getProperty(KEY_WHOLE_DEV) == "true",
            audio = props.getProperty(KEY_AUDIO)?.toBooleanStrictOrNull(),
            microphone = props.getProperty(KEY_MICROPHONE) == "true",
            alwaysAllowMicrophone = props.getProperty(KEY_MICROPHONE_ALWAYS) == "true",
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
        settings.lastOpened?.let { props.setProperty(KEY_LAST_OPENED, it.toString()) }
        props.setProperty(KEY_WHOLE_DEV, settings.wholeDev.toString())
        settings.audio?.let { props.setProperty(KEY_AUDIO, it.toString()) }
        if (settings.microphone) props.setProperty(KEY_MICROPHONE, "true")
        if (settings.alwaysAllowMicrophone) props.setProperty(KEY_MICROPHONE_ALWAYS, "true")
        file.writeTextAtomically(StringWriter().also { props.store(it, null) }.toString())
    }

    fun update(change: (AppSettings) -> AppSettings) = save(change(load()))

    fun delete() {
        file.delete()
    }

    private companion object {
        const val KEY_SIZE = "display.size"
        const val KEY_WIDTH = "display.width"
        const val KEY_HEIGHT = "display.height"
        const val KEY_ELECTRON_NEWER = "electron.newerFlags"
        const val KEY_LAST_OPENED = "lastOpened"
        const val KEY_WHOLE_DEV = "dev.whole"
        const val KEY_AUDIO = "permission.audio"
        const val KEY_MICROPHONE = "permission.microphone"
        const val KEY_MICROPHONE_ALWAYS = "permission.microphone.always"
    }
}
