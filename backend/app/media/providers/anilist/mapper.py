import logging
from datetime import UTC, datetime
from typing import Any

from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers.base import (
    MediaRef,
    NextEpisode,
    ProviderMedia,
    ProviderMediaSummary,
    ProviderSearchPage,
)
from app.media.providers.genres import ANILIST_GENRES, map_genres

logger = logging.getLogger(__name__)

_STATUS_MAP = {
    "RELEASING": MediaStatus.AIRING,
    "FINISHED": MediaStatus.FINISHED,
    "CANCELLED": MediaStatus.FINISHED,
    "NOT_YET_RELEASED": MediaStatus.NOT_YET_AIRED,
    # HIATUS is AIRING, not FINISHED. Status is what Phase 5's sync job filters on, so marking a
    # paused show FINISHED stops polling it and it is never seen resuming. When the mapping is
    # uncertain, prefer the value that keeps the job looking: a wrong FINISHED is silent and
    # permanent, a wrong AIRING costs one wasted request per cycle.
    "HIATUS": MediaStatus.AIRING,
}


def _title(raw: dict[str, Any]) -> str:
    """English first, romaji as the fallback: AniList's `english` is frequently null and
    `romaji` is always present, so the reverse order would show far more romaji than needed.
    """
    title = raw.get("title") or {}
    return title.get("english") or title.get("romaji") or "Untitled"


def _year(raw: dict[str, Any]) -> int | None:
    return (raw.get("startDate") or {}).get("year")


def _cover(raw: dict[str, Any]) -> str | None:
    return (raw.get("coverImage") or {}).get("large")


def _status(raw_status: str | None) -> MediaStatus:
    mapped = _STATUS_MAP.get(raw_status or "")
    if mapped is None:
        logger.warning("unknown AniList status %r; treating as airing", raw_status)
        return MediaStatus.AIRING
    return mapped


def _airs_at(airing_at: int | None) -> datetime | None:
    """Guarded for the same reason http.py's `_parse_reset_at` is: an integer that is perfectly
    valid but outside `datetime`'s representable range raises ValueError, OverflowError or OSError
    depending on platform. Not reachable today — GraphQL's `Int` is 32-bit, so `airingAt` is
    bounded — but this is the same unguarded conversion of third-party bytes that this phase
    already had a Critical about, and a mapper degrades a field rather than raising.
    """
    if airing_at is None:
        return None
    try:
        return datetime.fromtimestamp(airing_at, tz=UTC)
    except (ValueError, OverflowError, OSError):
        logger.warning("unusable AniList airingAt %r; leaving airs_at unset", airing_at)
        return None


def _next_episode(raw: dict[str, Any] | None) -> NextEpisode | None:
    if not raw:
        return None
    return NextEpisode(
        # AniList models a cour as its own entity, so there is only ever one season — the same
        # domain assumption Episode.season_number's server_default of 1 encodes.
        season_number=1,
        number=raw["episode"],
        airs_at=_airs_at(raw.get("airingAt")),
    )


def to_summary(raw: dict[str, Any]) -> ProviderMediaSummary:
    return ProviderMediaSummary(
        ref=MediaRef(source=MediaSource.ANILIST, external_id=str(raw["id"])),
        type=MediaType.ANIME,
        title=_title(raw),
        year=_year(raw),
        genres=map_genres(ANILIST_GENRES, raw.get("genres") or []),
        cover_image_url=_cover(raw),
    )


def to_search_page(raw_page: dict[str, Any]) -> ProviderSearchPage:
    return ProviderSearchPage(
        items=tuple(to_summary(entry) for entry in raw_page.get("media") or []),
        has_more=bool((raw_page.get("pageInfo") or {}).get("hasNextPage")),
    )


def to_media(raw: dict[str, Any]) -> ProviderMedia:
    return ProviderMedia(
        ref=MediaRef(source=MediaSource.ANILIST, external_id=str(raw["id"])),
        type=MediaType.ANIME,
        title=_title(raw),
        year=_year(raw),
        genres=map_genres(ANILIST_GENRES, raw.get("genres") or []),
        cover_image_url=_cover(raw),
        status=_status(raw.get("status")),
        next_episode=_next_episode(raw.get("nextAiringEpisode")),
    )
