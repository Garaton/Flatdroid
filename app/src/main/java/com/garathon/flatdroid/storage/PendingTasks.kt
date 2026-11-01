package com.garathon.flatdroid.storage

import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.util.writeTextAtomically
import java.io.File
import java.io.StringReader
import java.io.StringWriter
import java.util.Properties
import java.util.UUID

/** A [StorageTask] as strings: for the service's intent and for [PendingTasks]. */
object StorageTaskCodec {
    private const val TASK = "task"
    private const val APP_ID = "appId"
    private const val CLASH = "clash"
    private const val SHARED = "shared"

    fun encode(task: StorageTask): Map<String, String> {
        val values = linkedMapOf(TASK to nameOf(task))
        task.appId?.let { values[APP_ID] = it }
        when (task) {
            is StorageTask.ImportFiles -> if (task.target == ImportTarget.Shared) values[SHARED] = "true"
            is StorageTask.ImportSharedZip -> values[CLASH] = task.clash.name
            else -> Unit
        }
        return values
    }

    fun decode(values: Map<String, String>): StorageTask? {
        val appId = values[APP_ID]?.takeIf(InstalledApps::isValidId)
        return when (values[TASK]) {
            "import" -> when {
                values[SHARED] == "true" -> StorageTask.ImportFiles(ImportTarget.Shared)
                appId != null -> StorageTask.ImportFiles(ImportTarget.Private(appId))
                else -> null
            }
            "saveToDownload" -> StorageTask.SaveToDownload
            "exportTemp" -> appId?.let { StorageTask.ExportTempHome(it) }
            "exportHome" -> appId?.let { StorageTask.ExportHome(it) }
            "exportSettings" -> appId?.let { StorageTask.ExportAppFiles(it) }
            "exportFlatdroidSettings" -> StorageTask.ExportFlatdroidSettings
            "exportAllHomes" -> StorageTask.ExportAllHomes
            "exportAllSettings" -> StorageTask.ExportAllAppFiles
            "importAllHomes" -> StorageTask.ImportAllHomes
            "importAllSettings" -> StorageTask.ImportAllAppFiles
            "exportSharedDotfiles" -> StorageTask.ExportSharedDotfiles
            "importSharedDotfiles" -> StorageTask.ImportSharedDotfiles
            "importHome" -> appId?.let { StorageTask.ImportHome(it) }
            "importSettings" -> appId?.let { StorageTask.ImportAppFiles(it) }
            "importSharedZip" -> HomeZip.Clash.entries.firstOrNull { it.name == values[CLASH] }?.let { StorageTask.ImportSharedZip(it) }
            else -> null
        }
    }

    private fun nameOf(task: StorageTask): String = when (task) {
        is StorageTask.ImportFiles -> "import"
        StorageTask.SaveToDownload -> "saveToDownload"
        is StorageTask.ExportTempHome -> "exportTemp"
        is StorageTask.ExportHome -> "exportHome"
        is StorageTask.ExportAppFiles -> "exportSettings"
        StorageTask.ExportFlatdroidSettings -> "exportFlatdroidSettings"
        StorageTask.ExportAllHomes -> "exportAllHomes"
        StorageTask.ExportAllAppFiles -> "exportAllSettings"
        StorageTask.ImportAllHomes -> "importAllHomes"
        StorageTask.ImportAllAppFiles -> "importAllSettings"
        StorageTask.ExportSharedDotfiles -> "exportSharedDotfiles"
        StorageTask.ImportSharedDotfiles -> "importSharedDotfiles"
        is StorageTask.ImportHome -> "importHome"
        is StorageTask.ImportAppFiles -> "importSettings"
        is StorageTask.ImportSharedZip -> "importSharedZip"
    }
}

/**
 * Storage tasks not finished yet, one file each in [dir] (`files/storage-tasks/`, which survives
 * Flatdroid being stopped): a task that stops part-way can continue from it. A record holds the
 * task, the picked files (content URIs, readable through access Flatdroid keeps only while the
 * task is unfinished; [Record.resumable] is false when Android didn't grant that), and how many of
 * them, or of a zip's entries, are done.
 */
class PendingTasks(private val dir: File) {
    data class Record(
        val id: String,
        val task: StorageTask,
        val uris: List<String>,
        /** Picked files, or entries of the zip, already done. */
        val done: Int = 0,
        val resumable: Boolean = true,
        /** When it was asked for, in milliseconds since the epoch: the oldest is asked about first. */
        val created: Long = 0,
    )

    fun add(task: StorageTask, uris: List<String>, resumable: Boolean): Record =
        Record(UUID.randomUUID().toString(), task, uris, resumable = resumable, created = System.currentTimeMillis()).also(::save)

    /**
     * Records that [done] items are finished, after each one, so a continued task never does one
     * twice (which Keep both would turn into a copy). Not synced to disk: a kill keeps what was
     * written, and only a power loss could lose the last few.
     */
    fun progress(id: String, done: Int) {
        val record = get(id) ?: return
        if (record.done != done) save(record.copy(done = done), durable = false)
    }

    fun remove(id: String) {
        file(id).delete()
    }

    fun get(id: String): Record? = file(id).takeIf { it.isFile }?.let { read(id, it.readText()) }

    /** Every unfinished task; unreadable records are removed. */
    fun all(): List<Record> = dir.listFiles().orEmpty().filter { it.name.endsWith(SUFFIX) }.mapNotNull { file ->
        read(file.name.removeSuffix(SUFFIX), file.readText()) ?: run { file.delete(); null }
    }.sortedBy { it.created }

    private fun save(record: Record, durable: Boolean = true) {
        val props = Properties()
        for ((key, value) in StorageTaskCodec.encode(record.task)) props.setProperty(key, value)
        record.uris.forEachIndexed { i, uri -> props.setProperty("$URI$i", uri) }
        props.setProperty(DONE, record.done.toString())
        props.setProperty(RESUMABLE, record.resumable.toString())
        props.setProperty(CREATED, record.created.toString())
        file(record.id).writeTextAtomically(StringWriter().also { props.store(it, null) }.toString(), durable)
    }

    private fun read(id: String, text: String): Record? {
        if (!ID.matches(id)) return null
        val props = try {
            Properties().apply { load(StringReader(text)) }
        } catch (_: Exception) {
            return null
        }
        val task = StorageTaskCodec.decode(props.stringPropertyNames().associateWith { props.getProperty(it) }) ?: return null
        val uris = generateSequence(0) { it + 1 }.map { props.getProperty("$URI$it") }.takeWhile { it != null }.filterNotNull().toList()
        return Record(
            id, task, uris,
            done = props.getProperty(DONE)?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            resumable = props.getProperty(RESUMABLE) != "false",
            created = props.getProperty(CREATED)?.toLongOrNull() ?: 0,
        )
    }

    private fun file(id: String) = File(dir, id + SUFFIX)

    private companion object {
        const val SUFFIX = ".properties"
        const val URI = "uri."
        const val DONE = "done"
        const val RESUMABLE = "resumable"
        const val CREATED = "created"
        val ID = Regex("[0-9a-f-]{36}")
    }
}
