from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from app import db


async def test_session_executes_a_statement(db_session: AsyncSession) -> None:
    """First half of a deliberate pair — see the next test for why there are two."""
    result = await db_session.execute(text("SELECT 1"))

    assert result.scalar_one() == 1


async def test_a_second_session_executes_in_the_same_run(db_session: AsyncSession) -> None:
    """The regression test for the Phase 0 blocker.

    An AsyncEngine binds its connection pool to the event loop that first uses it. With a
    module-level engine — or a session-scoped engine under function-scoped loops — the
    FIRST test passes and this one fails with
    `RuntimeError: Task got Future attached to a different loop`. One test cannot catch it.
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
