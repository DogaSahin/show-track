"""Two refreshes with one token at the same moment: exactly one wins, the other is reuse."""

import asyncio

from sqlalchemy import delete, select

from app.db import get_sessionmaker
from app.users import service
from app.users.models import RefreshToken, User
from tests.factories import make_user


async def test_concurrent_refreshes_with_one_token_let_exactly_one_through(monkeypatch) -> None:
    """Real sessions on separate connections, not db_session: the race is between two
    transactions, and the savepoint fixture puts everything in one.

    The barrier makes the interleaving deterministic — both lookups see `revoked_at IS NULL`
    before either claims. Without it, one task can finish before the other starts and the test
    passes through the ordinary "already revoked" branch, proving nothing.

    Each task rotates AND commits in its own session: the loser's claim blocks on the winner's
    row lock until the winner commits, so gathering the rotates and committing afterwards hangs.
    """
    maker = get_sessionmaker()
    async with maker() as seed:
        user = make_user(username="refresh-race", email="refresh-race@example.com")
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

    async def attempt():
        async with maker() as session:
            pair = await service.rotate_refresh_token(session, raw)
            await session.commit()
            return pair

    try:
        results = await asyncio.wait_for(asyncio.gather(attempt(), attempt()), timeout=10)

        assert sum(result is not None for result in results) == 1
        async with maker() as check:
            tokens = (await check.scalars(select(RefreshToken).where(RefreshToken.user_id == user_id))).all()
        # The original and the winner's new token — and the loser's reuse cascade revoked both.
        assert len(tokens) == 2
        assert all(token.revoked_at is not None for token in tokens)
    finally:
        async with maker() as cleanup:
            await cleanup.execute(delete(User).where(User.id == user_id))  # tokens cascade
            await cleanup.commit()
