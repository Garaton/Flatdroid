package com.garathon.flatdroid.alpine.programs

import com.garathon.flatdroid.flatpak.Deployment
import com.garathon.flatdroid.flatpak.FlatpakRef
import java.io.File

/**
 * An Alpine program's root, in place of the installation's deployments ([com.garathon.flatdroid.launcher.LaunchPlanner]):
 * Alpine's `/usr` as the runtime and its `/lib`, `/bin`, `/sbin` and `/etc` beside it, an empty
 * `/app`, and Alpine's hard links' files in link2symlink's store one by one, each at the store
 * path Alpine's links name (the whole store holds every Flatpak's files). Flatpak's installation isn't in it. These folders stay writable
 * (proot has no read-only binds): [AlpineChanges].
 */
data class AlpineRoot(
    val app: Deployment,
    val runtime: Deployment,
    /** Alpine's `/lib`, `/bin` and `/sbin`: host to guest path. */
    val binds: List<Pair<String, String>>,
    /** Alpine's `/etc`. */
    val etc: String,
    /** Files in the store that Alpine's links lead to: host file to the (host) path the links name. */
    val l2sBinds: List<Pair<String, String>>,
    /** The program's arguments from its desktop file, before the developer setting's. */
    val args: List<String> = emptyList(),
    /** The Alpine rootfs these come from. */
    val rootfs: String = "",
    /** A stand-in bound over Alpine's `/usr/bin/bwrap` ([com.garathon.flatdroid.alpine.GuestFiles.NO_NAMESPACES_BWRAP]); null for none. */
    val bwrap: String? = null,
) {
    /**
     * Whether binding [guest] would make proot put a stand-in into Alpine itself: a path in one of
     * Alpine's folders that Alpine doesn't have (proot creates it for the run, then removes it,
     * which Alpine safe launch would report).
     */
    fun bindWouldChangeAlpine(guest: String): Boolean {
        val rel = guest.removePrefix("/")
        return DIRS.any { rel == it || rel.startsWith("$it/") } && !File(rootfs, rel).exists()
    }

    companion object {
        /** Alpine's folders in the root, relative to the rootfs; `usr` is the runtime. */
        val DIRS = listOf("usr", "lib", "bin", "sbin", "etc")

        /** Where Alpine has bwrap (Flatpak depends on it). */
        const val BWRAP = "/usr/bin/bwrap"

        /** [program]'s root from the Alpine at [rootfs]; [emptyApp] is an empty folder for `/app`. */
        fun of(
            program: AlpineProgram, rootfs: File, emptyApp: File, arch: String, branch: String,
            l2sBinds: List<Pair<String, String>>, bwrap: File? = null,
        ): AlpineRoot {
            val metadata = program.metadata(arch, branch)
            return AlpineRoot(
                app = Deployment(FlatpakRef(program.id, arch, branch), emptyApp, metadata, kind = "app", filesDir = emptyApp),
                runtime = Deployment(FlatpakRef(AlpineProgramIds.RUNTIME, arch, branch), rootfs, metadata, filesDir = File(rootfs, "usr")),
                binds = listOf("lib", "bin", "sbin").map { File(rootfs, it).path to "/$it" },
                etc = File(rootfs, "etc").path,
                l2sBinds = l2sBinds,
                args = program.command.drop(1),
                rootfs = rootfs.path,
                bwrap = bwrap?.path,
            )
        }
    }
}
