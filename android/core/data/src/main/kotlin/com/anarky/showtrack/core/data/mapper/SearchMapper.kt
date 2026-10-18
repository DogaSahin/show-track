package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaSummary
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.SearchResult
import com.anarky.showtrack.core.model.SearchResults
import com.anarky.showtrack.core.model.SourceStatus
import com.anarky.showtrack.core.model.UserMediaStatus
import com.anarky.showtrack.core.network.dto.MediaSearchResponseDto
import com.anarky.showtrack.core.network.dto.SearchItemDto

/**
 * An unknown SOURCE key is dropped; an unknown STATUS counts as degraded. Both directions are
 * deliberate: the client must not crash when the server grows a provider it has never heard of,
 * and it must not silently claim health for a status it cannot interpret.
 */
fun MediaSearchResponseDto.toDomain(): SearchResults =
    SearchResults(
        items = items.mapNotNull(SearchItemDto::toDomain),
        hasMore = hasMore,
        degraded =
            sources.entries.mapNotNull { (source, status) ->
                val known = wireEnum(source, MediaSource.UNKNOWN).takeIf { it != MediaSource.UNKNOWN }
                known?.takeIf { statusOf(status) != SourceStatus.OK }
            },
    )

private fun statusOf(raw: String): SourceStatus = wireEnum(raw, SourceStatus.UNKNOWN)

/**
 * Null for a result whose source or type this client does not know: it cannot be opened or added
 * (the request would carry a value the server never agreed to), so it is left out of the list.
 * A known result whose library status is unknown maps to [UserMediaStatus.UNKNOWN], never to
 * "not tracked" — a title the user has must not look addable.
 */
fun SearchItemDto.toDomain(): SearchResult? {
    val knownSource = wireEnum(source, MediaSource.UNKNOWN)
    val knownType = wireEnum(type, MediaType.UNKNOWN)
    if (knownSource == MediaSource.UNKNOWN || knownType == MediaType.UNKNOWN) return null
    return SearchResult(
        media =
            MediaSummary(
                source = knownSource,
                externalId = externalId,
                type = knownType,
                title = title,
                year = year,
                genres = genres,
                coverImageUrl = coverImageUrl,
            ),
        mediaId = mediaId,
        libraryStatus = libraryEntry?.status?.let { raw -> wireEnum(raw, UserMediaStatus.UNKNOWN) },
    )
}
