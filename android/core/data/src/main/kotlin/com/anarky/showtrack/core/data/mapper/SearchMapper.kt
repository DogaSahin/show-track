package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.SourceStatus
import com.anarky.showtrack.core.network.dto.MediaSearchResponseDto
import com.anarky.showtrack.core.network.dto.MediaSummaryDto

fun MediaSummaryDto.toDomain(): MediaSummary =
    MediaSummary(
        source = MediaSource.valueOf(source.uppercase()),
        externalId = externalId,
        type = MediaType.valueOf(type.uppercase()),
        title = title,
        year = year,
        genres = genres,
        coverImageUrl = coverImageUrl,
    )

/**
 * An unknown SOURCE key is dropped; an unknown STATUS counts as degraded. Both directions are
 * deliberate: the client must not crash when the server grows a provider it has never heard of,
 * and it must not silently claim health for a status it cannot interpret.
 */
fun MediaSearchResponseDto.toDomain(): SearchResults =
    SearchResults(
        items = items.map(MediaSummaryDto::toDomain),
        hasMore = hasMore,
        degraded =
            sources.entries.mapNotNull { (source, status) ->
                val known = MediaSource.entries.find { it.name.equals(source, ignoreCase = true) }
                known?.takeIf { statusOf(status) != SourceStatus.OK }
            },
    )

private fun statusOf(raw: String): SourceStatus =
    SourceStatus.entries.find { it.name.equals(raw, ignoreCase = true) } ?: SourceStatus.UNKNOWN
