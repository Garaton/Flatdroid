package com.garathon.flatdroid.storage

import android.content.Context
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.AppData
import com.garathon.flatdroid.flatpak.FlatpakInstallation
import com.garathon.flatdroid.flatpak.InstalledApps
import com.garathon.flatdroid.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Sizes measured once and saved, one file per name in [dir], so storage isn't walked again every
 * time it is shown. Whatever changes what a file measures deletes it before the change and again
 * once done ([forget]): a change that stops part-way leaves nothing stale, and a file that is
 * missing or unreadable (cut off while written) is measured again. A measurement that a [forget]
 * overtook isn't saved; one name is measured once at a time (a second caller waits for it).
 * What a forget set aside is still there as the [last] sizes, for showing until new ones are in.
 */
class SizeStore(private val dir: File, private val onMeasured: (name: String, millis: Long) -> Unit = { _, _ -> }) {
    private val generations = HashMap<String, Int>()
    private var allGeneration = 0
    private val measuring = HashMap<String, Any>()

    /** The sizes saved as [name] with exactly [keys], or [measure]d now and saved. */
    fun get(name: String, keys: Set<String>, measure: () -> Map<String, Long>): Map<String, Long> {
        val file = file(name)
        val lock = synchronized(measuring) { measuring.getOrPut(name) { Any() } }
        synchronized(lock) {
            read(file)?.takeIf { it.keys == keys }?.let { return it }
            val before = generation(name)
            val started = System.nanoTime()
            val sizes = measure()
            onMeasured(name, (System.nanoTime() - started) / 1_000_000)
            synchronized(this) { if (generation(name) == before) write(file, sizes) }
            return sizes
        }
    }

    fun forget(name: String) = synchronized(this) {
        generations[name] = (generations[name] ?: 0) + 1
        setAside(file(name))
    }

    /** Every saved size, or with [keep], all but those. */
    fun forgetAll(keep: Set<String> = emptySet()) = synchronized(this) {
        allGeneration++
        dir.listFiles()?.filter { it.name.endsWith(SUFFIX) && it.name.removeSuffix(SUFFIX) !in keep }?.forEach(::setAside)
        // The kept ones' measurements in progress may still be saved.
        for (name in keep) generations[name] = (generations[name] ?: 0) - 1
    }

    private fun generation(name: String) = synchronized(this) { allGeneration + (generations[name] ?: 0) }

    /**
     * The sizes saved as [name] with exactly [keys], or those a forget set aside (from before a
     * change, so possibly out of date); null when there are none. Never measures.
     */
    fun last(name: String, keys: Set<String>): Map<String, Long>? =
        (read(file(name)) ?: read(lastFile(name)))?.takeIf { it.keys == keys }

    private fun file(name: String) = File(dir, name + SUFFIX)
    private fun lastFile(name: String) = File(dir, name + SUFFIX + LAST)

    /** Keeps a forgotten file's sizes as the last ones (a newer set replaces an older). */
    private fun setAside(file: File) {
        if (read(file) != null) file.renameTo(File(file.path + LAST)) else file.delete()
    }

    /** `key=value` lines, then [END]; anything else (a file cut off part-way) is unreadable. */
    private fun read(file: File): Map<String, Long>? {
        val lines = try {
            file.readLines()
        } catch (_: Exception) {
            return null
        }
        if (lines.lastOrNull() != END) return null
        val sizes = HashMap<String, Long>()
        for (line in lines.dropLast(1)) {
            val key = line.substringBefore('=', "")
            val value = line.substringAfter('=').toLongOrNull()
            if (key.isEmpty() || value == null || value < 0) return null
            sizes[key] = value
        }
        return sizes
    }

    // Written in place: the file was deleted before, and an unfinished one has no END line.
    private fun write(file: File, sizes: Map<String, Long>) {
        try {
            dir.mkdirs()
            file.delete()
            file.writeText(sizes.entries.joinToString("") { (key, value) -> "$key=$value\n" } + END + "\n")
            File(file.path + LAST).delete()
        } catch (_: Exception) {
            file.delete()
        }
    }

    private companion object {
        const val SUFFIX = ".sizes"
        const val LAST = ".last"
        const val END = "end"
    }
}

/**
 * Flatdroid's storage by part, saved in a [SizeStore] (`files/storage-sizes/`): per app (by app
 * ID) its Flatpak files, private home and settings, and once for everything [shared] (runtimes,
 * and the rest: Alpine and Flatdroid's own files). Caches aren't saved, since they change by
 * themselves; they are measured each time they're shown.
 *
 * A change [forget]s what it changes before it starts, and once done reports it as [changed]
 * (or [changedApps], [changedAll]): the sizes are then measured again in the background, one at
 * a time, at the lowest priority and without a notification, so a screen showing them only reads
 * files. What a stop cut short is measured at the next start ([measureMissing]) or when shown.
 * Between [forgetAll] and the last [changedAll] nothing is measured: those changes (Flatpak tasks, setup,
 * the terminal) touch everything, so a measurement would be wasted and would slow them down.
 */
object SavedSizes {
    private const val SHARED = "shared"
    private const val RUNTIMES = "runtimes"
    private val APP_KEYS = setOf("app", "flatpak", "home", "settings")
    private val SHARED_KEYS = setOf("runtimes", "other")

    private val _updates = MutableStateFlow(0)
    /** Bumped each time background measuring has caught up, so screens read the sizes again. */
    val updates: StateFlow<Int> = _updates.asStateFlow()

    private val background = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor { Thread(it, "SavedSizes").apply { priority = Thread.MIN_PRIORITY; isDaemon = true } }
            .asCoroutineDispatcher(),
    )
    /** Names waiting to be measured in the background (not yet started). */
    private val queued = LinkedHashSet<String>()

    /** One app's saved sizes, in bytes. */
    data class App(
        /** Its own Flatpak files (deployments, its own extensions such as translations) and its Electron swap. */
        val app: Long,
        /** Of [app], the Flatpak files alone ([shared] subtracts them from everything Flatdroid keeps). */
        val flatpak: Long,
        val privateHome: Long,
        /** Its settings and other files of its own ([AppFiles]), with Flatdroid's settings for it. */
        val settings: Long,
    )

    data class Shared(
        /** Runtimes and their extensions, without apps' own extensions. */
        val runtimes: Long,
        /** Everything else Flatdroid keeps outside the cache: the Alpine system, Flatpak's repository and Flatdroid's own files. */
        val other: Long,
    )

    @Volatile private var store: SizeStore? = null

    /** How many [forgetAll]s have no [changedAll] yet (a task and the terminal may overlap); also ends a measurement under way. */
    private val changingAll = AtomicInteger()
    private val stop = { changingAll.get() > 0 }

    private fun store(context: Context): SizeStore =
        store ?: synchronized(this) { store ?: SizeStore(File(context.filesDir, "storage-sizes")) { name, millis -> DebugLog.i("Measured $name in $millis ms") }
            .also { store = it } }

    /** [appId]'s sizes as last saved, perhaps from before a change; null when never measured. Never measures. */
    fun lastApp(context: Context, appId: String): App? =
        store(context).last(appId, APP_KEYS)?.let(::app)

    private fun app(sizes: Map<String, Long>) = App(sizes.getValue("app"), sizes.getValue("flatpak"), sizes.getValue("home"), sizes.getValue("settings"))

    /** The shared sizes as last saved, perhaps from before a change; null when never measured. Never measures. */
    fun lastShared(context: Context): Shared? =
        store(context).last(SHARED, SHARED_KEYS)?.let { Shared(it.getValue("runtimes"), it.getValue("other")) }

    /** Null while everything is changing ([forgetAll]). */
    fun app(context: Context, appId: String): App? {
        require(InstalledApps.isValidId(appId))
        val sizes = measuring {
            store(context).get(appId, APP_KEYS) {
                val env = AlpineEnvironment.get(context)
                val l2s = File(env.paths().l2sDir)
                val flatpak = StorageSizes.following(flatpakFiles(env, appId), l2s, stop)
                mapOf(
                    "app" to flatpak + StorageSizes.of(File(AppData.electronDir(context), appId), stop),
                    "flatpak" to flatpak,
                    "home" to StorageSizes.of(AppData.privateHome(context, appId), stop),
                    "settings" to AppFiles.dirs(File(AppData.root(context), appId), appId).sumOf { StorageSizes.of(it, stop) } +
                        StorageSizes.of(AppData.settingsFile(context, appId)),
                )
            }
        } ?: return null
        return app(sizes)
    }

    /** Null while everything is changing ([forgetAll]). */
    fun shared(context: Context): Shared? {
        val sizes = measuring { store(context).get(SHARED, SHARED_KEYS) {
            val env = AlpineEnvironment.get(context)
            val apps = FlatpakInstallation(env.flatpakDir, env.arch).appIds()
            val runtimes = (runtimes(context) ?: throw MeasureStopped()).values.sum()
            // As each app's own size counts them, so that the rest is what neither counts. Saved with
            // the app's sizes (apps are measured before this), so read rather than walked again.
            val appFiles = apps.sumOf { id -> (app(context, id) ?: throw MeasureStopped()).flatpak }
            // Measured per app instead: their data, settings and Electron swaps, and these sizes.
            val perApp = setOf(AppData.root(context), AppData.electronDir(context), AppData.settingsDir(context), File(context.filesDir, "storage-sizes"))
                .map { it.canonicalPath }
            val files = context.filesDir.canonicalFile.listFiles().orEmpty().filter { it.canonicalPath !in perApp }.sumOf { StorageSizes.of(it, stop) }
            mapOf("runtimes" to runtimes, "other" to (files - runtimes - appFiles).coerceAtLeast(0))
        } } ?: return null
        return Shared(sizes.getValue("runtimes"), sizes.getValue("other"))
    }

    /**
     * Each runtime's size (ID to bytes; its branches and their extensions' own deployments with it),
     * without apps' own extensions (counted with the app). Null while everything is changing.
     */
    fun runtimes(context: Context): Map<String, Long>? = measuring {
        val env = AlpineEnvironment.get(context)
        val l2s = File(env.paths().l2sDir)
        val dirs = runtimeDirs(env)
        store(context).get(RUNTIMES, dirs.map { it.name }.toSet()) {
            dirs.associate { dir -> dir.name to StorageSizes.following(listOf(dir), l2s, stop) }
        }
    }

    /** The runtimes' sizes as last saved, perhaps from before a change; null when never measured. Never measures. */
    fun lastRuntimes(context: Context): Map<String, Long>? =
        store(context).last(RUNTIMES, runtimeDirs(AlpineEnvironment.get(context)).map { it.name }.toSet())

    private fun runtimeDirs(env: AlpineEnvironment): List<File> {
        val apps = FlatpakInstallation(env.flatpakDir, env.arch).appIds()
        return File(env.flatpakDir, "runtime").listFiles().orEmpty().filter { dir -> apps.none { dir.name.startsWith("$it.") } }.sortedBy { it.name }
    }

    /** [measure], or null while everything is changing or when that began during it. */
    private fun <T> measuring(measure: () -> T): T? {
        if (stop()) return null
        return try {
            measure()
        } catch (_: MeasureStopped) {
            null
        }
    }

    /** Before anything that changes [appId]'s files. */
    fun forget(context: Context, appId: String) {
        if (InstalledApps.isValidId(appId)) store(context).forget(appId)
    }

    /** Before anything that changes apps' files, but not Alpine, Flatpak or runtimes. */
    fun forgetApps(context: Context) = store(context).forgetAll(keep = setOf(SHARED, RUNTIMES))

    /** Before anything that may change any of it: Flatpak tasks, setup, the terminal. Nothing is measured until [changedAll]. */
    fun forgetAll(context: Context) {
        changingAll.incrementAndGet()
        store(context).forgetAll()
    }

    /** After [appId]'s files changed: forgotten, and measured again in the background. */
    fun changed(context: Context, appId: String) {
        forget(context, appId)
        if (InstalledApps.isValidId(appId)) measureLater(context, listOf(appId))
    }

    /** After apps' files changed: every app's sizes forgotten and measured again in the background. */
    fun changedApps(context: Context) {
        forgetApps(context)
        measureLater(context, appIds(context))
    }

    /**
     * After a change that may touch any of it: everything forgotten, and measured again in the
     * background, or with [measureNow] false only once a screen shows it (after a terminal
     * command, which seldom changes anything).
     */
    fun changedAll(context: Context, measureNow: Boolean = true) {
        store(context).forgetAll()
        if (changingAll.updateAndGet { maxOf(0, it - 1) } > 0 || !measureNow) return
        measureLater(context, appIds(context) + SHARED)
    }

    /** At start: measures in the background whatever isn't saved (a stop cut its change or measurement short). */
    fun measureMissing(context: Context) = measureLater(context, appIds(context) + SHARED)

    /** Apps Flatdroid has files for: installed, or with data or Flatdroid settings left. */
    fun appIds(context: Context): Set<String> {
        val env = AlpineEnvironment.get(context)
        return FlatpakInstallation(env.flatpakDir, env.arch).appIds().toSet() +
            AppData.root(context).list().orEmpty().filter(InstalledApps::isValidId) +
            AppData.settingsDir(context).list().orEmpty().mapNotNull(SettingsBackup::appId)
    }

    private fun measureLater(context: Context, names: Collection<String>) {
        val appContext = context.applicationContext
        for (name in names) {
            if (!synchronized(queued) { queued.add(name) }) continue
            background.launch {
                val last = synchronized(queued) { queued.remove(name); queued.isEmpty() }
                // Not before setup has finished: a partial Alpine would be saved as its size.
                if (AlpineEnvironment.get(appContext).isInstalled()) {
                    try {
                        when (name) {
                            SHARED -> shared(appContext)
                            RUNTIMES -> runtimes(appContext)
                            else -> app(appContext, name)
                        }
                    } catch (e: Exception) {
                        DebugLog.w("Measuring $name failed: $e")
                    }
                }
                if (last) _updates.update { it + 1 }
            }
        }
    }

    /** The app's deployments and its own extensions (`<app ID>.Locale` and so on). */
    private fun flatpakFiles(env: AlpineEnvironment, appId: String): List<File> =
        listOf(File(env.flatpakDir, "app/$appId")) + File(env.flatpakDir, "runtime").listFiles { file -> file.name.startsWith("$appId.") }.orEmpty()
}
