package com.garathon.flatdroid.flatpak

import android.system.ErrnoException
import android.system.Os
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.launcher.LaunchPlanner
import com.garathon.flatdroid.proot.Fdsec
import com.garathon.flatdroid.proot.ProotCommand
import com.garathon.flatdroid.proot.ProotRunner
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.IOException

/**
 * Extra data: what an app or extension downloads while it installs (VS Code itself, the openh264
 * codec), unpacked by its own `/app/bin/apply_extra`. Flatpak's CLI only reports that script as
 * done (Flatdroid's stand-in for bwrap, which can't run under proot); this runs it afterwards, for
 * each deployment once ([stateFile]), whether it succeeds or not: a failed script may have left
 * its `extra` directory half unpacked, which reinstalling the app or the next update replaces.
 */
class ExtraData(private val env: AlpineEnvironment, private val stateFile: File, private val workDir: File) {
    /** One deployment's script: [isApp] for an app (it can't run without it), else an extension. */
    data class Result(val deployment: File, val isApp: Boolean, val exitCode: Int)

    /** Deployments (`<kind>/<ID>/<arch>/<branch>/<commit>`) with a script not run yet. */
    fun pending(): List<File> {
        val done = applied()
        return listOf("app", "runtime").flatMap { kind ->
            File(env.flatpakDir, kind).listFiles().orEmpty().flatMap { id ->
                id.listFiles().orEmpty().flatMap { arch -> arch.listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() } }
            }
        }.filter { !AndroidTarFs.isSymlink(it) && File(it, "files/bin/apply_extra").exists() && it.path !in done }
    }

    /**
     * Forgets that the scripts under [branches] (`<kind>/<ID>/<arch>/<branch>`, relative to the
     * installation) ran, or every script's with null: a reinstall redeploys the same commit at the
     * same path, without the files its script made.
     */
    fun forget(branches: List<String>? = null) {
        val prefix = env.flatpakDir.path + "/"
        val keep = if (branches == null) emptyList() else applied().filter { path ->
            branches.none { path.removePrefix(prefix).startsWith("$it/") }
        }
        stateFile.writeTextAtomically(keep.joinToString("\n", postfix = if (keep.isEmpty()) "" else "\n"))
    }

    /** Runs every pending script, [onLine] getting their output; records each as applied. */
    fun applyAll(onLine: (String) -> Unit): List<Result> = pending().map { deployment ->
        val result = Result(deployment, deployment.parentFile!!.parentFile!!.parentFile!!.parentFile!!.name == "app", run(deployment, onLine))
        stateFile.writeTextAtomically((applied() + deployment.path).joinToString("\n", postfix = "\n"))
        result
    }

    private fun run(deployment: File, onLine: (String) -> Unit): Int {
        val name = deployment.path.removePrefix(env.flatpakDir.path + "/")
        val metadata = runCatching { FlatpakMetadata.parse(File(deployment, "metadata").readText()) }.getOrNull() ?: run {
            onLine("[flatdroid] apply_extra for $name: its metadata can't be read")
            return -1
        }
        val runtime = metadata.extraDataRuntime?.let { ref ->
            FlatpakInstallation(env.flatpakDir, env.arch).runtime(ref)?.files ?: run {
                onLine("[flatdroid] apply_extra: runtime $ref isn't installed")
                return -1
            }
        }
        val root = File(workDir, "root")
        val tmp = File(workDir, "tmp")
        try {
            AndroidTarFs.deleteTree(workDir)
            for (dir in listOf("usr", "app", "tmp", "dev")) File(root, dir).mkdirs()
            tmp.mkdirs()
            for (dev in listOf("null", "zero", "urandom")) File(root, "dev/$dev").createNewFile()
            File(root, Fdsec.APP_PATH.removePrefix("/")).apply { parentFile!!.mkdirs() }.createNewFile()
            if (runtime != null) {
                for (link in LaunchPlanner.USR_LINKS.filter { File(runtime, it).exists() }) Os.symlink("usr/$link", File(root, link).path)
            }
            onLine("[flatdroid] apply_extra for $name")
            val invocation = ProotCommand.applyExtra(env.paths(), root.path, runtime?.path, File(deployment, "files").path, tmp.path)
            // Stopped when the task is cancelled (rethrown, so this deployment isn't recorded) or runs too long.
            val result = ProotRunner.run(invocation, workDir, timeoutSeconds = TIMEOUT_SECONDS, onLine = onLine)
            if (result.timedOut) onLine("[flatdroid] apply_extra for $name took too long and was stopped")
            return result.exitCode ?: -1
        } catch (e: ErrnoException) {
            onLine("[flatdroid] apply_extra for $name: ${e.message}")
            return -1
        } catch (e: IOException) {
            onLine("[flatdroid] apply_extra for $name: ${e.message}")
            return -1
        } finally {
            AndroidTarFs.deleteTree(workDir)
        }
    }

    /** Deployments already run, without those since removed. */
    private fun applied(): Set<String> =
        runCatching { stateFile.readLines() }.getOrDefault(emptyList()).filter { it.isNotBlank() && File(it).isDirectory }.toSet()

    private companion object {
        /** VS Code's script unpacks about 100 MB through proot. */
        const val TIMEOUT_SECONDS = 30 * 60L
    }
}
