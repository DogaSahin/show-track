from datetime import UTC, datetime
from typing import ClassVar

import pytest
from sqlalchemy import delete

from app.db import get_sessionmaker
from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaProvider, MediaRef, NextEpisode, ProviderMedia
from app.users.models import User
from tests.factories import make_media, make_user, make_user_media

AIR_DATE = datetime(2026, 12, 1, 15, 0, tzinfo=UTC)


class OneTitleProvider(MediaProvider):
    source: ClassVar[MediaSource] = MediaSource.ANILIST
    media_type: ClassVar[MediaType] = MediaType.ANIME

    async def search(self, query: str, page: int):
        raise AssertionError("not used in these tests")

    async def get_by_id(self, external_id: str):
        raise AssertionError("the sync job must use get_many")

    async def get_many(self, external_ids):
        return {
            external_id: ProviderMedia(
                ref=MediaRef(source=MediaSource.ANILIST, external_id=external_id),
                type=MediaType.ANIME,
                title=f"Show {external_id}",
                year=2024,
                genres=(),
                cover_image_url=None,
                status=MediaStatus.AIRING,
                next_episode=NextEpisode(season_number=1, number=9, airs_at=AIR_DATE),
            )
            for external_id in external_ids
        }


@pytest.fixture
async def committed_title():
    """A user + media + library entry that REALLY exists in the database, cleaned up afterwards.

    Not the db_session fixture. That one is bound to a Connection inside an external transaction
    with join_transaction_mode="create_savepoint", so its commit() is a RELEASE SAVEPOINT and
    nothing escapes — tests/test_db.py exists to prove exactly that. run_sync opens its own session
    on a DIFFERENT pooled connection and would see nothing, so the route would report checked=0.

    Cleanup lives in this fixture's finally, not in the test body: a body-level cleanup does not
    run when an assertion above it fails, and the unique constraints on media(source, external_id)
    and users.email would then make every subsequent run of this file fail rather than merely
    leaving rows behind.
    """
    async with get_sessionmaker()() as seed:
        user = make_user(username="debugsync", email="debugsync@example.com")
        media = make_media(external_id="4242", status=MediaStatus.AIRING, next_episode_number=1)
        seed.add_all([user, media])
        await seed.flush()
        seed.add(make_user_media(user.id, media.id))
        await seed.commit()
        user_id, media_id = user.id, media.id
    try:
        yield media_id
    finally:
        async with get_sessionmaker()() as cleanup:
            await cleanup.execute(delete(Media).where(Media.id == media_id))
            await cleanup.execute(delete(User).where(User.id == user_id))
            await cleanup.commit()


async def test_the_debug_route_runs_the_sync_and_returns_a_summary(auth_client, committed_title, use_providers):
    """It calls the SAME function the scheduler calls. A debug trigger that exercises a different
    code path is worse than no debug trigger.
    """
    use_providers({MediaSource.ANILIST: OneTitleProvider()})

    response = await auth_client.post("/v1/debug/sync")

    assert response.status_code == 200
    body = response.json()
    assert body["ran"] is True
    # >= not ==: this test reads a real database and sees whatever else is committed there.
    assert body["updated"] >= 1

    async with get_sessionmaker()() as check:
        refreshed = await check.get(Media, committed_title)
        assert refreshed.next_episode_date == AIR_DATE


async def test_a_contended_trigger_reports_ran_false(auth_client, use_providers):
    """The manual trigger takes the SAME advisory lock the scheduler does, so it cannot run
    concurrently with a scheduled sync — which is the entire point of the lock. If the scheduled
    job holds it, this answers ran: false rather than doing the work twice.
    """
    from app.sync.locks import SYNC_LOCK_KEY, advisory_lock

    use_providers({MediaSource.ANILIST: OneTitleProvider()})

    async with advisory_lock(SYNC_LOCK_KEY) as held:
        assert held is True
        response = await auth_client.post("/v1/debug/sync")

    assert response.status_code == 200
    assert response.json()["ran"] is False


# No hand-written 401 test: /v1/debug/sync carries no "{" in its path, so
# tests/test_auth_protection.py already parametrizes over it and asserts both the 401 and the
# mount-level dependency.
