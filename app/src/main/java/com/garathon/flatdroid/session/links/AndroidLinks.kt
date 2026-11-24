package com.garathon.flatdroid.session.links

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.garathon.flatdroid.R
import com.garathon.flatdroid.session.bus.Portal
import com.garathon.flatdroid.storage.AppData
import com.garathon.flatdroid.storage.FolderDocuments
import com.garathon.flatdroid.storage.SharedHome
import java.io.File
import java.io.FileDescriptor

/**
 * Opens an app's links through Android's chooser, every time: addresses as they are, files
 * through [OpenedFilesProvider], folders in the Files app ([FolderDocuments]), by descriptor or
 * by host path (`FileManager1`). Only while
 * Flatdroid is on screen: Android doesn't let an app in the background start one (Android 10 and
 * later), and a link the user didn't just ask for shouldn't appear later. At most one a second, so
 * an app can't bury the screen in choosers: one refused for that says so, at most once per toast.
 */
class AndroidLinks(private val context: Context, private val log: (String) -> Unit) : Portal.Links {
    private var lastShown = 0L
    private var lastToast = 0L

    override fun openUri(uri: String): Boolean {
        // Browsable only: the activities browsers may open from a web page.
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE)
        return show(view, "a link (${uri.substringBefore(':')})")
    }

    override fun openFile(fd: FileDescriptor, writable: Boolean): Boolean {
        val stat = runCatching { Os.fstat(fd) }.getOrNull() ?: return false
        // A folder opens as one, as with the real portal (`xdg-open <folder>` sends it here).
        if (OsConstants.S_ISDIR(stat.st_mode)) return openDirectory(fd)
        if (!OsConstants.S_ISREG(stat.st_mode)) return false
        val held = ParcelFileDescriptor.dup(fd)
        val path = runCatching { Os.readlink("/proc/self/fd/${held.fd}") }.getOrDefault("file")
        val name = File(path).name.ifEmpty { "file" }
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        // Writing only when asked and the app itself opened the file for it.
        val canWrite = writable && openedForWriting(fd)
        val uri = OpenedFiles.add(OpenedFiles.Entry(held, name, mime, canWrite))
        var flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        if (canWrite) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return show(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(flags), "a file ($mime)")
    }

    override fun openDirectory(fd: FileDescriptor): Boolean {
        val stat = runCatching { Os.fstat(fd) }.getOrNull() ?: return false
        if (!OsConstants.S_ISDIR(stat.st_mode)) return false
        val path = runCatching { ParcelFileDescriptor.dup(fd).use { Os.readlink("/proc/self/fd/${it.fd}") } }.getOrNull() ?: return false
        return showFolder(path)
    }

    override fun showFolder(path: String): Boolean {
        if (!File(path).isDirectory) {
            log("[flatdroid] the app asked to show a folder that isn't there")
            return false
        }
        val (authority, document) = FolderDocuments.of(path, AppData.root(context).path, SharedHome.storageRoot().path) ?: run {
            log("[flatdroid] the app asked to show a folder Android's Files app can't open")
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildDocumentUri(authority, document), DocumentsContract.Document.MIME_TYPE_DIR)
        return show(intent, "a folder")
    }

    private fun openedForWriting(fd: FileDescriptor): Boolean {
        val flags = runCatching { Os.fcntlInt(fd, OsConstants.F_GETFL, 0) }.getOrNull() ?: return false
        if (flags and O_PATH != 0) return false
        val mode = flags and OsConstants.O_ACCMODE
        return mode == OsConstants.O_WRONLY || mode == OsConstants.O_RDWR
    }

    @Synchronized
    private fun show(target: Intent, what: String): Boolean {
        if (!onScreen()) {
            log("[flatdroid] the app asked to open $what while Flatdroid wasn't on screen; not opened")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastShown < MIN_INTERVAL_MS) {
            log("[flatdroid] the app asked to open $what again within a second; not opened")
            if (now - lastToast >= TOAST_INTERVAL_MS) {
                lastToast = now
                Handler(Looper.getMainLooper()).post { Toast.makeText(context, R.string.link_too_soon, Toast.LENGTH_SHORT).show() }
            }
            return false
        }
        return try {
            context.startActivity(Intent.createChooser(target, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            lastShown = now
            log("[flatdroid] the app opened $what")
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /** Whether one of Flatdroid's screens is visible. */
    private fun onScreen(): Boolean {
        val state = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(state)
        return state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            state.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
    }

    private companion object {
        const val MIN_INTERVAL_MS = 1_000L
        /** About a short toast's time on screen: a burst of links shows one. */
        const val TOAST_INTERVAL_MS = 2_500L
        /** Linux's O_PATH on arm64 and x86_64 (Android's constants don't have it). */
        const val O_PATH = 0x200000
    }
}
