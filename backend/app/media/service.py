import asyncio
import logging
import uuid
from collections.abc import Callable, Mapping, Sequence
from contextlib import AbstractAsyncContextManager
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from itertools import zip_longest

from sqlalchemy import Integer, and_, delete, exists, func, literal, select, tuple_, update
from sqlalchemy.dialects.postgresql import ARRAY
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import BULK_INSERT_CHUNK_SIZE, chunked
from app.library.models import UserMedia
from app.media.models import Episode, Media, MediaSource, MediaStatus
from app.media.providers.base import (
    MediaProvider,
    MediaRef,
    ProviderEpisode,
    ProviderMedia,
    ProviderMediaSummary,
    ProviderSearchPage,
)
from app.media.providers.errors import ProviderError, ProviderRateLimited, ProviderTimeout
from app.media.schemas import (
    EpisodeItem,
    EpisodeList,
    LibraryEntryRef,
    MediaDetail,
    MediaSearchResponse,
    MediaSummary,
    PersistedMedia,
    SearchItem,
    SeasonEpisodes,
    SourceStatus,
)

# A module reference, not `from ... import`: app.sync.service imports this module too, and a
# module reference is what lets either side be imported first.
from app.sync import service as sync_service

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
        items=[SearchItem(**_to_summary(item).model_dump()) for item in _interleave(pages)],
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


def to_persisted(media: Media) -> PersistedMedia:
    """The stale-proof half of the Media -> schema mapping.

    Takes no clock, deliberately: nothing it returns is time-dependent, which is exactly why
    `recommendations` embeds this. A recommendation candidate is by construction NOT in anyone's
    library, so the sync job never refreshes it and any airing field on it would be frozen at the
    moment the seed job created the row.
    """
    return PersistedMedia(
        id=media.id,
        source=media.source,
        external_id=media.external_id,
        type=media.type,
        title=media.title,
        year=media.year,
        genres=list(media.genres),
        cover_image_url=media.cover_image_url,
    )


def to_detail(media: Media, now: datetime) -> MediaDetail:
    """Public because `library` embeds MediaDetail in every entry; one owner of the
    Media -> schema mapping means the two cannot drift.
    """
    return MediaDetail(
        **to_persisted(media).model_dump(),
        status=media.status,
        next_episode_season=media.next_episode_season,
        next_episode_number=media.next_episode_number,
        next_episode_date=media.next_episode_date,
        days_until_next_episode=days_until(media.next_episode_date, now),
        total_episodes=media.total_episodes,
    )


async def get_media_detail(session: AsyncSession, media_id: uuid.UUID, now: datetime) -> MediaDetail | None:
    media = await session.get(Media, media_id)
    return to_detail(media, now) if media is not None else None


# A title nobody tracks is outside the sync job's worklist, so its airing fields freeze. Opening it
# from search refreshes it once it is this old.
UNLIBRARIED_REFRESH_AFTER = timedelta(hours=24)


async def search_with_library_state(
    session: AsyncSession,
    providers: Mapping[MediaSource, MediaProvider],
    *,
    user_id: uuid.UUID,
    query: str,
    page: int,
) -> MediaSearchResponse:
    """`search_media`, then one query marking which results are stored and which the caller tracks.

    The read transaction the auth dependency began is ended before the provider fan-out (decision
    4-M: no connection idle-in-transaction across an external HTTP call). It is ended with a
    commit, not a rollback: nothing is pending, so the two are equivalent in production, and a
    rollback would also discard rows a test seeded inside the savepoint its session runs in
    (`apply_refresh`'s docstring measured that). `user_id` is passed in already read.

    One query whatever the page size: `media` outer-joined to the caller's own `user_media` rows,
    filtered on the page's (source, external_id) pairs. Scoped by the join condition, so another
    user's entry can never mark a result. It runs over whatever came back, so a degraded search
    (one provider down) still gets the fields for the results it has.
    """
    await session.commit()
    response = await search_media(providers, query, page)
    if not response.items:
        return response

    pairs = list({(item.source, item.external_id) for item in response.items})
    rows = await session.execute(
        select(Media.source, Media.external_id, Media.id, UserMedia.id, UserMedia.status)
        .outerjoin(UserMedia, and_(UserMedia.media_id == Media.id, UserMedia.user_id == user_id))
        .where(tuple_(Media.source, Media.external_id).in_(pairs))
    )
    known = {
        (source, external_id): (media_id, entry_id, entry_status)
        for source, external_id, media_id, entry_id, entry_status in rows
    }

    def annotate(item: SearchItem) -> SearchItem:
        match = known.get((item.source, item.external_id))
        if match is None:
            return item
        media_id, entry_id, entry_status = match
        entry = LibraryEntryRef(id=entry_id, status=entry_status) if entry_id is not None else None
        return item.model_copy(update={"media_id": media_id, "library_entry": entry})

    return response.model_copy(update={"items": [annotate(item) for item in response.items]})


async def resolve_media(
    session: AsyncSession,
    providers: Mapping[MediaSource, MediaProvider],
    ref: MediaRef,
    now: datetime,
) -> MediaDetail:
    """A search result's stored row, created if need be, so a client can open it without adding it.

    A new title goes through `get_or_create_media` (race-free, insert-once). An existing row that
    no library holds and that was last synced over `UNLIBRARIED_REFRESH_AFTER` ago is refreshed
    from its provider first, through the sync job's own writer (`sync_service.apply_refresh`), so freshness
    still has one owner. If that refresh fails, the stored row is returned as it is: a slightly
    stale screen beats an error for a title that exists. Flushes; the caller commits.

    A row this endpoint just created has never been synced (only the sync writer stamps
    `last_synced_at`), so opening it a second time refreshes it once; from then on it is fresh.
    """
    existing = await _select_by_ref(session, ref)
    if existing is None:
        media = await get_or_create_media(session, providers, ref)
        return to_detail(media, now)

    media_id, last_synced_at = existing.id, existing.last_synced_at
    tracked = await session.scalar(select(exists().where(UserMedia.media_id == media_id)))
    stale = last_synced_at is None or now - last_synced_at > UNLIBRARIED_REFRESH_AFTER
    provider = providers.get(ref.source)
    if not tracked and stale and provider is not None:
        # Decision 4-M again: end the read transaction before the provider call (a commit, for
        # the reason given in search_with_library_state).
        await session.commit()
        try:
            detail = await provider.get_by_id(ref.external_id)
        except ProviderError:
            logger.info("refreshing %s %s on resolve failed; returning the stored row", ref.source, ref.external_id)
        except Exception:
            # A provider-client bug (an unmapped payload, say) must not turn a title that exists
            # into a 500 either; logged loudly because, unlike an outage, it needs fixing.
            logger.exception(
                "refreshing %s %s on resolve raised; returning the stored row", ref.source, ref.external_id
            )
        else:
            fetched = {(ref.source, ref.external_id): detail} if detail is not None else {}
            await sync_service.apply_refresh(session, [(media_id, ref.source, ref.external_id)], fetched, (), now=now)

    media = await session.get(Media, media_id, populate_existing=True)
    return to_detail(media, now)


# Six bound parameters per row (the client-side id plus five columns), so this stays far below
# Postgres' 32,767-parameter ceiling even for a 1,000-episode show.
EPISODE_INSERT_CHUNK_SIZE = 1000

SessionFactory = Callable[[], AbstractAsyncContextManager[AsyncSession]]


async def store_episodes(
    session: AsyncSession,
    media_id: uuid.UUID,
    episodes: Sequence[ProviderEpisode],
    now: datetime,
) -> None:
    """Replace a title's stored episode list with a provider's answer. Flushes; the caller commits.

    Upserts on the (media_id, season_number, number) constraint, then deletes what the provider no
    longer lists (a renumbered TMDB season, say). Stamps the list as fetched and stores its size.
    """
    unique = {(episode.season_number, episode.number): episode for episode in episodes}
    ordered = [unique[key] for key in sorted(unique)]
    for chunk in chunked(ordered, EPISODE_INSERT_CHUNK_SIZE):
        insert = pg_insert(Episode).values(
            [
                {
                    "media_id": media_id,
                    "season_number": episode.season_number,
                    "number": episode.number,
                    "title": episode.title,
                    "air_date": episode.air_date,
                }
                for episode in chunk
            ]
        )
        await session.execute(
            insert.on_conflict_do_update(
                index_elements=["media_id", "season_number", "number"],
                set_={"title": insert.excluded.title, "air_date": insert.excluded.air_date},
            )
        )

    stale = delete(Episode).where(Episode.media_id == media_id)
    if unique:
        # The kept pairs travel as two array parameters, not two parameters per episode, so even a
        # very long show stays far below Postgres' bind-parameter ceiling.
        kept = select(
            func.unnest(literal([season for season, _ in unique], ARRAY(Integer))),
            func.unnest(literal([number for _, number in unique], ARRAY(Integer))),
        )
        stale = stale.where(tuple_(Episode.season_number, Episode.number).not_in(kept))
    await session.execute(stale)
    await session.execute(
        update(Media)
        .where(Media.id == media_id)
        # An empty list has no meaningful total ("x of 0"), so it stays unknown.
        .values(episodes_synced_at=now, total_episodes=len(unique) or None, episodes_refresh_due=False)
    )
    # The UPDATE ran in the database; a Media already loaded in this session still holds the old
    # values until it is re-read (the async identity-map trap).
    await session.get(Media, media_id, populate_existing=True)


async def fetch_and_store_episodes(
    session_factory: SessionFactory,
    providers: Mapping[MediaSource, MediaProvider],
    media_id: uuid.UUID,
    ref: MediaRef,
) -> None:
    """Fetch one title's episode list and store it, in a session of its own. Runs after a title is
    added to a library, so its episodes are there when the user opens it rather than after the
    next sync. Nothing above a background task catches anything, so every failure is logged and
    swallowed: the sync job retries a list that is still unfetched.
    """
    provider = providers.get(ref.source)
    if provider is None:
        return
    try:
        episodes = await provider.get_episodes(ref.external_id)
        if episodes is None:
            return
        async with session_factory() as session:
            await store_episodes(session, media_id, episodes, datetime.now(tz=UTC))
            await session.commit()
    except ProviderError:
        logger.info("fetching episodes for %s %s failed; the sync job will retry", ref.source, ref.external_id)
    except Exception:
        logger.exception("storing episodes for %s %s failed", ref.source, ref.external_id)


async def mark_episodes_checked(session: AsyncSession, media_id: uuid.UUID, now: datetime) -> None:
    """The provider no longer knows the title. Its stored list is kept as it is (it may be a passing
    404, and watched records hang off these rows); only the stamp moves, so it is not asked again
    every cycle. Flushes; the caller commits.
    """
    await session.execute(
        update(Media).where(Media.id == media_id).values(episodes_synced_at=now, episodes_refresh_due=False)
    )
    await session.get(Media, media_id, populate_existing=True)


def _aired(episode: Episode, media: Media, now: datetime) -> bool:
    """An undated episode of a finished show has aired; of an airing or upcoming show, not yet.

    A dated episode has aired once its day has begun (UTC), except the one the next-episode pointer
    names while its exact air time is still ahead: a TMDB date is the local air date, so an evening
    US episode would otherwise read as aired most of a day early.
    """
    if episode.air_date is None:
        return media.status == MediaStatus.FINISHED
    today = now.astimezone(UTC).date()
    if episode.air_date < today:
        return True
    if episode.air_date > today:
        return False
    is_next = (episode.season_number, episode.number) == (media.next_episode_season, media.next_episode_number)
    return not (is_next and media.next_episode_date is not None and media.next_episode_date > now)


async def get_episode_list(session: AsyncSession, media_id: uuid.UUID, now: datetime) -> EpisodeList | None:
    """Database only, like every read but search. None when the title does not exist."""
    media = await session.get(Media, media_id)
    if media is None:
        return None
    if media.episodes_synced_at is None:
        return EpisodeList(synced_at=None, total_episodes=None, seasons=[])

    rows = await session.scalars(
        select(Episode).where(Episode.media_id == media_id).order_by(Episode.season_number, Episode.number)
    )
    seasons: dict[int, list[EpisodeItem]] = {}
    for episode in rows:
        seasons.setdefault(episode.season_number, []).append(
            EpisodeItem(
                id=episode.id,
                number=episode.number,
                title=episode.title,
                air_date=episode.air_date,
                aired=_aired(episode, media, now),
            )
        )
    return EpisodeList(
        synced_at=media.episodes_synced_at,
        total_episodes=media.total_episodes,
        seasons=[
            SeasonEpisodes(number=number, episode_count=len(items), episodes=items) for number, items in seasons.items()
        ],
    )
