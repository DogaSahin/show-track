"""Logout and refresh of one token at the same moment: no token in the session survives."""

import asyncio

from sqlalchemy import delete, select, text

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


async def test_logout_with_a_stale_token_during_a_refresh_leaves_no_live_token(monkeypatch) -> None:
    """Logout presents A while a refresh of its successor B is mid-flight: B is claimed but its
    successor C is not committed yet. The logout must wait for that refresh and still revoke C,
    even though C did not exist when the logout started.

    The refresh is held inside `issue_token_pair`, after its claim has locked B. The logout is
    released only once Postgres reports it waiting on a lock, so the ordering is fixed, not timed.
    """
    maker = get_sessionmaker()
    async with maker() as seed:
        user = make_user(username="logout-stale", email="logout-stale@example.com")
        seed.add(user)
        await seed.flush()
        _, token_a = await service.issue_token_pair(seed, user)
        await seed.commit()
        user_id = user.id
    async with maker() as first_refresh:
        _, token_b = await service.rotate_refresh_token(first_refresh, token_a)
        await first_refresh.commit()

    real_issue = service.issue_token_pair
    claimed = asyncio.Event()
    release = asyncio.Event()

    async def gated_issue(session, user, *, family_id=None):
        claimed.set()
        await release.wait()
        return await real_issue(session, user, family_id=family_id)

    monkeypatch.setattr(service, "issue_token_pair", gated_issue)

    async def refresh():
        async with maker() as session:
            pair = await service.rotate_refresh_token(session, token_b)
            await session.commit()
            return pair

    async def logout():
        async with maker() as session:
            await service.revoke_refresh_token(session, token_a)
            await session.commit()

    async def logout_is_waiting_on_a_lock() -> None:
        # pg_stat_activity is read once per transaction, so each poll ends its own.
        async with maker() as probe:
            while True:
                waiting = await probe.scalar(
                    text(
                        "SELECT count(*) FROM pg_stat_activity "
                        "WHERE datname = current_database() AND wait_event_type = 'Lock'"
                    )
                )
                await probe.rollback()
                if waiting:
                    return
                await asyncio.sleep(0.02)

    refreshing = logging_out = None
    try:
        refreshing = asyncio.create_task(refresh())
        await asyncio.wait_for(claimed.wait(), timeout=10)
        logging_out = asyncio.create_task(logout())
        await asyncio.wait_for(logout_is_waiting_on_a_lock(), timeout=10)
        release.set()
        token_c = (await asyncio.wait_for(refreshing, timeout=10))[1]
        await asyncio.wait_for(logging_out, timeout=10)

        assert token_c is not None
        async with maker() as check:
            tokens = (await check.scalars(select(RefreshToken).where(RefreshToken.user_id == user_id))).all()
        assert len(tokens) == 3
        assert all(token.revoked_at is not None for token in tokens)
    finally:
        release.set()
        for task in (refreshing, logging_out):
            if task is not None and not task.done():
                task.cancel()
        await asyncio.gather(*(t for t in (refreshing, logging_out) if t is not None), return_exceptions=True)
        async with maker() as cleanup:
            await cleanup.execute(delete(User).where(User.id == user_id))  # tokens cascade
            await cleanup.commit()
