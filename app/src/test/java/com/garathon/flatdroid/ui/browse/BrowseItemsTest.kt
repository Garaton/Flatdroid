package com.garathon.flatdroid.ui.browse

import com.garathon.flatdroid.flatpak.catalog.CatalogApp
import com.garathon.flatdroid.flatpak.catalog.FlathubLists
import com.garathon.flatdroid.ui.list.ListQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BrowseItemsTest {
    private fun app(id: String, runtime: String? = null) = CatalogApp(id, id.substringAfterLast('.'), "", null, emptyList(), emptyList(), null, runtime)

    // Saved as Catalog writes them: Popular's order, then installs, added and updated (seconds).
    private val lists = FlathubLists.parse(
        listOf(
            "org.example.Big\t900\t300\t500",
            "org.example.Mid\t50\t100\t700",
            "org.example.Small\t\t200\t",
            "org.example.Big\t1\t1\t1",
            "broken line",
        ),
    )
    private val apps = listOf(app("org.example.Small"), app("org.example.Unlisted", "org.gnome.Platform/49"), app("org.example.Mid"), app("org.example.Big"))
    private val items = BrowseItems(apps, lists)
    private val spec = browseListSpec(lists, setOf("org.gnome.Platform/49"))

    private fun ids(sort: String, descending: Boolean = false, filters: Set<String> = emptySet(), seed: Int = 0) =
        items.select(spec, ListQuery(sort = sort, descending = descending, filters = filters), null, seed).map { it.id.substringAfterLast('.') }

    @Test
    fun flathubsAppsAreRankedByInstallsAndDates() {
        assertEquals(mapOf("org.example.Big" to 0, "org.example.Mid" to 1, "org.example.Small" to 2), lists.popular)
        assertEquals(mapOf("org.example.Big" to 900L, "org.example.Mid" to 50L), lists.installs)
        // Newest first.
        assertEquals(listOf("org.example.Big", "org.example.Small", "org.example.Mid"), lists.added.entries.sortedBy { it.value }.map { it.key })
        // No date: after those with one.
        assertEquals(listOf("org.example.Mid", "org.example.Big", "org.example.Small"), lists.updated.entries.sortedBy { it.value }.map { it.key })
    }

    @Test
    fun eitherDirectionKeepsAppsFlathubDoesntListLast() {
        assertEquals(listOf("Big", "Mid", "Small", "Unlisted"), ids("popular"))
        // The least installed first: the reverse of Flathub's order, not of the whole list.
        assertEquals(listOf("Small", "Mid", "Big", "Unlisted"), ids("popular", descending = true))
        assertEquals(listOf("Mid", "Small", "Big", "Unlisted"), ids("added", descending = true))
        assertEquals(listOf("Unlisted", "Small", "Mid", "Big"), ids("name", descending = true))
    }

    @Test
    fun descriptionMatchesKeepTheOrder() {
        // Small's description mentions "big": it stays in Popular's order, after Big, not after the name matches.
        val described = BrowseItems(apps, lists, listOf("not as big as others", "", "", "")).select(spec, ListQuery(text = "big", sort = "popular", descending = true), null)
        assertEquals(listOf("Small", "Big"), described.map { it.id.substringAfterLast('.') })
    }

    @Test
    fun randomIsTheSameForASeedAndTheRuntimeFilterMatches() {
        assertEquals(ids("random", seed = 1), ids("random", seed = 1))
        assertEquals(ids("random", seed = 1), ids("random", descending = true, seed = 1))
        assertEquals(4, ids("random", seed = 2).toSet().size)
        assertNotEquals((1..20).map { ids("random", seed = 1) }.toSet().size, (1..20).map { ids("random", seed = it) }.toSet().size)
        assertEquals(listOf("Unlisted"), ids("popular", filters = setOf("runtimes")))
    }
}
