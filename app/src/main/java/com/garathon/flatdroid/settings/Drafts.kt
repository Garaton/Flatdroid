package com.garathon.flatdroid.settings

import com.garathon.flatdroid.alpine.GuestFiles

/**
 * Flatdroid's settings while they are being edited across the settings pages: the settings
 * themselves plus the fields typed as text. Nothing is saved until [toSettings] is applied.
 */
data class UserSettingsDraft(
    val settings: UserSettings,
    /** Flatpak languages: the device's (true) or all (false); [toSettings] turns it into the saved value. */
    val onlyMyLanguages: Boolean,
    val customFrameRate: String,
    val audioLatency: String,
) {
    /** Empty means not set; anything else must be a rate Xvnc takes. */
    private val customFrameRateValue: Int? get() = customFrameRate.toIntOrNull()?.takeIf { it in UserSettings.CUSTOM_FRAME_RATES }
    private val audioLatencyValue: Int? get() = audioLatency.toIntOrNull()?.takeIf { it in UserSettings.AUDIO_LATENCIES_MS }

    val customFrameRateValid: Boolean get() = customFrameRate.isEmpty() || customFrameRateValue != null
    val audioLatencyValid: Boolean get() = audioLatency.isEmpty() || audioLatencyValue != null
    val valid: Boolean get() = customFrameRateValid && audioLatencyValid

    /** The settings to save, with the device's languages as Flatpak's value ([deviceLanguages]); null while a typed field is invalid. */
    fun toSettings(deviceLanguages: String): UserSettings? {
        if (!valid) return null
        return settings.copy(
            flatpakLanguages = if (onlyMyLanguages) deviceLanguages else GuestFiles.ALL_LANGUAGES,
            customFrameRate = customFrameRateValue,
            customAudioLatencyMs = audioLatencyValue,
        )
    }

    companion object {
        fun of(settings: UserSettings) = UserSettingsDraft(
            settings = settings,
            onlyMyLanguages = settings.onlyMyLanguages,
            customFrameRate = settings.customFrameRate?.toString().orEmpty(),
            audioLatency = settings.customAudioLatencyMs?.toString().orEmpty(),
        )
    }
}

/**
 * One app's settings while they are being edited across its settings pages. The settings keep
 * their overrides (null follows the default); a page that shows the value in effect writes null
 * back when the value matches the default.
 */
data class AppSettingsDraft(
    val settings: AppSettings,
    val width: String,
    val height: String,
    val environment: String,
    val launchArgs: String,
) {
    private fun size(text: String) = text.toIntOrNull()?.takeIf { it in AppSettings.MIN_SIZE..AppSettings.MAX_SIZE }

    val widthValid: Boolean get() = size(width) != null
    val heightValid: Boolean get() = size(height) != null
    private val environmentValue: Map<String, String>? get() = EnvironmentText.parse(environment)
    val environmentValid: Boolean get() = environmentValue != null

    /** Typed sizes matter only for a custom size; the environment only with [developerOptions]. */
    fun valid(developerOptions: Boolean): Boolean =
        (settings.resolution != Resolution.CUSTOM || (widthValid && heightValid)) && (!developerOptions || environmentValid)

    /**
     * The settings to save; null while a field that counts is invalid. Valid typed values are kept
     * even while another size is chosen. Developer settings are written only with [developerOptions].
     */
    fun toSettings(developerOptions: Boolean): AppSettings? {
        if (!valid(developerOptions)) return null
        return settings.copy(
            customWidth = size(width) ?: settings.customWidth,
            customHeight = size(height) ?: settings.customHeight,
            environment = if (developerOptions) environmentValue.orEmpty() else settings.environment,
            launchArgs = if (developerOptions) LaunchArgs.parse(launchArgs) else settings.launchArgs,
        )
    }

    companion object {
        fun of(settings: AppSettings) = AppSettingsDraft(
            settings = settings,
            width = settings.customWidth.toString(),
            height = settings.customHeight.toString(),
            environment = EnvironmentText.format(settings.environment),
            launchArgs = LaunchArgs.format(settings.launchArgs),
        )
    }
}
