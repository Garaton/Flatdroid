package com.garathon.flatdroid.flatpak

/** Flatpak commands run in Alpine, all on the user installation (/root/.local/share/flatpak). */
object FlatpakCommands {
    const val FLATPAK = "/usr/bin/flatpak"
    const val FLATHUB_REPO = "https://dl.flathub.org/repo/flathub.flatpakrepo"

    /** Flathub's signing key as remote-add stores it, relative to the rootfs. */
    const val FLATHUB_KEY = "root/.local/share/flatpak/repo/flathub.trustedkeys.gpg"

    val addFlathub = listOf(FLATPAK, "--user", "remote-add", "--if-not-exists", "flathub", FLATHUB_REPO)
    val deleteFlathub = listOf(FLATPAK, "--user", "remote-delete", "--force", "flathub")

    fun setLanguages(languages: String) = listOf(FLATPAK, "--user", "config", "--set", "languages", languages)
}
