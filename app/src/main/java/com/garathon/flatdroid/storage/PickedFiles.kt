package com.garathon.flatdroid.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/** Where a file picked in Android's picker is, when its provider says. */
object PickedFiles {
    /**
     * The picked document's path on shared storage, from Android's own storage, downloads and
     * media providers; null for any other provider (cloud storage apps), or when it can't tell.
     */
    fun pathOf(context: Context, uri: Uri): File? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val root = SharedHome.storageRoot().path
        val direct = SharedFolderPath.of(uri.authority, documentId, root, broadAccess = true) as? SharedFolderPath.Result.Ok
        if (direct != null) return File(direct.path)
        // Media and most downloads are named by MediaStore ID; MediaStore knows their folder.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val media = MediaStore.getMediaUri(context, uri) ?: return null
            val columns = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME)
            context.contentResolver.query(media, columns, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) return null
                File("$root/${cursor.getString(0).trim('/')}", cursor.getString(1))
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Whether Flatdroid, and so its apps, can see [file]: listed in its folder and readable.
     * Shared storage answers a direct look-up for a file another app made, but leaves it out of
     * listings and refuses to open it.
     */
    fun visible(file: File): Boolean =
        file.parentFile?.list()?.contains(file.name) == true && runCatching { file.inputStream().close() }.isSuccess

    /** [file] is [dir] or inside it, links resolved. */
    fun inside(file: File, dir: File): Boolean {
        val path = file.canonicalPath
        val base = dir.canonicalPath
        return path.startsWith("$base/")
    }
}
