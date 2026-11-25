package com.garathon.flatdroid.flatpak.catalog

import android.content.Context
import android.os.LocaleList
import android.system.Os
import android.util.Xml
import com.garathon.flatdroid.alpine.AlpineEnvironment
import com.garathon.flatdroid.flatpak.flathub.FlathubApp
import com.garathon.flatdroid.flatpak.flathub.FlathubClient
import com.garathon.flatdroid.flatpak.flathub.FlathubStats
import com.garathon.flatdroid.settings.MobileData
import com.garathon.flatdroid.settings.mobileDataPolicy
import com.garathon.flatdroid.util.OnceAsked
import com.garathon.flatdroid.util.debug.DebugLog
import com.garathon.flatdroid.util.appScope
import com.garathon.flatdroid.util.io.writeTextAtomically
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import org.xmlpull.v1.XmlPullParser
import java.util.zip.GZIPInputStream

/** What the browser can show. */
sealed interface CatalogState {
    /** Not looked at yet. */
    data object Idle : CatalogState
    /** The index is being read or built. */
    data object Loading : CatalogState
    /** Flatpak has no catalog on this phone yet: a refresh downloads it. */
    data object Missing : CatalogState
    /** [lists]: Flathub's Popular, Recently added and Recently updated lists and installs; empty until fetched. */
    data class Ready(val index: CatalogIndex, val lists: FlathubLists) : CatalogState
    data class Failed(val message: String) : CatalogState
}

/**
 * Flathub's apps, for the browser's orders: each app ID's place in [popular] (most installs in the
 * last month first, Flathub's Popular list), [added] (newest first) and [updated] (most recent
 * first), and its installs of the last month ([installs]). Every app on Flathub is in each.
 */
data class FlathubLists(
    val popular: Map<String, Int> = emptyMap(),
    val added: Map<String, Int> = emptyMap(),
    val updated: Map<String, Int> = emptyMap(),
    val installs: Map<String, Long> = emptyMap(),
    /** The apps Flathub says are verified (their developer's website or account): its answer, not the catalog's, which an installed app can rewrite. */
    val verified: Set<String> = emptySet(),
) {
    companion object {
        /**
         * Catalog's saved lines (`<app ID>\t<installs>\t<added>\t<updated>\t<verified>`, in Popular's
         * order; the last, "1" or "0", is missing from an older file, which reads as not verified):
         * an app's first line counts; one without a date goes after those with one.
         */
        fun parse(lines: List<String>): FlathubLists {
            val popular = LinkedHashMap<String, Int>()
            val installs = HashMap<String, Long>()
            val added = HashMap<String, Long>()
            val updated = HashMap<String, Long>()
            val verified = HashSet<String>()
            for (line in lines) {
                val parts = line.split('\t')
                if ((parts.size != 4 && parts.size != 5) || parts[0] in popular) continue
                val id = parts[0]
                popular[id] = popular.size
                parts[1].toLongOrNull()?.let { installs[id] = it }
                added[id] = parts[2].toLongOrNull() ?: Long.MIN_VALUE
                updated[id] = parts[3].toLongOrNull() ?: Long.MIN_VALUE
                if (parts.size == 5 && parts[4] == "1") verified += id
            }
            // Newest first; the same date keeps Popular's order (a stable sort).
            fun ranks(dates: Map<String, Long>) = popular.keys.sortedByDescending { dates.getValue(it) }.withIndex().associate { it.value to it.index }
            return FlathubLists(popular, ranks(added), ranks(updated), installs, verified)
        }
    }
}

/**
 * Flathub's catalog for the browser: Flatpak's own AppStream data for this phone's architecture
 * (`appstream/flathub/<arch>/active` in Flatpak's directory), read into an index in Flatdroid's
 * files ([CatalogIndex]; outside the cache, so Android's Clear cache doesn't cost a rebuild, and
 * removed with Storage's Clear all caches, [clear]). The index is rebuilt only when that data changed; Flatpak changes it
 * only when asked to (a refresh, or as part of an install or update), never by itself. Whenever the
 * index is rebuilt, what was cached from Flathub about single apps (their sizes and permissions,
 * installs, screenshots) is dropped with it; Flathub's lists are kept until they are fetched again.
 */
object Catalog {
    private val _state = MutableStateFlow<CatalogState>(CatalogState.Idle)
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    /** Flathub's answers about single apps (sizes, permissions; installs), until the index is rebuilt. */
    private val summaries = OnceAsked<FlathubApp>()
    private val stats = OnceAsked<FlathubStats>()

    private val lock = Any()

    /** The index and Flathub's lists: kept, as rebuilding the index takes a while. Screenshots are in the cache ([CatalogImages]). */
    fun dir(context: Context) = File(context.filesDir, "catalog")
    private fun indexDir(context: Context) = File(dir(context), "index")
    private fun listsFile(context: Context) = File(dir(context), "flathub-apps")

    /** Flatpak's AppStream folder for Flathub and this phone's architecture (a link to the current version). */
    private fun source(context: Context): File {
        val env = AlpineEnvironment.get(context)
        return File(env.flatpakDir, "appstream/flathub/${env.arch}/active")
    }

    /**
     * Which catalog the index must come from: Flatpak's version of it (the commit `active` points
     * to) and the languages it was read in.
     */
    private fun version(context: Context): String? {
        val active = source(context)
        if (!File(active, APPSTREAM).isFile) return null
        val commit = runCatching { File(Os.readlink(active.path)).name }.getOrNull() ?: active.canonicalFile.name
        return "$commit|${languages().joinToString(",")}"
    }

    private fun languages(): List<String> {
        val locales = LocaleList.getDefault()
        return (0 until locales.size()).map { locales[it].toLanguageTag() }
    }

    /**
     * The browser or an app's page opened: loads the index (building it if the catalog changed, or if
     * its files are gone), off the main thread. Flatpak's catalog isn't downloaded; Flathub's lists
     * are, when their saved copy is gone (Android's Clear cache) and downloads are allowed.
     */
    fun open(context: Context) {
        val app = context.applicationContext
        val current = _state.value
        if (current is CatalogState.Loading) return
        appScope.launch(Dispatchers.IO) {
            if (current is CatalogState.Ready && current.index.exists() && current.index.version == version(app)) return@launch
            _state.value = CatalogState.Loading
            _state.value = try {
                synchronized(lock) { load(app) }
            } catch (e: Exception) {
                DebugLog.w { "Catalog: reading failed: $e" }
                CatalogState.Failed(e.message ?: e.toString())
            }
            if (_state.value is CatalogState.Ready && !listsFile(app).isFile) synchronized(lock) { fetchLists(app) }
        }
    }

    /**
     * Flatpak may have downloaded a new catalog (a refresh, an install or an update did): rebuilds
     * the index if it changed (Flatpak's commit of it is its hash: an unchanged catalog keeps the
     * index), and then (or always when [popular], for the user's own refresh) fetches Flathub's
     * lists again ([FlathubLists]), unless mobile data is off for downloads. Without [popular], an
     * index that was never built isn't built here: the browser builds it when it opens, so an
     * install by link doesn't take that time. Blocking: for the task that did the download. True when
     * the index was rebuilt.
     */
    fun refreshed(context: Context, popular: Boolean): Boolean {
        val app = context.applicationContext
        val rebuild = if (popular) version(app).let { it != null && it != builtVersion(app) } else changed(app)
        if (!rebuild && !popular) return false
        synchronized(lock) {
            if (rebuild) _state.value = load(app)
            fetchLists(app)
        }
        return rebuild
    }

    /** Whether Flatpak's catalog differs from the one an existing index was built from (false without an index). */
    fun changed(context: Context): Boolean {
        val now = version(context) ?: return false
        val built = builtVersion(context) ?: return false
        return built != now
    }

    /** The catalog version the index was built from; null when there is none. */
    private fun builtVersion(context: Context): String? =
        (_state.value as? CatalogState.Ready)?.index?.takeIf { it.exists() }?.version ?: CatalogIndex.version(indexDir(context))

    /** The index for the current catalog: the cached one, or a new one (dropping the browser's caches). */
    private fun load(context: Context): CatalogState {
        val version = version(context) ?: return CatalogState.Missing
        val cached = CatalogIndex.read(indexDir(context))
        val index = if (cached?.version == version) cached else build(context, version)
        return CatalogState.Ready(index, readLists(context))
    }

    private fun build(context: Context, version: String): CatalogIndex {
        val started = System.currentTimeMillis()
        clearCaches(context)
        val reader = AppStreamReader(languages())
        val index = GZIPInputStream(File(source(context), APPSTREAM).inputStream(), 64 * 1024).use { gz ->
            val parser = Xml.newPullParser()
            // The catalog uses no namespaces but xml:lang, which the reader finds either way; without them the parser is faster.
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(gz.buffered(64 * 1024), "UTF-8")
            CatalogIndex.write(indexDir(context), version) { add -> reader.read(parser, add) }
        }
        DebugLog.i { "Catalog: ${index.apps.size} apps read in ${System.currentTimeMillis() - started} ms" }
        return index
    }

    /**
     * Drops what was cached from Flathub about single apps: it belongs to the catalog being replaced.
     * Flathub's lists stay until they are fetched again: they don't depend on the catalog, and an
     * index rebuilt only for a new language (or by the browser opening) fetches nothing.
     */
    private fun clearCaches(context: Context) {
        summaries.clear()
        stats.clear()
        CatalogImages.clear(context)
    }

    /**
     * Every Flathub app's installs, dates and verification, saved as `<app ID>\t<installs>\t<added>\t<updated>\t<verified>`
     * lines in Popular's order; not on mobile data when downloads there are off. The browser's state
     * changes only when they differ, so an unchanged answer doesn't reorder the list.
     */
    private fun fetchLists(context: Context) {
        if (mobileDataPolicy(context) == MobileData.NEVER) return
        try {
            val started = System.currentTimeMillis()
            val apps = FlathubClient.allApps()
            // Whole or not at all: a file cut short would stay until the next refresh.
            listsFile(context).writeTextAtomically(
                apps.joinToString("") { "${it.id}\t${it.installsLastMonth ?: ""}\t${it.addedAt ?: ""}\t${it.updatedAt ?: ""}\t${if (it.verified) "1" else "0"}\n" },
                durable = false,
            )
            // Written by earlier versions: three lists of 250 apps.
            File(dir(context), "lists").delete()
            DebugLog.i { "Catalog: Flathub's ${apps.size} apps fetched in ${System.currentTimeMillis() - started} ms" }
            val lists = readLists(context)
            (_state.value as? CatalogState.Ready)?.takeIf { it.lists != lists }?.let { _state.value = it.copy(lists = lists) }
        } catch (e: IOException) {
            DebugLog.w { "Catalog: Flathub's lists couldn't be fetched: $e" }
        }
    }

    private fun readLists(context: Context): FlathubLists =
        FlathubLists.parse(runCatching { listsFile(context).readLines() }.getOrDefault(emptyList()))

    /**
     * Storage's Clear all caches: the index, Flathub's lists and the screenshots are deleted, and
     * forgotten here; the browser builds the index again when it next opens. Not while a task runs.
     */
    fun clear(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            dir(app).deleteRecursively()
            summaries.clear()
            stats.clear()
            CatalogImages.clear(app)
            _state.value = CatalogState.Idle
        }
    }

    /** [id]'s installs from Flathub, asked once until the index is rebuilt. Network work. */
    fun stats(id: String): FlathubStats = stats.get(id) { FlathubClient.stats(id) }

    /** [id]'s sizes and permissions from Flathub, asked once until the index is rebuilt. Network work. */
    fun summary(id: String): FlathubApp = summaries.get(id) { FlathubClient.fetch(id) }

    /** The file of [icon] ([CatalogApp.icon]) in Flatpak's catalog, if it is there. */
    fun iconFile(context: Context, icon: String?): File? = icon?.let { File(iconsDir(context), it) }?.takeIf { it.isFile }

    /** Where the catalog's icons are ([iconFile]'s folder). */
    fun iconsDir(context: Context) = File(source(context), "icons")

    private const val APPSTREAM = "appstream.xml.gz"
}
