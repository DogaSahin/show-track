package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CountdownBadge(
    daysUntil: Int?,
    modifier: Modifier = Modifier,
) {
    val label =
        when {
            daysUntil == null -> return // nothing to count down to; render nothing
            daysUntil <= 0 -> "Today"
            daysUntil == 1 -> "Tomorrow"
            else -> "$daysUntil days"
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
