package com.anarky.showtrack.core.designsystem

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.anarky.showtrack.core.designsystem.component.CountdownBadge
import com.anarky.showtrack.core.designsystem.component.EmptyState
import com.anarky.showtrack.core.designsystem.component.ErrorState
import com.anarky.showtrack.core.designsystem.component.LoadingState
import com.anarky.showtrack.core.designsystem.component.MediaCard
import com.anarky.showtrack.core.designsystem.component.ScoreChip
import com.anarky.showtrack.core.designsystem.component.StatusTab
import com.anarky.showtrack.core.designsystem.theme.ShowTrackTheme
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.UserMediaStatus
import java.math.BigDecimal

// coverImageUrl = null is deliberate: a preview must render without network, so this is what
// exercises MediaCard's placeholder path rather than a real image load.
private val previewMedia =
    Media(
        id = "preview",
        source = MediaSource.ANILIST,
        externalId = "1",
        type = MediaType.ANIME,
        title = "Frieren: Beyond Journey's End",
        year = 2023,
        genres = listOf("Adventure", "Drama", "Fantasy"),
        coverImageUrl = null,
        status = MediaStatus.AIRING,
        nextEpisodeSeason = 1,
        nextEpisodeNumber = 12,
        nextEpisodeDate = null,
        daysUntilNextEpisode = 1,
    )

@Preview(showBackground = true, name = "Design system gallery")
@Preview(showBackground = true, name = "Dark", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun GalleryPreview() {
    ShowTrackTheme {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            MediaCard(
                media = previewMedia,
                status = UserMediaStatus.WATCHING,
                score = BigDecimal("8.5"),
                onClick = {},
            )
            CountdownBadge(daysUntil = 1)
            CountdownBadge(daysUntil = 12)
            ScoreChip(score = BigDecimal("8.5"))
            ScoreChip(score = null)
            // Selected state is the only thing visually distinguishing a tab from a plain label,
            // so both states are shown side by side rather than just one.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusTab(status = UserMediaStatus.WATCHING, selected = true, onClick = {})
                StatusTab(status = UserMediaStatus.COMPLETED, selected = false, onClick = {})
            }
            EmptyState(message = "Nothing here yet")
            LoadingState()
            ErrorState(message = "Could not reach the server", onRetry = {})
        }
    }
}
