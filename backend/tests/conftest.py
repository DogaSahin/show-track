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

    The pattern is SQLAlchemy's "joining a session into an external transaction". What makes
    it safe is the binding, not the mode: the session is bound to a `Connection` that is
    already inside a transaction, so `SessionTransaction._connection_for_bind` takes its
    `elif conn.in_transaction()` branch and joins that transaction instead of beginning one
    (sqlalchemy/orm/session.py:1207-1242). Bind to the engine instead and that branch is
    skipped for the `conn.begin()` below it — a real transaction, which a commit really
    commits, and the write escapes.

    `join_transaction_mode` only decides how the commit is absorbed. `create_savepoint` opens
    a SAVEPOINT, so `session.commit()` becomes RELEASE SAVEPOINT and code under test commits
    the way it does in production; the default `conditional_savepoint` would resolve to
    `rollback_only` here (the outer transaction is not itself nested) and set
    `should_commit = False`. Neither lets the commit through — see test_db.py.
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
        # pop, not clear(): this fixture only owns the one key it set, and Phase 2 adds an
        # auth-dependency override that an outer fixture may already have installed.
        app.dependency_overrides.pop(get_session, None)


@pytest.fixture
async def auth_client(client: AsyncClient) -> AsyncGenerator[AsyncClient, None]:
    """A client carrying a bearer token for a freshly registered user.

    Registers and logs in through the real endpoints rather than minting a token directly, so
    the fixture exercises the same path a device does and cannot drift from it.
    """
    from app.config import get_settings

    body = {
        "username": "fixture-user",
        "email": "fixture@example.com",
        "password": "fixture-password",
        "invite_code": get_settings().registration_code,
    }
    await client.post("/v1/auth/register", json=body)
    tokens = (await client.post("/v1/auth/login", json={"email": body["email"], "password": body["password"]})).json()

    client.headers["Authorization"] = f"Bearer {tokens['access_token']}"
    yield client
    client.headers.pop("Authorization", None)
