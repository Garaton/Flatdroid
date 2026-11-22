package com.garathon.flatdroid.session

import android.content.Context
import android.os.Build
import android.os.FileObserver
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.camera.CameraScript
import com.garathon.flatdroid.launcher.AppEnding
import com.garathon.flatdroid.launcher.ProotExit
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.session.helpers.SessionBus
import com.garathon.flatdroid.session.helpers.SessionScript
import com.garathon.flatdroid.session.helpers.WaylandVnc
import com.garathon.flatdroid.setup.rootfs.AndroidTarFs
import com.garathon.flatdroid.setup.rootfs.deleteTree
import com.garathon.flatdroid.storage.AppData
import com.garathon.flatdroid.util.io.readTextUpTo
import com.garathon.flatdroid.util.io.writeAtomically
import com.garathon.flatdroid.util.io.writeTextAtomically
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The part of a session every target shares (a Flatpak app, Alpine's shell): the helpers in Alpine
 * ([SessionScript]: the display always, PulseAudio, the session bus, PipeWire and a terminal as the
 * target asks), their files, and waiting for them. One per session; [stop] ends the helpers.
 */
class SessionHelpers(private val context: Context, private val env: AlpineEnvironment = AlpineEnvironment.get(context)) {
    val sessionDir: File = AppData.sessionDir(context)
    val x11Dir = File(sessionDir, "x11")
    val pulseDir = File(sessionDir, "pulse")
    val vncDir = File(sessionDir, "vnc")
    val waylandDir = File(sessionDir, "wayland")
    // The daemon's socket stays among the helpers; an app's root gets only the proxy's directory.
    val busDaemonDir = File(sessionDir, "dbus")
    val busProxyDir = File(sessionDir, "bus")
    val pipewireDir = File(sessionDir, "pipewire")

    /** fdsec refused to start a command in one of the session's proots, so nothing ran in it. */
    val filterRefused = AtomicBoolean(false)
    private val started = CountDownLatch(1)
    /** The helpers' proot, once started. */
    var process: ProotProcess? = null
        private set

    /** What the helpers' files say ([writeFiles]). */
    data class Files(
        /** The external viewer's password; null for Flatdroid's own viewer. */
        val password: String? = null,
        /** wayvnc asks an external viewer to log in. */
        val wayvncLogin: Boolean = false,
        val titleBars: Boolean = true,
        val darkDecorations: Boolean = false,
        /** PulseAudio's script ([com.garathon.flatdroid.session.helpers.PulseScript]); null without sound. */
        val pulseScript: String? = null,
        /** The session bus proxy's arguments ([SessionBus.proxyArgs]); null without a bus. */
        val busArgs: List<String>? = null,
        /** The terminal's window title; null without a terminal. */
        val terminalTitle: String? = null,
        /** The terminal's app may copy to the clipboard. */
        val clipboard: Boolean = false,
    )

    /** The helpers' files for this session, fresh: old sockets and configuration are removed first. */
    fun writeFiles(files: Files) {
        for (dir in listOf(x11Dir, pulseDir, vncDir, waylandDir, busDaemonDir, busProxyDir, pipewireDir)) {
            AndroidTarFs.deleteTree(dir)
            dir.mkdirs()
        }
        // The compositor's XDG_RUNTIME_DIR: wlroots wants it private.
        Os.chmod(waylandDir.path, 0x1c0) // 0700
        if (files.password != null) {
            File(vncDir, "passwd").apply {
                writeBytes(VncPassword.file(files.password))
                Os.chmod(path, 0x180) // 0600
            }
        }
        val wayvncConfig = File(vncDir, File(SessionScript.GUEST_WAYVNC_CONFIG).name)
        // Only an external viewer logs in; Flatdroid's own uses the private socket.
        if (files.wayvncLogin && files.password != null) {
            wayvncConfig.writeText(WaylandVnc.config(files.password))
            Os.chmod(wayvncConfig.path, 0x180) // 0600
        }
        File(sessionDir, SCRIPT).writeTextAtomically(SessionScript.TEXT, durable = false)
        File(sessionDir, OPENBOX_RC).writeTextAtomically(GuestFiles.openboxRc(files.titleBars), durable = false)
        val themerc = GuestFiles.themerc(files.darkDecorations)
        File(sessionDir, OPENBOX_THEMERC).writeTextAtomically(themerc, durable = false)
        val labwcDir = File(vncDir, File(SessionScript.GUEST_LABWC_DIR).name).apply { mkdirs() }
        File(labwcDir, "rc.xml").writeTextAtomically(GuestFiles.labwcRc(files.titleBars), durable = false)
        File(labwcDir, GuestFiles.LABWC_THEME_OVERRIDE).writeTextAtomically(themerc, durable = false)
        if (files.terminalTitle != null) {
            File(vncDir, File(SessionScript.GUEST_FOOT_CONFIG).name).writeTextAtomically(GuestFiles.footIni(files.terminalTitle, files.clipboard), durable = false)
            File(vncDir, File(SessionScript.GUEST_TERMINAL_FONTS).name).writeTextAtomically(GuestFiles.terminalFonts(SessionScript.GUEST_ANDROID_FONTS), durable = false)
        }
        val pulseFile = File(sessionDir, PULSE_SCRIPT)
        if (files.pulseScript != null) pulseFile.writeTextAtomically(files.pulseScript, durable = false) else pulseFile.delete()
        File(sessionDir, BUS_CONFIG).writeTextAtomically(SessionBus.CONFIG, durable = false)
        File(sessionDir, PROXY_ARGS).writeAtomically(durable = false) { it.write(SessionBus.argsFile(files.busArgs.orEmpty())) }
    }

    /**
     * Starts [command] ([SessionScript.command]) behind [filter], with PulseAudio's script bound when
     * there is [sound] and Android's fonts for a [terminal]; [onLine] also gets each line of the
     * helpers' output, before it is logged.
     */
    fun start(command: List<String>, sound: Boolean, filter: Fdsec.Filter, terminal: Boolean = false, onLine: (String) -> Unit = {}): ProotProcess {
        val binds = listOf(
            x11Dir.path to GuestPaths.X11_DIR,
            pulseDir.path to GuestPaths.PULSE_DIR,
            vncDir.path to SessionScript.GUEST_VNC_DIR,
            waylandDir.path to GuestPaths.WAYLAND_DIR,
            File(sessionDir, SCRIPT).path to SessionScript.GUEST_SCRIPT,
            File(sessionDir, OPENBOX_RC).path to SessionScript.GUEST_OPENBOX_RC,
            File(sessionDir, OPENBOX_THEMERC).path to SessionScript.GUEST_OPENBOX_THEMERC,
            busDaemonDir.path to SessionBus.GUEST_DAEMON_DIR,
            busProxyDir.path to SessionBus.GUEST_PROXY_DIR,
            pipewireDir.path to CameraScript.GUEST_PIPEWIRE_DIR,
            File(sessionDir, BUS_CONFIG).path to SessionBus.GUEST_CONFIG,
            File(sessionDir, PROXY_ARGS).path to SessionBus.GUEST_PROXY_ARGS,
        ) + (if (sound) listOf(File(sessionDir, PULSE_SCRIPT).path to SessionScript.GUEST_PULSE_SCRIPT) else emptyList()) +
            // The terminal's fonts: Android's own, read-only on the device (an app can't list the helpers' files).
            (if (terminal && ANDROID_FONTS.isDirectory) listOf(ANDROID_FONTS.path to SessionScript.GUEST_ANDROID_FONTS) else emptyList())
        return env.start(command, binds, asAppUser = true, filter = filter) { line ->
            if (Fdsec.refused(line)) filterRefused.set(true)
            if (line == SessionScript.DISPLAY_READY) started.countDown()
            onLine(line)
            SessionController.log("[display] $line")
        }.also { process = it }
    }

    /** Waits for the display's socket ([wayland] or X11) and for the script to have started every helper. */
    fun awaitDisplay(wayland: Boolean) {
        val helpers = checkNotNull(process)
        try {
            val display = if (wayland) File(waylandDir, GuestPaths.WAYLAND_SOCKET) else File(x11Dir, SessionScript.X_SOCKET)
            waitForFile(display, DISPLAY_TIMEOUT_MS, context.getString(R.string.launch_display_failed))
            val end = SystemClock.uptimeMillis() + DISPLAY_TIMEOUT_MS
            while (!started.await(HELPERS_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (!helpers.isAlive || SystemClock.uptimeMillis() > end) throw LaunchException(context.getString(R.string.launch_display_failed))
            }
        } catch (e: LaunchException) {
            throw if (filterRefused.get()) LaunchException(context.getString(R.string.launch_filter_refused)) else e
        }
    }

    /**
     * The terminal's name (`/dev/pts/N`), once foot's command has given it up ([Fdsec.holdTerminal]).
     * What that command says when it fails (`.error` beside the name) is logged and shown.
     */
    fun awaitTerminal(): String {
        val named = File(vncDir, File(SessionScript.GUEST_TTY).name)
        val error = File(vncDir, named.name + ".error")
        val failed = context.getString(R.string.launch_terminal_failed)
        if (!waitForFile(listOf(named, error), TERMINAL_TIMEOUT_MS, null) || !named.exists()) {
            val detail = error.readTextUpTo(512)?.trim()?.filter { it >= ' ' }
            if (detail != null) SessionController.log("[display] $detail")
            throw LaunchException(if (detail != null) "$failed ($detail)" else failed)
        }
        return named.readTextUpTo(64)?.trim()?.takeIf(Fdsec::isTerminal) ?: throw LaunchException(failed)
    }

    /**
     * Waits until [file] exists, woken by a [FileObserver] on its directory as soon as it is
     * created. Fails with [error] (or returns false when it is null) on timeout or when the
     * helpers have exited, which is checked every [HELPER_CHECK_MS].
     */
    fun waitForFile(file: File, timeoutMs: Long, error: String?): Boolean = waitForFile(listOf(file), timeoutMs, error)

    /** [waitForFile] for the first of [files] (all in one directory) to exist. */
    fun waitForFile(files: List<File>, timeoutMs: Long, error: String?): Boolean {
        val helpers = checkNotNull(process)
        val created = CountDownLatch(1)
        val names = files.map { it.name }.toSet()
        val observer = directoryObserver(files.first().parentFile!!) { name -> if (name in names) created.countDown() }
        observer.startWatching()
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (files.none { it.exists() }) {
                if (!helpers.isAlive || System.currentTimeMillis() > deadline) {
                    if (error != null) throw LaunchException(error)
                    return false
                }
                created.await(HELPER_CHECK_MS, TimeUnit.MILLISECONDS)
            }
            return true
        } finally {
            observer.stopWatching()
        }
    }

    /**
     * Says how a terminal's program ended on its terminal [tty], then waits for a key (Enter, where
     * the program left the terminal reading lines), for the window to close, or for a Stop. False
     * when the terminal is gone already: its window was closed.
     */
    fun holdTerminal(tty: String, ending: AppEnding): Boolean {
        val helpers = checkNotNull(process)
        val text = when (ending) {
            is AppEnding.Failed -> context.getString(R.string.terminal_exited, ending.code)
            is AppEnding.Crashed -> context.getString(R.string.terminal_ended, ProotExit.signalName(ending.signal))
            is AppEnding.Ended -> context.getString(R.string.terminal_ended, ProotExit.signalName(ending.signal))
        }
        val fd = try {
            Os.open(tty, OsConstants.O_RDWR or OsConstants.O_NOCTTY or OsConstants.O_CLOEXEC, 0)
        } catch (e: ErrnoException) {
            return false
        }
        try {
            val bytes = "\r\n[$text]\r\n".toByteArray()
            try {
                Os.write(fd, bytes, 0, bytes.size)
            } catch (e: ErrnoException) {
                return false
            }
            val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
            while (helpers.isAlive && !Thread.currentThread().isInterrupted) {
                if (Os.poll(arrayOf(poll), HOLD_POLL_MS) > 0) break
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
        } catch (e: ErrnoException) {
            // Closed meanwhile.
        } finally {
            runCatching { Os.close(fd) }
        }
        return true
    }

    /** Ends the helpers. */
    fun stop() {
        process?.stop()
    }

    /** Calls [onCreated] with the name of each entry created in [dir]. */
    private fun directoryObserver(dir: File, onCreated: (String) -> Unit): FileObserver {
        val mask = FileObserver.CREATE or FileObserver.MOVED_TO
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            object : FileObserver(dir, mask) {
                override fun onEvent(event: Int, path: String?) { path?.let(onCreated) }
            }
        } else {
            @Suppress("DEPRECATION")
            object : FileObserver(dir.path, mask) {
                override fun onEvent(event: Int, path: String?) { path?.let(onCreated) }
            }
        }
    }

    companion object {
        /** Android's own fonts, readable by every app. */
        val ANDROID_FONTS = File("/system/fonts")
        const val DISPLAY_TIMEOUT_MS = 30_000L
        const val HELPERS_POLL_MS = 50L
        const val HELPER_CHECK_MS = 250L
        /** foot starting and naming its terminal (it reads Android's fonts the first time). */
        const val TERMINAL_TIMEOUT_MS = 30_000L
        /** How often [holdTerminal] looks whether the session was stopped. */
        private const val HOLD_POLL_MS = 250
        // The helpers' files in the session folder (beside the folders above).
        private const val SCRIPT = "session.sh"
        private const val OPENBOX_RC = "openbox-rc.xml"
        private const val OPENBOX_THEMERC = "openbox-themerc"
        private const val PULSE_SCRIPT = "pulse.pa"
        private const val BUS_CONFIG = "dbus.conf"
        private const val PROXY_ARGS = "proxy.args"
    }
}
