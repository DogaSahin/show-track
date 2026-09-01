package com.anarky.showtrack.core.designsystem.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.R
import java.math.BigDecimal

/** Renders the user's score, or `"—"` when unset — never a raw `null` or an empty string. */
@Composable
fun ScoreChip(
    score: BigDecimal?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(
            text = score?.toPlainString() ?: stringResource(R.string.score_unset),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
