package com.anarky.showtrack.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.MediaCard
import com.anarky.showtrack.core.model.LibraryEntry

/**
 * The stateful entry point. `hiltViewModel()` is the only line in this module that touches DI:
 * it resolves [LibraryViewModel] out of the nearest `ViewModelStoreOwner`'s Hilt-backed factory,
 * so the ViewModel's `LibraryRepository` arrives from the singleton component with no wiring
 * here at all.
 */
@Composable
fun LibraryScreen(
    onEntryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    // collectAsStateWithLifecycle, not collectAsState: the latter keeps collecting while the
    // screen is in the background, which is what makes the ViewModel's WhileSubscribed(5s)
    // upstream never stop.
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    LibraryScreen(entries = entries, onEntryClick = onEntryClick, modifier = modifier)
}

/**
 * The stateless half, split out so it can be previewed and screenshot-tested without a graph.
 * Phase 9 fills in the status tabs, refresh and paging affordances; this task's contract is only
 * that the list arrives from an injected repository interface.
 */
@Composable
internal fun LibraryScreen(
    entries: List<LibraryEntry>,
    onEntryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        EmptyState(message = "Nothing in your library yet.", modifier = modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(all = 12.dp),
        verticalArrangement = Arrangement.spacedBy(space = 8.dp),
    ) {
        // Keyed by entry id: without a key, LazyColumn identifies items by index and a refresh
        // that reorders the list re-uses the wrong composable state for every row.
        items(items = entries, key = LibraryEntry::id) { entry ->
            MediaCard(
                media = entry.media,
                status = entry.status,
                score = entry.score,
                onClick = { onEntryClick(entry.id) },
            )
        }
    }
}
