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
from app.media.providers.genres import TMDB_GENRES, map_genres

logger = logging.getLogger(__name__)

# w500 is TMDB's standard poster width. The fully correct source for this is the /configuration
# endpoint, which costs a request per process to learn a value that has not changed in years —
# not worth it until it breaks.
IMAGE_BASE_URL = "https://image.tmdb.org/t/p/w500"

_STATUS_MAP = {
    "Returning Series": MediaStatus.AIRING,
    "Ended": MediaStatus.FINISHED,
    "Canceled": MediaStatus.FINISHED,
    "In Production": MediaStatus.NOT_YET_AIRED,
    "Planned": MediaStatus.NOT_YET_AIRED,
    "Pilot": MediaStatus.NOT_YET_AIRED,
}


def _year(raw_date: str | None) -> int | None:
    """TMDB sends "" rather than null for an unknown air date, so a truthiness check is not
    enough on its own — the slice has to be validated too.
    """
    if not raw_date or len(raw_date) < 4 or not raw_date[:4].isdigit():
        return None
    return int(raw_date[:4])


def _cover(poster_path: str | None) -> str | None:
    return f"{IMAGE_BASE_URL}{poster_path}" if poster_path else None


def _status(raw_status: str | None) -> MediaStatus:
    mapped = _STATUS_MAP.get(raw_status or "")
    if mapped is None:
        logger.warning("unknown TMDB status %r; treating as airing", raw_status)
        return MediaStatus.AIRING
    return mapped


def _next_episode(raw: dict[str, Any] | None) -> NextEpisode | None:
    if not raw:
        return None
    air_date = raw.get("air_date")
    return NextEpisode(
        season_number=raw["season_number"],
        number=raw["episode_number"],
        # TMDB gives day granularity only, so the time is fabricated as midnight UTC. Accepted
        # imprecision: TV countdowns are day-accurate where anime countdowns are hour-accurate.
        airs_at=datetime.strptime(air_date, "%Y-%m-%d").replace(tzinfo=UTC) if air_date else None,
    )


def to_summary(raw: dict[str, Any]) -> ProviderMediaSummary:
    """Search results carry `genre_ids` as bare integers."""
    return ProviderMediaSummary(
        ref=MediaRef(source=MediaSource.TMDB, external_id=str(raw["id"])),
        type=MediaType.TV,
        title=raw.get("name") or "Untitled",
        year=_year(raw.get("first_air_date")),
        genres=map_genres(TMDB_GENRES, raw.get("genre_ids") or []),
        cover_image_url=_cover(raw.get("poster_path")),
    )


def to_search_page(raw: dict[str, Any]) -> ProviderSearchPage:
    return ProviderSearchPage(
        items=tuple(to_summary(entry) for entry in raw.get("results") or []),
        has_more=int(raw.get("page") or 1) < int(raw.get("total_pages") or 1),
    )


def to_media(raw: dict[str, Any]) -> ProviderMedia:
    """Detail results carry `genres` as objects, not ids — same table, different extraction."""
    genre_ids = [entry["id"] for entry in raw.get("genres") or []]
    return ProviderMedia(
        ref=MediaRef(source=MediaSource.TMDB, external_id=str(raw["id"])),
        type=MediaType.TV,
        title=raw.get("name") or "Untitled",
        year=_year(raw.get("first_air_date")),
        genres=map_genres(TMDB_GENRES, genre_ids),
        cover_image_url=_cover(raw.get("poster_path")),
        status=_status(raw.get("status")),
        next_episode=_next_episode(raw.get("next_episode_to_air")),
    )
