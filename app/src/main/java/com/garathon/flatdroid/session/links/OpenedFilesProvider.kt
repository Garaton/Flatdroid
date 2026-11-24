package com.garathon.flatdroid.session.links

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

/**
 * Files an app asked to open (`OpenFile`), each served to the Android app the user picks:
 * exactly that file, by the descriptor the app passed, never by a path the other app could
 * change. Each read opens the file anew through the descriptor, with its own position. Writing
 * only where the app opened the file for writing. Not exported: only apps given an address
 * through the chooser (a URI grant) reach it, and only until the session ends ([OpenedFiles.clear]).
 */
class OpenedFilesProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = OpenedFiles.open(uri, mode)

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val entry = OpenedFiles.get(uri) ?: throw FileNotFoundException(uri.toString())
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val size = OpenedFiles.size(entry)
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == OpenableColumns.DISPLAY_NAME) entry.name else size })
        }
    }

    override fun getType(uri: Uri): String? = OpenedFiles.get(uri)?.mime
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val AUTHORITY = "com.garathon.flatdroid.opened"
    }
}

/** The session's opened files, by a random token in their address. */
object OpenedFiles {
    class Entry(val fd: ParcelFileDescriptor, val name: String, val mime: String, val writable: Boolean)

    /** At most this many at a time; the oldest goes first. */
    private const val MAX = 32

    private val random = SecureRandom()
    private val entries = LinkedHashMap<String, Entry>()

    /** Keeps [entry] (which is then this registry's to close) and gives its address. */
    @Synchronized
    fun add(entry: Entry): Uri {
        while (entries.size >= MAX) {
            val oldest = entries.keys.first()
            entries.remove(oldest)?.fd?.let { runCatching { it.close() } }
        }
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        entries[token] = entry
        return Uri.Builder().scheme("content").authority(OpenedFilesProvider.AUTHORITY).appendPath(token).appendPath(entry.name).build()
    }

    @Synchronized
    fun get(uri: Uri): Entry? = uri.pathSegments.firstOrNull()?.let { entries[it] }

    /**
     * The file behind [uri], opened anew in [mode]. Under the lock: a file closed meanwhile
     * ([add] past [MAX], or [clear]) could have its descriptor's number reused for another file.
     */
    @Synchronized
    fun open(uri: Uri, mode: String): ParcelFileDescriptor {
        val entry = get(uri) ?: throw FileNotFoundException(uri.toString())
        val writes = 'w' in mode || 'a' in mode
        if (writes && !entry.writable) throw FileNotFoundException("read-only")
        return ParcelFileDescriptor.open(File("/proc/self/fd/${entry.fd.fd}"), ParcelFileDescriptor.parseMode(mode))
    }

    /** [entry]'s size; null once it is closed. */
    @Synchronized
    fun size(entry: Entry): Long? = runCatching { Os.fstat(entry.fd.fileDescriptor).st_size }.getOrNull()

    /** Closes every file: their addresses stop working. At the session's end. */
    @Synchronized
    fun clear() {
        for (entry in entries.values) runCatching { entry.fd.close() }
        entries.clear()
    }
}
