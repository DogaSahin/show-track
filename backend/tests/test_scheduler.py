import asyncio

import pytest

from app.config import Settings
from app.sync import scheduler as scheduler_module
from app.sync.scheduler import DISPATCH_JOB_ID, SYNC_JOB_ID, THRESHOLD_JOB_ID, start_scheduler


def _settings(**overrides) -> Settings:
    """Settings built without reading the developer's real .env.

    Same convention, and same reason, as tests/test_provider_contract.py: without `_env_file=None`
    these assertions read the developer's environment rather than the class defaults — and the
    README tells developers to run with SYNC_ENABLED=false and a short scan interval, exactly the
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


@pytest.fixture(autouse=True)
def no_transport(monkeypatch):
    """ntfy off by default, so registration assertions do not depend on the developer's .env.

    get_transport reads app.config's get_settings directly, so the `_env_file=None` trick these
    tests use for everything else does not reach it — a machine with NTFY_BASE_URL set would
    otherwise register a third job and fail the set-equality assertion below.
    """
    monkeypatch.setattr(scheduler_module, "get_transport", lambda: None)


def test_the_scheduler_does_not_start_when_sync_is_disabled(monkeypatch):
    """sync_enabled is how a SECOND REPLICA runs safely: scheduler off, API on. The advisory lock
    protects against the mistake; this setting is how you avoid making it.
    """
    monkeypatch.setattr(scheduler_module, "get_settings", lambda: _settings(sync_enabled=False))

    assert start_scheduler() is None


async def test_the_db_only_jobs_are_registered_with_the_configured_intervals(monkeypatch):
    """MUST be async: APScheduler 3.11 changed AsyncIOScheduler.start() from get_event_loop() to
    get_running_loop(), so a plain `def` raises RuntimeError: no running event loop.

    get_providers is patched even though this test never awaits between start() and shutdown():
    both jobs are due almost immediately with misfire_grace_time=None, so a single added `await`
    would dispatch them, build the real AniListProvider and issue LIVE requests — violating a
    project rule by accident rather than by intent.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(
        scheduler_module, "get_settings", lambda: _settings(sync_interval_hours=2, threshold_scan_minutes=5)
    )

    scheduler = start_scheduler()
    try:
        assert {job.id for job in scheduler.get_jobs()} == {SYNC_JOB_ID, THRESHOLD_JOB_ID}
        assert scheduler.get_job(SYNC_JOB_ID).trigger.interval.total_seconds() == 2 * 3600
        assert scheduler.get_job(THRESHOLD_JOB_ID).trigger.interval.total_seconds() == 5 * 60
    finally:
        scheduler.shutdown(wait=False)


async def test_the_dispatch_job_is_not_registered_without_a_transport(monkeypatch):
    """6-K: ntfy is optional. A job that returns immediately every minute forever is noise, so it
    is not registered at all rather than registered and inert. Tasks keep queueing as `pending`
    and drain on the first run once ntfy is configured.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(scheduler_module, "get_settings", _settings)

    scheduler = start_scheduler()
    try:
        assert DISPATCH_JOB_ID not in {job.id for job in scheduler.get_jobs()}
    finally:
        scheduler.shutdown(wait=False)


async def test_the_dispatch_job_is_registered_when_a_transport_exists(monkeypatch):
    """And it fires at boot, like the threshold scan: it is a single indexed query when the queue
    is empty, so there is nothing to stagger it against.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(scheduler_module, "get_settings", lambda: _settings(notification_dispatch_minutes=3))
    monkeypatch.setattr(scheduler_module, "get_transport", lambda: object())

    scheduler = start_scheduler()
    try:
        job = scheduler.get_job(DISPATCH_JOB_ID)
        assert job.trigger.interval.total_seconds() == 3 * 60
        assert str(job.trigger.timezone) == "UTC"
        assert job.misfire_grace_time is None
        assert job.next_run_time <= scheduler.get_job(THRESHOLD_JOB_ID).next_run_time
    finally:
        scheduler.shutdown(wait=False)


async def test_the_dispatch_job_is_a_no_op_when_the_transport_disappears(monkeypatch):
    """Registration and execution read the transport separately, so a settings-cache clear between
    them must not push run_dispatch a None transport and blow up inside _guarded.
    """
    called = False

    async def never(*args, **kwargs):
        nonlocal called
        called = True

    monkeypatch.setattr(scheduler_module.notifications_service, "run_dispatch", never)
    monkeypatch.setattr(scheduler_module, "get_transport", lambda: None)

    await scheduler_module.run_dispatch_job()

    assert called is False


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
        for job_id in (SYNC_JOB_ID, THRESHOLD_JOB_ID):
            assert str(scheduler.get_job(job_id).trigger.timezone) == "UTC"
    finally:
        scheduler.shutdown(wait=False)


async def test_a_missed_run_is_not_silently_discarded(monkeypatch):
    """APScheduler's default misfire_grace_time is ONE SECOND: any run the loop cannot dispatch
    within a second of its due time is thrown away with a "was missed by" warning, and coalesce
    does not rescue it — coalescing collapses PENDING runs, and a run past its grace window is
    discarded first. A GC pause or the 6-hourly sync occupying the same single-threaded loop is
    enough, and each drop is a whole scan interval of no notifications.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(scheduler_module, "get_settings", _settings)

    scheduler = start_scheduler()
    try:
        for job_id in (SYNC_JOB_ID, THRESHOLD_JOB_ID):
            assert scheduler.get_job(job_id).misfire_grace_time is None
    finally:
        scheduler.shutdown(wait=False)


async def test_the_provider_sync_does_not_fire_at_boot(monkeypatch):
    """IntervalTrigger schedules its first run at now + interval, so a process restarting more
    often than its interval would never sync at all — but firing BOTH jobs at boot makes every
    `uvicorn --reload` a full provider sweep against an API observed degraded to 30/min, and the
    lock does not help because restarts are sequential. The DB-only scan starts immediately (free,
    and its precision is the point); the provider sync takes a short offset.
    """
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})
    monkeypatch.setattr(scheduler_module, "get_settings", _settings)

    scheduler = start_scheduler()
    try:
        scan_at = scheduler.get_job(THRESHOLD_JOB_ID).next_run_time
        sync_at = scheduler.get_job(SYNC_JOB_ID).next_run_time
        assert sync_at > scan_at
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

    monkeypatch.setattr(scheduler_module.service, "run_threshold_scan", hang)

    with caplog.at_level("INFO"):
        try:
            await scheduler_module.run_threshold_job()
        except asyncio.CancelledError:
            pass

    assert "cancelled at shutdown" in caplog.text
    assert "failed" not in caplog.text


async def test_both_jobs_register_for_the_shutdown_drain(monkeypatch):
    """shutdown(wait=False) CANCELS in-flight jobs and returns; cancellation is delivered on the
    next loop iteration, so without a drain a cancelled job's finally — the advisory unlock, the
    session close — runs AFTER dispose_engine() has torn down the pool. An earlier version
    registered only the sync job, leaving the threshold job (which runs 24x more often, and is by
    far the likelier one to be in flight) undrained.
    """
    seen: list[int] = []

    async def observe(*args, **kwargs):
        seen.append(len(scheduler_module._inflight))
        return scheduler_module.service.ThresholdScanSummary(ran=False)

    monkeypatch.setattr(scheduler_module.service, "run_threshold_scan", observe)
    monkeypatch.setattr(scheduler_module.service, "run_sync", observe)
    monkeypatch.setattr(scheduler_module, "get_providers", lambda: {})

    await scheduler_module.run_threshold_job()
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
    assert settings.threshold_scan_minutes == 15
    assert settings.notify_soon_hours == 6
    assert settings.notification_dispatch_minutes == 1
