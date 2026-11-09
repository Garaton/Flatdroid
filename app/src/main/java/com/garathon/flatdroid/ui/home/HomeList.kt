package com.garathon.flatdroid.ui.home

import com.garathon.flatdroid.R
import com.garathon.flatdroid.flatpak.InstalledApp
import com.garathon.flatdroid.ui.list.ListSort
import com.garathon.flatdroid.ui.list.ListSpec

/**
 * How the home screen's list is searched and ordered: by name or ID, and by when an app was last
 * opened (newest first; a new app counts from its installation, so it starts at the top), by name
 * (A to Z) or by the time it was used (most first). It uses the shared search bar.
 */
val homeListSpec = ListSpec<InstalledApp>(
    searchText = { listOf(it.name, it.id) },
    filters = emptyList(),
    sorts = listOf(
        ListSort("lastOpened", R.string.home_sort_last_opened, defaultDescending = true,
            comparator = compareBy<InstalledApp> { maxOf(it.lastOpened ?: Long.MIN_VALUE, it.installed ?: Long.MIN_VALUE) }.thenBy { it.name.lowercase() }.thenBy { it.id }),
        ListSort("name", R.string.home_sort_name,
            comparator = compareBy<InstalledApp> { it.name.lowercase() }.thenBy { it.id }),
        ListSort("mostUsed", R.string.home_sort_most_used, defaultDescending = true,
            comparator = compareBy<InstalledApp> { it.usedMillis }.thenBy { it.name.lowercase() }.thenBy { it.id }),
    ),
    defaultSort = "lastOpened",
)
