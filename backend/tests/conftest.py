from collections.abc import AsyncGenerator

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, create_async_engine

from app.config import get_settings
from app.db import get_session
from main import app


@pytest.fixture(scope="session")
async def engine() -> AsyncGenerator[AsyncEngine, None]:
    """One engine for the whole run, on the single session-scoped event loop.

    Deliberately not the application's `get_engine()`: the tests own their engine's
    lifetime, and keeping them separate means a test can dispose the app engine
    (see test_db.py) without pulling the rug out from under every other test.
    """
    test_engine = create_async_engine(get_settings().database_url)
    yield test_engine
    await test_engine.dispose()


@pytest.fixture
async def db_session(engine: AsyncEngine) -> AsyncGenerator[AsyncSession, None]:
    """A session whose writes never survive the test.

    The pattern is SQLAlchemy's "joining a session into an external transaction".
    `join_transaction_mode="create_savepoint"` makes the session open a SAVEPOINT rather
    than a real transaction, so `session.commit()` becomes RELEASE SAVEPOINT: code under
    test commits exactly as it does in production, and the outer rollback still discards
    everything.
    """
    async with engine.connect() as conn:
        trans = await conn.begin()
        session = AsyncSession(bind=conn, join_transaction_mode="create_savepoint", expire_on_commit=False)
        try:
            yield session
        finally:
            await session.close()
            await trans.rollback()


@pytest.fixture
async def client(db_session: AsyncSession) -> AsyncGenerator[AsyncClient, None]:
    """An HTTP client whose routes share the test's transaction."""
    app.dependency_overrides[get_session] = lambda: db_session
    try:
        transport = ASGITransport(app=app)
        async with AsyncClient(transport=transport, base_url="http://test") as ac:
            yield ac
    finally:
        app.dependency_overrides.clear()
