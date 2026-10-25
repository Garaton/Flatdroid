package com.garathon.flatdroid.setup

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File

/** [TarFs] through android.system.Os (java.nio.file needs API 26; minSdk is 24). */
object AndroidTarFs : TarFs {
    override fun symlink(target: String, link: File) = Os.symlink(target, link.path)
    override fun chmod(file: File, mode: Int) = Os.chmod(file.path, mode)
    override fun useOwnGroup(file: File) = Os.lchown(file.path, -1, Os.getgid())
    override fun isSymlink(file: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(file.path).st_mode)
    } catch (_: ErrnoException) {
        false
    }
}
