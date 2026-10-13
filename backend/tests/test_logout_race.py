"""Logout and refresh of one token at the same moment: no token in the session survives."""

import asyncio

from sqlalchemy import delete, select

from app.db import get_sessionmaker
from app.users import service
from app.users.models import RefreshToken, User
from tests.factories import make_user


async def test_logout_racing_a_refresh_leaves_no_live_token(monkeypatch) -> None:
    """Real sessions on separate connections, as in test_refresh_race: the race is between two
    transactions. The barrier holds both lookups until each has seen the token unrevoked, so
    neither request runs before the other has started.

    Either order must end with every token in the family revoked. If the refresh wins, logout
    waits on its row lock and then revokes the successor; if logout wins, the refresh sees a
    revoked token, treats it as reuse and revokes the family itself. The deterministic version
    of the first ordering is test_logout_with_a_rotated_token_ends_the_session.
    """
    maker = get_sessionmaker()
    async with maker() as seed:
        user = make_user(username="logout-race", email="logout-race@example.com")
        seed.add(user)
        await seed.flush()
        _, raw = await service.issue_token_pair(seed, user)
        await seed.commit()
        user_id = user.id

    real_find = service._find_refresh_token
    arrived = 0
    both_read = asyncio.Event()

    async def gated_find(session, raw_token):
        nonlocal arrived
        found = await real_find(session, raw_token)
        arrived += 1
        if arrived == 2:
            both_read.set()
        await both_read.wait()
        return found

    monkeypatch.setattr(service, "_find_refresh_token", gated_find)

    async def refresh():
        async with maker() as session:
            pair = await service.rotate_refresh_token(session, raw)
            await session.commit()
            return pair

    async def logout():
        async with maker() as session:
            await service.revoke_refresh_token(session, raw)
            await session.commit()

    try:
        await asyncio.wait_for(asyncio.gather(refresh(), logout()), timeout=10)

        async with maker() as check:
            tokens = (await check.scalars(select(RefreshToken).where(RefreshToken.user_id == user_id))).all()
        assert tokens
        assert all(token.revoked_at is not None for token in tokens)
    finally:
        async with maker() as cleanup:
            await cleanup.execute(delete(User).where(User.id == user_id))  # tokens cascade
            await cleanup.commit()
