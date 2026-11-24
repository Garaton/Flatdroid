package com.garathon.flatdroid.settings

import com.garathon.flatdroid.R
import com.garathon.flatdroid.camera.AndroidCameras
import com.garathon.flatdroid.camera.ScreenStreamer

/**
 * The settings a performance level sets ([PerformanceLevel]): the size apps draw at, the viewer's
 * frame rate, the sound's output, the cameras' frames and Flatdroid's own animations. Each can still
 * be changed on its own; values that match no level are "Custom". No security setting is among
 * them, and an app's own choices stay as they are. The interface size follows the screen's size, so
 * apps look about the same size at every level.
 */
data class PerformanceSettings(
    val appResolution: Resolution = AppDefaults.DEFAULT_RESOLUTION,
    val appInterfaceSize: Int = AppDefaults.DEFAULT_INTERFACE_SIZE,
    val frameRate60: Boolean = false,
    val lowLatencyAudio: Boolean = false,
    val highQualityAudio: Boolean = false,
    val cameraResolution: Int = AndroidCameras.DEFAULT_RESOLUTION,
    val cameraFrameRate: Int = AndroidCameras.DEFAULT_FRAME_RATE,
    val screenScale: Int = ScreenStreamer.DEFAULT_SCALE,
    val reduceMotion: Boolean = false,
)

/** Presets for [PerformanceSettings], asked for before setup beside the security level. */
enum class PerformanceLevel(
    override val settings: PerformanceSettings,
    override val labelRes: Int,
    override val hintRes: Int,
) : Preset<PerformanceSettings> {
    BATTERY(
        PerformanceSettings(
            appResolution = Resolution.HALF,
            appInterfaceSize = 75,
            cameraResolution = 480,
            cameraFrameRate = 15,
            screenScale = 25,
            reduceMotion = true,
        ),
        R.string.performance_level_battery, R.string.performance_level_battery_hint,
    ),
    BALANCED(PerformanceSettings(), R.string.performance_level_balanced, R.string.performance_level_balanced_hint),
    QUALITY(
        PerformanceSettings(
            appResolution = Resolution.FULL,
            appInterfaceSize = 150,
            frameRate60 = true,
            lowLatencyAudio = true,
            highQualityAudio = true,
            cameraResolution = 1080,
            screenScale = 75,
        ),
        R.string.performance_level_quality, R.string.performance_level_quality_hint,
    ),
    ;

    companion object {
        /** The level whose settings these are; null for Custom. */
        fun of(settings: PerformanceSettings): PerformanceLevel? = entries.presetFor(settings)
    }
}
