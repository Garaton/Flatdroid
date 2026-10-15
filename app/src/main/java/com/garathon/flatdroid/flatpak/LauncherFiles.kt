package com.garathon.flatdroid.flatpak

import com.garathon.flatdroid.alpine.GuestFiles
import java.io.File
import com.garathon.flatdroid.util.writeTextAtomically

/**
 * The /etc files every Flatpak root gets, which a runtime can't provide on Android: hosts,
 * machine-id, passwd and group (with the app's Android UID). Written at setup, in private
 * storage outside the Alpine rootfs; the launcher binds them into each root. resolv.conf is not
 * here: it follows the current network, so it is generated for every launch.
 *
 * Planned with the launcher: call [write] before every launch, and bind a copy of the machine-id,
 * since binds are writable and one Flatpak could otherwise change what later ones see.
 *
 * Kept by a reset that keeps Flatpaks (apps may key data to the machine-id), deleted by one that
 * wipes everything.
 */
class LauncherFiles(val dir: File, private val uid: Int, private val gid: Int) {
    val hosts get() = File(dir, "hosts")
    val machineId get() = File(dir, "machine-id")
    val passwd get() = File(dir, "passwd")
    val group get() = File(dir, "group")

    fun write() {
        hosts.writeTextAtomically(GuestFiles.hosts())
        // The machine-id identifies this installation: generated once, never replaced.
        if (!machineId.isFile) machineId.writeTextAtomically(GuestFiles.machineId())
        passwd.writeTextAtomically(GuestFiles.passwd(uid, gid))
        group.writeTextAtomically(GuestFiles.group(gid))
    }
}
