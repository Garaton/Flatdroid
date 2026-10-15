package com.garathon.flatdroid.setup

import android.os.StatFs
import com.garathon.flatdroid.BuildConfig
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.alpine.GuestFiles
import com.garathon.flatdroid.flatpak.FlatpakCommands
import com.garathon.flatdroid.flatpak.LauncherFiles
import com.garathon.flatdroid.proot.ProcessResult
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.IOException

/** What each [SetupStep] does in Alpine. */
class AlpineSetup(
    private val env: AlpineEnvironment,
    private val launcherFiles: LauncherFiles,
) {
    fun perform(step: SetupStep, reporter: SetupReporter) = when (step) {
        SetupStep.ROOTFS -> rootfs(reporter)
        SetupStep.PACKAGES -> packages(reporter)
        SetupStep.FLATHUB -> flathub(reporter)
        SetupStep.CONFIG_FILES -> configFiles()
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
            reporter.progress(fraction, "${mib(done)} / ${if (total > 0) mib(total) else "?"} MiB")
            if (done == total) reporter.progress(null, "Checking and extracting")
        }
        env.installer.install(source)
        reporter.log("Extracted Alpine $version ($arch)")
        checkProot(reporter)
    }

    /** Fails early, with proot's own output, on a device where proot can't run Alpine at all. */
    private fun checkProot(reporter: SetupReporter) {
        reporter.progress(null, "Checking that proot runs")
        val result = run(listOf("/bin/true"), reporter)
        if (result.exitCode != 0) throw IOException("proot can't run Alpine on this device: ${lastLine(result)}")
    }

    /** Setup's downloads and installs all go to this app's internal storage (files and cache). */
    private fun requireSpace(bytes: Long) {
        val free = StatFs(env.cacheDir.path).availableBytes
        if (free < bytes) {
            throw IOException("Not enough free storage: ${free shr 20} MiB free, about ${bytes shr 20} MiB needed")
        }
    }

    private fun packages(reporter: SetupReporter) {
        // A retry after a partial install needs less; only check while packages are missing.
        if (run(listOf("/sbin/apk", "info", "-e") + PACKAGES, reporter).exitCode != 0) requireSpace(PACKAGES_BYTES)
        val cache = File(env.cacheDir, "apk-cache").apply { mkdirs() }
        // Upgrade the minirootfs's own packages first: `apk add` alone only upgrades what the new
        // packages require, so base packages would miss fixes released after the minirootfs.
        reporter.progress(null, "Upgrading Alpine")
        apk(listOf("upgrade"), cache, reporter)
        reporter.progress(null, "Installing packages")
        var summary = apk(listOf("add") + PACKAGES, cache, reporter)
        // apk ends with "OK: …" or "N errors; …". The count is kept in apk's database, so a later
        // run still reports packages whose install scripts failed; `apk fix` runs those again
        // (verify that it re-runs triggers too).
        if (!summary.ok) {
            reporter.progress(null, "Running failed package scripts again")
            summary = apk(listOf("fix"), cache, reporter)
        }
        if (!summary.ok) {
            val failed = summary.errors.ifEmpty { listOf(summary.line ?: "no summary line") }
            throw IOException("Package scripts failed:\n${failed.joinToString("\n")}")
        }
        val check = run(listOf("/sbin/apk", "info", "-e") + PACKAGES, reporter)
        if (check.exitCode != 0) {
            val installed = check.output.lines().filter { it.isNotBlank() }.toSet()
            throw IOException("Packages not installed: ${PACKAGES.filter { it !in installed }.joinToString(" ")}")
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
            reporter.progress(null, if (attempt == 1) "Adding Flathub" else "Adding Flathub (attempt $attempt of $REMOTE_ADD_ATTEMPTS)")
            val result = run(FlatpakCommands.addFlathub, reporter, 5 * 60)
            if (result.exitCode == 0 && key.isFile) break
            reporter.log("Flathub was not added with its signing key (exit ${result.exitCode})")
            run(FlatpakCommands.deleteFlathub, reporter)
            if (attempt == REMOTE_ADD_ATTEMPTS) {
                throw IOException("Could not add Flathub with its signing key: ${lastLine(result)}")
            }
            Thread.sleep(RETRY_DELAY_MS * attempt)
        }
    }

    /**
     * Files Flatdroid generates once, outside the rootfs: the Openbox config (to be bound into
     * Alpine when Openbox runs) and the /etc files for Flatpak roots.
     */
    private fun configFiles() {
        launcherFiles.write()
        env.openboxConfig.writeTextAtomically(GuestFiles.openboxRc())
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
        if (result.timedOut) throw IOException("Timed out: ${command.joinToString(" ")}")
        return result
    }

    private fun lastLine(result: ProcessResult) = result.output.lines().lastOrNull { it.isNotBlank() } ?: ""

    private fun mib(bytes: Long) = "%.1f".format(bytes / 1048576.0)

    companion object {
        /** Alpine's tigervnc stays until our own Xvnc build replaces it. */
        val PACKAGES = listOf("flatpak", "tigervnc", "openbox", "pulseaudio", "ca-certificates")
        private const val APK_CACHE = "/var/cache/apk"
        /** Free space setup needs: the minirootfs, and the package cache plus installed packages. */
        private const val ROOTFS_BYTES = 20L shl 20
        private const val PACKAGES_BYTES = 600L shl 20
        private const val REMOTE_ADD_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 2_000L
    }
}
