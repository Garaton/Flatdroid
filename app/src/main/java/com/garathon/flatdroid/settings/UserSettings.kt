package com.garathon.flatdroid.settings

import com.garathon.flatdroid.alpine.GuestFiles
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
    /** The VNC viewer's package; null to choose every time. */
    val vncViewer: String? = null,
    /** Xvnc sends up to 60 frames a second instead of 30: smoother, more CPU when the picture moves. */
    val frameRate60: Boolean = false,
    /** Developer setting: Xvnc's frame rate, overriding [frameRate60]; null when not set. */
    val customFrameRate: Int? = null,
    /** Developer setting: as many frames as Xvnc can send, overriding the others. */
    val uncappedFrameRate: Boolean = false,
    /** Developer setting: let Flatpak install vendor GPU runtimes (masked otherwise; see [commands]). */
    val installGpuRuntimes: Boolean = false,
    /** Developer setting: Openbox draws title bars (close, maximize) on windows. */
    val titleBars: Boolean = true,
    /** Developer setting: the audio player's buffer in milliseconds; null for [DEFAULT_AUDIO_LATENCY_MS]. */
    val customAudioLatencyMs: Int? = null,
    /** The player uses Android's low latency output instead of the power saving one ([audioOutputMode]). */
    val lowLatencyAudio: Boolean = false,
    /** PulseAudio resamples at a higher quality ([resampler]). */
    val highQualityAudio: Boolean = false,
) {
    val onlyMyLanguages: Boolean get() = flatpakLanguages != GuestFiles.ALL_LANGUAGES

    /** Xvnc's `-FrameRate`. The developer settings count only while developer options are on. */
    val frameRate: Int
        get() = when {
            developerOptions && uncappedFrameRate -> UNCAPPED_FRAME_RATE
            developerOptions && customFrameRate != null -> customFrameRate
            frameRate60 -> 60
            else -> DEFAULT_FRAME_RATE
        }

    /** Whether windows get title bars; the developer setting counts only while developer options are on. */
    val windowTitleBars: Boolean get() = !developerOptions || titleBars

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
    /** The saved settings, or null when the user hasn't chosen any yet. */
    fun load(): UserSettings? {
        if (!file.isFile) return null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val languages = props.getProperty(KEY_LANGUAGES) ?: return null
        return UserSettings(
            flatpakLanguages = languages,
            developerOptions = props.getProperty(KEY_DEVELOPER) == "true",
            vncViewer = props.getProperty(KEY_VNC_VIEWER)?.takeIf { it.isNotBlank() },
            frameRate60 = props.getProperty(KEY_FRAME_RATE_60) == "true",
            customFrameRate = props.getProperty(KEY_CUSTOM_FRAME_RATE)?.toIntOrNull()?.takeIf { it in UserSettings.CUSTOM_FRAME_RATES },
            uncappedFrameRate = props.getProperty(KEY_UNCAPPED_FRAME_RATE) == "true",
            installGpuRuntimes = props.getProperty(KEY_GPU_RUNTIMES) == "true",
            titleBars = props.getProperty(KEY_TITLE_BARS) != "false",
            customAudioLatencyMs = props.getProperty(KEY_AUDIO_LATENCY)?.toIntOrNull()?.takeIf { it in UserSettings.AUDIO_LATENCIES_MS },
            lowLatencyAudio = props.getProperty(KEY_LOW_LATENCY_AUDIO) == "true",
            highQualityAudio = props.getProperty(KEY_HIGH_QUALITY_AUDIO) == "true",
        )
    }

    fun save(settings: UserSettings) {
        val props = Properties()
        props.setProperty(KEY_LANGUAGES, settings.flatpakLanguages)
        if (settings.developerOptions) props.setProperty(KEY_DEVELOPER, "true")
        settings.vncViewer?.let { props.setProperty(KEY_VNC_VIEWER, it) }
        if (settings.frameRate60) props.setProperty(KEY_FRAME_RATE_60, "true")
        settings.customFrameRate?.let { props.setProperty(KEY_CUSTOM_FRAME_RATE, it.toString()) }
        if (settings.uncappedFrameRate) props.setProperty(KEY_UNCAPPED_FRAME_RATE, "true")
        if (settings.installGpuRuntimes) props.setProperty(KEY_GPU_RUNTIMES, "true")
        if (!settings.titleBars) props.setProperty(KEY_TITLE_BARS, "false")
        settings.customAudioLatencyMs?.let { props.setProperty(KEY_AUDIO_LATENCY, it.toString()) }
        if (settings.lowLatencyAudio) props.setProperty(KEY_LOW_LATENCY_AUDIO, "true")
        if (settings.highQualityAudio) props.setProperty(KEY_HIGH_QUALITY_AUDIO, "true")
        file.writeTextAtomically(StringWriter().also { props.store(it, null) }.toString())
    }

    private companion object {
        const val KEY_LANGUAGES = "flatpak.languages"
        const val KEY_DEVELOPER = "ui.developerOptions"
        const val KEY_VNC_VIEWER = "vnc.viewer"
        const val KEY_FRAME_RATE_60 = "vnc.frameRate60"
        const val KEY_CUSTOM_FRAME_RATE = "vnc.frameRate"
        const val KEY_UNCAPPED_FRAME_RATE = "vnc.frameRateUncapped"
        const val KEY_GPU_RUNTIMES = "flatpak.gpuRuntimes"
        const val KEY_TITLE_BARS = "display.titleBars"
        const val KEY_AUDIO_LATENCY = "audio.latencyMs"
        const val KEY_LOW_LATENCY_AUDIO = "audio.lowLatency"
        const val KEY_HIGH_QUALITY_AUDIO = "audio.highQuality"
    }
}
