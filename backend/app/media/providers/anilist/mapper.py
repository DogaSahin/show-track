import logging
from datetime import UTC, datetime
from decimal import Decimal
from typing import Any

from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers.base import (
    ListEntryStatus,
    MediaRef,
    NextEpisode,
    ProviderListEntry,
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


_LIST_STATUS_MAP = {
    "CURRENT": ListEntryStatus.WATCHING,
    # A rewatch is still watching. PAUSED stays distinct from DROPPED because collapsing them
    # destroys information no re-import can recover.
    "REPEATING": ListEntryStatus.WATCHING,
    "PLANNING": ListEntryStatus.PLANNED,
    "COMPLETED": ListEntryStatus.COMPLETED,
    "DROPPED": ListEntryStatus.DROPPED,
    "PAUSED": ListEntryStatus.PAUSED,
}


def _list_score(raw: Any) -> Decimal | None:
    """AniList's 0 means UNSCORED — a sentinel, not a rating. Stored as 0.0 it drags the
    average-score stat down and tells Phase 7's recommender you hated everything you never rated.

    Range-checked BEFORE quantizing. Decimal(str(1e30)).quantize(Decimal("0.1")) raises
    InvalidOperation (the result exceeds context precision), and json.loads("1e400") yields inf —
    so a merely malformed upstream body, not only a hostile one, would escape this mapper as a
    500 and abort the whole atomic import. Comparing first also disposes of inf and nan for free:
    `1 <= inf <= 10` and `1 <= nan <= 10` are both False.

    Out-of-range values are refused rather than clipped: 85 on a 1-10 column means the score
    format assumption is wrong, and clipping to 10.0 would write a plausible wrong number and
    hide the misconfiguration.

    `isinstance(raw, bool)` is excluded explicitly because bool subclasses int in Python, so
    `True` would otherwise arrive as the score 1.
    """
    if isinstance(raw, bool) or not isinstance(raw, int | float):
        return None
    if not (1 <= raw <= 10):
        if raw > 0:
            logger.warning("AniList score %r is outside 1-10; treating as unscored", raw)
        return None
    return Decimal(str(raw)).quantize(Decimal("0.1"))


def _list_progress(raw: Any) -> int:
    """Bounded, for the same reason _list_score is. user_media.progress is int4, so a value at or
    beyond 2**31 is an asyncpg DataError — and because the import is a single transaction, ONE
    bad entry anywhere in a 10,000-title list aborts the whole thing as a 500 instead of landing
    in `failed`.
    """
    if isinstance(raw, bool) or not isinstance(raw, int) or not (0 <= raw <= 100_000):
        return 0
    return raw


def to_list_entries(raw_collection: dict[str, Any], seen: set[str]) -> tuple[tuple[ProviderListEntry, ...], int]:
    """Returns (entries, dropped).

    `seen` is owned by the caller and mutated here, so deduplication carries across chunk
    responses rather than restarting per chunk.

    Deduplication is load-bearing, not tidiness: AniList groups entries into `lists` (confirmed
    by introspection — MediaListGroup carries `isCustomList`), and with custom lists enabled a
    title appears in its status list and every custom list it belongs to. A duplicated conflict
    key inside one INSERT ... ON CONFLICT DO UPDATE raises cardinality_violation.
    """
    entries: list[ProviderListEntry] = []
    dropped = 0

    for raw_list in raw_collection.get("lists") or []:
        for raw_entry in raw_list.get("entries") or []:
            raw_media = raw_entry.get("media")
            if not isinstance(raw_media, dict) or raw_media.get("id") is None:
                # Counted once per occurrence and NOT deduplicated — there is no id to dedupe on.
                # A malformed entry repeated across two custom lists therefore adds 2 to
                # `dropped`. Accepted and stated rather than silently wrong: the alternative is
                # inventing an identity for an entry that has none.
                logger.warning("AniList list entry carried no usable media object; dropping it")
                dropped += 1
                continue

            external_id = str(raw_media["id"])
            if external_id in seen:
                continue
            # Marked seen as soon as the id resolves, BEFORE the status branch. Otherwise an
            # entry with an unmappable status never enters `seen`, so a title AniList returns in
            # two lists is counted in `dropped` twice and the summary's `failed` over-reports the
            # very number decision 4-I exists to make trustworthy.
            seen.add(external_id)

            status = _LIST_STATUS_MAP.get(raw_entry.get("status") or "")
            if status is None:
                logger.warning(
                    "unknown AniList list status %r on entry %s; dropping it",
                    raw_entry.get("status"),
                    external_id,
                )
                dropped += 1
                continue

            entries.append(
                ProviderListEntry(
                    media=to_media(raw_media),
                    status=status,
                    score=_list_score(raw_entry.get("score")),
                    progress=_list_progress(raw_entry.get("progress")),
                )
            )

    return tuple(entries), dropped
