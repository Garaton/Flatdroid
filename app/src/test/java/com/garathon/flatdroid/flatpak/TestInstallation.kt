package com.garathon.flatdroid.flatpak

import java.io.File
import java.nio.file.Files

/** Builds a Flatpak user installation on disk, laid out as Flatpak deploys (relative links). */
class TestInstallation(val root: File, private val arch: String = "aarch64") {
    fun app(id: String, branch: String = "stable", metadata: String, current: Boolean = true): File {
        val deploy = deploy("app/$id/$arch/$branch", metadata)
        if (current) link(File(root, "app/$id/current"), "$arch/$branch")
        return File(deploy, "files")
    }

    fun runtime(name: String, branch: String, metadata: String = "[Runtime]\nname=$name\n"): File =
        File(deploy("runtime/$name/$arch/$branch", metadata), "files")

    private fun deploy(path: String, metadata: String): File {
        val hash = File(root, "$path/0123abcd")
        File(hash, "files").mkdirs()
        File(hash, "metadata").writeText(metadata)
        link(File(root, "$path/active"), "0123abcd")
        return hash
    }

    companion object {
        fun link(link: File, target: String) {
            link.parentFile?.mkdirs()
            Files.createSymbolicLink(link.toPath(), File(target).toPath())
        }

        fun readlink(file: File): String? =
            if (Files.isSymbolicLink(file.toPath())) Files.readSymbolicLink(file.toPath()).toString() else null
    }
}
