package com.garathon.flatdroid.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PendingTasksTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun everyTaskSurvivesTheCodec() {
        val tasks = listOf(
            StorageTask.ImportFiles(ImportTarget.Shared), StorageTask.ImportFiles(ImportTarget.Private("org.kde.kate")),
            StorageTask.SaveToDownload, StorageTask.ExportTempHome("a.App"), StorageTask.ExportHome("a.App"),
            StorageTask.ExportAppFiles("a.App"), StorageTask.ExportFlatdroidSettings, StorageTask.ImportHome("a.App"),
            StorageTask.ImportAppFiles("a.App"), StorageTask.ExportAllHomes, StorageTask.ExportAllAppFiles,
            StorageTask.ImportAllHomes, StorageTask.ImportAllAppFiles,
        ) + HomeZip.Clash.entries.map { StorageTask.ImportSharedZip(it) }
        for (task in tasks) assertEquals(task, StorageTaskCodec.decode(StorageTaskCodec.encode(task)))
        assertNull(StorageTaskCodec.decode(mapOf("task" to "importHome", "appId" to "../x")))
    }

    @Test
    fun recordsKeepTasksUntilRemoved() {
        val pending = PendingTasks(File(tmp.root, "storage-tasks"))
        val uris = listOf("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fa.zip")
        val record = pending.add(StorageTask.ImportSharedZip(HomeZip.Clash.SKIP), uris, resumable = true)
        pending.progress(record, 42)
        assertEquals(record.copy(done = 42), pending.get(record.id))
        val export = pending.add(StorageTask.ExportHome("a.App"), emptyList(), resumable = false)
        assertEquals(setOf(record.id, export.id), pending.all().map { it.id }.toSet())
        assertFalse(pending.get(export.id)!!.resumable)
        pending.remove(record.id)
        assertEquals(listOf(export.id), pending.all().map { it.id })
        File(tmp.root, "storage-tasks/00000000-0000-0000-0000-000000000000.properties").writeText("task=nonsense\n")
        assertEquals(listOf(export.id), pending.all().map { it.id })
    }
}
