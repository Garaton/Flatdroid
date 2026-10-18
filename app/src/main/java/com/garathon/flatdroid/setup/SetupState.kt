package com.garathon.flatdroid.setup

import androidx.annotation.StringRes
import com.garathon.flatdroid.R
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.StringWriter
import java.util.Properties

/** First-run setup, in order. Every step can be repeated safely. */
enum class SetupStep(@param:StringRes val title: Int) {
    ROOTFS(R.string.setup_step_rootfs),
    PACKAGES(R.string.setup_step_packages),
    FLATHUB(R.string.setup_step_flathub),
    CONFIG_FILES(R.string.setup_step_files),
    /** Applies the settings the user chose before setup. */
    SETTINGS(R.string.setup_step_settings),
}

/** What setup has finished in this installation of Alpine. */
data class SetupState(
    val completed: Set<SetupStep> = emptySet(),
    /** The user chose their settings (kept separately) before this setup started. */
    val settingsChosen: Boolean = false,
) {
    val next: SetupStep? get() = SetupStep.entries.firstOrNull { it !in completed }
    val isComplete: Boolean get() = next == null
}

/** Keeps [SetupState] in a small properties file, replaced atomically on every save. */
class SetupStateStore(private val file: File) {
    fun load(): SetupState {
        if (!file.isFile) return SetupState()
        val props = Properties().apply { file.inputStream().use { load(it) } }
        val names = props.getProperty(KEY_COMPLETED, "").split(',').filter { it.isNotEmpty() }
        return SetupState(
            completed = SetupStep.entries.filter { it.name in names }.toSet(),
            settingsChosen = props.getProperty(KEY_SETTINGS_CHOSEN) == "true",
        )
    }

    fun save(state: SetupState) {
        val props = Properties()
        props.setProperty(KEY_COMPLETED, SetupStep.entries.filter { it in state.completed }.joinToString(",") { it.name })
        if (state.settingsChosen) props.setProperty(KEY_SETTINGS_CHOSEN, "true")
        file.writeTextAtomically(StringWriter().also { props.store(it, null) }.toString())
    }

    fun clear() {
        file.delete()
    }

    private companion object {
        const val KEY_COMPLETED = "completed"
        const val KEY_SETTINGS_CHOSEN = "settingsChosen"
    }
}
