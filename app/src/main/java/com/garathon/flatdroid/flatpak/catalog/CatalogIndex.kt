package com.garathon.flatdroid.flatpak.catalog

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The catalog as Flatdroid keeps it in cache: [LIST] holds every app's list data (read whole when
 * the browser opens), [DETAILS] each app's page data, read one app at a time at the offset the list
 * gives. Both start with [FORMAT] and the catalog [version] they were made from.
 */
class CatalogIndex private constructor(
    val version: String,
    val apps: List<CatalogApp>,
    private val offsets: Map<String, Long>,
    private val detailsFile: File,
) {
    /** [apps] by ID, for an app's page, the queue's names and the typed-ID check. */
    private val byId: Map<String, CatalogApp> = apps.associateBy { it.id }

    /** The app with [id]; null when the catalog doesn't have it (no build for this phone, say). */
    fun app(id: String): CatalogApp? = byId[id]

    operator fun contains(id: String) = id in byId

    /**
     * [id]'s page data; null when it isn't in the index or the file can't be read: removed (Android's
     * Clear cache), or replaced by a newer index meanwhile, whose offsets differ.
     */
    fun details(id: String): CatalogDetails? {
        val offset = offsets[id] ?: return null
        return try {
            RandomAccessFile(detailsFile, "r").use { file ->
                // The header straight from the file (it reads like a DataInputStream), then one buffered stream at the app.
                if (file.readInt() != FORMAT || file.readUTF() != version) return null
                file.seek(offset)
                readDetails(DataInputStream(BufferedInputStream(FileInputStream(file.fd))))
            }
        } catch (_: IOException) {
            null
        }
    }

    /** Whether its files are still there (Android's Clear cache removes them under a running Flatdroid). */
    fun exists() = detailsFile.isFile && File(detailsFile.parentFile, LIST).isFile

    companion object {
        const val LIST = "list"
        const val DETAILS = "details"
        private const val FORMAT = 1

        /**
         * Writes the entries [entries] produces (passed one at a time to its callback) into [dir],
         * through a folder beside it, so a half-written index never replaces a whole one.
         */
        fun write(dir: File, version: String, entries: ((CatalogEntry) -> Unit) -> Unit): CatalogIndex {
            val temp = File(dir.parentFile, dir.name + ".new").apply { deleteRecursively(); mkdirs() }
            val apps = mutableListOf<CatalogApp>()
            val offsets = mutableListOf<Long>()
            // DataOutputStream counts what it wrote: that is each app's offset.
            DataOutputStream(BufferedOutputStream(File(temp, DETAILS).outputStream())).use { details ->
                details.writeInt(FORMAT)
                details.writeUTF(version)
                entries { entry ->
                    offsets += details.size().toLong()
                    writeDetails(details, entry.details)
                    apps += entry.app
                }
            }
            DataOutputStream(BufferedOutputStream(File(temp, LIST).outputStream())).use { out ->
                out.writeInt(FORMAT)
                out.writeUTF(version)
                out.writeInt(apps.size)
                apps.forEachIndexed { i, app -> writeApp(out, app); out.writeLong(offsets[i]) }
            }
            dir.deleteRecursively()
            if (!temp.renameTo(dir)) throw IOException("Can't move the catalog index into place")
            // What was just written is all here already: not read back.
            return CatalogIndex(version, apps, apps.indices.associate { apps[it].id to offsets[it] }, File(dir, DETAILS))
        }

        /** The catalog version of the index in [dir], from its header only; null when there is none or it is of another format. */
        fun version(dir: File): String? = try {
            DataInputStream(BufferedInputStream(File(dir, LIST).inputStream(), 512)).use { input ->
                if (input.readInt() == FORMAT) input.readUTF() else null
            }
        } catch (_: IOException) {
            null
        }

        /** The index in [dir]; null when there is none or it is of another format. */
        fun read(dir: File): CatalogIndex? {
            val list = File(dir, LIST)
            val details = File(dir, DETAILS)
            if (!list.isFile || !details.isFile) return null
            return try {
                DataInputStream(BufferedInputStream(list.inputStream())).use { input ->
                    if (input.readInt() != FORMAT) return null
                    val version = input.readUTF()
                    val count = input.readInt()
                    val apps = ArrayList<CatalogApp>(count)
                    val offsets = HashMap<String, Long>(count * 2)
                    repeat(count) {
                        val app = readApp(input)
                        apps += app
                        offsets[app.id] = input.readLong()
                    }
                    CatalogIndex(version, apps, offsets, details)
                }
            } catch (_: IOException) {
                null
            }
        }

        private fun writeApp(out: DataOutputStream, app: CatalogApp) {
            out.writeUTF(app.id)
            writeLong(out, app.name)
            writeLong(out, app.summary)
            writeOptional(out, app.developer)
            out.writeBoolean(app.verified)
            writeList(out, app.categories)
            writeList(out, app.keywords)
            writeOptional(out, app.icon)
            out.writeBoolean(app.touch)
        }

        private fun readApp(input: DataInputStream) = CatalogApp(
            id = input.readUTF(),
            name = readLong(input),
            summary = readLong(input),
            developer = readOptional(input),
            verified = input.readBoolean(),
            categories = readList(input),
            keywords = readList(input),
            icon = readOptional(input),
            touch = input.readBoolean(),
        )

        private fun writeDetails(out: DataOutputStream, details: CatalogDetails) {
            out.writeInt(details.description.size)
            for (block in details.description) when (block) {
                is TextBlock.Paragraph -> { out.writeByte(0); writeLong(out, block.text) }
                is TextBlock.Items -> { out.writeByte(if (block.ordered) 2 else 1); out.writeInt(block.items.size); block.items.forEach { writeLong(out, it) } }
            }
            writeOptional(out, details.license)
            out.writeInt(details.urls.size)
            for ((kind, link) in details.urls) { out.writeUTF(kind); writeLong(out, link) }
            out.writeInt(details.screenshots.size)
            for (shot in details.screenshots) { writeLong(out, shot.image); writeOptional(out, shot.full); writeOptional(out, shot.caption) }
        }

        private fun readDetails(input: DataInputStream): CatalogDetails {
            val description = List(readCount(input)) {
                when (val kind = input.readByte().toInt()) {
                    0 -> TextBlock.Paragraph(readLong(input))
                    else -> TextBlock.Items(kind == 2, List(readCount(input)) { readLong(input) })
                }
            }
            val license = readOptional(input)
            val urls = List(readCount(input)) { input.readUTF() to readLong(input) }
            val screenshots = List(readCount(input)) { Screenshot(readLong(input), readOptional(input), readOptional(input)) }
            return CatalogDetails(description, license, urls, screenshots)
        }

        /** writeUTF is limited to 64 KB; a description paragraph or a link may be longer. */
        private fun writeLong(out: DataOutputStream, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.writeInt(bytes.size)
            out.write(bytes)
        }

        private fun readLong(input: DataInputStream): String {
            val size = input.readInt()
            if (size !in 0..MAX_TEXT) throw IOException("Bad text length $size")
            return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
        }

        private fun writeOptional(out: DataOutputStream, text: String?) {
            out.writeBoolean(text != null)
            if (text != null) writeLong(out, text)
        }

        private fun readOptional(input: DataInputStream): String? = if (input.readBoolean()) readLong(input) else null

        private fun writeList(out: DataOutputStream, items: List<String>) {
            out.writeInt(items.size)
            items.forEach { writeLong(out, it) }
        }

        private fun readList(input: DataInputStream): List<String> = List(readCount(input)) { readLong(input) }

        /** A count of things that follow, checked: a damaged file mustn't make a list of millions. */
        private fun readCount(input: DataInputStream): Int {
            val size = input.readInt()
            if (size !in 0..MAX_ITEMS) throw IOException("Bad list length $size")
            return size
        }

        private const val MAX_TEXT = 1 shl 20
        private const val MAX_ITEMS = 10_000
    }
}
