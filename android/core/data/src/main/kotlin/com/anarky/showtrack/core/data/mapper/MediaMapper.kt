package com.anarky.showtrack.core.data.mapper

import com.anarky.showtrack.core.model.Episode
import com.anarky.showtrack.core.model.EpisodeList
import com.anarky.showtrack.core.model.Media
import com.anarky.showtrack.core.model.MediaSource
import com.anarky.showtrack.core.model.MediaStatus
import com.anarky.showtrack.core.model.MediaType
import com.anarky.showtrack.core.model.Season
import com.anarky.showtrack.core.network.dto.EpisodeListDto
import com.anarky.showtrack.core.network.dto.MediaDto
import java.time.Instant
import java.time.LocalDate

/**
 * The wire/domain boundary. `MediaDto` keeps every field in its wire type — `String` enums and
 * ISO-8601 `String` timestamps — precisely so the parse happens once, here, and nothing
 * downstream of `:core:data` ever handles a raw wire value.
 *
 * `uppercase()` because the backend serialises its enums lower-case: `"anilist"`, `"anime"`,
 * `"finished"`, `"not_yet_aired"`, decoded from a real response in `:core:network`'s
 * `WireContractTest`. `valueOf` therefore throws on a value a newer backend adds — deliberate,
 * and the same asymmetry `ignoreUnknownKeys` gives the DTOs: a NEW field is ignored, a CHANGED
 * meaning fails loudly rather than becoming a silently wrong default.
 */
fun MediaDto.toDomain(): Media =
    Media(
        id = id,
        source = MediaSource.valueOf(source.uppercase()),
        externalId = externalId,
        type = MediaType.valueOf(type.uppercase()),
        title = title,
        year = year,
        genres = genres,
        coverImageUrl = coverImageUrl,
        status = MediaStatus.valueOf(status.uppercase()),
        nextEpisodeSeason = nextEpisodeSeason,
        nextEpisodeNumber = nextEpisodeNumber,
        nextEpisodeDate = nextEpisodeDate?.let(Instant::parse),
        daysUntilNextEpisode = daysUntilNextEpisode,
        totalEpisodes = totalEpisodes,
    )

/** An unparseable date reads as unknown rather than failing the whole list. */
fun EpisodeListDto.toDomain(): EpisodeList =
    EpisodeList(
        syncedAt = syncedAt?.let { runCatching { Instant.parse(it) }.getOrNull() },
        totalEpisodes = totalEpisodes,
        seasons =
            seasons.map { season ->
                Season(
                    number = season.number,
                    episodes =
                        season.episodes.map { episode ->
                            Episode(
                                id = episode.id,
                                number = episode.number,
                                title = episode.title,
                                airDate = episode.airDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                                aired = episode.aired,
                            )
                        },
                )
            },
    )
