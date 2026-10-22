package com.garathon.flatdroid.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.Os
import android.webkit.MimeTypeMap
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
import java.io.File
import java.io.FileNotFoundException

/**
 * Document IDs of [HomesProvider]: [ROOT] lists the apps, an app ID is that app's private home,
 * and `<app ID>/<path>` is a file or folder in it.
 */
object HomeDocuments {
    const val ROOT = "root"

    /** The app and the path inside its home (empty for the home itself), or null for anything else. */
    fun parse(id: String): Pair<String, String>? {
        val appId = id.substringBefore('/')
        val path = id.substringAfter('/', missingDelimiterValue = "")
        if (!InstalledApps.isValidId(appId)) return null
        if (path.isNotEmpty() && path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return appId to path
    }

    fun child(parentId: String, name: String) = "$parentId/$name"
}

/**
 * Private homes in the Files app, under a "Flatdroid" entry: one folder per app that has one. Its
 * permission (MANAGE_DOCUMENTS) lets only Android's own file picker and Files app browse it;
 * other apps get only the files the user picks there. Nothing outside a home is reachable: links
 * that lead out of it are hidden and refused.
 */
class HomesProvider : DocumentsProvider() {
    override fun onCreate() = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val context = context!!
        return MatrixCursor(projection ?: ROOT_COLUMNS).apply {
            newRow()
                .add(Root.COLUMN_ROOT_ID, HomeDocuments.ROOT)
                .add(Root.COLUMN_DOCUMENT_ID, HomeDocuments.ROOT)
                .add(Root.COLUMN_TITLE, context.getString(R.string.app_name))
                .add(Root.COLUMN_SUMMARY, context.getString(R.string.homes_provider_summary))
                .add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
                .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (documentId == HomeDocuments.ROOT) addRootRow(this) else addRow(this, documentId, fileOf(documentId))
        }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val context = context!!
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (parentDocumentId == HomeDocuments.ROOT) {
            val apps = AppData.root(context).list().orEmpty()
                .filter { InstalledApps.isValidId(it) && AppData.privateHome(context, it).isDirectory }.sorted()
            for (appId in apps) addRow(cursor, appId, home(appId))
        } else {
            val (appId, _) = HomeDocuments.parse(parentDocumentId) ?: throw FileNotFoundException(parentDocumentId)
            // Resolved once for the whole listing.
            val homePath = home(appId).canonicalPath
            for (child in fileOf(parentDocumentId).listFiles().orEmpty().sortedBy { it.name }) {
                if (!inside(child, homePath)) continue
                addRow(cursor, HomeDocuments.child(parentDocumentId, child.name), child)
            }
        }
        cursor.setNotificationUri(context.contentResolver, DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId))
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = fileOf(documentId)
        if (!file.isFile) throw FileNotFoundException(documentId)
        val parsed = ParcelFileDescriptor.parseMode(mode)
        if (mode == "r") return checkOpened(documentId, ParcelFileDescriptor.open(file, parsed))
        // Written until it is closed: its home's saved size is forgotten now and again then.
        forgetSize(documentId)
        return checkOpened(documentId, ParcelFileDescriptor.open(file, parsed, Handler(Looper.getMainLooper())) { forgetSize(documentId, changed = true) })
    }

    /**
     * [opened] if it is the file [documentId] names inside its home now. A running app can swap a
     * folder in its home for a link between [fileOf]'s check and the open; what was opened
     * through it is then another file, and is closed and refused.
     */
    private fun checkOpened(documentId: String, opened: ParcelFileDescriptor): ParcelFileDescriptor {
        val same = try {
            val a = Os.fstat(opened.fileDescriptor)
            val b = Os.stat(fileOf(documentId).path)
            a.st_dev == b.st_dev && a.st_ino == b.st_ino
        } catch (_: Exception) {
            false
        }
        if (same) return opened
        opened.close()
        throw FileNotFoundException(documentId)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val dir = fileOf(parentDocumentId)
        if (!dir.isDirectory) throw FileNotFoundException(parentDocumentId)
        val target = Names.unique(dir, Names.safe(displayName, "file"))
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException(displayName)
        forgetSize(parentDocumentId, changed = true)
        notifyChildren(parentDocumentId)
        return HomeDocuments.child(parentDocumentId, target.name)
    }

    override fun deleteDocument(documentId: String) {
        val (_, path) = HomeDocuments.parse(documentId) ?: throw FileNotFoundException(documentId)
        if (path.isEmpty()) throw UnsupportedOperationException("A home itself isn't deleted here")
        forgetSize(documentId)
        try {
            AndroidTarFs.deleteTree(fileOf(documentId))
        } finally {
            forgetSize(documentId, changed = true)
        }
        notifyChildren(documentId.substringBeforeLast('/'))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (_, path) = HomeDocuments.parse(documentId) ?: throw FileNotFoundException(documentId)
        if (path.isEmpty()) throw UnsupportedOperationException("A home itself isn't renamed here")
        val file = fileOf(documentId)
        val name = Names.safe(displayName, file.name)
        val parentId = documentId.substringBeforeLast('/')
        // Renamed to its own name: nothing to do (its name would otherwise count as taken).
        if (name == file.name) return documentId
        val target = Names.unique(file.parentFile!!, name)
        if (!file.renameTo(target)) throw FileNotFoundException(displayName)
        notifyChildren(parentId)
        return HomeDocuments.child(parentId, target.name)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        parentDocumentId == HomeDocuments.ROOT && HomeDocuments.parse(documentId) != null ||
            documentId.startsWith("$parentDocumentId/") && HomeDocuments.parse(documentId) != null

    override fun getDocumentType(documentId: String): String =
        if (documentId == HomeDocuments.ROOT) Document.MIME_TYPE_DIR else mimeOf(fileOf(documentId))

    private fun addRootRow(cursor: MatrixCursor) {
        cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, HomeDocuments.ROOT)
            .add(Document.COLUMN_DISPLAY_NAME, context!!.getString(R.string.app_name))
            .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            .add(Document.COLUMN_FLAGS, 0)
    }

    /** A row for [documentId], which is [file] (already checked to be inside its home). */
    private fun addRow(cursor: MatrixCursor, documentId: String, file: File) {
        val context = context!!
        val row = cursor.newRow().add(Document.COLUMN_DOCUMENT_ID, documentId)
        val (appId, path) = HomeDocuments.parse(documentId) ?: throw FileNotFoundException(documentId)
        val isHome = path.isEmpty()
        val flags = when {
            isHome -> Document.FLAG_DIR_SUPPORTS_CREATE
            file.isDirectory -> Document.FLAG_DIR_SUPPORTS_CREATE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            else -> Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        row.add(Document.COLUMN_DISPLAY_NAME, if (isHome) InstalledApps(context).name(appId) ?: appId else file.name)
            .add(Document.COLUMN_MIME_TYPE, mimeOf(file))
            .add(Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            .add(Document.COLUMN_FLAGS, flags)
    }

    private fun mimeOf(file: File): String = when {
        file.isDirectory -> Document.MIME_TYPE_DIR
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }

    private fun home(appId: String): File = AppData.privateHome(context!!, appId)

    /**
     * The file [documentId] names, which must exist inside its app's private home, as is its
     * folder. Returned with its folder resolved, so creating, renaming and deleting don't follow a
     * folder swapped for a link after this check; a swap between resolving and acting is still
     * followed (Android's API has no `unlinkat` or `renameat` to close it).
     */
    private fun fileOf(documentId: String): File {
        val (appId, path) = HomeDocuments.parse(documentId) ?: throw FileNotFoundException(documentId)
        val home = home(appId)
        if (!home.isDirectory) throw FileNotFoundException(documentId)
        val homePath = home.canonicalPath
        if (path.isEmpty()) return File(homePath)
        val file = File(home, path)
        val parent = file.parentFile!!
        if (!file.exists() || !inside(file, homePath) || !inside(parent, homePath)) throw FileNotFoundException(documentId)
        return File(parent.canonicalPath, file.name)
    }

    /** [file] is the home at [homePath] (resolved) or inside it once links are resolved. */
    private fun inside(file: File, homePath: String): Boolean {
        val path = file.canonicalPath
        return path == homePath || path.startsWith("$homePath/")
    }

    /** The private home [documentId] is in is changing, or has [changed] and is measured again ([SavedSizes]). */
    private fun forgetSize(documentId: String, changed: Boolean = false) {
        val (appId, _) = HomeDocuments.parse(documentId) ?: return
        if (changed) SavedSizes.changed(context!!, appId) else SavedSizes.forget(context!!, appId)
    }

    private fun notifyChildren(parentDocumentId: String) =
        context!!.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId), null)

    companion object {
        const val AUTHORITY = "com.garathon.flatdroid.homes"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )
    }
}
