package com.garathon.flatdroid.alpine

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.system.Os
import com.garathon.flatdroid.proot.AlpineBinds
import com.garathon.flatdroid.proot.DevNodes
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.proot.ProcessResult
import com.garathon.flatdroid.proot.ProcessStopper
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.proot.ProotCommand
import com.garathon.flatdroid.proot.ProotPaths
import com.garathon.flatdroid.proot.ProotRunner
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.RootfsInstaller
import com.garathon.flatdroid.util.writeTextAtomically
import com.garathon.flatdroid.util.readTextUpTo
import java.io.File
import java.io.FileInputStream

/**
 * The Alpine rootfs and everything around it: where its files live, the files generated for it,
 * and running commands in it.
 *
 * Persistent: `files/alpine/rootfs` and `files/alpine/generated` (config bound into the guest).
 * Temporary, in cache: the guest's /tmp and /dev/shm, proot's own temporary files, downloads.
 */
class AlpineEnvironment private constructor(private val context: Context) {
    // Fully resolved paths only. Android hands out /data/user/0/<package>, which on some devices
    // resolves elsewhere; link2symlink writes PROOT_L2S_DIR into every link it creates, and proot
    // maps a link target back into the guest only when it starts with the rootfs's resolved path.
    // With the unresolved prefix, every hard link in Alpine dangled (gpg couldn't lock its keyring).
    private val filesDir = context.filesDir.canonicalFile

    /** For downloads and other files that are deleted once used. */
    val cacheDir: File = context.cacheDir.canonicalFile
    private val alpineDir = File(filesDir, "alpine")
    private val generatedDir = File(alpineDir, "generated")
    private val cacheAlpine = File(cacheDir, "alpine")
    private val libDir = File(context.applicationInfo.nativeLibraryDir).canonicalFile

    val installer = RootfsInstaller(alpineDir, cacheDir, AndroidTarFs)

    /** Alpine/Flathub architecture of the installed native libraries (lib/arm64 or lib/x86_64). */
    val arch: String = ARCH_BY_LIB_DIR[libDir.name] ?: error("Unsupported ABI directory: $libDir")

    fun isInstalled(): Boolean = installer.installedVersion() != null

    /** Everything temporary that belongs to Alpine: guest /tmp and /dev/shm, proot's files, downloads. */
    fun temporaryFiles(): List<File> =
        listOf(cacheAlpine, File(cacheDir, "proot-tmp"), File(cacheDir, "apk-cache")) +
            (cacheDir.listFiles { f -> f.name.startsWith("alpine-minirootfs-") }?.toList() ?: emptyList())

    /**
     * Runs [command] in Alpine as fake root. Generated files are refreshed first, since the
     * network (and so its DNS servers) may have changed since the last run. Cancelled or timed
     * out, it gets [interruptGraceMillis] to end on SIGINT; the default is long, since most
     * commands change Alpine or Flatpak's files (see ProcessStopper).
     */
    fun run(
        command: List<String>,
        timeoutSeconds: Long = 60,
        stdin: ByteArray? = null,
        extraBinds: List<Pair<String, String>> = emptyList(),
        interruptGraceMillis: Long = ProcessStopper.LENIENT_GRACE_MS,
        onLine: (String) -> Unit = {},
    ): ProcessResult {
        check(isInstalled()) { "The Alpine rootfs is not installed" }
        val invocation = ProotCommand.alpine(paths(), prepareBinds(extraBinds), command)
        return ProotRunner.run(invocation, alpineDir, timeoutSeconds, stdin, interruptGraceMillis = interruptGraceMillis, onLine = onLine)
    }

    /**
     * Starts [command] in Alpine as fake root and returns at once; the caller stops it. Used for
     * the terminal and the long-running display and audio helpers. [filter] is [Fdsec]'s; only
     * the terminal may pass null (its developer setting), every other start keeps a filter.
     */
    fun start(
        command: List<String>,
        extraBinds: List<Pair<String, String>> = emptyList(),
        /** As the app's own user IDs instead of root ([ProotCommand.alpine]'s ids). */
        asAppUser: Boolean = false,
        filter: Fdsec.Filter? = Fdsec.Filter(),
        onLine: (String) -> Unit = {},
    ): ProotProcess {
        check(isInstalled()) { "The Alpine rootfs is not installed" }
        val uid = Os.getuid()
        val gid = Os.getgid()
        val ids = if (asAppUser) "$uid:$gid" else "0:0"
        val binds = if (!asAppUser) extraBinds else extraBinds + listOf(
            write("passwd", GuestFiles.passwdWith(rootfsFile("etc/passwd"), uid, gid)).path to "/etc/passwd",
            write("group", GuestFiles.groupWith(rootfsFile("etc/group"), gid)).path to "/etc/group",
        )
        val invocation = ProotCommand.alpine(paths(), prepareBinds(binds), command, ids = ids, filter = filter)
        return ProotRunner.start(invocation, alpineDir, onLine = onLine)
    }

    /** The Flatpak user installation inside the rootfs. */
    val flatpakDir: File get() = File(installer.rootfs, "root/.local/share/flatpak")

    /** The resolv.conf every Alpine proot binds; kept in step with the network during a Flatpak task ([ResolvConfRefresher]). */
    val resolvConfFile: File get() = File(generatedDir, "resolv.conf")

    /** resolv.conf for the current network (its Private DNS provider's servers when it names one that answers). */
    fun resolvConf(): String = GuestFiles.resolvConf(NetworkDns.servers(context), NetworkDns.privateDnsServers(context))

    fun paths(): ProotPaths = ProotPaths(
        proot = File(libDir, "libproot.so").path,
        loader = File(libDir, "libproot-loader.so").path,
        tmpDir = dir(File(cacheDir, "proot-tmp")).path,
        rootfs = installer.rootfs.path,
        fdsec = File(libDir, Fdsec.LIBRARY).path,
    )

    private fun prepareBinds(extra: List<Pair<String, String>>): AlpineBinds {
        dir(generatedDir)
        val resolv = write("resolv.conf", resolvConf())
        val hosts = write("hosts", GuestFiles.hosts())

        val uname = Os.uname()
        val uptime = SystemClock.elapsedRealtime() / 1000
        val proc = GuestFiles.fakeProc(
            cpuCount = Runtime.getRuntime().availableProcessors(),
            kernelRelease = uname.release,
            kernelVersion = uname.version,
            uptimeSeconds = uptime,
            bootEpochSeconds = System.currentTimeMillis() / 1000 - uptime,
        )
        val fakeProc = linkedMapOf<String, String>()
        dir(File(generatedDir, "proc"))
        for ((guest, content) in proc) {
            if (hostCanRead(guest)) continue
            val name = guest.removePrefix("/proc/").replace('/', '_')
            fakeProc[guest] = write("proc/$name", content).path
        }

        val bwrap = write("bwrap", GuestFiles.BWRAP_STAND_IN).also { Os.chmod(it.path, 0x1ed) } // 0755

        val dev = DevNodes.forAlpine(DevNodes::hostHas)
        DevNodes.createMountPoints(installer.rootfs, dev + AlpineBinds.FD_LINKS.keys)

        return AlpineBinds(
            resolvConf = resolv.path,
            hosts = hosts.path,
            tmp = dir(File(cacheAlpine, "tmp")).path,
            shm = dir(File(cacheAlpine, "shm")).path,
            emptyDir = dir(File(generatedDir, "empty")).path,
            fakeProc = fakeProc,
            dev = dev,
            extra = extra + (bwrap.path to GuestPaths.BWRAP_STAND_IN),
        )
    }

    /** A small file of the rootfs, or empty when it can't be read. */
    private fun rootfsFile(path: String): String =
        runCatching { File(installer.rootfs, path).readTextUpTo() }.getOrNull().orEmpty()

    private fun write(name: String, content: String): File {
        val file = File(generatedDir, name)
        if (!file.isFile || file.readText() != content) file.writeTextAtomically(content, durable = false)
        return file
    }

    private fun dir(file: File): File = file.apply { mkdirs() }

    private fun hostCanRead(path: String): Boolean = try {
        FileInputStream(path).use { it.read() }
        true
    } catch (_: Exception) {
        false
    }

    companion object {
        private val ARCH_BY_LIB_DIR = mapOf("arm64" to "aarch64", "x86_64" to "x86_64")

        // Holds the application context, which lives as long as the process.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AlpineEnvironment? = null

        /** One per process: it holds only paths, all derived from the application context. */
        fun get(context: Context): AlpineEnvironment = instance ?: synchronized(this) {
            instance ?: AlpineEnvironment(context.applicationContext).also { instance = it }
        }
    }
}
