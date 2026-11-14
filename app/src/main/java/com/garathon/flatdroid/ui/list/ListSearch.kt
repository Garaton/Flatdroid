package com.garathon.flatdroid.ui.list

import androidx.annotation.StringRes

/**
 * A filter of a searchable list. [matches] says whether a row passes; a row the filter doesn't
 * apply to (a runtime under an apps-only filter, say) simply doesn't match it. Filters of one
 * [group] are alternatives (any of them may match); filters of different groups must all match.
 */
class ListFilter<T>(
    val key: String,
    @StringRes val label: Int,
    val group: String = key,
    val matches: (T) -> Boolean,
)

/** An order of a list; [defaultDescending] is its natural direction (newest first, A to Z), set when it is chosen. */
class ListSort<T>(val key: String, @StringRes val label: Int, val defaultDescending: Boolean = false, val comparator: Comparator<T>)

/**
 * What a list can be searched, filtered and sorted by; the same for every list that uses the shared
 * search bar (the Storage lists, the Home list). [searchText] is what a row's search matches,
 * one entry for each name it can be found by.
 */
class ListSpec<T>(
    val searchText: (T) -> List<String>,
    val filters: List<ListFilter<T>>,
    val sorts: List<ListSort<T>>,
    val defaultSort: String = sorts.first().key,
) {
    fun newQuery() = ListQuery(sort = defaultSort, descending = sorts.first { it.key == defaultSort }.defaultDescending)

    /** [query] ordered by [key], in that order's natural direction. */
    fun sortedBy(query: ListQuery, key: String): ListQuery =
        query.copy(sort = key, descending = sorts.firstOrNull { it.key == key }?.defaultDescending ?: false)

    /** [items] by [query]: matching the text and the selected filters, in the chosen order. */
    fun apply(items: List<T>, query: ListQuery): List<T> {
        val text = query.text.trim()
        val selected = filters.filter { it.key in query.filters }.groupBy { it.group }.values
        val matching = items.filter { item ->
            (text.isEmpty() || searchText(item).any { it.contains(text, ignoreCase = true) }) &&
                selected.all { group -> group.any { it.matches(item) } }
        }
        val sort = sorts.firstOrNull { it.key == query.sort } ?: sorts.first()
        val ordered = matching.sortedWith(sort.comparator)
        return if (query.descending) ordered.asReversed() else ordered
    }
}

/** The state of a list's search bar. */
data class ListQuery(
    val text: String = "",
    /** The [ListFilter.key]s that are on. */
    val filters: Set<String> = emptySet(),
    val sort: String,
    val descending: Boolean = false,
) {
    fun toggled(key: String) = copy(filters = if (key in filters) filters - key else filters + key)
}
