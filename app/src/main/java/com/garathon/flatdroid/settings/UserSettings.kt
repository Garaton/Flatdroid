package com.garathon.flatdroid.settings

import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.flatpak.FlatpakCommands
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.StringWriter
import java.util.Properties

/**
 * Choices made in Flatdroid's settings. The settings screen asks for them once setup is done,
 * and they can be changed later. Kept in `files/settings.properties`, which is backed up (end-to-end
 * encrypted only) and survives a reset that keeps Flatpaks.
 */
data class UserSettings(
    /** Flatpak's `languages` value: the device's languages plus English, or [GuestFiles.ALL_LANGUAGES]. */
    val flatpakLanguages: String,
) {
    val onlyMyLanguages: Boolean get() = flatpakLanguages != GuestFiles.ALL_LANGUAGES

    /** The commands that apply these settings in Alpine, in order. */
    fun commands(): List<List<String>> = listOf(FlatpakCommands.setLanguages(flatpakLanguages))

    companion object {
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
        return UserSettings(flatpakLanguages = languages)
    }

    fun save(settings: UserSettings) {
        val props = Properties()
        props.setProperty(KEY_LANGUAGES, settings.flatpakLanguages)
        file.writeTextAtomically(StringWriter().also { props.store(it, null) }.toString())
    }

    private companion object {
        const val KEY_LANGUAGES = "flatpak.languages"
    }
}
