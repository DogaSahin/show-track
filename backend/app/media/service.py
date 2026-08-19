import asyncio
import logging
import uuid
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from itertools import zip_longest

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.media.models import Media, MediaSource
from app.media.providers.base import (
    MediaProvider,
    MediaRef,
    ProviderMedia,
    ProviderMediaSummary,
    ProviderSearchPage,
)
from app.media.providers.errors import ProviderError, ProviderRateLimited, ProviderTimeout
from app.media.schemas import MediaDetail, MediaSearchResponse, MediaSummary, SourceStatus

logger = logging.getLogger(__name__)

# Above the HTTP layer's 5s read timeout on purpose: this is the outer guard for a provider
# that stalls BETWEEN requests rather than during one.
SEARCH_TIMEOUT_SECONDS = 6.0


@dataclass(frozen=True, slots=True)
class _Outcome:
    source: MediaSource
    status: SourceStatus
    page: ProviderSearchPage | None


async def _search_one(source: MediaSource, provider: MediaProvider, query: str, page: int) -> _Outcome:
    """Never raises.

    Exceptions must not reach asyncio.gather: with its default return_exceptions=False the first
    failure propagates immediately and the sibling's result is discarded, which makes partial
    results impossible. return_exceptions=True also works but hands every caller a
    `list[X | BaseException]` to type-switch over. Catching here keeps the result type honest.

    The final `except Exception` is load-bearing, not defensive padding: real upstream shapes
    reach it today (a 200 body that is a valid JSON array raises AttributeError on `.get()`; a
    mapper indexing a missing "id" key raises KeyError). Without this clause those propagate
    through asyncio.gather(return_exceptions=False), 500 the whole request, and discard the
    healthy sibling's page — exactly what this function's docstring promises cannot happen.
    `Exception`, never `BaseException`: asyncio.CancelledError inherits from BaseException, and
    swallowing it here would defeat client-disconnect cancellation, leaving the request running
    after the caller has gone.
    """
    try:
        async with asyncio.timeout(SEARCH_TIMEOUT_SECONDS):
            result = await provider.search(query, page)
    except (ProviderTimeout, TimeoutError):
        logger.warning("provider %s timed out during search", source)
        return _Outcome(source, SourceStatus.TIMEOUT, None)
    except ProviderRateLimited:
        logger.warning("provider %s is rate limited", source)
        return _Outcome(source, SourceStatus.RATE_LIMITED, None)
    except ProviderError:
        logger.exception("provider %s failed during search", source)
        return _Outcome(source, SourceStatus.ERROR, None)
    except Exception:
        logger.exception("provider %s raised an unexpected error during search", source)
        return _Outcome(source, SourceStatus.ERROR, None)
    return _Outcome(source, SourceStatus.OK, result)


def _interleave(pages: list[ProviderSearchPage]) -> list[ProviderMediaSummary]:
    """Round-robin across providers.

    Interleaving preserves each provider's own relevance ranking internally, rather than
    concatenating one provider's page entirely behind another's, and needs no cross-provider
    similarity metric we would have to defend. `sorted()` in search_media fixes a deterministic
    provider order first, so which provider lands in slot 1 does not shift with dict iteration
    order — AniList sorts first today, so it deterministically takes the top slot on every
    query; this function does not choose that, it only makes the interleave stable once that
    order is fixed. If one provider returns nothing this degenerates to plain concatenation at
    no cost.
    """
    return [item for row in zip_longest(*(page.items for page in pages)) for item in row if item is not None]


def _to_summary(item: ProviderMediaSummary) -> MediaSummary:
    return MediaSummary(
        source=item.ref.source,
        external_id=item.ref.external_id,
        type=item.type,
        title=item.title,
        year=item.year,
        genres=list(item.genres),
        cover_image_url=item.cover_image_url,
    )


async def search_media(providers: Mapping[MediaSource, MediaProvider], query: str, page: int) -> MediaSearchResponse:
    # sorted() so interleave order does not depend on dict insertion order. A stable ordering is
    # what makes the route tests assert on a sequence rather than a set.
    ordered_sources = sorted(providers)
    outcomes = await asyncio.gather(
        *(_search_one(source, providers[source], query, page) for source in ordered_sources)
    )

    # Keyed off the registry key passed into _search_one, not provider.source: a provider
    # registered under a key that does not match its own self-reported source would otherwise
    # report under the wrong entry and could silently overwrite a sibling's status.
    sources = {source: SourceStatus.NOT_CONFIGURED for source in MediaSource}
    sources.update({outcome.source: outcome.status for outcome in outcomes})

    pages = [outcome.page for outcome in outcomes if outcome.page is not None]
    return MediaSearchResponse(
        items=[_to_summary(item) for item in _interleave(pages)],
        page=page,
        has_more=any(page_result.has_more for page_result in pages),
        sources=sources,
    )


def days_until(next_date: datetime | None, now: datetime) -> int | None:
    """Whole calendar days in UTC, not elapsed hours // 24.

    A countdown should say "1" for tomorrow even when tomorrow is two hours away. Computed on
    the server so "3 days" means the same thing regardless of the device's clock; the accepted
    cost is that a user far from UTC can see the boundary shift by a day.
    """
    if next_date is None:
        return None
    return max((next_date.date() - now.date()).days, 0)


async def _select_by_ref(session: AsyncSession, ref: MediaRef) -> Media | None:
    return await session.scalar(select(Media).where(Media.source == ref.source, Media.external_id == ref.external_id))


def _insert_values(detail: ProviderMedia) -> dict[str, object]:
    episode = detail.next_episode
    return {
        "type": detail.type,
        "source": detail.ref.source,
        "external_id": detail.ref.external_id,
        "title": detail.title,
        "year": detail.year,
        "genres": list(detail.genres),
        "cover_image_url": detail.cover_image_url,
        "status": detail.status,
        "next_episode_season": episode.season_number if episode else None,
        "next_episode_number": episode.number if episode else None,
        "next_episode_date": episode.airs_at if episode else None,
    }


async def get_or_create_media(
    session: AsyncSession, providers: Mapping[MediaSource, MediaProvider], ref: MediaRef
) -> Media | None:
    """The only writer of `media` rows outside Phase 5's sync job.

    Insert-once: an existing row is returned untouched and costs no provider request. Refreshing
    here would put unpredictable third-party latency on a read path and give freshness two
    owners.
    """
    existing = await _select_by_ref(session, ref)
    if existing is not None:
        return existing

    provider = providers.get(ref.source)
    if provider is None:
        return None
    detail = await provider.get_by_id(ref.external_id)
    if detail is None:
        return None

    # ON CONFLICT DO NOTHING ... RETURNING, not SELECT-then-INSERT: the latter has a TOCTOU
    # window where two concurrent adds of the same title make one raise IntegrityError. The
    # Phase 1 unique constraint on (source, external_id) is what makes the atomic form
    # expressible at all.
    statement = (
        pg_insert(Media)
        .values(**_insert_values(detail))
        .on_conflict_do_nothing(index_elements=["source", "external_id"])
        .returning(Media.id)
    )
    media_id = await session.scalar(statement)
    if media_id is None:
        # A concurrent insert won the race; the row exists now.
        return await _select_by_ref(session, ref)
    return await session.get(Media, media_id)


def _to_detail(media: Media, now: datetime) -> MediaDetail:
    return MediaDetail(
        id=media.id,
        source=media.source,
        external_id=media.external_id,
        type=media.type,
        title=media.title,
        year=media.year,
        genres=list(media.genres),
        cover_image_url=media.cover_image_url,
        status=media.status,
        next_episode_season=media.next_episode_season,
        next_episode_number=media.next_episode_number,
        next_episode_date=media.next_episode_date,
        days_until_next_episode=days_until(media.next_episode_date, now),
    )


async def get_media_detail(session: AsyncSession, media_id: uuid.UUID, now: datetime) -> MediaDetail | None:
    media = await session.get(Media, media_id)
    return _to_detail(media, now) if media is not None else None
