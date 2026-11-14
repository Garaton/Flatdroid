package com.garathon.flatdroid.ui.list

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * A new order (another sort, or the direction turned) starts the list at its top; search and
 * filters leave it where it is. Every list with the shared search bar uses this, so they behave
 * the same. A list that comes back on screen with its order unchanged (after Settings, say) is
 * left where it was.
 */
@Composable
fun ScrollToTopOnNewOrder(query: ListQuery, state: LazyListState) {
    var seen by remember { mutableStateOf(query.sort to query.descending) }
    LaunchedEffect(query.sort, query.descending) {
        val now = query.sort to query.descending
        if (now != seen) {
            seen = now
            state.scrollToItem(0)
        }
    }
}
