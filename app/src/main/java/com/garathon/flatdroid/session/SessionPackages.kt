package com.garathon.flatdroid.session

import android.content.Context
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.setup.AlpineSetup
import com.garathon.flatdroid.setup.ApkProgress
import com.garathon.flatdroid.setup.ApkSummary
import com.garathon.flatdroid.setup.PackageGroup
import com.garathon.flatdroid.setup.rootfs.AndroidTarFs
import com.garathon.flatdroid.setup.rootfs.deleteTree

/** Alpine packages a session needs, added before it starts ([PackageGroup]). */
object SessionPackages {
    /** Adding a group: a slow connection, but apk itself doesn't hang. */
    private const val TIMEOUT_SECONDS = 30 * 60L

    /** The first group [needed] that isn't in Alpine yet: the user is asked about it ([SessionState.PackagesOffer]). */
    fun missing(context: Context, needed: List<PackageGroup>): PackageGroup? {
        val rootfs = AlpineEnvironment.get(context).installer.rootfs
        return needed.firstOrNull { !it.addedIn(rootfs) }
    }

    /**
     * Adds [group] to Alpine for the session [sessionId], after the user agreed. Flatpak tasks wait
     * while a session is starting, so no other apk runs meanwhile.
     */
    fun add(context: Context, sessionId: String, group: PackageGroup) {
        val env = AlpineEnvironment.get(context)
        val cache = env.apkCacheDir.apply { mkdirs() }
        val command = AlpineSetup.apkCommand(listOf("add") + group.packages(PackageGroup.ANDROID_MONOSPACE.isFile))
        SessionController.log("[flatdroid] $ ${command.joinToString(" ")}")
        val preparing = context.getString(if (group == PackageGroup.X11) R.string.packages_adding_x11 else R.string.packages_adding_terminal)
        SessionController.preparing(sessionId, preparing, null)
        val lines = mutableListOf<String>()
        try {
            val result = env.run(command, TIMEOUT_SECONDS, extraBinds = listOf(cache.path to AlpineSetup.APK_CACHE)) { line ->
                lines += line
                SessionController.log(line)
                ApkProgress.parse(line)?.let { (done, total) -> SessionController.preparing(sessionId, preparing, done.toFloat() / total) }
            }
            val summary = ApkSummary.parse(lines)
            if (result.timedOut || result.exitCode != 0 || !group.addedIn(env.installer.rootfs)) {
                val last = summary.errors.firstOrNull() ?: summary.line ?: lines.lastOrNull { it.isNotBlank() } ?: "exit ${result.exitCode}"
                throw LaunchException(context.getString(R.string.packages_failed, last))
            }
        } finally {
            AndroidTarFs.deleteTree(cache)
        }
    }
}
