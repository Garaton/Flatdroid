package com.garathon.flatdroid.settings

import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.flatpak.FlatpakCommands
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.StringWriter
import java.util.Properties

/** The Android output path the audio player's `AudioTrack` asks for (its performance mode). */
enum class AudioOutputMode {
    /** The fast mixer: less latency and fewer underruns, much more CPU in Android's audio server. */
    LOW_LATENCY,
    /** The deep-buffer output: far less CPU, more latency. */
    POWER_SAVING,
}

/** The theme Flatpak apps are asked to use (see DesktopConfig). */
enum class FlatpakTheme {
    /** Android's dark mode, at each launch. */
    SYSTEM,
    DARK,
    LIGHT,
}

/** Flatdroid's own theme: Android's light or dark mode, or one of them; AMOLED is dark with black backgrounds. */
enum class InterfaceTheme(val key: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark"), AMOLED("amoled");

    /** Whether the dark colors are used, given Android's own mode. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK, AMOLED -> true
    }
}

/** What Flatpak tasks do on a connection Android calls metered (mobile data, mostly). */
enum class MobileData(val key: String) { ASK("ask"), ALWAYS("always"), NEVER("never") }

/** What the right column of the home list shows for each app. */
enum class AppTime(val key: String) { TIME_USED("used"), LAST_OPENED("lastOpened") }

/** PulseAudio's resampler (`--resample-method`), used for apps whose rate differs from the device's. */
enum class Resampler(val pulseName: String) {
    FAST("speex-float-0"),
    /** PulseAudio's own default: the same resampler at a higher quality level. */
    STANDARD("speex-float-1"),
}

/**
 * Choices made in Flatdroid's settings. The settings screen asks for them before setup, and
 * they can be changed later. Kept in `files/settings.properties`, which is backed up (end-to-end
 * encrypted only) and survives a reset that keeps Flatpaks.
 */
data class UserSettings(
    /** Flatpak's `languages` value: the device's languages plus English, or [GuestFiles.ALL_LANGUAGES]. */
    val flatpakLanguages: String,
    /** Shows settings Flatdroid normally manages itself (such as an Electron app's flags). */
    val developerOptions: Boolean = false,
    /**
     * Apps are shown in an external VNC viewer app, which connects to a local port with a
     * password; off (the default), only Flatdroid's own viewer shows them, through a socket in
     * private storage.
     */
    val externalViewer: Boolean = false,
    /** The external VNC viewer's package; null when none is chosen yet. */
    val vncViewer: String? = null,
    /** Xvnc sends up to 60 frames a second instead of 30: smoother, more CPU when the picture moves. */
    val frameRate60: Boolean = false,
    /** Developer setting: Xvnc's frame rate, overriding [frameRate60]; null when not set. */
    val customFrameRate: Int? = null,
    /** Developer setting: as many frames as Xvnc can send, overriding the others. */
    val uncappedFrameRate: Boolean = false,
    /** Developer setting: let Flatpak install vendor GPU runtimes (masked otherwise; see [commands]). */
    val installGpuRuntimes: Boolean = false,
    /** Developer setting: the audio player's buffer in milliseconds; null for [DEFAULT_AUDIO_LATENCY_MS]. */
    val customAudioLatencyMs: Int? = null,
    /** The player uses Android's low latency output instead of the power saving one ([audioOutputMode]). */
    val lowLatencyAudio: Boolean = false,
    /** PulseAudio resamples at a higher quality ([resampler]). */
    val highQualityAudio: Boolean = false,
    /** The shared home's absolute path; null for the default, `Documents/Flatdroid`. */
    val sharedHome: String? = null,
    val flatpakTheme: FlatpakTheme = FlatpakTheme.SYSTEM,
    /** Flatdroid's own theme; not applied to apps ([flatpakTheme]). */
    val theme: InterfaceTheme = InterfaceTheme.SYSTEM,
    /** What installs and updates do on a metered connection. */
    val mobileData: MobileData = MobileData.ASK,
    /** What the home list shows next to each app. */
    val appTime: AppTime = AppTime.TIME_USED,
    /** The camera's frames' short side and rate for apps that don't override them (see [AppSettings]). */
    val cameraResolution: Int = AndroidCameras.DEFAULT_RESOLUTION,
    val cameraFrameRate: Int = AndroidCameras.DEFAULT_FRAME_RATE,
    /** What the security level sets ([SecurityLevel]). */
    val security: SecuritySettings = SecuritySettings(),
    /** Developer setting: the terminal's commands run behind fdsec's filter. */
    val terminalFilter: Boolean = true,
    /** Developer setting: the terminal runs commands while an app runs, and apps start while it does. */
    val terminalDuringSession: Boolean = false,
) {
    /** The level [security] matches; null for Custom. */
    val securityLevel: SecurityLevel? get() = SecurityLevel.of(security)

    /** Whether the terminal filters its commands; the developer setting counts only while developer options are on. */
    val filterTerminal: Boolean get() = !developerOptions || terminalFilter

    /**
     * Whether the terminal and a running app may overlap; the developer setting counts only while
     * developer options are on. A command started during a session isn't hidden from the app,
     * which could take it over (see [com.garathon.flatdroid.alpine.AlpineCommands]).
     */
    val terminalWithApps: Boolean get() = developerOptions && terminalDuringSession

    val onlyMyLanguages: Boolean get() = flatpakLanguages != GuestFiles.ALL_LANGUAGES

    /** Xvnc's `-FrameRate`. The developer settings count only while developer options are on. */
    val frameRate: Int
        get() = when {
            developerOptions && uncappedFrameRate -> UNCAPPED_FRAME_RATE
            developerOptions && customFrameRate != null -> customFrameRate
            frameRate60 -> 60
            else -> DEFAULT_FRAME_RATE
        }

    /**
     * Whether windows get title bars: with an external viewer, which has no other way to close a
     * window; not in Flatdroid's viewer, whose bar closes the focused one.
     */
    val windowTitleBars: Boolean get() = externalViewer

    /** What an app's own settings start as. */
    val appDefaults: AppDefaults get() = security.appDefaults.copy(cameraResolution = cameraResolution, cameraFrameRate = cameraFrameRate)

    /** The audio player's buffer; the developer setting counts only while developer options are on. */
    val audioLatencyMs: Int get() = customAudioLatencyMs?.takeIf { developerOptions } ?: DEFAULT_AUDIO_LATENCY_MS

    val audioOutputMode: AudioOutputMode get() = if (lowLatencyAudio) AudioOutputMode.LOW_LATENCY else AudioOutputMode.POWER_SAVING

    val resampler: Resampler get() = if (highQualityAudio) Resampler.STANDARD else Resampler.FAST

    /**
     * The commands that apply these settings in Alpine, in order. Vendor GPU runtimes are masked
     * and uninstalled unless the developer setting allows them: masking is idempotent, while
     * removing a mask that isn't there fails, so allowing them adds each mask and then removes it.
     */
    fun commands(): List<List<String>> {
        val allowGpu = developerOptions && installGpuRuntimes
        val gpu = FlatpakCommands.GPU_RUNTIME_PATTERNS.flatMap { pattern ->
            listOf(FlatpakCommands.mask(pattern)) + if (allowGpu) listOf(FlatpakCommands.unmask(pattern)) else emptyList()
        }
        val removeGpu = if (allowGpu) emptyList() else listOf(FlatpakCommands.uninstallMatching(FlatpakCommands.GPU_RUNTIME_PATTERNS))
        return listOf(FlatpakCommands.setLanguages(flatpakLanguages)) + gpu + removeGpu
    }

    companion object {
        const val DEFAULT_FRAME_RATE = 30
        /**
         * "Uncapped": Xvnc 1.16 has no unlimited value (`-FrameRate 0` makes it exit once a viewer
         * connects), and 1000 is above anything it reaches.
         */
        const val UNCAPPED_FRAME_RATE = 1000
        val CUSTOM_FRAME_RATES = 1..UNCAPPED_FRAME_RATE
        const val DEFAULT_AUDIO_LATENCY_MS = 60
        val AUDIO_LATENCIES_MS = 1..1000
        /** Per command of [commands]: uninstalling runtimes can take minutes under proot. */
        const val APPLY_TIMEOUT_SECONDS = 15 * 60L

        /** Defaults for a device whose languages, as Flatpak's value, are [deviceLanguages]. */
        fun defaults(deviceLanguages: String) = UserSettings(flatpakLanguages = deviceLanguages)
    }
}

class UserSettingsStore(private val file: File) {
    /** Reads [LiveSettings] (at start-up; [save] keeps them current). */
    fun loadLive() {
        val settings = runCatching { load() }.getOrNull()
        LiveSettings.theme.value = settings?.theme ?: InterfaceTheme.SYSTEM
        LiveSettings.hideScreen.value = settings?.security?.hideScreen == true
    }

    /** The saved settings, or null when the user hasn't chosen any yet. */
    fun load(): UserSettings? {
        if (!file.isFile) return null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val languages = props.getProperty(KEY_LANGUAGES) ?: return null
        return UserSettings(
            flatpakLanguages = languages,
            developerOptions = props.getProperty(KEY_DEVELOPER) == "true",
            externalViewer = props.getProperty(KEY_EXTERNAL_VIEWER) == "true",
            theme = InterfaceTheme.entries.firstOrNull { it.key == props.getProperty(KEY_THEME_FLATDROID) } ?: InterfaceTheme.SYSTEM,
            mobileData = MobileData.entries.firstOrNull { it.key == props.getProperty(KEY_MOBILE_DATA) } ?: MobileData.ASK,
            appTime = AppTime.entries.firstOrNull { it.key == props.getProperty(KEY_APP_TIME) } ?: AppTime.TIME_USED,
            cameraResolution = props.getProperty(KEY_CAMERA_RESOLUTION)?.toIntOrNull()
                ?.takeIf { it in AndroidCameras.RESOLUTIONS || it == AndroidCameras.FULL_RESOLUTION } ?: AndroidCameras.DEFAULT_RESOLUTION,
            cameraFrameRate = props.getProperty(KEY_CAMERA_FRAME_RATE)?.toIntOrNull()?.takeIf { it in 1..AndroidCameras.MAX_FRAME_RATE }
                ?: AndroidCameras.DEFAULT_FRAME_RATE,
            vncViewer = props.getProperty(KEY_VNC_VIEWER)?.takeIf { it.isNotBlank() },
            frameRate60 = props.getProperty(KEY_FRAME_RATE_60) == "true",
            customFrameRate = props.getProperty(KEY_CUSTOM_FRAME_RATE)?.toIntOrNull()?.takeIf { it in UserSettings.CUSTOM_FRAME_RATES },
            uncappedFrameRate = props.getProperty(KEY_UNCAPPED_FRAME_RATE) == "true",
            installGpuRuntimes = props.getProperty(KEY_GPU_RUNTIMES) == "true",
            customAudioLatencyMs = props.getProperty(KEY_AUDIO_LATENCY)?.toIntOrNull()?.takeIf { it in UserSettings.AUDIO_LATENCIES_MS },
            lowLatencyAudio = props.getProperty(KEY_LOW_LATENCY_AUDIO) == "true",
            highQualityAudio = props.getProperty(KEY_HIGH_QUALITY_AUDIO) == "true",
            sharedHome = props.getProperty(KEY_SHARED_HOME)?.takeIf { it.startsWith("/") },
            flatpakTheme = FlatpakTheme.entries.firstOrNull { it.name.lowercase() == props.getProperty(KEY_THEME) } ?: FlatpakTheme.SYSTEM,
            security = security(props),
            terminalFilter = props.getProperty(KEY_TERMINAL_FILTER) != "false",
            terminalDuringSession = props.getProperty(KEY_TERMINAL_DURING_SESSION) == "true",
        )
    }

    /**
     * A level is saved by name, so its settings follow the level as Flatdroid changes it; only
     * Custom saves each setting (any missing one is Default's).
     */
    private fun security(props: Properties): SecuritySettings {
        val level = props.getProperty(KEY_SECURITY_LEVEL)
        SecurityLevel.entries.firstOrNull { it.name.lowercase() == level }?.let { return it.settings }
        if (level != CUSTOM) return SecurityLevel.DEFAULT.settings
        val default = SecurityLevel.DEFAULT.settings
        fun flag(key: String, fallback: Boolean) = props.getProperty(key)?.toBooleanStrictOrNull() ?: fallback
        return SecuritySettings(
            extraSyscallFilter = flag(KEY_EXTRA_SYSCALLS, default.extraSyscallFilter),
            hideHelperProcesses = flag(KEY_HIDE_HELPERS, default.hideHelperProcesses),
            permissionsFromMetadata = flag(KEY_PERMISSIONS_FROM_METADATA, default.permissionsFromMetadata),
            defaultHomeMode = HomeMode.entries.firstOrNull { it.name.lowercase() == props.getProperty(KEY_DEFAULT_HOME) } ?: default.defaultHomeMode,
            limitDevices = flag(KEY_LIMIT_DEVICES, default.limitDevices),
            hideScreen = flag(KEY_HIDE_SCREEN, default.hideScreen),
            checkFilesBeforeLaunch = flag(KEY_CHECK_FILES, default.checkFilesBeforeLaunch),
            androidFonts = flag(KEY_ANDROID_FONTS, default.androidFonts),
            shareClipboard = flag(KEY_SHARE_CLIPBOARD, default.shareClipboard),
            shareLocale = flag(KEY_SHARE_LOCALE, default.shareLocale),
        )
    }

    fun save(settings: UserSettings) {
        val props = Properties()
        props.setProperty(KEY_LANGUAGES, settings.flatpakLanguages)
        if (settings.developerOptions) props.setProperty(KEY_DEVELOPER, "true")
        if (settings.externalViewer) props.setProperty(KEY_EXTERNAL_VIEWER, "true")
        if (settings.theme != InterfaceTheme.SYSTEM) props.setProperty(KEY_THEME_FLATDROID, settings.theme.key)
        if (settings.mobileData != MobileData.ASK) props.setProperty(KEY_MOBILE_DATA, settings.mobileData.key)
        if (settings.appTime != AppTime.TIME_USED) props.setProperty(KEY_APP_TIME, settings.appTime.key)
        if (settings.cameraResolution != AndroidCameras.DEFAULT_RESOLUTION) props.setProperty(KEY_CAMERA_RESOLUTION, settings.cameraResolution.toString())
        if (settings.cameraFrameRate != AndroidCameras.DEFAULT_FRAME_RATE) props.setProperty(KEY_CAMERA_FRAME_RATE, settings.cameraFrameRate.toString())
        settings.vncViewer?.let { props.setProperty(KEY_VNC_VIEWER, it) }
        if (settings.frameRate60) props.setProperty(KEY_FRAME_RATE_60, "true")
        settings.customFrameRate?.let { props.setProperty(KEY_CUSTOM_FRAME_RATE, it.toString()) }
        if (settings.uncappedFrameRate) props.setProperty(KEY_UNCAPPED_FRAME_RATE, "true")
        if (settings.installGpuRuntimes) props.setProperty(KEY_GPU_RUNTIMES, "true")
        settings.customAudioLatencyMs?.let { props.setProperty(KEY_AUDIO_LATENCY, it.toString()) }
        if (settings.lowLatencyAudio) props.setProperty(KEY_LOW_LATENCY_AUDIO, "true")
        if (settings.highQualityAudio) props.setProperty(KEY_HIGH_QUALITY_AUDIO, "true")
        settings.sharedHome?.let { props.setProperty(KEY_SHARED_HOME, it) }
        if (settings.flatpakTheme != FlatpakTheme.SYSTEM) props.setProperty(KEY_THEME, settings.flatpakTheme.name.lowercase())
        val level = settings.securityLevel
        props.setProperty(KEY_SECURITY_LEVEL, level?.name?.lowercase() ?: CUSTOM)
        if (level == null) {
            val s = settings.security
            props.setProperty(KEY_EXTRA_SYSCALLS, s.extraSyscallFilter.toString())
            props.setProperty(KEY_HIDE_HELPERS, s.hideHelperProcesses.toString())
            props.setProperty(KEY_PERMISSIONS_FROM_METADATA, s.permissionsFromMetadata.toString())
            props.setProperty(KEY_DEFAULT_HOME, s.defaultHomeMode.name.lowercase())
            props.setProperty(KEY_LIMIT_DEVICES, s.limitDevices.toString())
            props.setProperty(KEY_HIDE_SCREEN, s.hideScreen.toString())
            props.setProperty(KEY_CHECK_FILES, s.checkFilesBeforeLaunch.toString())
            props.setProperty(KEY_ANDROID_FONTS, s.androidFonts.toString())
            props.setProperty(KEY_SHARE_CLIPBOARD, s.shareClipboard.toString())
            props.setProperty(KEY_SHARE_LOCALE, s.shareLocale.toString())
        }
        if (!settings.terminalFilter) props.setProperty(KEY_TERMINAL_FILTER, "false")
        if (settings.terminalDuringSession) props.setProperty(KEY_TERMINAL_DURING_SESSION, "true")
        file.writeTextAtomically(StringWriter().also { props.store(it, null) }.toString())
        LiveSettings.theme.value = settings.theme
        LiveSettings.hideScreen.value = settings.security.hideScreen
    }

    private companion object {
        private const val CUSTOM = "custom"
        private const val KEY_SECURITY_LEVEL = "security.level"
        private const val KEY_EXTRA_SYSCALLS = "security.extraSyscalls"
        private const val KEY_HIDE_HELPERS = "security.hideHelpers"
        private const val KEY_PERMISSIONS_FROM_METADATA = "security.permissionsFromMetadata"
        private const val KEY_DEFAULT_HOME = "security.defaultHome"
        private const val KEY_LIMIT_DEVICES = "security.limitDevices"
        private const val KEY_HIDE_SCREEN = "security.hideScreen"
        private const val KEY_CHECK_FILES = "security.checkFiles"
        private const val KEY_ANDROID_FONTS = "security.androidFonts"
        private const val KEY_SHARE_CLIPBOARD = "security.shareClipboard"
        private const val KEY_SHARE_LOCALE = "security.shareLocale"
        private const val KEY_TERMINAL_FILTER = "terminal.filter"
        private const val KEY_TERMINAL_DURING_SESSION = "terminal.duringSession"
        private const val KEY_LANGUAGES = "flatpak.languages"
        private const val KEY_DEVELOPER = "ui.developerOptions"
        private const val KEY_VNC_VIEWER = "vnc.viewer"
        private const val KEY_EXTERNAL_VIEWER = "vnc.external"
        private const val KEY_FRAME_RATE_60 = "vnc.frameRate60"
        private const val KEY_CUSTOM_FRAME_RATE = "vnc.frameRate"
        private const val KEY_UNCAPPED_FRAME_RATE = "vnc.frameRateUncapped"
        private const val KEY_GPU_RUNTIMES = "flatpak.gpuRuntimes"
        private const val KEY_AUDIO_LATENCY = "audio.latencyMs"
        private const val KEY_LOW_LATENCY_AUDIO = "audio.lowLatency"
        private const val KEY_HIGH_QUALITY_AUDIO = "audio.highQuality"
        private const val KEY_SHARED_HOME = "home.shared"
        private const val KEY_THEME = "flatpak.theme"
        private const val KEY_THEME_FLATDROID = "ui.theme"
        private const val KEY_MOBILE_DATA = "flatpak.mobileData"
        private const val KEY_APP_TIME = "ui.appTime"
        private const val KEY_CAMERA_RESOLUTION = "camera.resolution"
        private const val KEY_CAMERA_FRAME_RATE = "camera.frameRate"
    }
}

/** Settings every screen follows as soon as they are saved: Flatdroid's theme, hiding the screen. */
object LiveSettings {
    val theme = kotlinx.coroutines.flow.MutableStateFlow(InterfaceTheme.SYSTEM)
    val hideScreen = kotlinx.coroutines.flow.MutableStateFlow(false)
}
