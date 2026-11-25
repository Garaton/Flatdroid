package com.garathon.flatdroid.util.io

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileDescriptor

/**
 * Paths kept to a folder although links in it may lead elsewhere (an app, an archive or a home
 * can put a link anywhere): checked with the links resolved. A file opened through [openRegular]
 * is checked again on what was opened, so a link swapped in after the check can't send the read
 * elsewhere.
 */
object Confined {
    /**
     * [file] with its links resolved is inside [base] (a resolved path), or is [base] itself when
     * [orBase]. False when it can't be resolved.
     */
    fun within(file: File, base: String, orBase: Boolean = true): Boolean {
        val path = runCatching { file.canonicalPath }.getOrNull() ?: return false
        val root = base.trimEnd('/')
        return (orBase && path == root.ifEmpty { "/" }) || path.startsWith("$root/")
    }

    /** [file] resolved when it is [base] or inside it ([base] resolved here too), else null. */
    fun resolve(file: File, base: File): File? {
        val root = runCatching { base.canonicalPath }.getOrNull() ?: return null
        val resolved = runCatching { file.canonicalFile }.getOrNull() ?: return null
        return resolved.takeIf { within(it, root) }
    }

    /** [fd] is a regular file, so reading it ends (a pipe or socket from an app could stay silent). */
    fun isRegular(fd: FileDescriptor): Boolean = runCatching { OsConstants.S_ISREG(Os.fstat(fd).st_mode) }.getOrDefault(false)

    /**
     * Opens [file], a resolved path, for reading when it is a regular file still at that path: the
     * opened descriptor's own path is compared, so a link put in its place meanwhile is refused.
     * Never waits on a pipe. Null when refused or unreadable.
     */
    fun openRegular(file: File): ParcelFileDescriptor? {
        val fd = runCatching {
            Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NONBLOCK or OsConstants.O_NOFOLLOW, 0)
        }.getOrNull() ?: return null
        try {
            if (!isRegular(fd)) return null
            val held = ParcelFileDescriptor.dup(fd)
            val opened = runCatching { Os.readlink("/proc/self/fd/${held.fd}") }.getOrNull()
            if (opened != file.path) {
                held.close()
                return null
            }
            return held
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { Os.close(fd) }
        }
    }
}
