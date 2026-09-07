package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.R

@Composable
fun CountdownBadge(
    daysUntil: Int?,
    modifier: Modifier = Modifier,
) {
    val label =
        when {
            daysUntil == null -> return // nothing to count down to; render nothing
            daysUntil <= 0 -> stringResource(R.string.countdown_today)
            daysUntil == 1 -> stringResource(R.string.countdown_tomorrow)
            else -> pluralStringResource(R.plurals.countdown_days_remaining, daysUntil, daysUntil)
        }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
