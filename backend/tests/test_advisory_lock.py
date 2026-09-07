import contextlib

from sqlalchemy import text

from app.db import get_engine
from app.sync.locks import SYNC_LOCK_KEY, THRESHOLD_LOCK_KEY, advisory_lock


async def _locks_held(key: int) -> int:
    """Count advisory locks on `key`, from a connection of its own.

    Asserted directly rather than inferred from a later re-acquisition: a lock stranded on a
    POOLED connection is invisible to "can I acquire it again?" if the pool happens to hand back
    the same connection, and that is exactly the failure this module has to avoid.

    Filtered by database because pg_locks is CLUSTER-wide — objid alone counts holders in other
    databases, which would make this fail for reasons that have nothing to do with the code.
    """
    async with get_engine().connect() as probe:
        return await probe.scalar(
            text(
                "SELECT count(*) FROM pg_locks "
                "WHERE locktype = 'advisory' AND objid = :key "
                "AND database = (SELECT oid FROM pg_database WHERE datname = current_database())"
            ),
            {"key": key},
        )


async def test_a_second_holder_is_refused_immediately():
    """The phase's stated acceptance: two concurrent invocations, one does the work and the other
    returns immediately.

    Deliberately does NOT use the db_session fixture. That fixture shares one connection across
    the whole test, and a Postgres advisory lock is per-SESSION — a second acquisition on the same
    connection always succeeds, so the fixture would make this pass no matter what.
    """
    async with advisory_lock(SYNC_LOCK_KEY) as first:
        assert first is True
        async with advisory_lock(SYNC_LOCK_KEY) as second:
            assert second is False


async def test_the_lock_is_released_on_exit():
    async with advisory_lock(SYNC_LOCK_KEY) as acquired:
        assert acquired is True

    async with advisory_lock(SYNC_LOCK_KEY) as reacquired:
        assert reacquired is True


async def test_the_lock_really_leaves_postgres_on_exit():
    """Re-acquisition is not proof: the pool could hand back the same connection, for which the
    lock is re-entrant. This reads pg_locks directly.
    """
    async with advisory_lock(SYNC_LOCK_KEY):
        assert await _locks_held(SYNC_LOCK_KEY) == 1

    assert await _locks_held(SYNC_LOCK_KEY) == 0


async def test_the_lock_is_released_when_the_body_raises():
    """A job that crashes must not strand the lock until the process dies."""
    with contextlib.suppress(RuntimeError):
        async with advisory_lock(SYNC_LOCK_KEY):
            raise RuntimeError("job blew up")

    assert await _locks_held(SYNC_LOCK_KEY) == 0


async def test_the_lock_connection_does_not_sit_idle_in_transaction():
    """Decision 5-E chose a session-scoped lock precisely so no transaction stays open across the
    job's provider calls. SQLAlchemy autobegins on the first statement, so without AUTOCOMMIT the
    connection is `idle in transaction` for the whole job — defeating the decision, and inviting a
    managed Postgres with idle_in_transaction_session_timeout to kill the connection mid-job,
    which RELEASES the lock while the job still runs.

    Scoped to the backend actually holding this lock: asserting over every connection in the
    database would fail for a psql session or a paused debugger, with a message blaming this code.
    """
    async with advisory_lock(SYNC_LOCK_KEY) as acquired:
        assert acquired is True
        async with get_engine().connect() as probe:
            states = list(
                await probe.scalars(
                    text(
                        "SELECT a.state FROM pg_stat_activity a "
                        "JOIN pg_locks l ON l.pid = a.pid "
                        "WHERE l.locktype = 'advisory' AND l.objid = :key "
                        "AND l.database = (SELECT oid FROM pg_database WHERE datname = current_database())"
                    ),
                    {"key": SYNC_LOCK_KEY},
                )
            )

    assert states == ["idle"], f"the lock connection is {states}, not idle"


async def test_the_two_jobs_do_not_block_each_other():
    """Distinct keys, so a slow provider sync never stalls the threshold scan."""
    async with advisory_lock(SYNC_LOCK_KEY) as sync_held:
        async with advisory_lock(THRESHOLD_LOCK_KEY) as scan_held:
            assert sync_held is True
            assert scan_held is True
