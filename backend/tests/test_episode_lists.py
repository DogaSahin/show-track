"""Episode lists (BT-01): storing them, the endpoint, the totals on every title, the fetch on add,
and the sync job's episode phase.
"""

import uuid
from datetime import UTC, date, datetime, timedelta
from typing import ClassVar

import pytest
from sqlalchemy import delete, func, select, update

from app.db import get_sessionmaker
from app.media import service as media_service
from app.media.models import Episode, Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaProvider, MediaRef, NextEpisode, ProviderEpisode, ProviderMedia
from app.media.providers.errors import ProviderRateLimited, ProviderUnavailable
from app.sync import service as sync_service
from app.users.models import User
from tests.factories import make_media, make_user, make_user_media

NOW = datetime(2026, 10, 4, 12, 0, tzinfo=UTC)
TODAY = NOW.date()


def _episode(season: int, number: int, *, title: str | None = None, air_date: date | None = None) -> ProviderEpisode:
    return ProviderEpisode(season_number=season, number=number, title=title, air_date=air_date)


class EpisodeProvider(MediaProvider):
    """Answers get_by_id with a fixed title and get_episodes with a fixed list, or raises."""

    source: ClassVar[MediaSource] = MediaSource.TMDB
    media_type: ClassVar[MediaType] = MediaType.TV

    def __init__(self, episodes=(), *, error: Exception | None = None) -> None:
        self._episodes = episodes
        self._error = error
        self.episode_calls: list[str] = []

    async def search(self, query: str, page: int):
        raise AssertionError("not used")

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        return ProviderMedia(
            ref=MediaRef(source=MediaSource.TMDB, external_id=external_id),
            type=MediaType.TV,
            title="Severance",
            year=2022,
            genres=(),
            cover_image_url=None,
            status=MediaStatus.AIRING,
            next_episode=NextEpisode(season_number=2, number=7, airs_at=NOW + timedelta(days=1)),
        )

    async def get_episodes(self, external_id: str):
        self.episode_calls.append(external_id)
        if self._error is not None:
            raise self._error
        return self._episodes

    async def fetch_similar(self, external_id: str):
        raise AssertionError("not used")


async def _stored_media(db_session, **overrides) -> Media:
    media = make_media(
        **{"source": MediaSource.TMDB, "type": MediaType.TV, "external_id": uuid.uuid4().hex[:8], **overrides}
    )
    db_session.add(media)
    await db_session.flush()
    return media


async def _tracked(db_session, **overrides) -> Media:
    media = await _stored_media(db_session, **overrides)
    tag = uuid.uuid4().hex[:8]
    user = make_user(username=f"u{tag}", email=f"{tag}@example.com")
    db_session.add(user)
    await db_session.flush()
    db_session.add(make_user_media(user.id, media.id))
    await db_session.flush()
    return media


async def _episodes_of(db_session, media_id) -> list[tuple[int, int, str | None]]:
    rows = await db_session.execute(
        select(Episode.season_number, Episode.number, Episode.title)
        .where(Episode.media_id == media_id)
        .order_by(Episode.season_number, Episode.number)
    )
    return [tuple(row) for row in rows]


# ---- storing


async def test_storing_upserts_updates_and_drops_what_the_provider_no_longer_lists(db_session):
    media = await _stored_media(db_session)
    await media_service.store_episodes(db_session, media.id, [_episode(1, 1, title="Old"), _episode(1, 2)], NOW)

    await media_service.store_episodes(
        db_session, media.id, [_episode(1, 1, title="New"), _episode(2, 1, air_date=TODAY)], NOW
    )

    assert await _episodes_of(db_session, media.id) == [(1, 1, "New"), (2, 1, None)]
    refreshed = await db_session.get(Media, media.id, populate_existing=True)
    assert refreshed.episodes_synced_at == NOW
    assert refreshed.total_episodes == 2


async def test_an_empty_answer_clears_the_list_and_still_counts_as_fetched(db_session):
    media = await _stored_media(db_session)
    await media_service.store_episodes(db_session, media.id, [_episode(1, 1)], NOW)

    await media_service.store_episodes(db_session, media.id, [], NOW)

    assert await _episodes_of(db_session, media.id) == []
    # "x of 0" means nothing: an empty list has an unknown total.
    assert (await db_session.get(Media, media.id, populate_existing=True)).total_episodes is None


async def test_a_long_show_is_stored_in_full(db_session):
    """Past one insert chunk (One Piece has 1,100+ episodes)."""
    media = await _stored_media(db_session)

    await media_service.store_episodes(db_session, media.id, [_episode(1, n) for n in range(1, 1201)], NOW)

    count = await db_session.scalar(select(func.count()).select_from(Episode).where(Episode.media_id == media.id))
    assert count == 1200


# ---- the endpoint


async def test_a_list_not_fetched_yet_says_so(auth_client, db_session):
    media = await _stored_media(db_session)

    body = (await auth_client.get(f"/v1/media/{media.id}/episodes")).json()

    assert body == {"synced_at": None, "total_episodes": None, "seasons": []}


async def test_the_list_comes_by_season_with_aired_worked_out_against_today(auth_client, db_session):
    media = await _stored_media(db_session, status=MediaStatus.AIRING)
    today = datetime.now(tz=UTC).date()
    await media_service.store_episodes(
        db_session,
        media.id,
        [
            _episode(2, 1, air_date=today + timedelta(days=1)),
            _episode(1, 2, air_date=today),
            _episode(1, 1, title="Pilot", air_date=today - timedelta(days=7)),
            _episode(2, 2),
        ],
        NOW,
    )

    body = (await auth_client.get(f"/v1/media/{media.id}/episodes")).json()

    assert body["total_episodes"] == 4
    assert [(s["number"], s["episode_count"]) for s in body["seasons"]] == [(1, 2), (2, 2)]
    season_one, season_two = body["seasons"]
    assert [(e["number"], e["title"], e["aired"]) for e in season_one["episodes"]] == [
        (1, "Pilot", True),
        (2, None, True),
    ]
    # Tomorrow has not aired; an undated episode of an airing show has not either.
    assert [e["aired"] for e in season_two["episodes"]] == [False, False]


async def test_an_undated_episode_of_a_finished_show_counts_as_aired(auth_client, db_session):
    media = await _stored_media(db_session, status=MediaStatus.FINISHED)
    await media_service.store_episodes(db_session, media.id, [_episode(1, 1)], NOW)

    body = (await auth_client.get(f"/v1/media/{media.id}/episodes")).json()

    assert body["seasons"][0]["episodes"][0]["aired"] is True


async def test_an_unknown_title_is_404(auth_client):
    assert (await auth_client.get(f"/v1/media/{uuid.uuid4()}/episodes")).status_code == 404


async def test_the_total_rides_along_on_every_title(auth_client, db_session, auth_user):
    media = await _stored_media(db_session)
    db_session.add(make_user_media(auth_user.id, media.id))
    await media_service.store_episodes(db_session, media.id, [_episode(1, 1), _episode(1, 2)], NOW)

    detail = (await auth_client.get(f"/v1/media/{media.id}")).json()
    library = (await auth_client.get("/v1/library")).json()

    assert detail["total_episodes"] == 2
    assert library["items"][0]["media"]["total_episodes"] == 2


# ---- the fetch on add


async def test_adding_a_title_fetches_its_episodes_straight_away(auth_client, db_session, use_providers):
    provider = EpisodeProvider([_episode(1, 1, title="Good News About Hell"), _episode(1, 2)])
    use_providers({MediaSource.TMDB: provider})

    response = await auth_client.post("/v1/library", json={"source": "tmdb", "external_id": "95396"})

    media_id = uuid.UUID(response.json()["media"]["id"])
    assert provider.episode_calls == ["95396"]
    assert await _episodes_of(db_session, media_id) == [(1, 1, "Good News About Hell"), (1, 2, None)]


async def test_a_failed_fetch_on_add_still_adds_the_title(auth_client, db_session, use_providers):
    use_providers({MediaSource.TMDB: EpisodeProvider(error=ProviderUnavailable("down"))})

    response = await auth_client.post("/v1/library", json={"source": "tmdb", "external_id": "95396"})

    assert response.status_code == 201
    media = await db_session.get(Media, uuid.UUID(response.json()["media"]["id"]), populate_existing=True)
    assert media.episodes_synced_at is None


async def test_a_title_whose_list_is_already_fetched_is_not_fetched_again_on_add(
    auth_client, db_session, use_providers
):
    media = await _stored_media(db_session, external_id="95396")
    await media_service.store_episodes(db_session, media.id, [_episode(1, 1)], NOW)
    provider = EpisodeProvider()
    use_providers({MediaSource.TMDB: provider})

    await auth_client.post("/v1/library", json={"source": "tmdb", "external_id": "95396"})

    assert provider.episode_calls == []


# ---- the sync job's episode phase


async def test_which_lists_are_due(db_session):
    never = await _tracked(db_session, status=MediaStatus.FINISHED)
    airing_stale = await _tracked(db_session, status=MediaStatus.AIRING, episodes_synced_at=NOW - timedelta(days=2))
    await _tracked(db_session, status=MediaStatus.AIRING, episodes_synced_at=NOW - timedelta(hours=1))
    await _tracked(db_session, status=MediaStatus.FINISHED, episodes_synced_at=NOW - timedelta(days=90))
    await _stored_media(db_session)  # nobody tracks it

    due = await sync_service.collect_episode_worklist(db_session, now=NOW)

    assert [media_id for media_id, _, _ in due] == [never.id, airing_stale.id]


async def test_a_status_change_makes_the_list_due_once_more(db_session):
    media = await _tracked(db_session, status=MediaStatus.AIRING, episodes_synced_at=NOW - timedelta(hours=1))
    detail = ProviderMedia(
        ref=MediaRef(source=media.source, external_id=media.external_id),
        type=media.type,
        title=media.title,
        year=None,
        genres=(),
        cover_image_url=None,
        status=MediaStatus.FINISHED,
        next_episode=None,
    )

    await sync_service.apply_refresh(
        db_session,
        [(media.id, media.source, media.external_id)],
        {(media.source, media.external_id): detail},
        (),
        now=NOW,
    )

    assert [m for m, _, _ in await sync_service.collect_episode_worklist(db_session, now=NOW)] == [media.id]
    # ...without hiding the list that is already stored.
    assert (await db_session.get(Media, media.id, populate_existing=True)).episodes_synced_at is not None


async def test_one_failing_title_does_not_stop_the_others_and_a_rate_limit_stops_its_source():
    ok = EpisodeProvider([_episode(1, 1)])
    worklist = [(uuid.uuid4(), MediaSource.TMDB, "a"), (uuid.uuid4(), MediaSource.ANILIST, "b")]

    lists, failed = await sync_service.fetch_episode_lists(
        {MediaSource.TMDB: ok, MediaSource.ANILIST: EpisodeProvider(error=ValueError("bad payload"))}, worklist
    )
    assert (list(lists), failed) == ([worklist[0][0]], 1)

    limited = EpisodeProvider(error=ProviderRateLimited("slow down", retry_after=60))
    twice = [(uuid.uuid4(), MediaSource.TMDB, "a"), (uuid.uuid4(), MediaSource.TMDB, "b")]
    lists, failed = await sync_service.fetch_episode_lists({MediaSource.TMDB: limited}, twice)
    assert (lists, failed, limited.episode_calls) == ({}, 2, ["a"])


async def test_a_title_the_provider_no_longer_knows_keeps_its_list(db_session):
    """A 404 may be passing, and watched records hang off these rows: only the stamp moves."""
    media = await _tracked(db_session, status=MediaStatus.FINISHED)
    await media_service.store_episodes(db_session, media.id, [_episode(1, 1), _episode(1, 2)], NOW - timedelta(days=9))

    refreshed, failed = await sync_service.store_episode_lists(db_session, {media.id: None}, now=NOW)

    assert (refreshed, failed) == (1, 0)
    assert len(await _episodes_of(db_session, media.id)) == 2
    stored = await db_session.get(Media, media.id, populate_existing=True)
    assert (stored.episodes_synced_at, stored.total_episodes) == (NOW, 2)


async def test_one_title_failing_to_store_does_not_cost_the_others(db_session):
    good = await _tracked(db_session)

    refreshed, failed = await sync_service.store_episode_lists(
        db_session, {uuid.uuid4(): (_episode(1, 1),), good.id: (_episode(1, 1),)}, now=NOW
    )

    assert (refreshed, failed) == (1, 1)
    assert len(await _episodes_of(db_session, good.id)) == 1


async def test_the_next_episode_dated_today_has_not_aired_before_its_time(auth_client, db_session):
    """A TMDB date is the local air date; an evening US episode must not read as aired at 00:00 UTC."""
    now = datetime.now(tz=UTC)
    media = await _stored_media(
        db_session,
        status=MediaStatus.AIRING,
        next_episode_season=1,
        next_episode_number=2,
        next_episode_date=now + timedelta(hours=3),
    )
    await media_service.store_episodes(
        db_session, media.id, [_episode(1, 1, air_date=now.date()), _episode(1, 2, air_date=now.date())], NOW
    )

    body = (await auth_client.get(f"/v1/media/{media.id}/episodes")).json()

    assert [e["aired"] for e in body["seasons"][0]["episodes"]] == [True, False]


@pytest.fixture
async def committed_show():
    """A tracked title that really exists, because run_sync opens its own session on another
    connection (see test_debug_sync_route.py). Cleaned up in the fixture so a failure cannot leak.
    """
    tag = uuid.uuid4().hex[:8]
    async with get_sessionmaker()() as seed:
        user = make_user(username=f"e{tag}", email=f"e{tag}@example.com")
        media = make_media(
            source=MediaSource.TMDB, type=MediaType.TV, external_id=f"e{tag}", status=MediaStatus.FINISHED
        )
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


async def test_a_sync_run_stores_lists_and_a_failed_run_keeps_them(committed_show):
    """The scheduled entry point end to end: the next-episode refresh, then the episode phase."""
    stored = await sync_service.run_sync({MediaSource.TMDB: SyncProvider([_episode(1, 1), _episode(1, 2)])}, now=NOW)

    async with get_sessionmaker()() as check:
        media = await check.get(Media, committed_show)
        assert (stored.episodes_refreshed, media.total_episodes, media.episodes_synced_at) == (1, 2, NOW)
        # Due again later only if something marks it so: finished shows are not refetched.
        await check.execute(update(Media).where(Media.id == committed_show).values(episodes_refresh_due=True))
        await check.commit()

    failed = await sync_service.run_sync(
        {MediaSource.TMDB: SyncProvider(error=ProviderUnavailable("down"))}, now=NOW + timedelta(days=1)
    )

    async with get_sessionmaker()() as check:
        media = await check.get(Media, committed_show)
        rows = await check.scalar(select(func.count()).select_from(Episode).where(Episode.media_id == committed_show))
        assert (failed.episodes_failed, rows, media.episodes_synced_at) == (1, 2, NOW)


class SyncProvider(EpisodeProvider):
    """The sync job asks get_many for the title first; this answers it unchanged."""

    async def get_many(self, external_ids):
        return {}
