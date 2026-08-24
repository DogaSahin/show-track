import asyncio
import contextlib
import logging
from datetime import UTC, datetime, timedelta

from apscheduler.schedulers.asyncio import AsyncIOScheduler
from apscheduler.triggers.interval import IntervalTrigger

from app.config import get_settings
from app.media.providers import get_providers
from app.notifications import service as notifications_service
from app.notifications.ntfy import get_transport
from app.recommendations import service as recommendations_service
from app.sync import service

logger = logging.getLogger(__name__)

SYNC_JOB_ID = "sync_airing_media"
THRESHOLD_JOB_ID = "scan_thresholds"
DISPATCH_JOB_ID = "dispatch_notifications"
SEED_JOB_ID = "seed_recommendations"

# Jobs that call a provider, and how long after boot each one first fires. The DB-only jobs are
# absent and start immediately: they are free, and their precision is the point.
#
# A startup offset exists so a crash loop or `uvicorn --reload` does not trigger a full provider
# sweep on every restart. The offsets DIFFER per job because the two are not independent
# consumers: get_providers() is memoised, so both draw from one AniList RateLimiter through one
# client. A single shared offset put them on the same tick at boot — and since the default
# intervals are 1h and 12h, 12 being a multiple of 1, every later seed run landed on a sync run
# too, in perpetuity. That contention is asymmetric: seed_once counts a ProviderRateLimited as one
# failed seed, while app/sync/service.py abandons a whole source for the cycle, so the 12-hourly
# recommendations job could cost the time-critical airing sync a full cycle. Worst case is the
# first boot after a large AniList import, when both worklists are at their largest at once.
#
# Five minutes for the seed: far enough past the sync's one minute that a fresh import's sync
# sweep has drained first, short enough not to punish a developer following the README. With the
# defaults the two never coincide again either — sync fires at 1 mod 60 minutes and seed at 5 mod
# 60 — though that is a happy consequence of the numbers, not a guarantee, since both intervals
# are configurable. The offsets remove the GUARANTEED collision; they do not remove contention.
PROVIDER_JOB_START_OFFSETS = {
    SYNC_JOB_ID: timedelta(minutes=1),
    SEED_JOB_ID: timedelta(minutes=5),
}

# Job wrappers register their running task here so lifespan can wait for cancellation to be
# DELIVERED before the engine is disposed. See main.py's shutdown comment.
_inflight: set[asyncio.Task[None]] = set()


async def drain_inflight(limit: float = 10.0) -> None:  # not `timeout=`: ruff ASYNC109
    """Wait for cancelled jobs to finish unwinding.

    scheduler.shutdown(wait=False) cancels the job futures and returns — APScheduler's
    AsyncIOExecutor cannot honour wait=True without becoming a coroutine. Cancellation is
    delivered on the next loop iteration, so without this a cancelled job's `finally` (the
    advisory unlock, the session close) runs AFTER dispose_engine() has torn down the pool.
    """
    if not _inflight:
        return
    with contextlib.suppress(TimeoutError):
        async with asyncio.timeout(limit):
            await asyncio.gather(*list(_inflight), return_exceptions=True)


async def _guarded(name: str, run) -> None:
    """Register in `_inflight`, swallow, log.

    Every job goes through this. An earlier version registered only the sync job, leaving the
    THRESHOLD job — which runs 24x more often and is by far the likelier one to be cancelled at
    shutdown — undrained.

    CancelledError is re-raised, not swallowed: it is a BaseException, so `except Exception`
    misses it. But catching it explicitly and logging it as an ordinary shutdown event is what
    stops APScheduler reporting every graceful shutdown as an ERROR with a stack trace — which is
    precisely the "stack trace nobody reads" these wrappers exist to prevent.
    """
    task = asyncio.current_task()
    if task is not None:
        _inflight.add(task)
    try:
        summary = await run()
        logger.info("%s finished: %s", name, summary.model_dump())
    except asyncio.CancelledError:
        logger.info("%s cancelled at shutdown", name)
        raise
    except Exception:
        logger.exception("%s failed", name)
    finally:
        if task is not None:
            _inflight.discard(task)


async def run_sync_job() -> None:
    await _guarded("sync job", lambda: service.run_sync(get_providers()))


async def run_threshold_job() -> None:
    await _guarded("threshold scan", service.run_threshold_scan)


async def run_seed_job() -> None:
    await _guarded("recommendation seed", lambda: recommendations_service.run_seed(get_providers()))


async def run_dispatch_job() -> None:
    """Resolves the transport per RUN, not at registration.

    start_scheduler only decides whether to register the job at all; a transport captured there
    would outlive a get_settings cache clear. Re-reading is one attribute lookup a minute.
    """
    transport = get_transport()
    if transport is None:
        return
    await _guarded("notification dispatch", lambda: notifications_service.run_dispatch(transport))


def start_scheduler() -> AsyncIOScheduler | None:
    """Register the jobs and start. Returns None when sync is disabled.

    Registration lives here and job LOGIC lives in service.py, so the jobs are callable — and
    testable — with no scheduler at all. That separation is also what lets POST /v1/debug/sync
    invoke the identical function rather than a parallel copy.
    """
    settings = get_settings()
    if not settings.sync_enabled:
        logger.info("sync_enabled is false; no background jobs registered")
        return None

    # timezone="UTC" here does NOT reach a pre-constructed IntervalTrigger — the trigger binds
    # get_localzone() at construction. Measured on a dev machine: a bare IntervalTrigger came back
    # as Europe/Istanbul. Hence timezone="UTC" on each trigger below as well; without it, run
    # times are computed in the host's zone and a 6-hourly job can drift across a DST transition.
    scheduler = AsyncIOScheduler(timezone="UTC")
    now = datetime.now(tz=UTC)

    jobs = [
        (run_sync_job, IntervalTrigger(hours=settings.sync_interval_hours, timezone="UTC"), SYNC_JOB_ID),
        (
            run_threshold_job,
            IntervalTrigger(minutes=settings.threshold_scan_minutes, timezone="UTC"),
            THRESHOLD_JOB_ID,
        ),
        (
            run_seed_job,
            IntervalTrigger(hours=settings.recommendations_seed_hours, timezone="UTC"),
            SEED_JOB_ID,
        ),
    ]
    # Guarded, not registered-and-inert: with ntfy unconfigured (6-K) run_dispatch_job returns
    # immediately, so registering it anyway would wake the loop every minute forever to do
    # nothing and log nothing. Tasks still accumulate as `pending` and drain once ntfy is set up.
    dispatch_enabled = get_transport() is not None
    if dispatch_enabled:
        jobs.append(
            (
                run_dispatch_job,
                IntervalTrigger(minutes=settings.notification_dispatch_minutes, timezone="UTC"),
                DISPATCH_JOB_ID,
            )
        )

    for job, trigger, job_id in jobs:
        scheduler.add_job(
            job,
            trigger,
            id=job_id,
            # The IN-PROCESS half of the protection: stop a slow run overlapping itself, and
            # collapse a backlog into one run after a pause. The advisory lock is the
            # CROSS-PROCESS half. Neither substitutes for the other.
            max_instances=1,
            coalesce=True,
            # APScheduler's default is ONE SECOND: any run the loop cannot dispatch within a
            # second of its due time is discarded with a "was missed by" warning, and coalesce
            # does not rescue it — coalescing collapses PENDING runs, and a run past its grace
            # window is thrown away first. Verified against apscheduler 3.11.3's own
            # BaseScheduler._configure. None = no limit, correct for jobs that are idempotent,
            # lock-guarded and catch-up safe.
            misfire_grace_time=None,
            # IntervalTrigger's first run is at now + interval, so a process restarting more often
            # than its interval (crash loop, rolling deploy, `uvicorn --reload`) would never run
            # the 6-hourly sync. But firing everything at boot makes every restart a full provider
            # sweep against an API observed degraded to 30/min, and the lock does not help because
            # restarts are sequential. So the DB-only jobs start immediately — they are free, and
            # their precision is the whole point — and each provider-calling job takes its own
            # offset, per PROVIDER_JOB_START_OFFSETS above.
            next_run_time=now + PROVIDER_JOB_START_OFFSETS.get(job_id, timedelta()),
        )

    scheduler.start()
    logger.info(
        "scheduler started: sync every %dh, threshold scan every %dm, recommendation seed every %dh, dispatch %s",
        settings.sync_interval_hours,
        settings.threshold_scan_minutes,
        settings.recommendations_seed_hours,
        f"every {settings.notification_dispatch_minutes}m" if dispatch_enabled else "disabled (no transport)",
    )
    return scheduler
