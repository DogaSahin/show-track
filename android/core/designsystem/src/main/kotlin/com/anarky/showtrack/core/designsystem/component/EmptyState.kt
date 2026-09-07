package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A centred, single-line explanation for a list or screen that has nothing to show.
 *
 * [actionLabel]/[onAction] (task 9c.5 fix round 1, BLOCKING B2): an optional action button below
 * the message, for the one case a bare sentence is not enough — a screen with genuinely nothing to
 * show AND nowhere else to go from without it (Feed's "you are in no groups" — §9.11's acceptance
 * criterion demands a create-or-join door, not just better copy on a dead end). Parameterising the
 * shared component rather than forking it (decision C-T) — `StaleDataBanner`'s own `messageRes`
 * precedent for widening instead of copying. Both default to `null`; the button renders only when
 * BOTH are supplied, so every existing caller (Library, Favorites, Discover, Search, Groups, the
 * group watchlist) is unaffected.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(all = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(space = 12.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Button(onClick = onAction) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}
