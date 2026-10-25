package com.garathon.flatdroid.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.util.DebugLog

/** Android's cameras, as Flatdroid offers them to apps. */
object AndroidCameras {
    enum class Facing { BACK, FRONT, EXTERNAL }

    const val DEFAULT_FRAME_RATE = 30
    /** The highest rate an app's setting is read as; the device's cameras set what's offered ([rates]). */
    const val MAX_FRAME_RATE = 240
    /** Some apps take only cameras whose formats stay at or under this rate, and skip the rest. */
    const val COMMON_MAX_FRAME_RATE = 30

    /** The frames' short sides an app's setting can ask for ([resolutions]). */
    val RESOLUTIONS = listOf(2160, 1080, 720, 480, 360, 144)
    /** Each camera's largest size, whatever its short side. */
    const val FULL_RESOLUTION = 0
    /** 720: a tablet's full camera size (2560×1440) cost about a core in Flatdroid and its source. */
    const val DEFAULT_RESOLUTION = 720

    data class Size(val width: Int, val height: Int) {
        val shortSide get() = minOf(width, height)
        val pixels get() = width.toLong() * height
    }

    /**
     * A choice for an app's camera size at one frame rate: a short side of [RESOLUTIONS], or
     * [FULL_RESOLUTION] with each camera's largest size ([fullSizes]).
     */
    data class Resolution(val shortSide: Int, val fullSizes: List<Size> = emptyList())

    /** A camera and how its frames go out: the size [pick] chooses among those it gives at [frameRate]. */
    data class Camera(
        val id: String, val facing: Facing, val width: Int, val height: Int,
        val sensorOrientation: Int = 0, val frameRate: Int = DEFAULT_FRAME_RATE,
    ) {
        /**
         * Clockwise turn that makes this camera's frames upright with the device turned by
         * [displayRotation] degrees from its natural orientation (Android's formula for camera
         * previews); 0 for a landscape phone held landscape.
         */
        fun rotation(displayRotation: Int): Int =
            if (facing == Facing.FRONT) (sensorOrientation + displayRotation) % 360
            else (sensorOrientation - displayRotation + 360) % 360

        /** Width and height of the frames turned by [rotation]. */
        fun turnedSize(rotation: Int): Pair<Int, Int> = if (rotation % 180 == 0) width to height else height to width
    }

    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    /** Whether the device has a camera at all, without asking the camera service. */
    fun present(context: Context) = context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * Every camera with a usable size, at [frameRate] where it can run at that rate, else at the
     * nearest rate it can ([rates]), at the size [pick] chooses for [resolution]; none
     * when Android has no camera service or no cameras.
     */
    fun list(context: Context, resolution: Int = DEFAULT_RESOLUTION, frameRate: Int = DEFAULT_FRAME_RATE): List<Camera> =
        characteristics(context).mapNotNull { (id, characteristics) ->
            val facing = when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_FRONT -> Facing.FRONT
                CameraCharacteristics.LENS_FACING_BACK -> Facing.BACK
                else -> Facing.EXTERNAL
            }
            val rate = rates(characteristics).minByOrNull { Math.abs(it - frameRate) } ?: return@mapNotNull null
            val size = pick(sizes(characteristics, rate), resolution) ?: return@mapNotNull null
            Camera(id, facing, size.width, size.height, characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0, rate)
        }

    /** For each rate at least one camera can run at ([rates]), the sizes an app can ask for. */
    fun resolutions(context: Context): Map<Int, List<Resolution>> {
        val cameras = characteristics(context).map { it.second }
        val rates = cameras.flatMap(::rates).distinct().sorted()
        return rates.associateWith { rate ->
            resolutions(cameras.mapNotNull { camera -> rates(camera).minByOrNull { Math.abs(it - rate) }?.let { sizes(camera, it) } })
        }
    }

    /**
     * The choices for cameras giving [sizes] (one list per camera): each short side of
     * [RESOLUTIONS] some camera gives exactly, largest first, after [FULL_RESOLUTION] unless the
     * largest of those already gives every camera its largest size.
     */
    fun resolutions(sizes: List<List<Size>>): List<Resolution> {
        val sides = RESOLUTIONS.filter { side -> sizes.any { camera -> camera.any { it.shortSide == side } } }
        val largest = sizes.mapNotNull { camera -> camera.maxByOrNull { it.pixels } }
        val full = largest.any { it.shortSide > (sides.firstOrNull() ?: 0) }
        return (if (full) listOf(Resolution(FULL_RESOLUTION, largest.distinct())) else emptyList()) + sides.map { Resolution(it) }
    }

    /**
     * Of [sizes], the largest with a short side up to [resolution] (all of them for
     * [FULL_RESOLUTION]), else the smallest; null for none.
     */
    fun pick(sizes: List<Size>, resolution: Int): Size? =
        sizes.filter { resolution == FULL_RESOLUTION || it.shortSide <= resolution }.maxByOrNull { it.pixels }
            ?: sizes.minByOrNull { it.pixels }

    private fun characteristics(context: Context): List<Pair<String, CameraCharacteristics>> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        return try {
            manager.cameraIdList.map { it to manager.getCameraCharacteristics(it) }
        } catch (e: Exception) {
            DebugLog.w("Cameras: ${e.message}")
            emptyList()
        }
    }

    /** The rates the camera's auto exposure can hold (its ranges' tops), with a YUV size fast enough. */
    private fun rates(characteristics: CameraCharacteristics): List<Int> =
        rates(characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().map { it.upper }) {
            sizes(characteristics, it).isNotEmpty()
        }

    /** Of the tops of a camera's auto exposure ranges, those it [hasSizesAt], once each and in order. */
    fun rates(rangeTops: List<Int>, hasSizesAt: (Int) -> Boolean): List<Int> =
        rangeTops.filter { it in 1..MAX_FRAME_RATE }.distinct().sorted().filter(hasSizesAt)

    /** Even YUV sizes the camera gives at [rate]. */
    private fun sizes(characteristics: CameraCharacteristics, rate: Int): List<Size> {
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        return map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            .filter { it.width % 2 == 0 && it.height % 2 == 0 }
            .filter { map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it) <= 1_000_000_000L / rate }
            .map { Size(it.width, it.height) }
    }
}
