package com.garathon.flatdroid.flatpak

import java.io.File

/**
 * Gets files the file check found changed downloaded again by a reinstall. Flatpak reuses every
 * object its repository has, and under link2symlink a deployed file and its repository object are
 * two links to one file in the store ([L2s]), so the changed content would just come back. This
 * removes the deployed file's link and its repository object's: the object is then missing (its
 * commit is marked partial by the caller) and the pull fetches it anew. The changed content itself
 * goes too when nothing else links to it (the link count in its name equals the links removed);
 * otherwise it stays for the other deployments still linking to it, whose own check reports them.
 * No link is followed, and only that content and its intermediate are deleted from the store.
 */
class ChangedContent(private val l2sDir: File, private val objectsDir: File, private val fs: LinkFs) {
    /** Removes the links of [files] (deployed files) and of their repository objects; returns how many went. */
    fun discard(files: List<File>): Int {
        val objectDirs = fs.list(objectsDir).orEmpty()
        var removed = 0
        for (file in files) {
            val target = fs.readlink(file)
            if (target == null) {
                // A plain file: the deployment's own copy, not shared with its repository object.
                if (!fs.isDirectory(file) && file.exists()) { fs.delete(file); removed++ }
                continue
            }
            val stored = L2s.storedName(target, l2sDir) ?: continue
            fs.delete(file)
            var links = 1
            // The object sits in one of the `xx/` folders under the name the store's file carries.
            val objectName = L2s.firstName(stored)
            for (dir in objectDirs) {
                val obj = File(File(objectsDir, dir), objectName)
                if (fs.readlink(obj)?.let { L2s.storedName(it, l2sDir) } == stored) { fs.delete(obj); links++ }
            }
            removed += links
            // Nothing else links to the changed content: it goes, with its intermediate.
            val intermediate = File(l2sDir, stored)
            val content = fs.readlink(intermediate)?.let { File(it).name }?.takeIf { it.startsWith("$stored.") } ?: continue
            if (content.substringAfterLast('.').toIntOrNull() == links) {
                fs.delete(File(l2sDir, content))
                fs.delete(intermediate)
            }
        }
        return removed
    }
}
