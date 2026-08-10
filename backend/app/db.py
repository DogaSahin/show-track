from collections.abc import AsyncGenerator

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import get_settings


class Base(DeclarativeBase):
    """Declarative base. Every model in every domain module inherits this."""


_engine: AsyncEngine | None = None
_sessionmaker: async_sessionmaker[AsyncSession] | None = None


def get_engine() -> AsyncEngine:
    """Build the engine on first use, inside the running event loop.

    Not at import time: an AsyncEngine binds its pool to the loop that first uses it, so a
    module-level engine is unusable from any second loop. Not on `app.state` either — the
    Phase 5 scheduler job and any CLI script have no request to reach the app through, and
    would each end up with their own pool.

    The lazy check is safe without a lock only because there is no `await` between the test
    and the assignment, so no other coroutine can interleave. That stops being true the
    moment anything here needs to await.
    """
    global _engine
    if _engine is None:
        _engine = create_async_engine(get_settings().database_url, echo=False)
    return _engine


def get_sessionmaker() -> async_sessionmaker[AsyncSession]:
    # expire_on_commit=False: the default (True) expires ORM objects on commit, so a later
    # attribute access triggers a lazy reload — which raises MissingGreenlet in async code
    # instead of quietly re-querying. Trade-off: a route can return a post-commit object, but
    # that object's attributes may be stale if the row changed elsewhere since the commit.
    global _sessionmaker
    if _sessionmaker is None:
        _sessionmaker = async_sessionmaker(get_engine(), class_=AsyncSession, expire_on_commit=False)
    return _sessionmaker


async def dispose_engine() -> None:
    """Release the pool and clear both memos, so a later `get_engine()` rebuilds."""
    global _engine, _sessionmaker
    if _engine is not None:
        await _engine.dispose()
    _engine = None
    _sessionmaker = None


async def get_session() -> AsyncGenerator[AsyncSession, None]:
    async with get_sessionmaker()() as session:
        yield session
