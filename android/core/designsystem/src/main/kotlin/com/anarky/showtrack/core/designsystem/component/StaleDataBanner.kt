package com.anarky.showtrack.core.designsystem.component

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
 */
@Composable
fun StaleDataBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stale_data_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onRetry) {
                Text(text = stringResource(R.string.action_retry))
            }
        }
    }
}
