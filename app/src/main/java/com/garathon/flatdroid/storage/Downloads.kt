package com.garathon.flatdroid.storage

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Saves a file in the Download folder: through MediaStore on Android 10 and newer (no permission
 * needed), by path before (with the storage permission).
 */
object Downloads {
    /**
     * Writes a new file named [name] (Android adds a number if it is taken) with [write], and
     * returns where it went, as the user knows it. A failed write leaves no file.
     */
    fun save(context: Context, name: String, mimeType: String, write: (OutputStream) -> Unit): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Couldn't create $name in Download")
            try {
                (resolver.openOutputStream(uri) ?: throw IOException("Couldn't open $name")).use(write)
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            val saved = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            return "${Environment.DIRECTORY_DOWNLOADS}/${saved ?: name}"
        }
        @Suppress("DEPRECATION")
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        val file = Names.unique(dir, name)
        try {
            file.outputStream().use(write)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/${file.name}"
    }
}
