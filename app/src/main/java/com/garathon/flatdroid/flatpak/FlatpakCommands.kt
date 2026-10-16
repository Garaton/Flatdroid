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

    // [id] must pass InstalledApps.isValidId, so it can't be read as an option.
    fun install(id: String) = listOf(FLATPAK, "--user", "install", "-y", "flathub", checked(id))
    fun uninstall(id: String) = listOf(FLATPAK, "--user", "uninstall", "-y", checked(id))
    val update = listOf(FLATPAK, "--user", "update", "-y")
    /** Removes runtimes and extensions no installed app uses any more. */
    val uninstallUnused = listOf(FLATPAK, "--user", "uninstall", "-y", "--unused")

    /**
     * Makes the repository consistent after a removal that stopped part way: deletes the remote
     * refs (`flathub:app/…`, `flathub:runtime/…`) of anything not installed (installed refs also
     * have a `deploy/…` ref), then every object no ref points to. A half-removed runtime's commit is
     * otherwise kept by its remote ref, and installing it again fails instead of downloading it.
     */
    val cleanRepository = listOf(
        "/bin/sh", "-c",
        """
        repo=/root/.local/share/flatpak/repo
        refs=$(/usr/bin/ostree --repo=${'$'}repo refs) || exit 1
        for ref in ${'$'}refs; do
            case ${'$'}ref in
                *:app/*|*:runtime/*)
                    printf '%s\n' "${'$'}refs" | grep -qxF "deploy/${'$'}{ref#*:}" ||
                        /usr/bin/ostree --repo=${'$'}repo refs --delete "${'$'}ref" || exit 1 ;;
            esac
        done
        exec /usr/bin/ostree --repo=${'$'}repo prune --refs-only
        """.trimIndent(),
    )

    private fun checked(id: String): String {
        require(InstalledApps.isValidId(id)) { "Not a Flatpak app ID: $id" }
        return id
    }
}
