package com.anarky.showtrack.core.designsystem.component

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.R

/**
 * Sits above a list rendered from a local cache while the network fetch that would normally
 * replace it has not answered yet (decision C-B). In `:core:designsystem`, not the feature that
 * first needed it (`:feature:library`) — decision C-T: a shared presentation belongs here once a
 * second screen backed by a cache is a matter of when, not if.
 *
 * [onRetry] is mandatory, not optional with a no-op default: a user looking at rows they cannot
 * be sure are current wants a way to ask again, and a caller with genuinely nothing better to do
 * on retry than [onRetry] can always pass its own no-op — the choice belongs to the call site,
 * not silently defaulted away here.
 *
 * [messageRes] defaults to [R.string.stale_data_notice] ("Showing saved titles...") for the three
 * existing callers (`LibraryScreen`, `FavoritesScreen`, `ProfileScreen`), which all render titles.
 * `:feature:groups` (task 9c.1 fix round 1) is the first caller whose rows are not titles at all
 * — "Showing saved titles" is the wrong noun for a list of groups — so this became a parameter
 * rather than a second, near-identical component: decision C-T forbids a feature re-implementing
 * a design-system component, and the fix for "the shared copy names the wrong noun" is to let the
 * copy vary, not to fork the banner.
 */
@Composable
fun StaleDataBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes messageRes: Int = R.string.stale_data_notice,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}
