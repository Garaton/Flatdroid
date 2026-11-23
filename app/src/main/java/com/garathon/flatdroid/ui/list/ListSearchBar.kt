package com.garathon.flatdroid.ui.list

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.garathon.flatdroid.R
import kotlinx.coroutines.delay

/** The query of a list that survives rotation. */
@Composable
fun <T> rememberListQuery(spec: ListSpec<T>) = rememberSaveable(
    stateSaver = listSaver(
        save = { listOf(it.text, it.filters.joinToString(","), it.sort, it.descending.toString()) },
        restore = { ListQuery(it[0], it[1].split(",").filter(String::isNotEmpty).toSet(), it[2], it[3].toBoolean()) },
    ),
) { mutableStateOf(spec.newQuery()) }

/**
 * The search bar every searchable list shares: the search field with the sort menu and the
 * direction beside it, and under it the filters of [spec] as chips (unless [showFilters] is false:
 * a list that shows them in a row of its own, [FilterChips]). [query] is applied with [ListSpec.apply].
 * Typed text reaches [onQuery] once typing pauses, so a list isn't searched for each letter of a
 * word not finished yet; a cleared search reaches it at once. The bar owns the text: [query]'s is
 * what was last searched.
 */
@Composable
fun <T> ListSearchBar(
    spec: ListSpec<T>, query: ListQuery, onQuery: (ListQuery) -> Unit, modifier: Modifier = Modifier,
    @StringRes placeholder: Int = R.string.list_search,
    showFilters: Boolean = true,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf(query.text) }
    val latest by rememberUpdatedState(query)
    val report by rememberUpdatedState(onQuery)
    LaunchedEffect(typed) {
        if (typed == latest.text) return@LaunchedEffect
        if (typed.isNotEmpty()) delay(SEARCH_DELAY_MS)
        report(latest.copy(text = typed))
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            placeholder = { Text(stringResource(placeholder)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                Row {
                    if (spec.sorts.size > 1) {
                        IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.list_sort))
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            for (sort in spec.sorts) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(sort.label)) },
                                    trailingIcon = { if (sort.key == query.sort) Icon(Icons.Filled.Check, contentDescription = null) },
                                    onClick = { sortMenu = false; onQuery(spec.sortedBy(query, sort.key)) },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { onQuery(query.copy(descending = !query.descending)) }, modifier = Modifier.size(40.dp)) {
                        Icon(
                            if (query.descending) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                            contentDescription = stringResource(if (query.descending) R.string.list_descending else R.string.list_ascending),
                        )
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showFilters && spec.filters.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChips(spec, query, onQuery) }
        }
    }
}

/** [spec]'s filters as chips, for [ListSearchBar] or a row of the list's own. */
@Composable
fun <T> FilterChips(spec: ListSpec<T>, query: ListQuery, onQuery: (ListQuery) -> Unit) {
    for (filter in spec.filters) {
        FilterChip(
            selected = filter.key in query.filters,
            onClick = { onQuery(query.toggled(filter.key)) },
            label = { Text(stringResource(filter.label)) },
        )
    }
}

/** How long typing pauses before the list is searched. */
private const val SEARCH_DELAY_MS = 250L
