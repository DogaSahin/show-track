package com.anarky.showtrack.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.label
import com.anarky.showtrack.core.designsystem.component.markColor
import com.anarky.showtrack.core.model.LibraryFilter
import com.anarky.showtrack.core.model.LibrarySort
import com.anarky.showtrack.core.model.LibraryStats
import com.anarky.showtrack.core.model.UserMediaStatus

private val ButtonHeight = 40.dp
private val DotSize = 8.dp

/**
 * "Watching ▾ · Next episode ▾ · 3 shows": two plain dropdowns that always say what you are
 * looking at, and how many shows that is. Sits outside the scrolling list, so it stays put.
 *
 * Counts come from [stats]; while it is null (not loaded yet, or failed) every count is left out
 * rather than shown as 0.
 */
@Composable
internal fun LibraryFilterRow(
    filter: LibraryFilter,
    stats: LibraryStats?,
    onStatusSelected: (UserMediaStatus?) -> Unit,
    onSortSelected: (LibrarySort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space = 8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        StatusDropdown(status = filter.status, stats = stats, onStatusSelected = onStatusSelected)
        SortDropdown(sort = filter.sort, onSortSelected = onSortSelected)
        Spacer(modifier = Modifier.weight(1f))
        stats?.countFor(filter.status)?.let { count ->
            Text(
                text = pluralStringResource(R.plurals.library_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun LibraryStats.countFor(status: UserMediaStatus?): Int = if (status == null) total else byStatus[status] ?: 0

@Composable
private fun StatusDropdown(
    status: UserMediaStatus?,
    stats: LibraryStats?,
    onStatusSelected: (UserMediaStatus?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = status?.label() ?: stringResource(R.string.library_status_all)
    val description = stringResource(R.string.library_status_description, current)
    Box {
        FilterButton(
            text = current,
            dot = status?.markColor(),
            filled = true,
            description = description,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + UserMediaStatus.entries).forEach { option ->
                val isCurrent = option == status
                DropdownMenuItem(
                    text = { Text(text = option?.label() ?: stringResource(R.string.library_status_all)) },
                    leadingIcon = option?.let { { Dot(color = it.markColor()) } },
                    trailingIcon =
                        stats?.let { s ->
                            {
                                Text(
                                    text = s.countFor(option).toString(),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                    onClick = {
                        expanded = false
                        if (!isCurrent) onStatusSelected(option)
                    },
                    modifier =
                        Modifier
                            .semantics { selected = isCurrent }
                            .then(
                                if (isCurrent) {
                                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                                } else {
                                    Modifier
                                },
                            ),
                )
            }
        }
    }
}

@Composable
private fun SortDropdown(
    sort: LibrarySort,
    onSortSelected: (LibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = stringResource(sort.labelRes())
    Box {
        FilterButton(
            text = current,
            dot = null,
            filled = false,
            description = stringResource(R.string.library_sort_description, current),
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySort.entries.forEach { option ->
                val isCurrent = option == sort
                DropdownMenuItem(
                    text = { Text(text = stringResource(option.labelRes())) },
                    trailingIcon =
                        if (isCurrent) {
                            (
                                {
                                    Text(
                                        text = "✓",
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            )
                        } else {
                            null
                        },
                    onClick = {
                        expanded = false
                        if (!isCurrent) onSortSelected(option)
                    },
                    modifier = Modifier.semantics { selected = isCurrent },
                )
            }
        }
    }
}

internal fun LibrarySort.labelRes(): Int =
    when (this) {
        LibrarySort.TITLE -> R.string.library_sort_title
        LibrarySort.SCORE -> R.string.library_sort_score
        LibrarySort.NEXT_EPISODE_DATE -> R.string.library_sort_next_episode
    }

/**
 * The status button is filled, the sort button outlined, so the two read as "what" and "in what
 * order". TalkBack hears "Status: Watching", not a bare "Watching" with no idea what it filters.
 */
@Composable
private fun FilterButton(
    text: String,
    dot: Color?,
    filled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (filled) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        contentColor = if (filled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (filled) null else BorderStroke(width = 1.dp, color = MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.height(ButtonHeight).clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space = 6.dp),
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            dot?.let { Dot(color = it) }
            Text(text = text, style = MaterialTheme.typography.labelLarge)
            Text(text = "▾", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Box(modifier = Modifier.size(DotSize).clip(CircleShape).background(color))
}
