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

    /**
     * Vendor GPU runtimes Flatpak would add next to an app's runtime: drivers for host GPUs
     * (NVIDIA's GL) and hardware video decoding (VAAPI). Flatdroid renders in software, so they
     * are masked unless a developer setting allows them. Not `GL.default`: that is Mesa, which
     * does the software OpenGL.
     */
    val GPU_RUNTIME_PATTERNS = listOf(
        "org.freedesktop.Platform.GL.nvidia*",
        "org.freedesktop.Platform.GL32.nvidia*",
        "org.freedesktop.Platform.VAAPI.*",
    )

    /** Keeps refs matching [pattern] from being installed or updated (repeating it is harmless). */
    fun mask(pattern: String) = listOf(FLATPAK, "--user", "mask", pattern)
    /** Fails when no such mask exists. */
    fun unmask(pattern: String) = listOf(FLATPAK, "--user", "mask", "--remove", pattern)

    /**
     * Uninstalls installed runtimes whose ID matches one of [patterns] (shell globs, as for
     * [mask]). Masking only stops new installs and updates, and removing unused runtimes keeps
     * these, since they count as extensions of the runtime they belong to.
     */
    fun uninstallMatching(patterns: List<String>) = listOf(
        "/bin/sh", "-c",
        """
        refs=$($FLATPAK --user list --runtime --columns=ref) || exit 1
        for ref in ${'$'}refs; do
            for pattern in "${'$'}@"; do
                case "${'$'}{ref%%/*}" in
                    ${'$'}pattern) $FLATPAK --user uninstall -y --noninteractive "${'$'}ref" || exit 1; break ;;
                esac
            done
        done
        """.trimIndent(),
        "sh",
    ) + patterns

    // [id] must pass InstalledApps.isValidId, so it can't be read as an option.
    fun install(id: String) = listOf(FLATPAK, "--user", "install", "-y", "flathub", checked(id))
    fun uninstall(id: String) = listOf(FLATPAK, "--user", "uninstall", "-y", checked(id))
    val update = listOf(FLATPAK, "--user", "update", "-y")
    /** Re-deploys [refs] (IDs or full refs) from Flathub, replacing their files. */
    fun reinstall(refs: List<String>) = listOf(FLATPAK, "--user", "install", "--reinstall", "-y", "flathub") + refs
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
