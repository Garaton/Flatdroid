package com.garathon.flatdroid.session

import android.content.Context
import android.system.Os
import com.garathon.flatdroid.FlatdroidApplication
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.launcher.AppEnding
import com.garathon.flatdroid.launcher.ProotExit
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.GuestPaths
import com.garathon.flatdroid.proot.ProotProcess
import com.garathon.flatdroid.session.bus.AndroidAppearance
import com.garathon.flatdroid.session.helpers.SessionScript
import com.garathon.flatdroid.session.helpers.WaylandVnc
import com.garathon.flatdroid.settings.AppDefaults
import com.garathon.flatdroid.settings.AppSettings
import com.garathon.flatdroid.settings.FlatpakTheme
import com.garathon.flatdroid.settings.Resampler
import com.garathon.flatdroid.settings.SecuritySettings
import com.garathon.flatdroid.settings.UserSettings
import com.garathon.flatdroid.setup.PackageGroup
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.viewer.ViewerSize
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Who Alpine's shell runs as (a developer setting). */
enum class AlpineShellUser(val key: String) {
    /** proot's fake root: apk and package changes work. */
    ROOT("root"),
    /** Android's user for Flatdroid, as the helpers run (and an Alpine program would): no fake root. */
    APP_USER("app"),
}

/**
 * Alpine's shell in a terminal window: a session with only the display and foot
 * ([SessionHelpers]), and a login shell in Alpine's root on foot's terminal, as [AlpineShellUser]
 * says. The shell sees all of Alpine, Flatpak's installation included. fdsec hands it the
 * terminal, behind its filter unless the developer setting turns it off. The session's Wayland
 * socket is in its root too, so a graphical Alpine program started from it shows in the session
 * (verify). Blocking; interrupting the thread ends the shell and the helpers.
 */
class AlpineShell(private val context: Context) {
    private val env = AlpineEnvironment.get(context)
    private val helpers = SessionHelpers(context, env)

    /** How the shell the last [run] ran ended when not as it should; null when it closed normally or was stopped. */
    var ending: AppEnding? = null
        private set

    /**
     * Runs the shell as [userChoice] (null: the saved setting) and returns when it exits; false
     * without starting when foot must be added first (the offer is then in [SessionController]).
     */
    fun run(userChoice: AlpineShellUser? = null): Boolean {
        ending = null
        if (!FlatdroidApplication.nonDumpable) throw LaunchException(context.getString(R.string.launch_dumpable))
        SessionPackages.missing(context, listOf(PackageGroup.TERMINAL))?.let { group ->
            SessionController.offerPackages(SessionState.PackagesOffer(ID, group))
            return false
        }
        val user = SetupController.settings(context).load()
        val security = user?.security ?: SecuritySettings()
        // No settings of its own: an app's defaults (size, interface size, clipboard).
        val app = AppSettings(defaults = user?.appDefaults ?: AppDefaults())
        val externalViewer = user?.externalViewer == true
        val password = if (externalViewer) VncPassword.random(length = WaylandVnc.PASSWORD_LENGTH) else null
        val (viewWidth, viewHeight) = ViewerSize.expected(context, bar = !externalViewer)
        val (width, height) = app.size(viewWidth, viewHeight)
        val frameRate = user?.frameRate ?: UserSettings.DEFAULT_FRAME_RATE
        SessionController.starting(ID, context.getString(R.string.session_starting_display))
        helpers.writeFiles(
            SessionHelpers.Files(
                password, wayvncLogin = externalViewer, titleBars = user?.windowTitleBars ?: true,
                darkDecorations = (user?.flatpakTheme ?: FlatpakTheme.SYSTEM).isDark(AndroidAppearance.androidDark(context.resources.configuration)),
                terminalTitle = context.getString(R.string.alpine_shell), clipboard = app.sharesClipboard,
            ),
        )
        var shell: ProotProcess? = null
        try {
            helpers.start(
                SessionScript.command(
                    "${width}x$height", frameRate, sound = false, Resampler.FAST, sessionBus = false,
                    clipboard = app.sharesClipboard, wayland = true, externalViewer = externalViewer,
                    interfaceSize = app.interfaceSize, memfd = SessionScript.kernelHasMemfd(Os.uname().release), terminal = true,
                ),
                sound = false,
                Fdsec.Filter(extraSyscalls = security.extraSyscallFilter),
                terminal = true,
                x11 = false,
            )
            helpers.awaitDisplay(wayland = true)
            val tty = helpers.awaitTerminal()
            val signal = AtomicInteger(0)
            shell = env.start(
                LOGIN_SHELL,
                listOf(helpers.waylandDir.path to GuestPaths.WAYLAND_DIR),
                asAppUser = (userChoice ?: user?.alpineShellUser) == AlpineShellUser.APP_USER,
                filter = Fdsec.Filter(extraSyscalls = security.extraSyscallFilter).takeIf { user?.filterShell ?: true },
                tty = tty,
                environment = mapOf(
                    "TERM" to TERM, "COLORTERM" to "truecolor",
                    "WAYLAND_DISPLAY" to "${GuestPaths.WAYLAND_DIR}/${GuestPaths.WAYLAND_SOCKET}",
                ),
            ) { line ->
                if (Fdsec.refused(line)) helpers.filterRefused.set(true)
                ProotExit.signalOf(line)?.let { signal.compareAndSet(0, it) }
                SessionController.log("[shell] $line")
            }
            // Never paused while hidden: a command left running (an install) goes on.
            if (!externalViewer) shell.pid?.let { HiddenPause.attach(it, null, pauseWhenHidden = false) }
            SessionController.running(
                if (externalViewer) SessionState.Running(ID, password, username = WaylandVnc.USERNAME)
                else SessionState.Running(
                    ID, socket = File(helpers.vncDir, SessionScript.VNC_SOCKET).path, wayland = true,
                    followPercent = app.resolution.percent,
                    fitAboveKeyboard = true,
                    waylandSocket = File(helpers.waylandDir, GuestPaths.WAYLAND_SOCKET).path,
                    shareClipboard = app.sharesClipboard,
                    outputRefresh = frameRate.takeIf { it <= SessionScript.MAX_OUTPUT_REFRESH },
                ),
            )
            val code = shell.waitFor()
            SessionController.log("[flatdroid] the shell exited ($code)")
            if (helpers.filterRefused.get()) throw LaunchException(context.getString(R.string.launch_filter_refused))
            ending = ProotExit.ending(signal.get(), code)
            // As a terminal app: a failure stays readable; a closed window is a plain close.
            if (ending != null && !helpers.holdTerminal(tty, ending!!)) ending = null
            return true
        } finally {
            var interrupted = Thread.interrupted()
            fun step(action: () -> Unit) {
                try {
                    action()
                } catch (e: InterruptedException) {
                    interrupted = true
                } catch (e: Exception) {
                    SessionController.log("[flatdroid] while ending the session: $e")
                }
            }
            step { HiddenPause.detach() }
            step { shell?.stop() }
            step { helpers.stop() }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        /** The session's ID where an app's would be: not a valid Flatpak ID, so no app has it. */
        const val ID = "alpine-shell"
        val LOGIN_SHELL = listOf("/bin/sh", "-l")
        /** foot's own description, which Alpine has with it (`ncurses-terminfo`). */
        const val TERM = "foot"
    }
}
