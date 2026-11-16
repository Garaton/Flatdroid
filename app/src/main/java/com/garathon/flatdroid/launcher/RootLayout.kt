package com.garathon.flatdroid.launcher

import com.garathon.flatdroid.proot.AlpineBinds
import com.garathon.flatdroid.proot.GuestPaths

/**
 * The links in an app's root, as Flatpak lays one out, where a bind isn't needed: proot checks
 * every bind on every path lookup, so each one left out makes the app's file access cheaper. The
 * root is rebuilt at every launch, so nothing here carries over from an earlier run.
 */
object RootLayout {
    /**
     * Path in the root (relative) to the link's target. Absolute targets are guest paths, which
     * proot resolves inside the root.
     */
    fun links(appId: String, usrLinks: List<String>, wholeDev: Boolean): Map<String, String> {
        val links = linkedMapOf<String, String>()
        for (name in usrLinks) links[name] = "usr/$name"
        links["var/run"] = "../run"
        links += varDirs(appId)
        // A whole /dev gets the host's bound over it instead.
        if (!wholeDev) for ((guest, target) in AlpineBinds.FD_LINKS) links[guest.removePrefix("/")] = target
        return links
    }

    /**
     * Flatpak's /var directories, which some apps' own launch scripts use: links into the app's
     * own data under `~/.var/app/<ID>`, which is bound already.
     */
    fun varDirs(appId: String): Map<String, String> {
        val varApp = "${GuestPaths.HOME}/.var/app/$appId"
        return linkedMapOf(
            "var/config" to "$varApp/config",
            "var/data" to "$varApp/data",
            "var/cache" to "$varApp/cache",
            "var/tmp" to "$varApp/cache/tmp",
        )
    }
}
