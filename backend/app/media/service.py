import asyncio
import logging
import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from itertools import zip_longest

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import BULK_INSERT_CHUNK_SIZE, chunked
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

    `.date()` reads the date in the datetime's OWN tzinfo, not in UTC, so both inputs are
    normalized with `.astimezone(UTC)` first — without it "in UTC" would hold only by
    convention (true today because asyncpg returns `timestamptz` as UTC-aware and both
    providers build `airs_at` with `tzinfo=UTC`), a non-UTC aware datetime would silently shift
    the answer by a day, and a naive datetime would not even raise: `date - date` is
    timezone-free, so it would quietly return a plausible but unanchored number.
    """
    if next_date is None:
        return None
    return max((next_date.astimezone(UTC).date() - now.astimezone(UTC).date()).days, 0)


class MediaSourceNotConfigured(Exception):
    """No provider is registered for the requested source.

    A fact about this server's configuration, not about the request — which is why it is
    distinct from MediaNotFound and answers 503 rather than 404. Reached whenever TMDB_API_KEY
    is unset and someone adds a TMDB title, which is the default local setup.
    """


class MediaNotFound(Exception):
    """The provider answered and has no title with that id."""


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


async def persist_media(session: AsyncSession, detail: ProviderMedia) -> Media:
    """Insert-once and race-free.

    ON CONFLICT DO UPDATE with a no-op SET, not DO NOTHING: DO NOTHING neither locks nor waits
    on a conflicting row held by an uncommitted transaction, and the fallback SELECT at READ
    COMMITTED cannot see that row either — so the loser of a concurrent add got None back for a
    title that existed moments later. DO UPDATE takes the lock, waits for the other transaction
    to resolve, and always RETURNS a row.

    The SET references the TARGET column rather than EXCLUDED. Both are no-ops (it is the
    conflict key, so the values are equal by definition), but the target form cannot later be
    misread as "refresh this field from the incoming payload". No column changes value:
    freshness is Phase 5's job and has exactly one owner.
    """
    statement = (
        pg_insert(Media)
        .values(**_insert_values(detail))
        .on_conflict_do_update(
            index_elements=["source", "external_id"],
            set_={"external_id": Media.__table__.c.external_id},
        )
        .returning(Media.id)
    )
    media_id = await session.scalar(statement)
    # Never None: DO UPDATE always returns a row. That is the whole point of the clause above.
    return await session.get(Media, media_id)


async def persist_media_bulk(session: AsyncSession, details: Sequence[ProviderMedia]) -> dict[MediaRef, uuid.UUID]:
    """The import path's writer: one statement per chunk instead of one per title.

    Deduplicating by ref first is not hygiene. A single INSERT carrying two rows with the same
    conflict key raises cardinality_violation under DO UPDATE ("cannot affect row a second
    time") — DO NOTHING tolerates it, DO UPDATE does not. Doing it here makes the function
    total, so a title AniList returns in two lists cannot become a 500 in a caller that forgot.
    """
    unique: dict[MediaRef, ProviderMedia] = {}
    for detail in details:
        unique.setdefault(detail.ref, detail)

    # Sorted, and not for tidiness: DO UPDATE takes a row lock per conflicting row, so two
    # concurrent imports sharing popular titles would otherwise acquire those locks in whatever
    # order each user's list happened to arrive in. Postgres detects the resulting deadlock and
    # kills one import outright. A global lock order removes the cycle; it also makes the
    # emitted SQL deterministic, which matters when reading a failing statement.
    ordered = sorted(unique.values(), key=lambda detail: (detail.ref.source, detail.ref.external_id))

    resolved: dict[MediaRef, uuid.UUID] = {}
    for chunk in chunked(ordered, BULK_INSERT_CHUNK_SIZE):
        statement = (
            pg_insert(Media)
            .values([_insert_values(detail) for detail in chunk])
            .on_conflict_do_update(
                index_elements=["source", "external_id"],
                set_={"external_id": Media.__table__.c.external_id},
            )
            .returning(Media.id, Media.source, Media.external_id)
        )
        for media_id, source, external_id in (await session.execute(statement)).all():
            resolved[MediaRef(source=MediaSource(source), external_id=external_id)] = media_id
    return resolved


async def get_or_create_media(
    session: AsyncSession, providers: Mapping[MediaSource, MediaProvider], ref: MediaRef
) -> Media:
    """The only writer of `media` rows outside Phase 5's sync job.

    Insert-once: an existing row is returned untouched and costs no provider request. Refreshing
    here would put unpredictable third-party latency on a read path and give freshness two
    owners.

    Raises rather than returning None, because "no provider configured" (503) and "no such
    title" (404) are different answers to the caller. Provider failures propagate untouched:
    this function does not catch ProviderError, so a timeout reaches app/errors.py as a 504
    rather than being flattened into one of these.
    """
    existing = await _select_by_ref(session, ref)
    if existing is not None:
        return existing

    # Decision 4-M. get_current_user's own read has already BEGUN a transaction, and the
    # provider call below can take up to TOTAL_TIMEOUT_SECONDS (8s). Holding a pooled connection
    # idle-in-transaction across an external HTTP call is the exact objection that got the
    # advisory-lock option rejected in 4-A; it would be incoherent to reject it there and do it
    # here. Discarding this transaction costs nothing: the read it contains found no row.
    #
    # CALLERS BEWARE: rollback() expires every persistent object in the identity map, primary
    # key included. Read anything you need off an ORM object (notably current_user.id) BEFORE
    # calling this, or the next attribute access is a lazy load in async code -> MissingGreenlet.
    await session.rollback()

    provider = providers.get(ref.source)
    if provider is None:
        raise MediaSourceNotConfigured(f"no provider registered for source {ref.source}")

    detail = await provider.get_by_id(ref.external_id)
    if detail is None:
        raise MediaNotFound(f"{ref.source} has no title {ref.external_id}")

    return await persist_media(session, detail)


def to_detail(media: Media, now: datetime) -> MediaDetail:
    """Public because `library` embeds MediaDetail in every entry; one owner of the
    Media -> schema mapping means the two cannot drift.
    """
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
    return to_detail(media, now) if media is not None else None
