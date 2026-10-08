import asyncio
from datetime import timedelta

from app.config import Settings
from app.sync import scheduler as scheduler_module
from app.sync.scheduler import (
    PROVIDER_JOB_START_OFFSETS,
    SEED_JOB_ID,
    SYNC_JOB_ID,
    start_scheduler,
)


def _settings(**overrides) -> Settings:
    """Settings built without reading the developer's real .env.

    Same convention, and same reason, as tests/test_provider_contract.py: without `_env_file=None`
    these assertions read the developer's environment rather than the class defaults — and the
    README tells developers to run with SYNC_ENABLED=false, exactly the
    values that would then make these tests fail locally and pass in CI. A test that fails because
    someone followed the documentation is a bad test.
    """
    base = {
        "_env_file": None,
        "database_url": "postgresql+asyncpg://x/y",
        "secret_key": "x",
        "registration_code": "x",
    }
    return Settings(**{**base, **overrides})


def test_the_scheduler_does_not_start_when_sync_is_disabled(monkeypatch):
    """sync_enabled is how a SECOND REPLICA runs safely: scheduler off, API on. The advisory lock
    protects against the mistake; this setting is how you avoid making it.
    """
    monkeypatch.setattr(scheduler_module, "get_settings", lambda: _settings(sync_enabled=False))

    assert start_scheduler() is None


async def test_the_always_on_jobs_are_registered_with_the_configured_intervals(monkeypatch):
    """MUST be async: APScheduler 3.11 changed AsyncIOScheduler.start() from get_event_loop() to
    get_running_loop(), so a plain `def` raises RuntimeError: no running event loop.

    get_providers is patched even though this test never awaits between start() and shutdown():
    the sync job is due a minute after boot with misfire_grace_time=None, so a slow test could
    reach it — building the real AniListProvider and issuing LIVE requests, violating a project
    rule by accident rather than by intent.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(
        scheduler_module,
        "get_settings",
        lambda: _settings(sync_interval_hours=2, recommendations_seed_hours=8),
    )

    scheduler = start_scheduler()
    try:
        assert {job.id for job in scheduler.get_jobs()} == {SYNC_JOB_ID, SEED_JOB_ID}
        assert scheduler.get_job(SYNC_JOB_ID).trigger.interval.total_seconds() == 2 * 3600
        assert scheduler.get_job(SEED_JOB_ID).trigger.interval.total_seconds() == 8 * 3600
    finally:
        scheduler.shutdown(wait=False)


async def test_the_triggers_are_utc_not_the_host_timezone(monkeypatch):
    """A pre-constructed IntervalTrigger binds get_localzone() at construction, so the scheduler's
    own timezone= never reaches it. Measured on this machine: a bare IntervalTrigger came back as
    Europe/Istanbul. Left alone, run times would be computed in the host's zone and a 6-hourly job
    could drift an hour across a DST transition.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(scheduler_module, "get_settings", _settings)

    scheduler = start_scheduler()
    try:
        for job_id in (SYNC_JOB_ID, SEED_JOB_ID):
            assert str(scheduler.get_job(job_id).trigger.timezone) == "UTC"
    finally:
        scheduler.shutdown(wait=False)


async def test_a_missed_run_is_not_silently_discarded(monkeypatch):
    """APScheduler's default misfire_grace_time is ONE SECOND: any run the loop cannot dispatch
    within a second of its due time is thrown away with a "was missed by" warning, and coalesce
    does not rescue it — coalescing collapses PENDING runs, and a run past its grace window is
    discarded first. A GC pause or the 6-hourly sync occupying the same single-threaded loop is
    enough, and each drop is a whole interval of stale air dates.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(scheduler_module, "get_settings", _settings)

    scheduler = start_scheduler()
    try:
        for job_id in (SYNC_JOB_ID, SEED_JOB_ID):
            assert scheduler.get_job(job_id).misfire_grace_time is None
    finally:
        scheduler.shutdown(wait=False)


async def test_each_provider_job_takes_its_own_boot_offset(monkeypatch):
    """IntervalTrigger schedules its first run at now + interval, so a process restarting more
    often than its interval would never sync at all — but firing every job at boot makes each
    `uvicorn --reload` a full provider sweep against an API observed degraded to 30/min, and the
    lock does not help because restarts are sequential. So each job takes an offset.

    The offsets are pinned per job, not asserted as one shared value, and that is the point of
    this test. Both provider jobs share one memoised AniList RateLimiter, so a single shared
    offset co-located them at boot — and with the default 1h/12h intervals, 12 being a multiple of
    1, every later seed run landed on a sync tick too. Collapsing these two back to one constant
    restores that collision, so this test must fail if anyone does.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(scheduler_module, "get_settings", _settings)

    scheduler = start_scheduler()
    try:
        sync_at = scheduler.get_job(SYNC_JOB_ID).next_run_time
        seed_at = scheduler.get_job(SEED_JOB_ID).next_run_time

        assert sync_at < seed_at
        # Separated by the difference the module declares, not merely ordered.
        assert seed_at - sync_at == PROVIDER_JOB_START_OFFSETS[SEED_JOB_ID] - PROVIDER_JOB_START_OFFSETS[SYNC_JOB_ID]
        assert PROVIDER_JOB_START_OFFSETS[SYNC_JOB_ID] == timedelta(minutes=1)
        assert PROVIDER_JOB_START_OFFSETS[SEED_JOB_ID] == timedelta(minutes=5)
    finally:
        scheduler.shutdown(wait=False)


async def test_a_job_that_raises_does_not_escape_into_the_scheduler(monkeypatch, caplog):
    """There is no error handler above a scheduled job — app/errors.py serves HTTP callers only.
    An exception escaping here is a stack trace nobody reads and a silently skipped cycle.
    """

    async def boom(*args, **kwargs):
        raise RuntimeError("provider exploded")

    monkeypatch.setattr(scheduler_module.service, "run_sync", boom)
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})

    with caplog.at_level("WARNING"):
        await scheduler_module.run_sync_job()  # must not raise

    assert "sync job failed" in caplog.text


async def test_a_cancelled_job_is_not_logged_as_a_failure(monkeypatch, caplog):
    """CancelledError is a BaseException, so `except Exception` misses it — and APScheduler then
    logs every graceful shutdown as an ERROR with a stack trace, which is exactly the "stack trace
    nobody reads" these wrappers exist to prevent.
    """

    async def hang(*args, **kwargs):
        raise asyncio.CancelledError

    monkeypatch.setattr(scheduler_module.service, "run_sync", hang)
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})

    with caplog.at_level("INFO"):
        try:
            await scheduler_module.run_sync_job()
        except asyncio.CancelledError:
            pass

    assert "cancelled at shutdown" in caplog.text
    assert "failed" not in caplog.text


async def test_both_jobs_register_for_the_shutdown_drain(monkeypatch):
    """shutdown(wait=False) CANCELS in-flight jobs and returns; cancellation is delivered on the
    next loop iteration, so without a drain a cancelled job's finally — the advisory unlock, the
    session close — runs AFTER dispose_engine() has torn down the pool.
    """
    seen: list[int] = []

    async def observe(*args, **kwargs):
        seen.append(len(scheduler_module._inflight))
        return scheduler_module.service.SyncSummary(ran=False)

    monkeypatch.setattr(scheduler_module.recommendations_service, "run_seed", observe)
    monkeypatch.setattr(scheduler_module.service, "run_sync", observe)
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})

    await scheduler_module.run_seed_job()
    await scheduler_module.run_sync_job()

    assert seen == [1, 1], "both jobs must register themselves while running"
    assert scheduler_module._inflight == set(), "and deregister when done"


async def test_a_registered_job_actually_fires():
    """The task breakdown's acceptance is "a trivial test job fires on schedule" AND the
    two-concurrent-invocations property. Everything else here asserts REGISTRATION — intervals,
    timezones, grace times — which is not the same claim. This one waits for a real dispatch.

    Uses its own scheduler and a plain counter rather than the app's jobs: the point is to prove
    the wiring dispatches at all, not to run a sync.
    """
    from apscheduler.schedulers.asyncio import AsyncIOScheduler
    from apscheduler.triggers.interval import IntervalTrigger

    fired = asyncio.Event()

    async def probe() -> None:
        fired.set()

    scheduler = AsyncIOScheduler(timezone="UTC")
    scheduler.add_job(probe, IntervalTrigger(seconds=1, timezone="UTC"), id="probe", misfire_grace_time=None)
    scheduler.start()
    try:
        await asyncio.wait_for(fired.wait(), timeout=10)
    finally:
        scheduler.shutdown(wait=False)

    assert fired.is_set()


def test_the_defaults_are_sane_and_nothing_is_required():
    """CLAUDE.md records that Phase 2 added two REQUIRED settings and broke backend-ci on every
    subsequent PR. Nothing here lacks a default.
    """
    settings = _settings()

    assert settings.sync_enabled is True
    # 1, not 6: the provider sync tiers its cadence per title (SYNC_TIERS) and can only honour
    # its tightest tier if the job wakes at least that often.
    assert settings.sync_interval_hours == 1
    assert settings.recommendations_seed_hours == 12
    assert settings.recommendations_ttl_hours == 24
