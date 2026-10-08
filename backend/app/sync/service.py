import logging
import uuid
from collections import defaultdict
from collections.abc import Collection, Mapping, Sequence
from datetime import UTC, datetime, timedelta

from sqlalchemy import DateTime, and_, case, exists, func, literal, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_sessionmaker
from app.library.models import UserMedia

# A module reference, not `from ... import`: app.media.service imports this module too, and a
# module reference is what lets either side be imported first.
from app.media import service as media_service
from app.media.models import Media, MediaSource, MediaStatus
from app.media.providers.base import MediaProvider, ProviderEpisode, ProviderMedia
from app.media.providers.errors import ProviderError, ProviderRateLimited
from app.sync.locks import SYNC_LOCK_KEY, advisory_lock
from app.sync.schemas import SyncSummary

logger = logging.getLogger(__name__)

# FINISHED is the only status never polled. Phase 3's TMDB mapper carries a note making this
# explicit: "In Production" maps to NOT_YET_AIRED, so polling only AIRING would mean a
# pre-premiere show never gets an air date, and the phone never schedules its first alert.
SYNCABLE_STATUSES = (MediaStatus.AIRING, MediaStatus.NOT_YET_AIRED)

# How often to re-ask the provider about a title, by how close its next episode is:
# (episode is at most this far away, refresh at most this often). First match wins, so this must
# stay ordered tightest-first.
#
# Deliberately a constant rather than four settings. The numbers interact — the scheduler interval
# has to be <= the tightest tier here, and the tightest tier is what bounds how stale the air
# date behind a phone's alert can be — so four independently-settable env vars can be put into a combination that
# is incoherent and fails silently. config.py already carries that lesson twice.
#
# The tiers cut BOTH ways against a flat interval: the long tail drops from four provider requests
# a day to one, while a title airing tonight goes from four to twenty-four. Fewer requests, aimed
# better.
SYNC_TIERS: tuple[tuple[timedelta, timedelta], ...] = (
    (timedelta(hours=48), timedelta(hours=1)),
    (timedelta(days=7), timedelta(hours=6)),
)
# Further out than the last tier.
DEFAULT_SYNC_INTERVAL = timedelta(hours=24)
# No known air date at all — its own tier, NOT the default. See _due_cutoff.
UNKNOWN_DATE_SYNC_INTERVAL = timedelta(hours=6)

Worklist = list[tuple[uuid.UUID, MediaSource, str]]

# Episode lists change far less often than next-episode pointers, so they get their own, slower
# schedule: a list never fetched is always due; an airing or upcoming title's list once a day; a
# finished title's list never again (its status changing is what makes it due one last time, see
# _apply). A TMDB list costs one request per season, which is why finished shows are left alone.
EPISODE_REFRESH_INTERVAL = timedelta(hours=24)
# Bounds one cycle's provider cost, oldest lists first; the rest wait for the next cycle.
EPISODE_TITLES_PER_CYCLE = 25


def _due_cutoff(now: datetime):
    """SQL CASE giving, per row, the newest `last_synced_at` that still counts as due.

    Built from SYNC_TIERS so the constant stays the single source of truth, and evaluated in SQL
    rather than Python because the entire point is to not load rows we are not going to fetch —
    filtering after the SELECT would read every tracked title to discard most of them.

    Cutoff TIMESTAMPS rather than intervals: `now - interval` is computed in Python, so no
    interval arithmetic reaches the database and the comparison is a plain timestamp <=.

    Note the tier conditions have no lower bound. A `next_episode_date` already in the past
    therefore lands in the tightest tier — correct, and intentional: a pointer stuck behind the
    current time is the strongest available signal that this row needs fresh data.

    A NULL date gets its own tier (UNKNOWN_DATE_SYNC_INTERVAL) rather than falling through to
    DEFAULT_SYNC_INTERVAL, and that branch is load-bearing. `_apply` writes NULL for both the
    number and the date whenever the provider reports no next episode, and AniList returns
    `nextAiringEpisode: null` TRANSIENTLY — a mid-season break, a delay announcement — not only
    at a finale. The phone cannot schedule an alert while the date is NULL, so a title that blips
    null 23 hours before an airing and is not re-polled for 24 loses BOTH alerts for that episode
    with no trace anywhere. Six hours bounds that window to the same width the flat pre-tiering
    interval had.

    "We lost the pointer on an airing show" and "airs in three weeks" are not the same
    confidence and must not share a cadence. No status condition is needed in the CASE: the
    worklist query already filters to SYNCABLE_STATUSES, so a NULL date reaching here is by
    construction a title we still expect episodes from.
    """
    whens = [
        # First, so the intent reads in source order. Ordering is not load-bearing — a NULL
        # compares NULL, never true, so it could never match a tier condition anyway.
        (Media.next_episode_date.is_(None), literal(now - UNKNOWN_DATE_SYNC_INTERVAL, DateTime(timezone=True))),
        *(
            (Media.next_episode_date <= now + horizon, literal(now - interval, DateTime(timezone=True)))
            for horizon, interval in SYNC_TIERS
        ),
    ]
    return case(*whens, else_=literal(now - DEFAULT_SYNC_INTERVAL, DateTime(timezone=True)))


def _apply(media: Media, detail: ProviderMedia) -> bool:
    """Write the provider's view onto the row. Returns whether anything actually changed, so the
    summary's `updated` means something rather than being a row count.
    """
    episode = detail.next_episode
    incoming = {
        "status": detail.status,
        "next_episode_season": episode.season_number if episode else None,
        "next_episode_number": episode.number if episode else None,
        "next_episode_date": episode.airs_at if episode else None,
    }
    changed = False
    if media.status != detail.status:
        # A status change (a show finishing, or a new season starting) is when the episode list is
        # most likely to have moved: make it due for one more refresh. A flag, not a cleared stamp,
        # so the stored list stays readable until that refresh lands.
        media.episodes_refresh_due = True
    for field, value in incoming.items():
        if getattr(media, field) != value:
            setattr(media, field, value)
            changed = True
    return changed


async def collect_worklist(session: AsyncSession, *, now: datetime) -> Worklist:
    """Which titles are DUE for refreshing. Read-only, so the caller can end the transaction after.

    Only (id, source, external_id) crosses the boundary — carrying ORM objects across the
    caller's rollback would hit the attribute-expiry hazard Phase 4 documented at length.

    `now` is a parameter rather than a clock read because a test whose expected worklist depends
    on the wall clock fails on a slow runner and nowhere else.
    """
    tracked = (
        select(Media.id, Media.source, Media.external_id)
        .where(Media.status.in_(SYNCABLE_STATUSES))
        # DELETE /v1/library deliberately leaves the shared media row behind, so without this the
        # job spends provider budget on titles nobody watches.
        .where(exists().where(UserMedia.media_id == Media.id))
        # NULL means never fetched, and is always due — that is every row the moment the
        # last_synced_at migration lands, and every row a library add creates.
        .where(or_(Media.last_synced_at.is_(None), Media.last_synced_at <= _due_cutoff(now)))
    )
    return [(media_id, source, external_id) for media_id, source, external_id in await session.execute(tracked)]


async def collect_episode_worklist(session: AsyncSession, *, now: datetime) -> Worklist:
    """Tracked titles whose episode list is due (see EPISODE_REFRESH_INTERVAL), never-fetched first."""
    due = (
        select(Media.id, Media.source, Media.external_id)
        .where(exists().where(UserMedia.media_id == Media.id))
        .where(
            or_(
                Media.episodes_synced_at.is_(None),
                Media.episodes_refresh_due.is_(True),
                and_(
                    Media.status.in_(SYNCABLE_STATUSES),
                    Media.episodes_synced_at <= now - EPISODE_REFRESH_INTERVAL,
                ),
            )
        )
        # Random among equally-due titles: a title that fails every time (and so is never stamped)
        # must not hold the same slot every cycle while the rest of the queue waits.
        .order_by(Media.episodes_synced_at.asc().nulls_first(), func.random())
        .limit(EPISODE_TITLES_PER_CYCLE)
    )
    return [(media_id, source, external_id) for media_id, source, external_id in await session.execute(due)]


async def fetch_episode_lists(
    providers: Mapping[MediaSource, MediaProvider], worklist: Sequence[tuple[uuid.UUID, MediaSource, str]]
) -> tuple[dict[uuid.UUID, tuple[ProviderEpisode, ...] | None], int]:
    """One provider call per title, with no session open (decision 4-M). Returns the lists that
    came back and how many titles failed. A failed title keeps its stored list and stays due.

    Every exception is caught per title, not only ProviderError: nothing above a scheduled job
    catches anything, and one malformed answer must not cost the other titles their refresh.
    A rate limit abandons the rest of that source for this cycle, as fetch_all does.
    """
    lists: dict[uuid.UUID, tuple[ProviderEpisode, ...] | None] = {}
    failed = 0
    rate_limited: set[MediaSource] = set()
    for media_id, source, external_id in worklist:
        provider = providers.get(source)
        if provider is None or source in rate_limited:
            failed += 1
            continue
        try:
            episodes = await provider.get_episodes(external_id)
        except ProviderRateLimited as exc:
            logger.warning("%s rate limited during episode sync; retry_after=%s", source, exc.retry_after)
            rate_limited.add(source)
            failed += 1
            continue
        except Exception:
            logger.exception("fetching episodes for %s %s failed", source, external_id)
            failed += 1
            continue
        # None: the provider no longer knows the title. Kept as None so the writer only moves the
        # stamp and leaves the stored list alone.
        lists[media_id] = episodes
    return lists, failed


async def store_episode_lists(
    session: AsyncSession, lists: Mapping[uuid.UUID, tuple[ProviderEpisode, ...] | None], *, now: datetime
) -> tuple[int, int]:
    """Write each fetched list in its own savepoint, so one title's database error costs only that
    title. Returns (refreshed, failed).
    """
    refreshed = failed = 0
    for media_id, episodes in lists.items():
        try:
            async with session.begin_nested():
                if episodes is None:
                    await media_service.mark_episodes_checked(session, media_id, now)
                else:
                    await media_service.store_episodes(session, media_id, episodes, now)
            refreshed += 1
        except Exception:
            logger.exception("storing episodes for %s failed", media_id)
            failed += 1
    return refreshed, failed


async def fetch_all(
    providers: Mapping[MediaSource, MediaProvider], worklist: Sequence[tuple[uuid.UUID, MediaSource, str]]
) -> tuple[dict[tuple[MediaSource, str], ProviderMedia], set[MediaSource]]:
    """Every provider call, with NO session and therefore no transaction in scope.

    Separated from the database work on purpose, and this is the shape decision 4-M asks for. An
    earlier version rolled back once and then applied per source inside the loop — but
    `session.get()` autobegins, so from the SECOND source onward the work session was `idle in
    transaction` across the next source's HTTP calls. Measured with two tracked sources: the
    first source's calls were clean and the second's were not, and which source got the clean
    slot depended on row order. The production registry is exactly two sources, and TMDB uses the
    ABC's looping default — N sequential 8-second requests. Taking no session at all is the only
    shape where that cannot regress.

    Returns (fetched, failed_sources). Failures are reported here rather than raised: nothing
    above a scheduled job catches anything.

    The second element is the set of sources that never answered, NOT a count. The caller has to
    tell "the provider replied and no longer knows this title" apart from "the provider was
    down" — they mean opposite things for whether the row may start a refresh cooldown, and both
    look identical as an absent key in `fetched`.
    """
    by_source: dict[MediaSource, list[str]] = defaultdict(list)
    for _media_id, source, external_id in worklist:
        by_source[source].append(external_id)

    fetched: dict[tuple[MediaSource, str], ProviderMedia] = {}
    failed_sources: set[MediaSource] = set()
    for source, external_ids in by_source.items():
        provider = providers.get(source)
        if provider is None:
            logger.warning("no provider registered for %s; %d titles not refreshed", source, len(external_ids))
            failed_sources.add(source)
            continue

        try:
            answered = await provider.get_many(external_ids)
        except ProviderRateLimited as exc:
            # Abandon this source for the cycle rather than sleeping. The next cycle is hours
            # away, the data is not urgent, and a job that sleeps inside a scheduler is harder to
            # reason about than one that gives up and comes back.
            logger.warning("%s rate limited; retry_after=%s; skipping this cycle", source, exc.retry_after)
            failed_sources.add(source)
            continue
        except ProviderError:
            # NOTHING above this catches anything: app/errors.py is registered on the FastAPI app
            # and a scheduled job has no request. One provider failing must not stop the others,
            # and must be a counted outcome rather than an exception into APScheduler.
            logger.exception("%s failed during sync; skipping this cycle", source)
            failed_sources.add(source)
            continue

        for external_id, detail in answered.items():
            fetched[(source, external_id)] = detail

    return fetched, failed_sources


async def apply_refresh(
    session: AsyncSession,
    worklist: Sequence[tuple[uuid.UUID, MediaSource, str]],
    fetched: Mapping[tuple[MediaSource, str], ProviderMedia],
    failed_sources: Collection[MediaSource],
    *,
    now: datetime,
) -> SyncSummary:
    """Write the fetched data back. Flushes; the caller commits.

    Takes no lock, opens no session and — importantly — does NOT roll back. An earlier version
    rolled back in here, which discarded the caller's data whenever the caller was a test: the
    db_session fixture's root transaction IS a savepoint, so rollback() is ROLLBACK TO SAVEPOINT.
    Measured: a seeded row count went 1 -> 0 and every title was then counted `missing`, so three
    tests failed and two passed for the wrong reason. Transaction boundaries belong to run_sync,
    which owns the session.

    One SELECT for every row rather than one per row: a 500-title library was 500 round trips.

    Also owns `last_synced_at`, which drives the tier the row lands in next cycle. It is stamped
    for every title the provider ANSWERED about — including the ones it disowned, because "we no
    longer know this title" is an answer — and left alone when the source itself failed. Both
    halves matter, in opposite directions:

    - Stamping on failure would push titles into cooldown BECAUSE the provider was down, so an
      outage would render as "everything looks fresh" — the one reading that hides it.
    - NOT stamping a disowned title is a hot loop: it keeps its now-past air date, which pins it
      to the tightest tier, which re-fetches it every hour forever.

    `now` is the job's start time rather than the moment of each write. That is off by however
    long the provider calls took, always in the direction of making the row due marginally
    sooner, which is the harmless direction.
    """
    summary = SyncSummary(ran=True, checked=len(worklist))
    if not worklist:
        return summary

    rows = {
        media.id: media
        for media in await session.scalars(select(Media).where(Media.id.in_([media_id for media_id, _, _ in worklist])))
    }

    for media_id, source, external_id in worklist:
        media = rows.get(media_id)
        if media is None:
            # Deleted between the worklist read and now — a DELETE /v1/library cascade, most
            # likely. Distinct from `missing`, which is a statement about the PROVIDER.
            logger.debug("media %s vanished before it could be refreshed", media_id)
            continue
        if source in failed_sources:
            # No answer came back at all. Counted per title so the summary reflects how much data
            # went stale, and pointedly NOT stamped — see the docstring.
            summary.failed += 1
            continue
        detail = fetched.get((source, external_id))
        if detail is None:
            # Verified against the live API: unknown ids are silently omitted from a batch. An
            # ordinary answer, so the row is stamped even though no field changes.
            logger.info("%s no longer knows %s; leaving the row untouched", source, external_id)
            summary.missing += 1
            media.last_synced_at = now
            continue
        if _apply(media, detail):
            summary.updated += 1
        else:
            summary.unchanged += 1
        media.last_synced_at = now

    await session.flush()
    return summary


async def run_sync(providers: Mapping[MediaSource, MediaProvider], *, now: datetime | None = None) -> SyncSummary:
    """The locked, session-owning entry point. BOTH the scheduler and POST /v1/debug/sync call
    this, so a manual trigger cannot run concurrently with a scheduled one — which is the whole
    point of the lock.

    Owns its session rather than borrowing a request's: a job is not a request, and a request's
    transaction must not be held open across the provider calls below (decision 4-M).
    """
    async with advisory_lock(SYNC_LOCK_KEY) as acquired:
        if not acquired:
            return SyncSummary(ran=False)
        # ONE `now` for the whole cycle, resolved before the worklist read and reused for the
        # stamp. Reading the clock twice would let a title be selected as due and then stamped
        # with a later time, which is harmless here but quietly stops the two halves being
        # about the same instant.
        now = now or datetime.now(tz=UTC)
        async with get_sessionmaker()() as session:
            worklist = await collect_worklist(session, now=now)
            # Decision 4-M, and it lives HERE because this function owns the session. The read
            # above autobegan a transaction and fetch_all below awaits provider HTTP; holding a
            # transaction across that is what 4-A and 4-M both reject. Safe to roll back because
            # nothing has been written and the read already returned what it needed.
            await session.rollback()

            fetched, failed_sources = await fetch_all(providers, worklist)

            summary = await apply_refresh(session, worklist, fetched, failed_sources, now=now)
            await session.commit()

            # The episode phase, after the refresh committed: a failure here never costs the
            # next-episode data above. Same shape: read, end the transaction, call providers with
            # no transaction open, then write.
            episode_work = await collect_episode_worklist(session, now=now)
            await session.rollback()
            lists, failed = await fetch_episode_lists(providers, episode_work)
            refreshed, store_failed = await store_episode_lists(session, lists, now=now)
            await session.commit()
            return summary.model_copy(
                update={"episodes_refreshed": refreshed, "episodes_failed": failed + store_failed}
            )
