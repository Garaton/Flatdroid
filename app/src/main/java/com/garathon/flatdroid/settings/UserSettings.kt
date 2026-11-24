package com.garathon.flatdroid.settings

import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.camera.ScreenStreamer
import com.garathon.flatdroid.flatpak.tasks.FlatpakCommands
import com.garathon.flatdroid.session.AlpineShellUser
import com.garathon.flatdroid.util.io.writeTextAtomically
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
    /** Android's dark mode, followed while an app runs where the app reads the settings portal. */
    SYSTEM,
    DARK,
    LIGHT,
    ;

    /** Dark or not, with Android in dark mode or not ([androidDark]). */
    fun isDark(androidDark: Boolean): Boolean = when (this) {
        SYSTEM -> androidDark
        DARK -> true
        LIGHT -> false
    }
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
    /** The screen-as-a-camera frames' size in percent of the screen's for apps that don't override it ([ScreenStreamer.SCALES]). */
    val screenScale: Int = ScreenStreamer.DEFAULT_SCALE,
    /** The screen's size and the interface size for apps that don't choose their own (see [AppSettings]). */
    val appResolution: Resolution = AppDefaults.DEFAULT_RESOLUTION,
    val appInterfaceSize: Int = AppDefaults.DEFAULT_INTERFACE_SIZE,
    val appRendering: Rendering = Rendering.AUTOMATIC,
    /** Apps are asked to turn off their animations (GTK's, and Chromium's on Electron apps). Not part of the security level. */
    val reduceMotion: Boolean = false,
    /** What the security level sets ([SecurityLevel]). */
    val security: SecuritySettings = SecuritySettings(),
    /** Developer setting: the Alpine shell (and the setup screen's commands) run behind fdsec's filter. */
    val shellFilter: Boolean = true,
    /**
     * Developer setting, on at every level: Alpine's files are checked for changes around each
     * Alpine program's session ([com.garathon.flatdroid.alpine.programs.AlpineChanges]).
     */
    val alpineSafeLaunch: Boolean = true,
    /** Developer setting: who Alpine's shell in a window runs as. */
    val alpineShellUser: AlpineShellUser = AlpineShellUser.ROOT,
) {
    /** The level [security] matches; null for Custom. */
    val securityLevel: SecurityLevel? get() = SecurityLevel.of(security)

    /** The settings a performance level sets, as they are now. */
    val performance: PerformanceSettings get() = PerformanceSettings(
        appResolution = appResolution, appInterfaceSize = appInterfaceSize, frameRate60 = frameRate60,
        lowLatencyAudio = lowLatencyAudio, highQualityAudio = highQualityAudio, cameraResolution = cameraResolution,
        cameraFrameRate = cameraFrameRate, screenScale = screenScale, reduceMotion = reduceMotion,
    )

    val performanceLevel: PerformanceLevel? get() = PerformanceLevel.of(performance)

    /** These settings with [p]'s performance settings. */
    fun withPerformance(p: PerformanceSettings): UserSettings = copy(
        appResolution = p.appResolution, appInterfaceSize = p.appInterfaceSize, frameRate60 = p.frameRate60,
        lowLatencyAudio = p.lowLatencyAudio, highQualityAudio = p.highQualityAudio, cameraResolution = p.cameraResolution,
        cameraFrameRate = p.cameraFrameRate, screenScale = p.screenScale, reduceMotion = p.reduceMotion,
    )

    /** Whether the shell is filtered; the developer setting counts only while developer options are on. */
    val filterShell: Boolean get() = !developerOptions || shellFilter

    /** Whether Alpine's files are checked around an Alpine program; the developer setting counts only while developer options are on. */
    val checkAlpine: Boolean get() = !developerOptions || alpineSafeLaunch

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
    val appDefaults: AppDefaults get() = security.appDefaults.copy(
        cameraResolution = cameraResolution, cameraFrameRate = cameraFrameRate, screenScale = screenScale,
        resolution = appResolution, interfaceSize = appInterfaceSize,
        rendering = appRendering,
    )

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
        /**
         * Measured on a Cortex-A53 phone: Android gives the power saving output 85 ms whatever is
         * asked; on the low latency one 40 ms played SuperTux and VLC without underruns, and its
         * floor of 32 ms underran once.
         */
        const val DEFAULT_AUDIO_LATENCY_MS = 40
        val AUDIO_LATENCIES_MS = 1..1000
        /** Per command of [commands]: uninstalling runtimes can take minutes under proot. */
        const val APPLY_TIMEOUT_SECONDS = 15 * 60L

        /** Defaults for a device whose languages, as Flatpak's value, are [deviceLanguages]. */
        fun defaults(deviceLanguages: String) = UserSettings(flatpakLanguages = deviceLanguages)
    }
}

class UserSettingsStore(private val file: File) {
    /**
     * Reads [LiveSettings] from the file: at start-up, and again after a reset, which deletes the
     * file. [save] keeps them current, so every other change of the file goes through one of the two.
     */
    fun loadLive() {
        val settings = runCatching { load() }.getOrNull()
        LiveSettings.theme.value = settings?.theme ?: InterfaceTheme.SYSTEM
        LiveSettings.hideScreen.value = settings?.security?.hideScreen == true
        LiveSettings.user.value = settings
    }

    /** The saved settings, or null when the user hasn't chosen any yet. */
    fun load(): UserSettings? {
        if (!file.isFile) return null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val languages = props.getProperty(KEY_LANGUAGES) ?: return null
        val loaded = UserSettings(
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
            screenScale = props.getProperty(KEY_SCREEN_SCALE)?.toIntOrNull()?.takeIf { it in ScreenStreamer.SCALES } ?: ScreenStreamer.DEFAULT_SCALE,
            appResolution = AppDefaults.DEFAULT_RESOLUTIONS.firstOrNull { it.percent.toString() == props.getProperty(KEY_APP_RESOLUTION) }
                ?: AppDefaults.DEFAULT_RESOLUTION,
            appInterfaceSize = props.getProperty(KEY_APP_INTERFACE_SIZE)?.toIntOrNull()?.takeIf { it in AppSettings.INTERFACE_SIZES }
                ?: AppDefaults.DEFAULT_INTERFACE_SIZE,
            reduceMotion = props.getProperty(KEY_REDUCE_MOTION) == "true",
            appRendering = Rendering.entries.firstOrNull { it.key == props.getProperty(KEY_APP_RENDERING) } ?: Rendering.AUTOMATIC,
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
            shellFilter = props.getProperty(KEY_SHELL_FILTER) != "false",
            alpineSafeLaunch = props.getProperty(KEY_ALPINE_SAFE_LAUNCH) != "false",
            alpineShellUser = AlpineShellUser.entries.firstOrNull { it.key == props.getProperty(KEY_ALPINE_SHELL_USER) } ?: AlpineShellUser.ROOT,
        )
        // As the security level: a performance level saved by name sets its settings as Flatdroid
        // defines them now; Custom (or none saved) keeps each setting as saved.
        val level = PerformanceLevel.entries.firstOrNull { it.name.lowercase() == props.getProperty(KEY_PERFORMANCE_LEVEL) }
        return level?.let { loaded.withPerformance(it.settings) } ?: loaded
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
            appNotifications = flag(KEY_APP_NOTIFICATIONS, default.appNotifications),
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
        if (settings.screenScale != ScreenStreamer.DEFAULT_SCALE) props.setProperty(KEY_SCREEN_SCALE, settings.screenScale.toString())
        if (settings.appResolution != AppDefaults.DEFAULT_RESOLUTION) props.setProperty(KEY_APP_RESOLUTION, settings.appResolution.percent.toString())
        if (settings.appInterfaceSize != AppDefaults.DEFAULT_INTERFACE_SIZE) props.setProperty(KEY_APP_INTERFACE_SIZE, settings.appInterfaceSize.toString())
        if (settings.reduceMotion) props.setProperty(KEY_REDUCE_MOTION, "true")
        if (settings.appRendering != Rendering.AUTOMATIC) props.setProperty(KEY_APP_RENDERING, settings.appRendering.key)
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
        props.setProperty(KEY_PERFORMANCE_LEVEL, settings.performanceLevel?.name?.lowercase() ?: CUSTOM)
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
            props.setProperty(KEY_APP_NOTIFICATIONS, s.appNotifications.toString())
            props.setProperty(KEY_SHARE_LOCALE, s.shareLocale.toString())
        }
        if (!settings.shellFilter) props.setProperty(KEY_SHELL_FILTER, "false")
        if (!settings.alpineSafeLaunch) props.setProperty(KEY_ALPINE_SAFE_LAUNCH, "false")
        if (settings.alpineShellUser != AlpineShellUser.ROOT) props.setProperty(KEY_ALPINE_SHELL_USER, settings.alpineShellUser.key)
        file.writeTextAtomically(StringWriter().also { props.store(it, null) }.toString())
        LiveSettings.theme.value = settings.theme
        LiveSettings.hideScreen.value = settings.security.hideScreen
        LiveSettings.user.value = settings
    }

    private companion object {
        private const val CUSTOM = "custom"
        private const val KEY_SECURITY_LEVEL = "security.level"
        private const val KEY_PERFORMANCE_LEVEL = "performance.level"
        private const val KEY_EXTRA_SYSCALLS = "security.extraSyscalls"
        private const val KEY_HIDE_HELPERS = "security.hideHelpers"
        private const val KEY_PERMISSIONS_FROM_METADATA = "security.permissionsFromMetadata"
        private const val KEY_DEFAULT_HOME = "security.defaultHome"
        private const val KEY_LIMIT_DEVICES = "security.limitDevices"
        private const val KEY_HIDE_SCREEN = "security.hideScreen"
        private const val KEY_CHECK_FILES = "security.checkFiles"
        private const val KEY_ANDROID_FONTS = "security.androidFonts"
        private const val KEY_SHARE_CLIPBOARD = "security.shareClipboard"
        private const val KEY_APP_NOTIFICATIONS = "security.appNotifications"
        private const val KEY_SHARE_LOCALE = "security.shareLocale"
        private const val KEY_SHELL_FILTER = "shell.filter"
        private const val KEY_ALPINE_SAFE_LAUNCH = "alpine.safeLaunch"
        private const val KEY_ALPINE_SHELL_USER = "terminal.shellUser"
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
        private const val KEY_APP_RESOLUTION = "apps.resolution"
        private const val KEY_APP_INTERFACE_SIZE = "apps.interfaceSize"
        private const val KEY_APP_RENDERING = "apps.rendering"
        private const val KEY_REDUCE_MOTION = "display.reduceMotion"
        private const val KEY_CAMERA_FRAME_RATE = "camera.frameRate"
        private const val KEY_SCREEN_SCALE = "screen.scale"
    }
}

/**
 * Settings every screen follows as soon as they are saved: Flatdroid's theme, hiding the screen,
 * and the saved settings as a whole ([user]), so the screens needn't read the file on the main thread.
 */
object LiveSettings {
    val theme = kotlinx.coroutines.flow.MutableStateFlow(InterfaceTheme.SYSTEM)
    val hideScreen = kotlinx.coroutines.flow.MutableStateFlow(false)
    /** What [UserSettingsStore.load] returns for Flatdroid's settings file; null until loaded, and when none are saved yet. */
    val user = kotlinx.coroutines.flow.MutableStateFlow<UserSettings?>(null)
}
