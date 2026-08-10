from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app import db


async def test_session_executes_a_statement(db_session: AsyncSession) -> None:
    """First half of a deliberate pair — see the next test for why there are two."""
    result = await db_session.execute(text("SELECT 1"))

    assert result.scalar_one() == 1


async def test_a_second_session_executes_in_the_same_run(db_session: AsyncSession) -> None:
    """Guards the pytest loop-scope configuration, not `app.db` directly.

    `db_session` runs on the session-scoped `engine` fixture in conftest.py — its own
    `create_async_engine` call, independent of `app.db`'s. An `AsyncEngine` binds its
    connection pool to the event loop that first uses it, so if `asyncio_default_test_loop_scope`
    were left at its default ("function") instead of "session", that session-scoped engine
    would end up bound to the FIRST test's loop; once that loop closed, this SECOND test would
    fail with `RuntimeError: Task got Future attached to a different loop`. A single test can't
    catch that — the failure only appears on the pool's second use, from a different test's loop.
    """
    result = await db_session.execute(text("SELECT 1"))

    assert result.scalar_one() == 1


async def test_get_engine_is_memoised_and_dispose_clears_it() -> None:
    """The engine must be reusable across calls but rebuildable after disposal — otherwise
    `lifespan` shutdown would leave later callers holding a disposed engine.
    """
    first = db.get_engine()
    assert db.get_engine() is first

    await db.dispose_engine()

    second = db.get_engine()
    assert second is not first

    await db.dispose_engine()


async def test_the_app_session_dependency_survives_two_round_trips() -> None:
    """Exercises `app.db.get_session()` itself, which `db_session` bypasses via dependency
    override (the `client` fixture always overrides `get_session`, so nothing else in the
    suite ever calls it for real).

    This is the test that actually guards the lazy engine in app/db.py: if the engine were
    built at import time instead of lazily on first use, the second round-trip here would fail
    on a pool bound to another loop, the same way the original Phase 0 bug did.
    """
    try:
        async for session in db.get_session():
            assert (await session.execute(text("SELECT 1"))).scalar_one() == 1
        async for session in db.get_session():
            assert (await session.execute(text("SELECT 1"))).scalar_one() == 1
    finally:
        # Leaves app.db's module globals back at their import-time state (both None) no
        # matter which assertion above failed, so this test can't leak a live/disposed
        # engine into whichever test runs next.
        await db.dispose_engine()


async def test_committed_work_inside_db_session_is_visible_in_the_same_test(db_session: AsyncSession) -> None:
    """Proves `join_transaction_mode="create_savepoint"` really lets code under test commit.

    Must run before `test_committed_work_does_not_survive_past_the_test` below (default
    file-declaration order; no random-order plugin is installed). A commit inside `db_session`
    becomes RELEASE SAVEPOINT rather than a real COMMIT, so the write must still be visible for
    the rest of *this* test — that part is what the next test's rollback check depends on.
    """
    await db_session.execute(text("CREATE TABLE db_session_commit_proof (id integer)"))
    await db_session.execute(text("INSERT INTO db_session_commit_proof (id) VALUES (1)"))
    await db_session.commit()

    result = await db_session.execute(text("SELECT id FROM db_session_commit_proof"))
    assert result.scalar_one() == 1


async def test_committed_work_does_not_survive_past_the_test(db_session: AsyncSession) -> None:
    """The other half of the commit/discard pair above.

    Runs on a fresh connection from a fresh `db_session`, after the previous test's fixture
    teardown rolled back the outer transaction. Guards the `db_session` fixture's external-
    transaction wiring as a whole — binding the session to the fixture's own `conn` (not the
    engine) so its commit joins that connection's transaction, and rolling that transaction
    back on teardown. Postgres DDL is transactional, so if that wiring ever let a commit
    escape the outer transaction, the table itself would still exist and `to_regclass` would
    return its name instead of NULL. Verified locally by binding the session directly to
    `engine` instead of `conn` — that reproduces the leak and fails this assertion; merely
    omitting `join_transaction_mode="create_savepoint"` (SQLAlchemy's default,
    `conditional_savepoint`, still declines to commit against an unsavepointed transaction)
    does not, so this test does not cover that narrower misconfiguration.
    """
    result = await db_session.execute(text("SELECT to_regclass('db_session_commit_proof')"))
    assert result.scalar_one() is None
