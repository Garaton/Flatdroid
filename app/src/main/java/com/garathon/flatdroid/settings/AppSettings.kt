package com.garathon.flatdroid.settings

import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.FlatpakContext
import com.garathon.flatdroid.launcher.LaunchEnvironment
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringWriter
import java.util.Properties

/**
 * The size of an app's screen: a share of the viewer's size ([percent]), which it follows as the
 * phone turns, or the user's own fixed size ([CUSTOM]). Smaller shares draw everything larger
 * (the viewer scales the screen up) and cost less to send.
 */
enum class Resolution(val percent: Int?) {
    FULL(100), THREE_QUARTERS(75), HALF(50), QUARTER(25), CUSTOM(null);

    /** The screen's size for a viewer of [viewWidth] x [viewHeight]; even sizes, at least [MIN_FOLLOW]. Not for [CUSTOM]. */
    fun scaled(viewWidth: Int, viewHeight: Int): Pair<Int, Int> {
        val p = requireNotNull(percent) { "a custom size follows nothing" }
        fun side(view: Int) = (view * p / 100 / 2 * 2).coerceIn(MIN_FOLLOW, AppSettings.MAX_SIZE)
        return side(viewWidth) to side(viewHeight)
    }

    companion object {
        /** The smallest side a followed size gets (a quarter of a small phone's width is less). */
        const val MIN_FOLLOW = 200
    }
}

/** What an app gets as its home. None of them isolates the app; they change what it can reach. */
enum class HomeMode {
    /** The shared home (`Documents/Flatdroid` unless the user chose another folder). */
    SHARED,
    /** The app's own folder in Flatdroid's private storage; files come in by import. */
    PRIVATE,
    /** A new home, settings included, at every launch; nothing is kept after the app closes. */
    TEMPORARY,
    /** All of Android's shared storage is the home (needs All files access); hidden config and key files stay private to the app. */
    ANDROID,
}

/**
 * What a Flatpak's own settings start as, from Flatdroid's settings ([SecuritySettings.appDefaults],
 * [UserSettings.appDefaults]): a setting the user hasn't overridden for the app follows these.
 */
data class AppDefaults(
    val homeMode: HomeMode = HomeMode.SHARED,
    /** Network and sound are on when the app's metadata asks for them; otherwise off until the user turns them on. */
    val permissionsFromMetadata: Boolean = true,
    val clipboard: Boolean = true,
    /** The filter also refuses the system calls apps rarely need (the security level's choice). */
    val blockExtraSyscalls: Boolean = true,
    val cameraResolution: Int = AndroidCameras.DEFAULT_RESOLUTION,
    val cameraFrameRate: Int = AndroidCameras.DEFAULT_FRAME_RATE,
)

/** Flatdroid's choices for one Flatpak. */
data class AppSettings(
    /** The screen's size: a share of the viewer's, or [customWidth] x [customHeight]. */
    val resolution: Resolution = Resolution.THREE_QUARTERS,
    /** Used with [Resolution.CUSTOM] only. */
    val customWidth: Int = CUSTOM_WIDTH,
    val customHeight: Int = CUSTOM_HEIGHT,
    /** Electron needed the flags for newer versions ("GPU process isn't usable" without them). Managed by the launcher. */
    val newerElectronFlags: Boolean = false,
    /**
     * The app's root has no /.flatpak-info: KDE's frameworks reported that they would run
     * processes on the host. Managed by the launcher.
     */
    val withoutFlatpakInfo: Boolean = false,
    /** When the app was last launched, in milliseconds since the epoch; null if never. */
    val lastOpened: Long? = null,
    /** How many times the app was launched. Managed by the launcher. */
    val launches: Int = 0,
    /** How long the app ran in all, in milliseconds: from its window being ready to its exit, so roughly. Managed by the launcher. */
    val usedMillis: Long = 0,
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
     * Clipboard text goes both ways between the app and Android, through the VNC viewer. Off,
     * Xvnc neither takes the viewer's clipboard nor sends its own, so the app can't read what was
     * copied in Android. Null follows [defaults] ([sharesClipboard]).
     */
    val clipboard: Boolean? = null,
    /** Developer setting: the filter refuses the rarely used system calls for this app or not; null follows [defaults] ([blocksExtraSyscalls]). */
    val blockExtraSyscalls: Boolean? = null,
    /**
     * Developer setting: the app's hidden config and key files (.ssh, .gnupg, shell start-up,
     * .gitconfig, …) come from a location shared with the other apps that opt in, instead of the
     * app's own, in any home mode. Off by default, so apps don't share keys unless asked.
     */
    val shareHiddenFiles: Boolean = false,
    /**
     * Microphone input, through Android's microphone while the app records. By default on for an
     * app that asks for sound (its metadata can't say more; Grant asked permissions), off
     * otherwise; null follows [defaults] ([microphoneAllowed]). On, the user is asked in a
     * notification the first time the app records in a session, and the answer holds for that
     * session. It also needs Android's microphone permission.
     */
    val microphone: Boolean? = null,
    /** With [microphone] on, record whenever the app does, without asking. */
    val alwaysAllowMicrophone: Boolean = false,
    /**
     * Android's cameras through Flatpak's camera portal. By default on for an app with a hint that
     * it uses a camera ([FlatpakContext.mayUseCamera]; Grant asked permissions), off otherwise;
     * null follows [defaults] ([cameraAllowed]). On, the app may use them whenever it runs
     * (Flatdroid can't ask at the moment of use), and Android's camera runs only while the app
     * takes its picture. It also needs Android's camera permission.
     */
    val camera: Boolean? = null,
    /**
     * Developer setting: with [camera] on, the frames' short side, one of [AndroidCameras.RESOLUTIONS]
     * or [AndroidCameras.FULL_RESOLUTION] (each camera's largest size; a camera without it takes
     * its largest size below), instead of Flatdroid's; null follows [defaults] ([cameraResolution]).
     */
    val cameraResolutionOverride: Int? = null,
    /** Developer setting: with [camera] on, frames per second as the device's cameras offer them, instead of Flatdroid's; null follows [defaults] ([cameraFrameRate]). */
    val cameraFrameRateOverride: Int? = null,
    /** Developer setting: variables added to the app's environment, over Flatdroid's own. */
    val environment: Map<String, String> = emptyMap(),
    /** Developer setting: arguments added after the app's command (one argument each; no quoting). */
    val launchArgs: List<String> = emptyList(),
    /** X11 for an app that takes both (by default such an app gets Wayland). */
    val forceX11: Boolean = false,
    /** On Wayland, how large the app draws everything, in percent ([INTERFACE_SIZES]); X11 apps keep their size. */
    val interfaceSize: Int = 100,
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
    val sharesClipboard: Boolean get() = clipboard ?: defaults.clipboard
    val blocksExtraSyscalls: Boolean get() = blockExtraSyscalls ?: defaults.blockExtraSyscalls
    val cameraResolution: Int get() = cameraResolutionOverride ?: defaults.cameraResolution
    val cameraFrameRate: Int get() = cameraFrameRateOverride ?: defaults.cameraFrameRate

    /** Whether the app gets sound: its own setting, else [defaults] and whether its metadata asks for PulseAudio. */
    fun audioAllowed(sockets: List<String>): Boolean = audio ?: (defaults.permissionsFromMetadata && PULSEAUDIO_SOCKET in sockets)

    /** Whether the app's microphone is on, whatever Android's permission: its own setting, else [defaults] and whether it asks for sound. */
    fun microphoneAllowed(sockets: List<String>): Boolean = audioAllowed(sockets) && (microphone ?: microphoneDefault(sockets))

    fun microphoneDefault(sockets: List<String>): Boolean = defaults.permissionsFromMetadata && PULSEAUDIO_SOCKET in sockets

    /** Whether the app's camera is on, whatever Android's permission: its own setting, else [defaults] and the hint that it uses one. */
    fun cameraAllowed(context: FlatpakContext): Boolean = camera ?: cameraDefault(context)

    fun cameraDefault(context: FlatpakContext): Boolean = defaults.permissionsFromMetadata && context.mayUseCamera

    /** Whether the app gets the Internet: its own setting, else [defaults] and whether its metadata asks for the network. */
    fun networkAllowed(shared: List<String>): Boolean = network ?: (defaults.permissionsFromMetadata && NETWORK in shared)

    /** The screen's size at launch for a viewer of [viewWidth] x [viewHeight]. */
    fun size(viewWidth: Int, viewHeight: Int): Pair<Int, Int> =
        if (resolution == Resolution.CUSTOM) customWidth to customHeight else resolution.scaled(viewWidth, viewHeight)

    companion object {
        const val CUSTOM_WIDTH = 1000
        const val CUSTOM_HEIGHT = 1400
        /** The interface sizes offered, in percent. */
        val INTERFACE_SIZES = listOf(50, 75, 100, 125, 150, 175, 200)
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
            resolution = Resolution.entries.firstOrNull { (it.percent?.toString() ?: "custom") == props.getProperty(KEY_RESOLUTION) } ?: defaults.resolution,
            customWidth = size(KEY_WIDTH, defaults.customWidth),
            customHeight = size(KEY_HEIGHT, defaults.customHeight),
            newerElectronFlags = props.getProperty(KEY_ELECTRON_NEWER) == "true",
            withoutFlatpakInfo = props.getProperty(KEY_NO_FLATPAK_INFO) == "true",
            lastOpened = props.getProperty(KEY_LAST_OPENED)?.toLongOrNull(),
            launches = props.getProperty(KEY_LAUNCHES)?.toIntOrNull()?.takeIf { it > 0 } ?: 0,
            usedMillis = props.getProperty(KEY_USED)?.toLongOrNull()?.takeIf { it > 0 } ?: 0,
            wholeDev = props.getProperty(KEY_WHOLE_DEV) == "true",
            audio = props.getProperty(KEY_AUDIO)?.toBooleanStrictOrNull(),
            network = props.getProperty(KEY_NETWORK)?.toBooleanStrictOrNull(),
            clipboard = props.getProperty(KEY_CLIPBOARD)?.toBooleanStrictOrNull(),
            blockExtraSyscalls = props.getProperty(KEY_EXTRA_SYSCALLS)?.toBooleanStrictOrNull(),
            shareHiddenFiles = props.getProperty(KEY_SHARE_HIDDEN_FILES) == "true",
            microphone = props.getProperty(KEY_MICROPHONE)?.toBooleanStrictOrNull(),
            camera = props.getProperty(KEY_CAMERA)?.toBooleanStrictOrNull(),
            // A saved value equal to the default is no override (older files saved these always).
            cameraResolutionOverride = props.getProperty(KEY_CAMERA_RESOLUTION)?.toIntOrNull()
                ?.takeIf { (it in AndroidCameras.RESOLUTIONS || it == AndroidCameras.FULL_RESOLUTION) && it != appDefaults.cameraResolution },
            cameraFrameRateOverride = props.getProperty(KEY_CAMERA_FRAME_RATE)?.toIntOrNull()
                ?.takeIf { it in 1..AndroidCameras.MAX_FRAME_RATE && it != appDefaults.cameraFrameRate },
            alwaysAllowMicrophone = props.getProperty(KEY_MICROPHONE_ALWAYS) == "true",
            environment = props.stringPropertyNames()
                .filter { it.startsWith(KEY_ENV_PREFIX) }
                .map { it.removePrefix(KEY_ENV_PREFIX) }
                .filter(LaunchEnvironment::isName)
                .sorted()
                .associateWith { props.getProperty(KEY_ENV_PREFIX + it) },
            launchArgs = LaunchArgs.parse(props.getProperty(KEY_LAUNCH_ARGS).orEmpty()),
            forceX11 = props.getProperty(KEY_FORCE_X11) == "true",
            interfaceSize = props.getProperty(KEY_INTERFACE_SIZE)?.toIntOrNull()?.takeIf { it in AppSettings.INTERFACE_SIZES } ?: 100,
            homeModeChoice = HomeMode.entries.firstOrNull { it.name.lowercase() == props.getProperty(KEY_HOME_MODE) },
            sdcard = props.getProperty(KEY_SDCARD) == "true",
            defaults = appDefaults,
        )
    }

    fun save(settings: AppSettings) {
        val props = Properties()
        props.setProperty(KEY_RESOLUTION, settings.resolution.percent?.toString() ?: "custom")
        // Kept while another size is chosen, so switching back finds them.
        props.setProperty(KEY_WIDTH, settings.customWidth.toString())
        props.setProperty(KEY_HEIGHT, settings.customHeight.toString())
        props.setProperty(KEY_ELECTRON_NEWER, settings.newerElectronFlags.toString())
        if (settings.withoutFlatpakInfo) props.setProperty(KEY_NO_FLATPAK_INFO, "true")
        settings.lastOpened?.let { props.setProperty(KEY_LAST_OPENED, it.toString()) }
        if (settings.launches > 0) props.setProperty(KEY_LAUNCHES, settings.launches.toString())
        if (settings.usedMillis > 0) props.setProperty(KEY_USED, settings.usedMillis.toString())
        props.setProperty(KEY_WHOLE_DEV, settings.wholeDev.toString())
        settings.audio?.let { props.setProperty(KEY_AUDIO, it.toString()) }
        settings.network?.let { props.setProperty(KEY_NETWORK, it.toString()) }
        settings.clipboard?.let { props.setProperty(KEY_CLIPBOARD, it.toString()) }
        settings.blockExtraSyscalls?.let { props.setProperty(KEY_EXTRA_SYSCALLS, it.toString()) }
        if (settings.shareHiddenFiles) props.setProperty(KEY_SHARE_HIDDEN_FILES, "true")
        settings.microphone?.let { props.setProperty(KEY_MICROPHONE, it.toString()) }
        if (settings.alwaysAllowMicrophone) props.setProperty(KEY_MICROPHONE_ALWAYS, "true")
        settings.camera?.let { props.setProperty(KEY_CAMERA, it.toString()) }
        settings.cameraResolutionOverride?.let { props.setProperty(KEY_CAMERA_RESOLUTION, it.toString()) }
        settings.cameraFrameRateOverride?.let { props.setProperty(KEY_CAMERA_FRAME_RATE, it.toString()) }
        for ((name, value) in settings.environment) props.setProperty(KEY_ENV_PREFIX + name, value)
        if (settings.launchArgs.isNotEmpty()) props.setProperty(KEY_LAUNCH_ARGS, LaunchArgs.format(settings.launchArgs))
        if (settings.forceX11) props.setProperty(KEY_FORCE_X11, "true")
        if (settings.interfaceSize != 100) props.setProperty(KEY_INTERFACE_SIZE, settings.interfaceSize.toString())
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
         * the camera, the network, the clipboard, the system call filter, its home, its environment and its launch arguments. A shared
         * settings file can't turn them on. The usage statistics stay as they are too.
         */
        fun imported(imported: ByteArray, local: ByteArray?): ByteArray {
            fun widensReach(key: String) =
                key in WIDENING_KEYS || key in USAGE_KEYS || key.startsWith(KEY_ENV_PREFIX)
            val props = Properties().apply { load(imported.inputStream()) }
            props.stringPropertyNames().filter(::widensReach).forEach { props.remove(it) }
            if (local != null) {
                val current = Properties().apply { load(local.inputStream()) }
                current.stringPropertyNames().filter(::widensReach).forEach { props.setProperty(it, current.getProperty(it)) }
            }
            return ByteArrayOutputStream().also { props.store(it, null) }.toByteArray()
        }

        private const val KEY_RESOLUTION = "display.resolution"
        private const val KEY_WIDTH = "display.width"
        private const val KEY_HEIGHT = "display.height"
        private const val KEY_ELECTRON_NEWER = "electron.newerFlags"
        private const val KEY_NO_FLATPAK_INFO = "flatpakInfo.none"
        private const val KEY_LAST_OPENED = "lastOpened"
        private const val KEY_LAUNCHES = "usage.launches"
        private const val KEY_USED = "usage.millis"
        private const val KEY_WHOLE_DEV = "dev.whole"
        private const val KEY_AUDIO = "permission.audio"
        private const val KEY_MICROPHONE = "permission.microphone"
        private const val KEY_MICROPHONE_ALWAYS = "permission.microphone.always"
        private const val KEY_CAMERA = "permission.camera"
        private const val KEY_CAMERA_RESOLUTION = "camera.resolution"
        private const val KEY_CAMERA_FRAME_RATE = "camera.frameRate"
        private const val KEY_ENV_PREFIX = "env."
        private const val KEY_LAUNCH_ARGS = "launch.args"
        private const val KEY_FORCE_X11 = "display.x11"
        private const val KEY_INTERFACE_SIZE = "display.interfaceSize"
        private const val KEY_HOME_MODE = "home.mode"
        private const val KEY_SDCARD = "storage.sdcard"
        private const val KEY_NETWORK = "permission.network"
        private const val KEY_CLIPBOARD = "permission.clipboard"
        private const val KEY_EXTRA_SYSCALLS = "filter.blockExtraSyscalls"
        private const val KEY_SHARE_HIDDEN_FILES = "home.shareHiddenFiles"
        /** Statistics of this device: an import doesn't bring or replace them. */
        private val USAGE_KEYS = setOf(KEY_LAUNCHES, KEY_USED)
        private val WIDENING_KEYS = setOf(
            KEY_WHOLE_DEV, KEY_SDCARD, KEY_MICROPHONE_ALWAYS, KEY_CAMERA, KEY_NETWORK, KEY_EXTRA_SYSCALLS, KEY_HOME_MODE,
            KEY_SHARE_HIDDEN_FILES, KEY_CLIPBOARD, KEY_LAUNCH_ARGS,
        )
    }
}

/** [AppSettings.launchArgs] as the user edits it: arguments separated by whitespace, each taken as it is (no quoting). */
object LaunchArgs {
    fun parse(text: String): List<String> = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun format(args: List<String>): String = args.joinToString(" ")
}
