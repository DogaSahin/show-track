import logging
import uuid
from collections import defaultdict
from collections.abc import Mapping, Sequence
from datetime import UTC, datetime, timedelta

from sqlalchemy import exists, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.db import BULK_INSERT_CHUNK_SIZE, chunked, get_sessionmaker
from app.library.models import UserMedia
from app.media.models import Media, MediaSource, MediaStatus
from app.media.providers.base import MediaProvider, ProviderMedia
from app.media.providers.errors import ProviderError, ProviderRateLimited
from app.notifications.models import NotificationPrefs, NotificationTask, NotificationThreshold
from app.sync.locks import SYNC_LOCK_KEY, THRESHOLD_LOCK_KEY, advisory_lock
from app.sync.schemas import SyncSummary, ThresholdScanSummary

logger = logging.getLogger(__name__)

# FINISHED is the only status never polled. Phase 3's TMDB mapper carries a note making this
# explicit: "In Production" maps to NOT_YET_AIRED, so polling only AIRING would mean a
# pre-premiere show never starts syncing and its first episode never notifies.
SYNCABLE_STATUSES = (MediaStatus.AIRING, MediaStatus.NOT_YET_AIRED)

Worklist = list[tuple[uuid.UUID, MediaSource, str]]


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
    for field, value in incoming.items():
        if getattr(media, field) != value:
            setattr(media, field, value)
            changed = True
    return changed


async def collect_worklist(session: AsyncSession) -> Worklist:
    """Which titles need refreshing. Read-only, so the caller can end the transaction afterwards.

    Only (id, source, external_id) crosses the boundary — carrying ORM objects across the
    caller's rollback would hit the attribute-expiry hazard Phase 4 documented at length.
    """
    tracked = (
        select(Media.id, Media.source, Media.external_id)
        .where(Media.status.in_(SYNCABLE_STATUSES))
        # DELETE /v1/library deliberately leaves the shared media row behind, so without this the
        # job spends provider budget on titles nobody watches.
        .where(exists().where(UserMedia.media_id == Media.id))
    )
    return [(media_id, source, external_id) for media_id, source, external_id in await session.execute(tracked)]


async def fetch_all(
    providers: Mapping[MediaSource, MediaProvider], worklist: Sequence[tuple[uuid.UUID, MediaSource, str]]
) -> tuple[dict[tuple[MediaSource, str], ProviderMedia], int]:
    """Every provider call, with NO session and therefore no transaction in scope.

    Separated from the database work on purpose, and this is the shape decision 4-M asks for. An
    earlier version rolled back once and then applied per source inside the loop — but
    `session.get()` autobegins, so from the SECOND source onward the work session was `idle in
    transaction` across the next source's HTTP calls. Measured with two tracked sources: the
    first source's calls were clean and the second's were not, and which source got the clean
    slot depended on row order. The production registry is exactly two sources, and TMDB uses the
    ABC's looping default — N sequential 8-second requests. Taking no session at all is the only
    shape where that cannot regress.

    Returns (fetched, failed). Failures are counted here rather than raised: nothing above a
    scheduled job catches anything.
    """
    by_source: dict[MediaSource, list[str]] = defaultdict(list)
    for _media_id, source, external_id in worklist:
        by_source[source].append(external_id)

    fetched: dict[tuple[MediaSource, str], ProviderMedia] = {}
    failed = 0
    for source, external_ids in by_source.items():
        provider = providers.get(source)
        if provider is None:
            logger.warning("no provider registered for %s; %d titles not refreshed", source, len(external_ids))
            failed += len(external_ids)
            continue

        try:
            answered = await provider.get_many(external_ids)
        except ProviderRateLimited as exc:
            # Abandon this source for the cycle rather than sleeping. The next cycle is hours
            # away, the data is not urgent, and a job that sleeps inside a scheduler is harder to
            # reason about than one that gives up and comes back. retry_after stays available to
            # Phase 6's dispatcher, where sleeping IS the right behaviour.
            logger.warning("%s rate limited; retry_after=%s; skipping this cycle", source, exc.retry_after)
            failed += len(external_ids)
            continue
        except ProviderError:
            # NOTHING above this catches anything: app/errors.py is registered on the FastAPI app
            # and a scheduled job has no request. One provider failing must not stop the others,
            # and must be a counted outcome rather than an exception into APScheduler.
            logger.exception("%s failed during sync; skipping this cycle", source)
            failed += len(external_ids)
            continue

        for external_id, detail in answered.items():
            fetched[(source, external_id)] = detail

    return fetched, failed


async def apply_refresh(
    session: AsyncSession,
    worklist: Sequence[tuple[uuid.UUID, MediaSource, str]],
    fetched: Mapping[tuple[MediaSource, str], ProviderMedia],
    failed: int,
) -> SyncSummary:
    """Write the fetched data back. Flushes; the caller commits.

    Takes no lock, opens no session and — importantly — does NOT roll back. An earlier version
    rolled back in here, which discarded the caller's data whenever the caller was a test: the
    db_session fixture's root transaction IS a savepoint, so rollback() is ROLLBACK TO SAVEPOINT.
    Measured: a seeded row count went 1 -> 0 and every title was then counted `missing`, so three
    tests failed and two passed for the wrong reason. Transaction boundaries belong to run_sync,
    which owns the session.

    One SELECT for every row rather than one per row: a 500-title library was 500 round trips.
    """
    summary = SyncSummary(ran=True, checked=len(worklist), failed=failed)
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
        detail = fetched.get((source, external_id))
        if detail is None:
            # Verified against the live API: unknown ids are silently omitted from a batch.
            logger.info("%s no longer knows %s; leaving the row untouched", source, external_id)
            summary.missing += 1
            continue
        if _apply(media, detail):
            summary.updated += 1
        else:
            summary.unchanged += 1

    await session.flush()
    return summary


async def run_sync(providers: Mapping[MediaSource, MediaProvider]) -> SyncSummary:
    """The locked, session-owning entry point. BOTH the scheduler and POST /v1/debug/sync call
    this, so a manual trigger cannot run concurrently with a scheduled one — which is the whole
    point of the lock.

    Owns its session rather than borrowing a request's: a job is not a request, and a request's
    transaction must not be held open across the provider calls below (decision 4-M).
    """
    async with advisory_lock(SYNC_LOCK_KEY) as acquired:
        if not acquired:
            return SyncSummary(ran=False)
        async with get_sessionmaker()() as session:
            worklist = await collect_worklist(session)
            # Decision 4-M, and it lives HERE because this function owns the session. The read
            # above autobegan a transaction and fetch_all below awaits provider HTTP; holding a
            # transaction across that is what 4-A and 4-M both reject. Safe to roll back because
            # nothing has been written and the read already returned what it needed.
            await session.rollback()

            fetched, failed = await fetch_all(providers, worklist)

            summary = await apply_refresh(session, worklist, fetched, failed)
            await session.commit()
            return summary


# The SQL prefilter's window. One horizon covers both thresholds because notify_soon_hours is
# bounded at 24 by its `le=` — without that bound a larger lead time would silently start dropping
# candidates the AIRING_SOON threshold should have caught.
NOTIFY_HORIZON = timedelta(hours=24)


def _crossed(airs_at: datetime, now: datetime, *, soon_hours: int) -> tuple[NotificationThreshold, ...]:
    """Which thresholds `now` has crossed for this air time.

    Both are LEAD TIMES. The earlier calendar rule for the second threshold fired only between UTC
    midnight and the air time, so an episode airing at 00:05 UTC had a five-minute window against
    a fifteen-minute scan — never late, simply never enqueued, and indistinguishable in the summary
    from a healthy quiet scan. A lead time has no midnight cliff: the window is the same width
    whatever the air time.
    """
    remaining = airs_at - now
    thresholds: list[NotificationThreshold] = []
    if timedelta(0) < remaining <= NOTIFY_HORIZON:
        thresholds.append(NotificationThreshold.TWENTY_FOUR_HOURS)
    if timedelta(0) < remaining <= timedelta(hours=soon_hours):
        thresholds.append(NotificationThreshold.AIRING_SOON)
    return tuple(thresholds)


def _airs_on(airs_at: datetime) -> datetime:
    """UTC midnight of the air date — the dedup key's time component.

    Truncated, because AniList revises airingAt by seconds for ordinary corrections and a precise
    key would mint a fresh notification for every nudge. `.astimezone(UTC)` first so the truncation
    is anchored to UTC rather than to whatever tzinfo the value happens to carry.
    """
    return airs_at.astimezone(UTC).replace(hour=0, minute=0, second=0, microsecond=0)


async def scan_thresholds(session: AsyncSession, *, now: datetime, soon_hours: int) -> ThresholdScanSummary:
    """Enqueue notifications for approaching episodes. Makes NO provider calls.

    That is the whole point of splitting the jobs: evaluating "airs within 24h" never needed
    provider data, so this can run far more often than the provider sync at zero upstream cost —
    and it keeps working through a provider outage, when dates go stale but the dates already
    known are still worth notifying about.

    `soon_hours` is a parameter rather than a settings read, so no test depends on the developer's
    .env for its expected counts.
    """
    candidates = (
        select(
            UserMedia.user_id,
            Media.id.label("media_id"),
            Media.next_episode_number,
            Media.next_episode_date,
        )
        .join(Media, UserMedia.media_id == Media.id)
        # Inner join: no prefs row means push was never configured, and defaulting it on here
        # would send pushes nobody asked for.
        .join(NotificationPrefs, NotificationPrefs.user_id == UserMedia.user_id)
        .where(NotificationPrefs.push_enabled.is_(True))
        .where(Media.next_episode_date.is_not(None))
        # NotificationTask.episode_number is NOT NULL, so a date without a number would raise at
        # insert time. Excluding it here is cheaper than a 500 in a background job.
        .where(Media.next_episode_number.is_not(None))
        # An episode that has already aired is never a notification.
        .where(Media.next_episode_date > now)
        .where(Media.next_episode_date <= now + NOTIFY_HORIZON)
    )
    rows = (await session.execute(candidates)).all()
    if not rows:
        return ThresholdScanSummary(ran=True)

    wanted = [
        {
            "user_id": row.user_id,
            "media_id": row.media_id,
            "episode_number": row.next_episode_number,
            "threshold": threshold,
            "airs_on": _airs_on(row.next_episode_date),
        }
        for row in rows
        for threshold in _crossed(row.next_episode_date, now, soon_hours=soon_hours)
    ]
    if not wanted:
        return ThresholdScanSummary(ran=True, considered=len(rows))

    enqueued = 0
    for chunk in chunked(wanted, BULK_INSERT_CHUNK_SIZE):
        statement = (
            pg_insert(NotificationTask)
            .values(list(chunk))
            # Dedup is the constraint, never application logic. Every scan between a threshold
            # crossing and the airing re-derives the same rows; being refused is the steady state,
            # not an error.
            .on_conflict_do_nothing(constraint="uq_notification_tasks_dedup")
            .returning(NotificationTask.id)
        )
        enqueued += len((await session.execute(statement)).all())

    await session.flush()
    return ThresholdScanSummary(
        ran=True, considered=len(rows), enqueued=enqueued, already_queued=len(wanted) - enqueued
    )


async def run_threshold_scan(*, now: datetime | None = None) -> ThresholdScanSummary:
    """The locked, session-owning entry point, mirroring run_sync.

    A separate lock key from the provider sync, so a slow six-hourly job never stalls the
    fifteen-minute one.
    """
    async with advisory_lock(THRESHOLD_LOCK_KEY) as acquired:
        if not acquired:
            return ThresholdScanSummary(ran=False)
        async with get_sessionmaker()() as session:
            summary = await scan_thresholds(
                session,
                now=now or datetime.now(tz=UTC),
                # Resolved HERE, not inside scan_thresholds: a settings read buried in the service
                # makes every threshold test depend on the developer's .env.
                soon_hours=get_settings().notify_soon_hours,
            )
            await session.commit()
            return summary
