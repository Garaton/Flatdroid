package com.garathon.flatdroid.ui.browse

import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.catalog.CatalogApp
import com.garathon.flatdroid.flatpak.catalog.CatalogIndex
import com.garathon.flatdroid.flatpak.catalog.FlathubLists
import com.garathon.flatdroid.ui.list.ListFilter
import com.garathon.flatdroid.ui.list.ListQuery
import com.garathon.flatdroid.ui.list.ListSort
import com.garathon.flatdroid.ui.list.ListSpec
import com.garathon.flatdroid.util.debug.DebugLog

/** The browser's orders: by Flathub's installs or dates (apps Flathub doesn't list after them, by name), by name, or shuffled. */
enum class BrowseOrder(val key: String) { POPULAR("popular"), ADDED("added"), UPDATED("updated"), NAME("name"), RANDOM("random") }

/**
 * How the browser's list is searched (name, ID, summary, developer, keywords), filtered (verified,
 * runtime already installed: [installedRuntimes] as `<ID>/<branch>`) and ordered ([BrowseOrder]).
 * The ordering itself is [BrowseItems]'s; the comparators here only serve the shared search bar's
 * menu and direction.
 */
fun browseListSpec(lists: FlathubLists, installedRuntimes: Set<String> = emptySet()) = ListSpec<CatalogApp>(
    searchText = { listOfNotNull(it.name, it.id, it.summary, it.developer) + it.keywords },
    filters = listOf(
        ListFilter("verified", R.string.browse_filter_verified) { it.id in lists.verified },
        ListFilter("runtimes", R.string.browse_filter_runtimes) { it.runtime != null && it.runtime in installedRuntimes },
    ),
    sorts = listOf(
        ListSort(BrowseOrder.POPULAR.key, R.string.browse_sort_popular, comparator = byRank(lists.popular)),
        ListSort(BrowseOrder.ADDED.key, R.string.browse_sort_added, comparator = byRank(lists.added)),
        ListSort(BrowseOrder.UPDATED.key, R.string.browse_sort_updated, comparator = byRank(lists.updated)),
        ListSort(BrowseOrder.NAME.key, R.string.browse_sort_name, comparator = byName),
        // The order is BrowseItems' shuffle; this one only fills the menu.
        ListSort(BrowseOrder.RANDOM.key, R.string.browse_sort_random, comparator = byName),
    ),
)

private val byName = compareBy<CatalogApp> { it.name.lowercase() }.thenBy { it.id }

private fun byRank(rank: Map<String, Int>) = compareBy<CatalogApp> { rank[it.id] ?: Int.MAX_VALUE }.thenBy { it.name.lowercase() }.thenBy { it.id }

/**
 * The browser's categories: AppStream's main categories as Flathub groups them, each shown when
 * any app has it. An app can be in several.
 */
enum class BrowseCategory(val appStream: String, val label: Int) {
    AUDIO_VIDEO("AudioVideo", R.string.browse_category_audio_video),
    DEVELOPMENT("Development", R.string.browse_category_development),
    EDUCATION("Education", R.string.browse_category_education),
    GAME("Game", R.string.browse_category_game),
    GRAPHICS("Graphics", R.string.browse_category_graphics),
    NETWORK("Network", R.string.browse_category_network),
    OFFICE("Office", R.string.browse_category_office),
    SCIENCE("Science", R.string.browse_category_science),
    SYSTEM("System", R.string.browse_category_system),
    UTILITY("Utility", R.string.browse_category_utility),
    ;

    fun matches(app: CatalogApp) = appStream in app.categories ||
        // AppStream's Audio and Video are part of AudioVideo.
        (this == AUDIO_VIDEO && ("Audio" in app.categories || "Video" in app.categories))
}

/**
 * The browser's list, prepared once per index and set of Flathub's lists so a keystroke only
 * filters: each app's search text lowercased into one string, and the list sorted in every
 * [BrowseOrder], both ways. [ListSpec.apply] would lowercase and sort the thousands of apps again at
 * every keystroke (110 to 290 ms on a Cortex-A53, against 8 to 25 ms).
 */
class BrowseItems(apps: List<CatalogApp>, lists: FlathubLists, descriptions: List<String>? = null) {
    /** [description]: the app's description, lowercase, searched as [text] is ([select]). */
    private class Item(val app: CatalogApp, val text: String, val nameKey: String, val description: String)

    /** Each order's key to its ascending and descending lists. */
    private val orders: Map<String, Pair<List<Item>, List<Item>>>
    private val byName: List<Item>

    /** The last shuffle and its seed ([select]'s random order). */
    private var shuffled: Pair<Int, List<Item>>? = null

    /** The categories any app is in, for the chips: found here, off the main thread, with the rest. */
    val categories: List<BrowseCategory> = BrowseCategory.entries.filter { category -> apps.any(category::matches) }

    init {
        val items = apps.mapIndexed { i, app ->
            val text = (listOfNotNull(app.name, app.id, app.summary, app.developer) + app.keywords).joinToString("\u0000").lowercase()
            Item(app, text, app.name.lowercase(), descriptions?.getOrNull(i).orEmpty())
        }
        byName = items.sortedWith(compareBy<Item> { it.nameKey }.thenBy { it.app.id })
        // Flathub's apps in its order, either way; apps it doesn't list (no installs or dates) after them by name, both ways.
        fun byList(rank: Map<String, Int>): Pair<List<Item>, List<Item>> {
            val (ranked, rest) = byName.partition { it.app.id in rank }
            val ordered = ranked.sortedBy { rank.getValue(it.app.id) }
            return (ordered + rest) to (ordered.asReversed() + rest)
        }
        orders = mapOf(
            BrowseOrder.NAME.key to (byName to byName.asReversed()),
            BrowseOrder.POPULAR.key to byList(lists.popular),
            BrowseOrder.ADDED.key to byList(lists.added),
            BrowseOrder.UPDATED.key to byList(lists.updated),
        )
    }

    /** All apps in a random order, the same for the same [seed]. */
    private fun shuffle(seed: Int): List<Item> = synchronized(this) {
        shuffled?.takeIf { it.first == seed }?.second ?: byName.shuffled(kotlin.random.Random(seed)).also { shuffled = seed to it }
    }

    /**
     * The apps [query] (text, filters of [spec], order and direction) and [category] select. The
     * random order is [seed]'s shuffle (the direction doesn't change it). Searched text matches the
     * name, ID, summary, developer, keywords or description, in the order asked for.
     */
    fun select(spec: ListSpec<CatalogApp>, query: ListQuery, category: BrowseCategory?, seed: Int = 0): List<CatalogApp> {
        val text = query.text.trim().lowercase()
        val selected = spec.filters.filter { it.key in query.filters }.groupBy { it.group }.values
        val ordered = if (query.sort == BrowseOrder.RANDOM.key) shuffle(seed) else {
            val (ascending, descending) = orders[query.sort] ?: orders.getValue(BrowseOrder.POPULAR.key)
            if (query.descending) descending else ascending
        }
        return ordered.filter { item ->
            (text.isEmpty() || item.text.contains(text) || item.description.contains(text)) &&
                (category == null || category.matches(item.app)) &&
                selected.all { group -> group.any { it.matches(item.app) } }
        }.map { it.app }
    }

    companion object {
        /** The last one prepared, with what it was prepared from: the browser opens again without preparing. */
        @Volatile private var last: Triple<CatalogIndex, FlathubLists, BrowseItems>? = null

        /** The prepared list for [index] and [lists] if it is ready. */
        fun ready(index: CatalogIndex, lists: FlathubLists): BrowseItems? = last?.takeIf { it.first === index && it.second == lists }?.third

        /** Prepares it (a few hundred ms on a slow phone, with the descriptions read: call it off the main thread), or returns the one ready. */
        fun prepare(index: CatalogIndex, lists: FlathubLists): BrowseItems = ready(index, lists) ?: run {
            val started = System.currentTimeMillis()
            BrowseItems(index.apps, lists, index.descriptions()).also {
                last = Triple(index, lists, it)
                DebugLog.i("Browser: ${index.apps.size} apps prepared in ${System.currentTimeMillis() - started} ms")
            }
        }
    }
}
