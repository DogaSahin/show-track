from collections.abc import AsyncGenerator, Mapping

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, create_async_engine

from app.config import get_settings
from app.db import get_session
from app.media.models import MediaSource
from app.media.providers import get_providers
from app.media.providers.base import MediaProvider
from app.users.models import User
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


@pytest.fixture
def use_providers():
    """Install a registry for one test, then remove it. Mirrors conftest.py's get_session
    override rather than monkeypatching a module global — which is the reason get_providers is
    a FastAPI dependency at all.

    Lives here rather than in one test module because Phase 4 gives it three consumers.
    """

    def install(providers: Mapping[MediaSource, MediaProvider]) -> None:
        app.dependency_overrides[get_providers] = lambda: providers

    yield install
    app.dependency_overrides.pop(get_providers, None)


@pytest.fixture
async def auth_user(auth_client: AsyncClient, db_session: AsyncSession) -> User:
    """The User row behind `auth_client`'s bearer token.

    Route tests need its id twice over: to seed rows the endpoint must return, and to seed a
    second user's rows it must not. Depends on auth_client so registration has already run.

    NOTE for 401 tests: requesting this fixture pulls in `auth_client`, which MUTATES the shared
    `client` object with an Authorization header. A test asserting 401 must not request it.
    """
    user = await db_session.scalar(select(User).where(User.email == "fixture@example.com"))
    assert user is not None, "auth_client did not register its fixture user"
    return user


@pytest.fixture
def commits(db_session: AsyncSession, monkeypatch: pytest.MonkeyPatch) -> list[int]:
    """Records every `session.commit()` the route under test makes.

    A route that forgets `await session.commit()` discards its write in production and NO
    existing test can tell: `client` hands the route the SAME session the test asserts through,
    so a flushed-but-uncommitted row is visible to every assertion in the file. Measured
    project-wide — stripping all 20 `await session.commit()` calls from every routes.py leaves
    663/672 green, and the 9 failures are incidental collateral from `get_or_create_media`'s
    rollback, not commit assertions.

    A spy rather than the structural fix (a request-scoped savepoint in the `client` fixture),
    which was prototyped and breaks 10 tests: `get_or_create_media` calls `session.rollback()`,
    which unwinds that savepoint. Reconciling the two is separate work.

    Patched on the INSTANCE, not the class, so it dies with the fixture and cannot leak into a
    test that did not ask for it.
    """
    calls: list[int] = []
    real = db_session.commit

    async def _spy() -> None:
        calls.append(1)
        await real()

    monkeypatch.setattr(db_session, "commit", _spy)
    return calls
