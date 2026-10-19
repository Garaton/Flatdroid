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
        apk(listOf("upgrade"), cache, reporter)
        reporter.progress(null, context.getString(R.string.setup_progress_packages))
        var summary = apk(listOf("add") + PACKAGES, cache, reporter)
        // apk ends with "OK: …" or "N errors; …". The count is kept in apk's database, so a later
        // run still reports packages whose install scripts failed; `apk fix` runs those again
        // (verify that it re-runs triggers too).
        if (!summary.ok) {
            reporter.progress(null, context.getString(R.string.setup_progress_fix))
            summary = apk(listOf("fix"), cache, reporter)
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
        AndroidTarFs.deleteTree(cache)
    }

    /** Runs one apk command with the package cache and progress; returns its summary. */
    private fun apk(args: List<String>, cache: File, reporter: SetupReporter): ApkSummary {
        val output = mutableListOf<String>()
        run(
            listOf("/sbin/apk") + args + listOf("--no-progress", "--cache-dir", APK_CACHE),
            reporter,
            timeoutSeconds = 60 * 60,
            extraBinds = listOf(cache.path to APK_CACHE),
        ) { line ->
            output += line
            ApkProgress.parse(line)?.let { (done, total) ->
                reporter.progress(done.toFloat() / total, line.substringAfter(") ").trim())
            }
        }
        return ApkSummary.parse(output)
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
            if (result.exitCode == 0 && key.isFile) break
            reporter.log("Flathub was not added with its signing key (exit ${result.exitCode})")
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
         * Alpine's tigervnc stays until our own Xvnc build replaces it. Openbox needs a font: without
         * one its title bars collapse to a few pixels. ostree repairs the Flatpak repository after
         * an interrupted removal (FlatpakTaskService). pulseaudio-utils has `pactl`, which tells
         * when an app records (SessionScript).
         */
        val PACKAGES = listOf("flatpak", "ostree", "tigervnc", "openbox", "font-cantarell", "pulseaudio", "pulseaudio-utils", "ca-certificates")
        private const val APK_CACHE = "/var/cache/apk"
        /** Free space setup needs: the minirootfs, and the package cache plus installed packages. */
        private const val ROOTFS_BYTES = 20L shl 20
        private const val PACKAGES_BYTES = 600L shl 20
        private const val REMOTE_ADD_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
    }
}
