package com.garathon.flatdroid.proot

import android.system.ErrnoException
import android.system.Os
import java.io.File

/**
 * Which host /dev entries a root gets, instead of the whole of /dev. Android's /dev holds Binder
 * and other Android-only nodes, through which anything in a root could call Android's system
 * services as Flatdroid.
 */
object DevNodes {
    /** Every root: the basic character devices and pseudo-terminals. */
    val BASE = listOf("/dev/null", "/dev/zero", "/dev/full", "/dev/random", "/dev/urandom", "/dev/tty", "/dev/ptmx", "/dev/pts")

    /** Flatpak's `devices=` names and what each adds. */
    val GROUPS: Map<String, List<String>> = linkedMapOf(
        "dri" to listOf("/dev/dri"),
        "input" to listOf("/dev/input"),
        "kvm" to listOf("/dev/kvm"),
        "usb" to listOf("/dev/bus/usb"),
    )

    /** `devices=all`: every group above (the host's whole /dev only by the app's own setting). */
    const val ALL = "all"

    /** Android-only names never bound from these lists: Binder, system properties, init's sockets. */
    val DENIED = setOf("binder", "hwbinder", "vndbinder", "__properties__", "socket")

    /**
     * `devices=all` when devices are limited ([forApp]): no group. Not even the GPU: OpenGL renders
     * in software (`LIBGL_ALWAYS_SOFTWARE`), and the limited /sys lacks what Mesa needs to identify
     * a GPU node anyway, so it would only open the GPU driver's kernel interface to the app.
     */
    val LIMITED_ALL = emptyList<String>()

    /**
     * The /sys paths an app gets when devices are limited, instead of the whole of /sys: CPU and
     * NUMA information, which runtimes and apps read to size their thread pools and memory.
     */
    val LIMITED_SYS = listOf("/sys/devices/system/cpu", "/sys/devices/system/node")

    /**
     * Host paths (bound at the same guest path) for an app whose metadata asks for [devices].
     * With [limited], `all` gives only [LIMITED_ALL]; groups the metadata names stay.
     */
    fun forApp(devices: List<String>, exists: (String) -> Boolean, limited: Boolean = false): List<String> {
        val all = if (limited) LIMITED_ALL else GROUPS.keys.toList()
        val groups = (if (ALL in devices) all else emptyList()) + devices.filter { it in GROUPS }
        return select(BASE + groups.distinct().flatMap { GROUPS.getValue(it) }, exists)
    }

    /** Alpine's roots: the base set, except /dev/random, which Alpine gets from /dev/urandom. */
    fun forAlpine(exists: (String) -> Boolean): List<String> = select(BASE - "/dev/random", exists)

    /**
     * Whether the device has [path] in a form proot can bind. Uses stat(), as proot does: Android
     * may let an app see that a node exists but not stat it (`/dev/full` on some devices), and
     * proot then skips the bind with a warning.
     */
    fun hostHas(path: String): Boolean = try {
        Os.stat(path)
        true
    } catch (_: ErrnoException) {
        false
    }

    /** Whether [path] is a /dev path a root may get: under /dev, nothing denied, no `..`. */
    fun isAllowed(path: String): Boolean {
        if (!path.startsWith("/dev/") || ':' in path) return false
        val parts = path.removePrefix("/dev/").split('/')
        return parts.none { it.isEmpty() || it == "." || it == ".." || it in DENIED }
    }

    private fun select(paths: List<String>, exists: (String) -> Boolean) = paths.distinct().filter { isAllowed(it) && exists(it) }

    /**
     * Creates what proot binds [paths] over inside [root]: a directory where the host has one,
     * else an empty file. Nothing that already exists is touched.
     */
    fun createMountPoints(root: File, paths: List<String>) {
        for (path in paths) {
            val target = File(root, path.removePrefix("/"))
            if (target.exists()) continue
            target.parentFile?.mkdirs()
            if (File(path).isDirectory) target.mkdirs() else target.createNewFile()
        }
    }
}
