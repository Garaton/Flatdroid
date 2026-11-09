package com.garathon.flatdroid.setup

import android.content.Context
import android.os.StatFs
import com.garathon.flatdroid.BuildConfig
import com.garathon.flatdroid.R
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.FlatpakCommands
import com.garathon.flatdroid.flatpak.LauncherFiles
import com.garathon.flatdroid.proot.ProcessResult
import com.garathon.flatdroid.settings.UserSettings
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference

/** What each [SetupStep] does in Alpine. */
class AlpineSetup(
    /** For the user-facing texts. */
    private val context: Context,
    private val env: AlpineEnvironment,
    private val launcherFiles: LauncherFiles,
) {
    fun perform(step: SetupStep, reporter: SetupReporter) = when (step) {
        SetupStep.ROOTFS -> rootfs(reporter)
        SetupStep.PACKAGES -> packages(reporter)
        SetupStep.FLATHUB -> flathub(reporter)
        SetupStep.CONFIG_FILES -> configFiles()
        SetupStep.SETTINGS -> applySettings(reporter)
    }

    private fun rootfs(reporter: SetupReporter) {
        if (env.isInstalled()) return checkProot(reporter)
        requireSpace(ROOTFS_BYTES + PACKAGES_BYTES)
        val arch = env.arch
        val sha256 = when (arch) {
            "aarch64" -> BuildConfig.ALPINE_SHA256_AARCH64
            else -> BuildConfig.ALPINE_SHA256_X86_64
        }
        val version = BuildConfig.ALPINE_VERSION
        val url = DownloadRootfsSource.alpineUrl(BuildConfig.ALPINE_BRANCH, version, arch)
        reporter.log("Downloading $url")
        val source = DownloadRootfsSource(
            version, sha256, url,
            cacheFile = File(env.cacheDir, "alpine-minirootfs-$version-$arch.tar.gz"),
        ) { done, total ->
            val fraction = if (total > 0) done.toFloat() / total else null
            reporter.progress(fraction, context.getString(R.string.setup_progress_mib, mib(done), if (total > 0) mib(total) else "?"))
            if (done == total) reporter.progress(null, context.getString(R.string.setup_progress_extracting))
        }
        env.installer.install(source)
        reporter.log("Extracted Alpine $version ($arch)")
        checkProot(reporter)
    }

    /** Fails early, with proot's own output, on a device where proot can't run Alpine at all. */
    private fun checkProot(reporter: SetupReporter) {
        reporter.progress(null, context.getString(R.string.setup_progress_proot))
        val result = run(listOf("/bin/true"), reporter)
        if (result.exitCode != 0) throw IOException(context.getString(R.string.setup_error_proot, lastLine(result)))
        // What the next steps need from the rootfs.
        requireFiles(listOf("sbin/apk", "bin/busybox"))
    }

    /** Setup's downloads and installs all go to this app's internal storage (files and cache). */
    private fun requireSpace(bytes: Long) {
        val free = StatFs(env.cacheDir.path).availableBytes
        if (free < bytes) {
            throw IOException(context.getString(R.string.setup_error_space, free shr 20, bytes shr 20))
        }
    }

    private fun packages(reporter: SetupReporter) {
        // A retry after a partial install needs less; only check while packages are missing.
        if (run(listOf("/sbin/apk", "info", "-e") + PACKAGES, reporter).exitCode != 0) requireSpace(PACKAGES_BYTES)
        val cache = File(env.cacheDir, "apk-cache").apply { mkdirs() }
        // Upgrade the minirootfs's own packages first: `apk add` alone only upgrades what the new
        // packages require, so base packages would miss fixes released after the minirootfs.
        reporter.progress(null, context.getString(R.string.setup_progress_upgrade))
        requireApkWorked(apk(listOf("upgrade"), cache, reporter))
        reporter.progress(null, context.getString(R.string.setup_progress_packages))
        var run = apk(listOf("add") + PACKAGES, cache, reporter)
        requireApkWorked(run)
        var summary = run.summary
        // apk ends with "OK: …" or "N errors; …". The count is kept in apk's database, so a later
        // run still reports packages whose install scripts failed; `apk fix` runs those again
        // (verify that it re-runs triggers too).
        if (!summary.ok) {
            reporter.progress(null, context.getString(R.string.setup_progress_fix))
            run = apk(listOf("fix"), cache, reporter)
            requireApkWorked(run)
            summary = run.summary
        }
        if (!summary.ok) {
            val failed = summary.errors.ifEmpty { listOf(summary.line ?: "no summary line") }
            throw IOException(context.getString(R.string.setup_error_scripts, failed.joinToString("\n")))
        }
        val check = run(listOf("/sbin/apk", "info", "-e") + PACKAGES, reporter)
        if (check.exitCode != 0) {
            val installed = check.output.lines().filter { it.isNotBlank() }.toSet()
            throw IOException(context.getString(R.string.setup_error_packages, PACKAGES.filter { it !in installed }.joinToString(" ")))
        }
        // The packages are listed as installed: check that they are there and that Flatpak runs.
        requireFiles(PACKAGE_FILES)
        val flatpak = run(listOf(FlatpakCommands.FLATPAK, "--version"), reporter)
        if (flatpak.exitCode != 0) throw IOException(context.getString(R.string.setup_error_flatpak_check, lastLine(flatpak).ifEmpty { "exit ${flatpak.exitCode}" }))
        AndroidTarFs.deleteTree(cache)
    }

    /** An apk command's end: its exit code and what it printed last. */
    private class ApkRun(val exitCode: Int?, val summary: ApkSummary)

    /**
     * Fails a run that couldn't reach the package servers (apk goes on with what it has, and says
     * OK when the packages are installed already), or that failed for another reason than a
     * package's own scripts, which `apk fix` runs again.
     */
    private fun requireApkWorked(run: ApkRun) {
        if (run.summary.networkErrors.isNotEmpty()) {
            throw IOException(context.getString(R.string.setup_error_network, run.summary.networkErrors.first()))
        }
        if (run.summary.unselectable) {
            // Online, so Alpine's packages don't have one setup asks for (renamed or dropped).
            val names = run.summary.missingPackages.ifEmpty { null }?.joinToString(" ")
            throw IOException(
                if (names != null) context.getString(R.string.setup_error_no_such_package, names)
                else context.getString(R.string.setup_error_unselectable),
            )
        }
        if (run.exitCode != 0 && run.summary.errors.isEmpty() && !run.summary.ok) {
            throw IOException(context.getString(R.string.setup_error_apk, run.exitCode ?: -1))
        }
    }

    /** Fails when one of [paths] (relative to the rootfs) is missing: a step that said it finished has to have left them. */
    private fun requireFiles(paths: List<String>) {
        val root = env.installer.rootfs
        val missing = paths.filter { val f = File(root, it); !f.exists() && !Files.isSymbolicLink(f.toPath()) }
        if (missing.isNotEmpty()) throw IOException(context.getString(R.string.setup_error_missing, missing.joinToString(" ")))
    }

    /**
     * Runs one apk command with the package cache and progress; returns how it ended. The first
     * line that is an error (apk's own, but not a package's script error, which `apk fix` runs
     * again) or says the package servers can't be reached stops it (it would go on for as long as
     * every repository takes to time out) and fails the step at once. The message says the servers
     * can't be reached when a line said so, that storage is full when it is, and otherwise gives the
     * error line. Packages apk can't select end the run by themselves ([requireApkWorked]).
     */
    private fun apk(args: List<String>, cache: File, reporter: SetupReporter): ApkRun {
        val output = mutableListOf<String>()
        val setupThread = Thread.currentThread()
        val unreachable = AtomicReference<String?>(null)
        val failure = AtomicReference<String?>(null)
        fun failed(line: String) = IOException(
            unreachable.get()?.let { context.getString(R.string.setup_error_network, it) }
                ?: if (ApkSummary.isNoSpace(line)) context.getString(R.string.setup_error_disk_full)
                else context.getString(R.string.setup_error_apk_line, line),
        )
        val result = try {
            run(
                apkCommand(args),
                reporter,
                timeoutSeconds = 60 * 60,
                extraBinds = listOf(cache.path to APK_CACHE),
            ) { line ->
                output += line
                if (ApkSummary.isNetworkError(line)) unreachable.compareAndSet(null, line.trim())
                // Interrupting the setup thread, which waits for the command, stops it (as cancelling does).
                if ((ApkSummary.isNetworkError(line) || ApkSummary.isFatalError(line)) && failure.compareAndSet(null, line.trim())) setupThread.interrupt()
                ApkProgress.parse(line)?.let { (done, total) ->
                    reporter.progress(done.toFloat() / total, line.substringAfter(") ").trim())
                }
            }
        } catch (e: InterruptedException) {
            // Ours, from the output; anything else is a cancellation and goes on.
            throw failed(failure.get() ?: throw e)
        }
        // It ended before the interrupt was seen: take the interrupt back, so it isn't taken for a cancellation later.
        failure.get()?.let { line ->
            Thread.interrupted()
            throw failed(line)
        }
        return ApkRun(result.exitCode, ApkSummary.parse(output))
    }

    private fun flathub(reporter: SetupReporter) {
        // Importing Flathub's signing key runs gpg, which has failed under proot before (fork on
        // x86_64, hard links through unresolved paths on arm64; both fixed), so remote-add is
        // retried. A failed attempt still writes the remote's config without its key, and
        // --if-not-exists would then accept that remote although nothing can be verified or
        // installed from it. So success means the key file exists, and a remote without one is
        // deleted, before a retry and when giving up.
        val key = File(env.installer.rootfs, FlatpakCommands.FLATHUB_KEY)
        for (attempt in 1..REMOTE_ADD_ATTEMPTS) {
            reporter.progress(
                null,
                if (attempt == 1) context.getString(R.string.setup_progress_flathub)
                else context.getString(R.string.setup_progress_flathub_retry, attempt, REMOTE_ADD_ATTEMPTS),
            )
            val result = run(FlatpakCommands.addFlathub, reporter, 5 * 60)
            if (result.exitCode == 0 && key.isFile) {
                // The remote is listed, not only written: the repository can be read.
                val remotes = run(FlatpakCommands.listRemotes, reporter)
                if (remotes.exitCode == 0 && remotes.output.lines().any { it.trim() == "flathub" }) break
                reporter.log("Flathub was added but is not listed")
            }
            if (result.exitCode != 0 || !key.isFile) reporter.log("Flathub was not added with its signing key (exit ${result.exitCode})")
            run(FlatpakCommands.deleteFlathub, reporter)
            if (attempt == REMOTE_ADD_ATTEMPTS) {
                throw IOException(context.getString(R.string.setup_error_flathub, lastLine(result)))
            }
            Thread.sleep(RETRY_DELAY_MS * attempt)
        }
    }

    /** Files Flatdroid generates outside the rootfs: the /etc files for Flatpak roots. */
    private fun configFiles() {
        launcherFiles.write()
        val missing = launcherFiles.missing()
        if (missing.isNotEmpty()) throw IOException(context.getString(R.string.setup_error_missing, missing.joinToString(" ")))
    }

    /** The settings the user chose before setup (Settings applies later changes itself). */
    private fun applySettings(reporter: SetupReporter) {
        val settings = SetupController.settings(context).load() ?: throw IOException(context.getString(R.string.setup_error_no_settings))
        for (command in settings.commands()) {
            val result = run(command, reporter, UserSettings.APPLY_TIMEOUT_SECONDS)
            if (result.exitCode != 0) throw IOException(lastLine(result).ifEmpty { "exit ${result.exitCode}" })
        }
    }

    private fun run(
        command: List<String>,
        reporter: SetupReporter,
        timeoutSeconds: Long = 60,
        extraBinds: List<Pair<String, String>> = emptyList(),
        onLine: (String) -> Unit = {},
    ): ProcessResult {
        reporter.log("$ ${command.joinToString(" ")}")
        val result = env.run(command, timeoutSeconds, extraBinds = extraBinds) { line ->
            reporter.log(line)
            onLine(line)
        }
        if (result.timedOut) throw IOException(context.getString(R.string.setup_error_timeout, command.joinToString(" ")))
        return result
    }

    private fun lastLine(result: ProcessResult) = result.output.lines().lastOrNull { it.isNotBlank() } ?: ""

    private fun mib(bytes: Long) = "%.1f".format(bytes / 1048576.0)

    companion object {
        /**
         * Xvnc is Alpine's tigervnc (an own build would save little: the camera stack needs Mesa
         * too), Openbox its window manager; labwc, wayvnc and wlr-randr are the Wayland session:
         * the compositor, its VNC server and what sets the output's size. Openbox needs a font:
         * without one its title bars collapse to a few pixels. ostree repairs the Flatpak repository after
         * an interrupted removal (FlatpakTaskService). dbus and xdg-dbus-proxy give apps their session bus
         * (SessionBus); PipeWire, WirePlumber, the portal and GStreamer's PipeWire elements carry
         * the camera (gst-plugins-good to split its JPEG frames), and pipewire-tools tells when an app uses it.
         */
        val PACKAGES = listOf(
            "flatpak", "ostree", "tigervnc", "openbox", "labwc", "wayvnc", "wlr-randr", "font-cantarell", "pulseaudio", "ca-certificates",
            "dbus", "xdg-dbus-proxy", "pipewire", "wireplumber", "xdg-desktop-portal", "gst-plugin-pipewire", "gstreamer-tools", "gst-plugins-good",
            "pipewire-tools",
        )

        /** What the packages must have left in the rootfs (relative to it): the programs setup's later steps and every launch run. */
        val PACKAGE_FILES = listOf(
            "usr/bin/flatpak", "usr/bin/ostree", "usr/bin/Xvnc", "usr/bin/openbox", "usr/bin/labwc", "usr/bin/wayvnc", "usr/bin/wlr-randr",
            "usr/bin/pulseaudio", "usr/bin/dbus-daemon", "usr/bin/xdg-dbus-proxy", "usr/bin/pipewire", "usr/bin/wireplumber",
            "usr/bin/gst-launch-1.0", "usr/bin/pw-cli",
        )

        /** Where apk's package cache is bound: a directory in Flatdroid's cache, deleted afterwards. */
        const val APK_CACHE = "/var/cache/apk"

        /** An apk command with [args], its cache at [APK_CACHE], without progress bars. */
        fun apkCommand(args: List<String>) = listOf("/sbin/apk") + args + listOf("--no-progress", "--cache-dir", APK_CACHE)
        /** Free space setup needs: the minirootfs, and the package cache plus installed packages. */
        private const val ROOTFS_BYTES = 20L shl 20
        private const val PACKAGES_BYTES = 600L shl 20
        private const val REMOTE_ADD_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
    }
}
