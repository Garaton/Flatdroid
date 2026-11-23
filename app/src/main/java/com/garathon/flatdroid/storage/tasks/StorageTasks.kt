package com.garathon.flatdroid.storage.tasks

import com.garathon.flatdroid.storage.AppFiles
import com.garathon.flatdroid.storage.backup.HomeZip
import com.garathon.flatdroid.storage.backup.SettingsBackup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A home files are imported into. */
sealed interface ImportTarget {
    data object Shared : ImportTarget
    data class Private(val appId: String) : ImportTarget
}

/**
 * One copy into or out of Flatdroid's storage, run by [StorageTaskService]. Files the user picked
 * travel beside the task, with their read grants.
 */
sealed interface StorageTask {
    /** The app whose files it changes, if one; that app can't be launched meanwhile. */
    val appId: String?

    /** It uses every app's files: no app can be launched meanwhile. */
    val allApps: Boolean get() = false

    /** Copies picked files into [target]'s Downloads folder. */
    data class ImportFiles(val target: ImportTarget) : StorageTask {
        override val appId get() = (target as? ImportTarget.Private)?.appId
    }

    /**
     * Copies files picked in a private home to the file the user chose in Android's save picker
     * (the last URI): one as it is, several in one zip.
     */
    data object ExportFiles : StorageTask {
        override val appId: String? get() = null
    }

    /** Zips a temporary home into the file picked in Android's save picker, then deletes it. */
    data class ExportTempHome(override val appId: String) : StorageTask

    /** Zips a private home to a picked file. */
    data class ExportHome(override val appId: String) : StorageTask

    /** Zips an app's settings and other files of its own ([AppFiles]) to a picked file. */
    data class ExportAppFiles(override val appId: String) : StorageTask

    /** Replaces a private home with a picked zip's contents. */
    data class ImportHome(override val appId: String) : StorageTask

    /** Replaces an app's settings and other files with a picked zip's ([AppFiles]). */
    data class ImportAppFiles(override val appId: String) : StorageTask

    /** Extracts a picked zip into the shared home, keeping or replacing files of the same name. */
    data class ImportSharedZip(val clash: HomeZip.Clash) : StorageTask {
        override val appId: String? get() = null
    }

    /** Zips every app's private home to a picked file, a folder per app ID. */
    data object ExportAllHomes : AllAppsTask

    /** Zips every app's settings and other files ([AppFiles]) to a picked file, a folder per app ID. */
    data object ExportAllAppFiles : AllAppsTask

    /** Replaces the private home of each app in a picked zip ([ExportAllHomes]); other apps' are left alone. */
    data object ImportAllHomes : AllAppsTask

    /** Replaces the settings and other files of each app in a picked zip ([ExportAllAppFiles]); other apps' are left alone. */
    data object ImportAllAppFiles : AllAppsTask

    /** Zips the hidden files apps share when they opt in ([com.garathon.flatdroid.storage.AppData.sharedDotfilesDir]) to a picked file. */
    data object ExportSharedDotfiles : AllAppsTask

    /** Replaces the shared hidden files with a picked zip's. */
    data object ImportSharedDotfiles : AllAppsTask

    /** Zips Flatdroid's own settings ([SettingsBackup]) to a picked file. */
    data object ExportFlatdroidSettings : StorageTask {
        override val appId: String? get() = null
    }
}

/** A task on every app's files, installed or not. */
sealed interface AllAppsTask : StorageTask {
    override val appId: String? get() = null
    override val allApps: Boolean get() = true
}

data class StorageTaskUi(
    val running: StorageTask? = null,
    /** Tasks waiting behind [running]. */
    val queued: List<StorageTask> = emptyList(),
    /** Bumped after every task, so measured sizes are refreshed. */
    val generation: Int = 0,
) {
    /** Whether a task for [appId], or for every app, is running or waiting. */
    fun busyWith(appId: String): Boolean = (listOfNotNull(running) + queued).any { it.appId == appId || it.allApps }
}

/** Process-wide state of storage tasks: written by [StorageTaskService], read by the UI and the launcher. */
object StorageTasks {
    private val _ui = MutableStateFlow(StorageTaskUi())
    val ui: StateFlow<StorageTaskUi> = _ui.asStateFlow()

    private val all get() = _ui.value.let { listOfNotNull(it.running) + it.queued }

    /** Whether a task for [appId] is running or waiting; the app isn't launched meanwhile. */
    fun busyWith(appId: String): Boolean = _ui.value.busyWith(appId)

    /** Whether any task is running or waiting. */
    val busy: Boolean get() = all.isNotEmpty()

    /**
     * Whether the temporary home is being exported, waits to be, or an export of it stopped
     * unfinished ([pending]); no launch may replace it meanwhile.
     */
    fun exportingTempHome(pending: PendingTasks): Boolean =
        all.any { it is StorageTask.ExportTempHome } || pending.all().any { it.task is StorageTask.ExportTempHome }

    internal fun queue(task: StorageTask) = _ui.update { it.copy(queued = it.queued + task) }

    /** Tasks sent to the service that it hasn't queued yet: the activity resumes from a picker before the service gets them. */
    private val arriving = java.util.concurrent.atomic.AtomicInteger()

    /** Whether a task is on its way to the service ([sent], not yet [received]). */
    val anyArriving: Boolean get() = arriving.get() > 0

    internal fun sent() { arriving.incrementAndGet() }

    internal fun received() { arriving.updateAndGet { maxOf(0, it - 1) } }

    internal fun start(task: StorageTask) = _ui.update { it.copy(running = task, queued = it.queued - task) }

    internal fun end() = _ui.update { it.copy(running = null, generation = it.generation + 1) }

    /** The service stopped: nothing runs or waits any more (sizes are measured again only if something was dropped). */
    internal fun reset() = _ui.update {
        if (it.running == null && it.queued.isEmpty()) it else it.copy(running = null, queued = emptyList(), generation = it.generation + 1)
    }
}
