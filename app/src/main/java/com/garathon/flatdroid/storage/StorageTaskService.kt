package com.garathon.flatdroid.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.garathon.flatdroid.MainActivity
import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.setup.AndroidTarFs
import com.garathon.flatdroid.setup.deleteTree
import com.garathon.flatdroid.setup.SetupController
import com.garathon.flatdroid.setup.useOwnGroupTree
import com.garathon.flatdroid.util.CountingInputStream
import com.garathon.flatdroid.util.DebugLog
import com.garathon.flatdroid.util.copyWithProgress
import com.garathon.flatdroid.util.removeAtomicLeftovers
import com.garathon.flatdroid.util.replaceWith
import com.garathon.flatdroid.util.writeAtomically
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Copies files into and out of Flatdroid's storage in the foreground (dataSync), one task at a
 * time in the order asked, with the progress in a notification and the result in another. Every
 * task is recorded in [PendingTasks] until it ends, with how far it got, so one that stops
 * part-way (Flatdroid stopped or killed) can continue; access to the files the user picked is
 * kept only that long.
 */
class StorageTaskService : Service() {
    // Queueing and the worker's start and end run on the main thread, as onStartCommand does, so a
    // task can't arrive unseen while the worker finishes.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = Channel<PendingTasks.Record>(Channel.UNLIMITED)
    private val pending by lazy { pendingTasks(this) }
    private var worker: Job? = null
    private var lastPercent = -1
    private var lastUpdate = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val record = intent?.let(::recordOf)
        startInForeground(progressNotification(record?.task ?: StorageTasks.ui.value.running, null))
        if (record == null) {
            if (StorageTasks.ui.value.running == null && StorageTasks.ui.value.queued.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        StorageTasks.queue(record.task)
        tasks.trySend(record)
        if (worker == null) worker = scope.launch { work() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        // Queued tasks die with the service; left in the state, their apps would never launch.
        StorageTasks.reset()
        super.onDestroy()
    }

    /** Android 15 limits dataSync services to 6 hours a day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private suspend fun work() {
        while (true) {
            val record = tasks.tryReceive().getOrNull() ?: break
            val task = record.task
            StorageTasks.start(task)
            lastPercent = -1
            lastUpdate = 0L
            updateProgress(task, null)
            val result = try {
                withContext(Dispatchers.IO) {
                    forgetSizes(task, done = false)
                    perform(record)
                }
            } catch (e: Exception) {
                failed(task, e)
            } finally {
                // Ended, done or failed: nothing to continue, and the picked files aren't needed any more.
                withContext(Dispatchers.IO) {
                    forgetSizes(task, done = true)
                    pending.remove(record.id)
                    releaseAccess(this@StorageTaskService, record.uris, pending.all())
                }
                StorageTasks.end()
            }
            notify(result.first, result.second)
        }
        worker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * The result's title and text. A task continued after a stop goes on from [PendingTasks.Record.done]
     * where it can (picked files, a zip into the shared home); exports and imports that replace
     * files start over, since they changed nothing before they stopped.
     */
    private fun perform(record: PendingTasks.Record): Pair<String, String> {
        val task = record.task
        val uris = record.uris.map(Uri::parse)
        return when (task) {
        is StorageTask.ImportFiles -> {
            val home = when (val target = task.target) {
                ImportTarget.Shared -> sharedHome()
                is ImportTarget.Private -> privateHome(target.appId)
            }
            val dir = HomeFolders.importDir(home)
            val result = copyIn(record, uris, dir, home.takeIf { task.target == ImportTarget.Shared })
            getString(R.string.storage_imported_title) to listOfNotNull(
                result.copied.takeIf { it > 0 || result.adopted + result.present == 0 }
                    ?.let { resources.getQuantityString(R.plurals.storage_imported, it, it, where(task.target, dir, home)) + "." },
                result.adopted.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.storage_adopted, it, it) },
                result.present.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.storage_present, it, it) },
            ).joinToString(" ")
        }
        StorageTask.SaveToDownload -> {
            for (i in record.done until uris.size) {
                val uri = uris[i]
                val name = Names.safe(queryName(uri), getString(R.string.storage_imported_file_name, i + 1))
                Downloads.save(this, name, contentResolver.getType(uri) ?: "application/octet-stream") { out ->
                    open(uri).use { it.copyWithProgress(out) }
                }
                pending.progress(record.id, i + 1)
                updateProgress(task, (i + 1).toFloat() / uris.size)
            }
            val count = uris.size
            getString(R.string.storage_saved_title) to resources.getQuantityString(R.plurals.storage_saved, count, count)
        }
        is StorageTask.ExportTempHome -> {
            val home = TempHomes.home(this)
            if (TempHomes.owner(this) != task.appId || !home.isDirectory) throw IOException(getString(R.string.storage_temp_home_gone))
            val saved = zipToDownload(task, getString(R.string.storage_temp_home_zip, label(task.appId), Names.timestamp()), listOf(HomeZip.Part(home, exclude = HomeZip.SETTINGS_DIRS)))
            TempHomes.delete(this)
            getString(R.string.storage_exported_title) to getString(R.string.storage_exported, saved)
        }
        is StorageTask.ExportHome -> {
            val home = AppData.privateHome(this, task.appId)
            if (!home.isDirectory) throw IOException(getString(R.string.storage_no_private_home, label(task.appId)))
            val saved = zipToDownload(task, getString(R.string.storage_home_zip, label(task.appId), Names.timestamp()), listOf(HomeZip.Part(home, exclude = HomeZip.SETTINGS_DIRS)))
            getString(R.string.storage_exported_title) to getString(R.string.storage_exported, saved)
        }
        is StorageTask.ExportAppFiles -> {
            val saved = zipToDownload(task, getString(R.string.storage_settings_zip, label(task.appId), Names.timestamp()), AppFiles.parts(appData(task.appId), task.appId))
            getString(R.string.storage_exported_title) to getString(R.string.storage_exported, saved)
        }
        StorageTask.ExportAllHomes -> {
            val parts = appsWithData().mapNotNull { appId ->
                AppData.privateHome(this, appId).takeIf { it.isDirectory }?.let { HomeZip.Part(it, "$appId/", HomeZip.SETTINGS_DIRS) }
            }
            if (parts.isEmpty()) throw IOException(getString(R.string.storage_no_private_homes))
            val saved = zipToDownload(task, getString(R.string.storage_all_homes_zip, Names.timestamp()), parts)
            getString(R.string.storage_exported_title) to getString(R.string.storage_exported, saved)
        }
        StorageTask.ExportAllAppFiles -> {
            val parts = appsWithData().flatMap { appId -> AppFiles.parts(appData(appId), appId).map { it.copy(prefix = "$appId/${it.prefix}") } }
            if (parts.none { it.dir.isDirectory }) throw IOException(getString(R.string.storage_no_app_settings))
            val saved = zipToDownload(task, getString(R.string.storage_all_settings_zip, Names.timestamp()), parts)
            getString(R.string.storage_exported_title) to getString(R.string.storage_exported, saved)
        }
        StorageTask.ImportAllHomes -> {
            val staging = staging(task)
            extractFrom(task, uris.single(), staging) { path -> appPath(path)?.let { (appId, rest) -> File(staging, "$appId/$rest") } }
            val apps = stagedApps(staging)
            for (appId in apps) putInPlace(File(staging, appId), AppData.privateHome(this, appId).also { it.parentFile?.mkdirs() })
            AndroidTarFs.deleteTree(staging)
            getString(R.string.storage_imported_title) to resources.getQuantityString(R.plurals.storage_imported_all_homes, apps.size, apps.size)
        }
        StorageTask.ImportAllAppFiles -> {
            val staging = staging(task)
            extractFrom(task, uris.single(), staging) { path ->
                appPath(path)?.let { (appId, rest) -> if (rest.isEmpty()) File(staging, appId) else AppFiles.destination(File(staging, appId), appId, rest) }
            }
            val apps = stagedApps(staging)
            for (appId in apps) {
                val appData = appData(appId).apply { mkdirs() }
                // As for one app: a directory the zip doesn't have becomes empty.
                for ((dir, _) in AppFiles.layout(appId)) putInPlace(File(staging, "$appId/$dir").apply { mkdirs() }, File(appData, dir))
            }
            AndroidTarFs.deleteTree(staging)
            getString(R.string.storage_imported_title) to resources.getQuantityString(R.plurals.storage_imported_all_settings, apps.size, apps.size)
        }
        StorageTask.ExportFlatdroidSettings -> {
            val saved = Downloads.save(this, getString(R.string.storage_flatdroid_settings_zip, Names.timestamp()), "application/zip") { out ->
                SettingsBackup.write(out, File(filesDir, SetupController.SETTINGS_FILE), AppData.settingsDir(this))
            }
            getString(R.string.storage_exported_title) to getString(R.string.storage_exported, saved)
        }
        is StorageTask.ImportHome -> {
            val staging = staging(task)
            extractFrom(task, uris.single(), staging) { File(staging, it) }
            putInPlace(staging, AppData.privateHome(this, task.appId))
            getString(R.string.storage_imported_title) to getString(R.string.storage_imported_home, label(task.appId))
        }
        is StorageTask.ImportAppFiles -> {
            val staging = staging(task)
            extractFrom(task, uris.single(), staging) { AppFiles.destination(staging, task.appId, it) }
            val appData = appData(task.appId)
            // A directory the zip doesn't have becomes empty: the zip is the whole of them.
            for ((dir, _) in AppFiles.layout(task.appId)) putInPlace(File(staging, dir).apply { mkdirs() }, File(appData, dir))
            AndroidTarFs.deleteTree(staging)
            getString(R.string.storage_imported_title) to getString(R.string.storage_imported_settings, label(task.appId))
        }
        is StorageTask.ImportSharedZip -> {
            val home = sharedHome()
            val result = extractFrom(
                task, uris.single(), home, task.clash, record.done,
                onEntry = { pending.progress(record.id, it) },
                onResume = { dest -> dest.parentFile?.let { removeAtomicLeftovers(it, record.created) } },
            ) { File(home, it) }
            getString(R.string.storage_imported_title) to
                resources.getQuantityString(R.plurals.storage_imported, result.files, result.files, SharedHome.display(home)) + "." +
                (if (result.kept > 0) " " + resources.getQuantityString(R.plurals.storage_kept, result.kept, result.kept) else "") +
                (if (result.skipped > 0) " " + resources.getQuantityString(R.plurals.storage_skipped, result.skipped, result.skipped) else "")
        }
        }
    }

    /** Saved sizes that [task] changes: forgotten before it, and once it is [done] measured again ([SavedSizes]). */
    private fun forgetSizes(task: StorageTask, done: Boolean) {
        when (task) {
            is StorageTask.ImportFiles, is StorageTask.ImportHome, is StorageTask.ImportAppFiles -> task.appId?.let {
                if (done) SavedSizes.changed(this, it) else SavedSizes.forget(this, it)
            }
            StorageTask.ImportAllHomes, StorageTask.ImportAllAppFiles -> if (done) SavedSizes.changedApps(this) else SavedSizes.forgetApps(this)
            else -> Unit
        }
    }

    /** Apps with files in `files/appdata/`, installed or not. */
    private fun appsWithData(): List<String> = AppData.root(this).list().orEmpty().filter(InstalledApps::isValidId).sorted()

    /** A zip of every app's files: an entry's app ID (its first folder) and its path under it. */
    private fun appPath(path: String): Pair<String, String>? {
        val appId = path.substringBefore('/')
        return if (InstalledApps.isValidId(appId)) appId to path.substringAfter('/', "") else null
    }

    /** The apps a zip of every app's files held, as extracted into [staging]; a zip without any isn't one. */
    private fun stagedApps(staging: File): List<String> =
        staging.list().orEmpty().filter { InstalledApps.isValidId(it) && File(staging, it).isDirectory }.sorted()
            .ifEmpty { throw IOException(getString(R.string.storage_no_apps_in_zip)) }

    private fun failed(task: StorageTask, e: Exception): Pair<String, String> {
        // Asked again in Flatdroid instead of being deleted unseen.
        if (task is StorageTask.ExportTempHome) TempHomePrompt.askAgain(task.appId)
        stagingDir(task)?.let { AndroidTarFs.deleteTree(it) }
        return getString(R.string.storage_failed_title) to (e.message ?: e.toString())
    }

    /**
     * Puts an import staged in the cache where [target] is (see replaceWith: the old one goes only
     * once the new one is in place), then gives it the app's own group, since files made in the
     * cache keep the cache's, which Android counts as cache wherever they are.
     */
    private fun putInPlace(staged: File, target: File) {
        target.replaceWith(staged, AndroidTarFs::deleteTree)
        AndroidTarFs.useOwnGroupTree(target)
    }

    private fun sharedHome(): File = SharedHome.ensure(this)
        ?: throw IOException(getString(R.string.storage_shared_home_missing, SharedHome.display(SharedHome.dir(this))))

    private fun privateHome(appId: String): File = AppData.privateHome(this, appId).also { HomeFolders.create(it) }

    private fun appData(appId: String) = File(AppData.root(this), appId)

    /** Where an import that replaces files lands first, in the cache; null for other tasks. */
    private fun stagingDir(task: StorageTask): File? = when (task) {
        is StorageTask.ImportHome -> "${task.appId}-home"
        is StorageTask.ImportAppFiles -> "${task.appId}-settings"
        StorageTask.ImportAllHomes -> "all-homes"
        StorageTask.ImportAllAppFiles -> "all-settings"
        else -> null
    }?.let { File(cacheDir.canonicalFile, "import/$it") }

    /** [task]'s staging folder, new and empty. */
    private fun staging(task: StorageTask): File = stagingDir(task)!!.also { AndroidTarFs.deleteTree(it); it.mkdirs() }

    private fun open(uri: Uri): InputStream = contentResolver.openInputStream(uri) ?: throw IOException(getString(R.string.storage_unreadable))

    private fun zipToDownload(task: StorageTask, name: String, parts: List<HomeZip.Part>): String =
        Downloads.save(this, Names.safe(name, "export.zip"), "application/zip") { out ->
            HomeZip.write(parts, out, AndroidTarFs::isSymlink) { done, total -> updateProgress(task, if (total > 0) done.toFloat() / total else null) }
        }

    private fun extractFrom(
        task: StorageTask,
        uri: Uri,
        base: File,
        clash: HomeZip.Clash = HomeZip.Clash.REPLACE,
        skipEntries: Int = 0,
        onEntry: (Int) -> Unit = {},
        onResume: (File) -> Unit = {},
        destination: (String) -> File?,
    ): HomeZip.Extracted {
        val size = querySize(uri)
        // Progress by the zip's bytes read, since its uncompressed size isn't known in advance.
        val counting = CountingInputStream(open(uri)) { read -> updateProgress(task, if (size != null && size > 0) read.toFloat() / size else null) }
        return counting.use { HomeZip.extract(it, base, destination, clash, skipEntries = skipEntries, onEntry = onEntry, onResume = onResume) }
    }

    /** What [copyIn] did: files copied in, files already in the shared home made Flatdroid's in place, files it could already see. */
    private class Copied(val copied: Int, val adopted: Int, val present: Int)

    /**
     * Copies the picked files into [dir], from [PendingTasks.Record.done] on. With [sharedHome], a
     * picked file that is already in it isn't copied: one Flatdroid can see is left as it is, and
     * one it can't (another app put it there) is adopted ([adopt]).
     */
    private fun copyIn(record: PendingTasks.Record, uris: List<Uri>, dir: File, sharedHome: File?): Copied {
        val task = record.task
        val sizes = uris.map { uri -> querySize(uri) }
        val total = sizes.takeIf { list -> list.all { it != null } }?.sumOf { it!! }
        var done = sizes.take(record.done).sumOf { it ?: 0L }
        var copied = 0
        var adopted = 0
        var present = 0
        if (record.done > 0) {
            // Where the file the stop cut off was being written: the import folder, or beside the file being adopted.
            removeAtomicLeftovers(dir, record.created)
            uris.getOrNull(record.done)?.let { PickedFiles.pathOf(this, it) }?.parentFile?.let { removeAtomicLeftovers(it, record.created) }
        }
        for (i in record.done until uris.size) {
            val uri = uris[i]
            val before = done
            val onCopied = { bytes: Long ->
                done = before + bytes
                updateProgress(task, if (total != null && total > 0) done.toFloat() / total else (i.toFloat() / uris.size))
            }
            val inPlace = sharedHome?.let { home -> PickedFiles.pathOf(this, uri)?.takeIf { PickedFiles.inside(it, home) } }
            when {
                inPlace != null && PickedFiles.visible(inPlace) -> present++
                inPlace != null -> if (adopt(uri, inPlace, onCopied)) adopted++ else copied++
                else -> {
                    val name = Names.safe(queryName(uri), getString(R.string.storage_imported_file_name, i + 1))
                    Names.unique(dir, name).writeAtomically(durable = false) { output -> open(uri).use { it.copyWithProgress(output, onCopied) } }
                    copied++
                }
            }
            pending.progress(record.id, i + 1)
        }
        return Copied(copied, adopted, present)
    }

    /**
     * A file in the shared home that Flatdroid can't see, since another app made it: copied beside
     * itself, which makes the copy Flatdroid's, and put in its place once the original is deleted
     * through the access the picker gave. Returns false when the original couldn't be deleted
     * (the provider or Android refused); the copy then stays beside it, numbered.
     */
    private fun adopt(uri: Uri, path: File, onCopied: (Long) -> Unit): Boolean {
        val dir = path.parentFile ?: throw IOException(path.path)
        val copy = File.createTempFile(".${path.name}.", ".part", dir)
        try {
            open(uri).use { input -> copy.outputStream().use { input.copyWithProgress(it, onCopied) } }
        } catch (e: Exception) {
            copy.delete()
            throw e
        }
        val deleted = runCatching { DocumentsContract.deleteDocument(contentResolver, uri) }.getOrDefault(false)
        if (deleted && copy.renameTo(path)) return true
        // A hidden file can still hold a name, so each free-looking name is tried in turn.
        val base = path.nameWithoutExtension.ifEmpty { path.name }
        val ext = if (base == path.name) "" else ".${path.extension}"
        for (n in 1..MAX_COPIES) {
            val candidate = File(dir, "$base ($n)$ext")
            if (!candidate.exists() && copy.renameTo(candidate)) return false
        }
        throw IOException("No free name for ${path.name}")
    }

    private fun queryName(uri: Uri): String? = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }

    private fun querySize(uri: Uri): Long? = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null
    }

    private fun where(target: ImportTarget, dir: File, home: File): String {
        val inside = dir.path.removePrefix(home.path).trimStart('/')
        val base = when (target) {
            ImportTarget.Shared -> SharedHome.display(home)
            is ImportTarget.Private -> getString(R.string.storage_private_home_of, label(target.appId))
        }
        return if (inside.isEmpty()) base else "$base/$inside"
    }

    private fun label(appId: String) = InstalledApps(applicationContext).name(appId) ?: appId

    private fun updateProgress(task: StorageTask, fraction: Float?) {
        val percent = fraction?.let { (it.coerceIn(0f, 1f) * 100).toInt() } ?: -1
        // Android drops a package's notifications, the result's too, once it posts too many too fast.
        val now = SystemClock.elapsedRealtime()
        if (percent == lastPercent || now - lastUpdate < PROGRESS_INTERVAL_MS) return
        lastPercent = percent
        lastUpdate = now
        getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progressNotification(task, fraction))
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, PROGRESS_ID, notification, type)
    }

    private fun progressNotification(task: StorageTask?, fraction: Float?): Notification {
        createChannel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val title = task?.let { describe(this, it) } ?: getString(R.string.storage_working)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .build()
    }

    private fun notify(title: String, text: String) {
        createChannel(this)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(RESULT_ID, notification)
    }

    companion object {
        internal const val CHANNEL_ID = "storage"
        private const val PROGRESS_ID = 30
        private const val RESULT_ID = 31
        private const val EXTRA_PREFIX = "task."
        private const val EXTRA_RESUMABLE = "resumable"
        private const val EXTRA_RECORD = "record"
        private const val MAX_COPIES = 9999
        private const val PROGRESS_INTERVAL_MS = 500L

        internal fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.storage_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }

        /**
         * Runs [task]; [uris] are the files the user picked for it, readable through this
         * intent's grant and, when [resumable], through access Flatdroid took to keep until the
         * task ends ([releaseAccess]), so it can continue after a stop.
         */
        fun start(context: Context, task: StorageTask, uris: List<Uri> = emptyList(), resumable: Boolean = true) {
            val intent = Intent(context, StorageTaskService::class.java)
            for ((key, value) in StorageTaskCodec.encode(task)) intent.putExtra(EXTRA_PREFIX + key, value)
            intent.putExtra(EXTRA_RESUMABLE, resumable)
            if (uris.isNotEmpty()) {
                val clip = ClipData.newRawUri(null, uris.first())
                for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Continues an unfinished task ([PendingTasks]) from where it stopped. */
        fun resume(context: Context, record: PendingTasks.Record) {
            ContextCompat.startForegroundService(context, Intent(context, StorageTaskService::class.java).putExtra(EXTRA_RECORD, record.id))
        }

        fun pendingTasks(context: Context) = PendingTasks(File(context.filesDir, "storage-tasks"))

        /**
         * Gives back access Flatdroid kept to picked files: [uris], or with none, every one no
         * unfinished task in [unfinished] still needs.
         */
        fun releaseAccess(context: Context, uris: List<String>?, unfinished: List<PendingTasks.Record>) {
            val needed = unfinished.flatMap { it.uris }.toSet()
            var released = 0
            for (permission in context.contentResolver.persistedUriPermissions) {
                val uri = permission.uri.toString()
                if (uri in needed || (uris != null && uri !in uris)) continue
                val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                if (runCatching { context.contentResolver.releasePersistableUriPermission(permission.uri, flags) }.isSuccess) released++
            }
            if (released > 0) DebugLog.i("released access to $released picked files; ${context.contentResolver.persistedUriPermissions.size} still kept")
        }

        /** What [task] does, as the user reads it. */
        fun describe(context: Context, task: StorageTask): String {
            fun label(appId: String) = InstalledApps(context).name(appId) ?: appId
            return when (task) {
                is StorageTask.ImportFiles -> when (val target = task.target) {
                    ImportTarget.Shared -> context.getString(R.string.storage_importing)
                    is ImportTarget.Private -> context.getString(R.string.storage_importing_into, context.getString(R.string.storage_private_home_of, label(target.appId)))
                }
                StorageTask.SaveToDownload -> context.getString(R.string.storage_saving)
                is StorageTask.ExportTempHome -> context.getString(R.string.storage_exporting_temp_home, label(task.appId))
                is StorageTask.ExportHome -> context.getString(R.string.storage_exporting_home, label(task.appId))
                is StorageTask.ExportAppFiles -> context.getString(R.string.storage_exporting_settings, label(task.appId))
                is StorageTask.ImportHome -> context.getString(R.string.storage_importing_home, label(task.appId))
                is StorageTask.ImportAppFiles -> context.getString(R.string.storage_importing_settings, label(task.appId))
                is StorageTask.ImportSharedZip -> context.getString(R.string.storage_importing_shared_zip)
                StorageTask.ExportFlatdroidSettings -> context.getString(R.string.storage_exporting_flatdroid_settings)
                StorageTask.ExportAllHomes -> context.getString(R.string.storage_exporting_all_homes)
                StorageTask.ExportAllAppFiles -> context.getString(R.string.storage_exporting_all_settings)
                StorageTask.ImportAllHomes -> context.getString(R.string.storage_importing_all_homes)
                StorageTask.ImportAllAppFiles -> context.getString(R.string.storage_importing_all_settings)
            }
        }
    }

    /** A new task from [intent] (recorded now), or the unfinished one it names. */
    private fun recordOf(intent: Intent): PendingTasks.Record? {
        intent.getStringExtra(EXTRA_RECORD)?.let { id -> return pending.get(id)?.takeIf { record -> StorageTasks.ui.value.queued.none { it == record.task } && StorageTasks.ui.value.running != record.task } }
        val values = intent.extras?.keySet().orEmpty().filter { it.startsWith(EXTRA_PREFIX) }
            .associate { it.removePrefix(EXTRA_PREFIX) to intent.getStringExtra(it).orEmpty() }
        val task = StorageTaskCodec.decode(values) ?: return null
        val uris = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri?.toString() } }.orEmpty()
        return pending.add(task, uris, intent.getBooleanExtra(EXTRA_RESUMABLE, true))
    }
}
