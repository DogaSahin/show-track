import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy import text

from app.db import get_engine

logger = logging.getLogger(__name__)

# Explicit constants, not hashes of a job name: a readable number in pg_locks is worth more than
# a clever derivation, and a hash collision between two job names would be invisible.
SYNC_LOCK_KEY = 5_000_001
THRESHOLD_LOCK_KEY = 5_000_002
# A third key, not a shared one: a slow dispatch must never stall the threshold scan. Same
# reasoning as 5-D, which split the jobs in the first place.
DISPATCH_LOCK_KEY = 5_000_003


@asynccontextmanager
async def advisory_lock(key: int) -> AsyncIterator[bool]:
    """Yield whether the lock was acquired. Never blocks, never raises on contention.

    `pg_try_advisory_lock`, not `pg_advisory_lock`: the acceptance criterion is that a second
    concurrent invocation returns IMMEDIATELY. The blocking form would queue it instead, which for
    a periodic job means a backlog rather than safety.

    SESSION-scoped on a connection of its own, NOT `pg_try_advisory_xact_lock`. The xact form is
    tempting because it auto-releases, but it would force a transaction to stay open for the whole
    job, including every provider HTTP call — the objection that got an advisory lock rejected in
    get_or_create_media (Phase 4, decision 4-A) and that added a rollback() to both provider paths
    (4-M). Holding the lock on a dedicated connection leaves the caller's work session free.

    A SECOND, INDEPENDENT reason the xact form is wrong, and the one that survives even if the
    open-transaction objection above is ever waved away: it releases at the end of the enclosing
    transaction, and neither connection gives it one that lasts the job. On this connection there
    is no enclosing transaction at all — AUTOCOMMIT (see below) makes the acquiring SELECT its own
    transaction, so the lock would be released the instant it was taken, before the job body runs
    a single statement. Moving it onto the caller's work session instead does not rescue it:
    notifications.service.dispatch_once commits MID-FUNCTION to make the attempt durable (6-G),
    which ends that transaction and drops the lock while the send loop is still running. Either
    placement lets a second replica acquire it and double-send.

    A dying connection releases the lock automatically, which is what makes this safe against a
    crashed replica — where a table-based "is it running" flag would strand a stale `true`. Note
    "dying" means the backend actually going away: returning a connection to the POOL does not
    release the lock, which is why the release path below invalidates on failure.
    """
    async with get_engine().connect() as connection:
        # AUTOCOMMIT, and this is load-bearing rather than tidy. SQLAlchemy AUTOBEGINS on the
        # first statement, so a plain connection sits `idle in transaction` for the entire job —
        # exactly the state this decision was taken to avoid, which would make it defeat itself.
        # Measured: 'idle in transaction' plain, 'idle' with autocommit, and a session-scoped lock
        # is unaffected either way. It also matters in production: a managed Postgres with
        # idle_in_transaction_session_timeout would kill this connection mid-job, releasing the
        # lock while the job still runs — the cross-replica guarantee failing silently.
        connection = await connection.execution_options(isolation_level="AUTOCOMMIT")

        acquired = bool(await connection.scalar(text("SELECT pg_try_advisory_lock(:key)"), {"key": key}))
        if not acquired:
            logger.info("advisory lock %s is already held; skipping this run", key)
        try:
            yield acquired
        finally:
            if acquired:
                try:
                    # text() + a named bind, exactly like the acquire above. NOT
                    # exec_driver_sql("... %s"): that bypasses the compiler and hands the string to
                    # asyncpg, whose paramstyle is numeric_dollar — Postgres answers
                    # `syntax error at or near "%"`. Because this sits in a `finally`, that error
                    # would replace whatever the job body was raising AND leave the lock held, so
                    # every later cycle would return ran=False and the sync would silently stop
                    # forever while logging a failure line nobody watches.
                    await connection.execute(text("SELECT pg_advisory_unlock(:key)"), {"key": key})
                except BaseException:
                    # engine.connect() returns the connection to the POOL; it does not close it,
                    # and reset-on-return's ROLLBACK does not release advisory locks (measured). A
                    # connection we could not unlock must not go back into circulation still
                    # holding it, or it is stranded for the process lifetime — worse than the
                    # table-flag approach this design rejects, because at least a flag is
                    # clearable. invalidate() discards the DBAPI connection, which IS the "dying
                    # connection" the docstring relies on.
                    #
                    # BaseException, not Exception: a CancelledError arriving during the unlock
                    # must still take this path, or shutdown strands the lock.
                    await connection.invalidate()
                    raise
