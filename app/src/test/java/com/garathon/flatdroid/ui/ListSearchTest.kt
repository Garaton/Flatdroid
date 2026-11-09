package com.garathon.flatdroid.ui

import com.garathon.flatdroid.ui.list.ListFilter
import com.garathon.flatdroid.ui.list.ListSort
import com.garathon.flatdroid.ui.list.ListSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class ListSearchTest {
    private data class Row(val name: String, val id: String, val bytes: Long, val app: Boolean, val unused: Boolean = false)

    private val rows = listOf(
        Row("Mousepad", "org.xfce.mousepad", 300, app = true),
        Row("Audacity", "org.audacityteam.Audacity", 900, app = true),
        Row("org.gnome.Platform", "org.gnome.Platform", 2000, app = false),
        Row("org.old.Runtime", "org.old.Runtime", 500, app = false, unused = true),
    )

    private val spec = ListSpec<Row>(
        searchText = { listOf(it.name, it.id) },
        filters = listOf(
            ListFilter("apps", 0, group = "kind") { it.app },
            ListFilter("runtimes", 0, group = "kind") { !it.app },
            ListFilter("unused", 0) { it.unused },
        ),
        sorts = listOf(
            ListSort("name", 0, comparator = compareBy { it.name.lowercase() }),
            ListSort("size", 0, defaultDescending = true, comparator = compareBy { it.bytes }),
        ),
        defaultSort = "size",
    )

    private fun names(query: com.garathon.flatdroid.ui.list.ListQuery) = spec.apply(rows, query).map { it.name }

    @Test
    fun startsInTheDefaultOrder() {
        assertEquals(listOf("org.gnome.Platform", "Audacity", "org.old.Runtime", "Mousepad"), names(spec.newQuery()))
    }

    @Test
    fun searchMatchesNamesAndIdsIgnoringCase() {
        assertEquals(listOf("Audacity"), names(spec.newQuery().copy(text = "AUDACITYTEAM")))
        assertEquals(listOf("Mousepad"), names(spec.newQuery().copy(text = " mouse ")))
        assertEquals(emptyList<String>(), names(spec.newQuery().copy(text = "nothing")))
    }

    @Test
    fun filtersOfOneGroupAreAlternativesAndGroupsMustAllMatch() {
        val q = spec.newQuery()
        assertEquals(listOf("Audacity", "Mousepad"), names(q.toggled("apps").copy(sort = "name", descending = false)))
        // Both kinds on: either passes.
        assertEquals(4, names(q.toggled("apps").toggled("runtimes")).size)
        // Another group narrows it: only the unused runtime is a runtime and unused.
        assertEquals(listOf("org.old.Runtime"), names(q.toggled("runtimes").toggled("unused")))
        // A row the filter doesn't apply to doesn't match: no app is unused.
        assertEquals(emptyList<String>(), names(q.toggled("apps").toggled("unused")))
    }

    @Test
    fun direction() {
        assertEquals(listOf("Audacity", "Mousepad", "org.gnome.Platform", "org.old.Runtime"), names(spec.newQuery().copy(sort = "name", descending = false)))
        assertEquals(listOf("org.old.Runtime", "org.gnome.Platform", "Mousepad", "Audacity"), names(spec.newQuery().copy(sort = "name", descending = true)))
    }

    @Test
    fun choosingAnOrderStartsInItsNaturalDirection() {
        val q = spec.newQuery()
        assertEquals(true, q.descending)
        val byName = spec.sortedBy(q, "name")
        assertEquals("name" to false, byName.sort to byName.descending)
        val bySize = spec.sortedBy(byName, "size")
        assertEquals("size" to true, bySize.sort to bySize.descending)
    }

    @Test
    fun toggleTurnsAFilterOnAndOff() {
        val q = spec.newQuery().toggled("apps")
        assertEquals(setOf("apps"), q.filters)
        assertEquals(emptySet<String>(), q.toggled("apps").filters)
    }
}
