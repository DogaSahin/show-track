"""Watched episodes (BT-02): marking, the progress they drive, the feed, and the legacy paths."""

import asyncio
import uuid
from datetime import UTC, date, datetime, timedelta

import pytest
from sqlalchemy import delete, func, select

from app.db import get_sessionmaker
from app.library import service as library_service
from app.library.models import Activity, ActivityKind, UserMedia, WatchedEpisode
from app.media import service as media_service
from app.media.models import Episode, Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import ProviderEpisode
from app.users.models import User
from tests.factories import make_media, make_user, make_user_media

NOW = datetime.now(tz=UTC)
PAST = (NOW - timedelta(days=30)).date()
FUTURE = (NOW + timedelta(days=30)).date()


async def _show(db_session, *, aired: int = 3, upcoming: int = 1, synced: bool = True) -> tuple[Media, list[Episode]]:
    media = make_media(
        source=MediaSource.TMDB, type=MediaType.TV, external_id=uuid.uuid4().hex[:8], status=MediaStatus.AIRING
    )
    db_session.add(media)
    await db_session.flush()
    if synced:
        dates: list[date | None] = [PAST] * aired + [FUTURE] * upcoming
        await media_service.store_episodes(
            db_session,
            media.id,
            [ProviderEpisode(season_number=1, number=n, title=None, air_date=d) for n, d in enumerate(dates, 1)],
            NOW,
        )
    episodes = list(
        await db_session.scalars(select(Episode).where(Episode.media_id == media.id).order_by(Episode.number))
    )
    return media, episodes


async def _entry(db_session, user_id, media, **overrides) -> UserMedia:
    entry = make_user_media(user_id, media.id, **overrides)
    db_session.add(entry)
    await db_session.flush()
    return entry


def _ids(*episodes: Episode) -> list[str]:
    return [str(episode.id) for episode in episodes]


async def _progress_events(db_session, user_id) -> list[dict]:
    rows = await db_session.scalars(
        select(Activity.payload).where(Activity.user_id == user_id, Activity.kind == ActivityKind.PROGRESSED)
    )
    return list(rows)


async def _watched(db_session, entry_id) -> set[uuid.UUID]:
    return set(
        await db_session.scalars(select(WatchedEpisode.episode_id).where(WatchedEpisode.user_media_id == entry_id))
    )


async def test_marking_a_batch_sets_progress_and_posts_one_feed_item(auth_client, auth_user, db_session):
    media, episodes = await _show(db_session)
    entry = await _entry(db_session, auth_user.id, media)

    response = await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched", json={"episode_ids": _ids(*episodes[:3]), "watched": True}
    )

    assert response.status_code == 200
    assert response.json()["progress"] == 3
    assert await _progress_events(db_session, auth_user.id) == [{"progress": 3}]
    watched = (await auth_client.get(f"/v1/library/{entry.id}/episodes/watched")).json()["episode_ids"]
    assert set(watched) == set(_ids(*episodes[:3]))


async def test_gaps_are_kept_and_unmarking_recounts(auth_client, auth_user, db_session):
    media, episodes = await _show(db_session)
    entry = await _entry(db_session, auth_user.id, media)
    url = f"/v1/library/{entry.id}/episodes/watched"

    await auth_client.put(url, json={"episode_ids": _ids(episodes[0], episodes[2]), "watched": True})
    response = await auth_client.put(url, json={"episode_ids": _ids(episodes[0]), "watched": False})

    assert response.json()["progress"] == 1
    assert await _watched(db_session, entry.id) == {episodes[2].id}


async def test_marking_what_is_already_marked_changes_nothing_and_posts_nothing(auth_client, auth_user, db_session):
    media, episodes = await _show(db_session)
    entry = await _entry(db_session, auth_user.id, media)
    url = f"/v1/library/{entry.id}/episodes/watched"
    await auth_client.put(url, json={"episode_ids": _ids(episodes[0]), "watched": True})

    again = await auth_client.put(url, json={"episode_ids": _ids(episodes[0]), "watched": True})
    unmark_unwatched = await auth_client.put(url, json={"episode_ids": _ids(episodes[1]), "watched": False})

    assert again.json()["progress"] == unmark_unwatched.json()["progress"] == 1
    assert len(await _progress_events(db_session, auth_user.id)) == 1


async def test_an_episode_of_another_title_rejects_the_whole_request(auth_client, auth_user, db_session):
    media, episodes = await _show(db_session)
    _, others = await _show(db_session)
    entry = await _entry(db_session, auth_user.id, media)

    response = await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched", json={"episode_ids": _ids(episodes[0], others[0]), "watched": True}
    )

    assert response.status_code == 422
    assert await _watched(db_session, entry.id) == set()


async def test_an_episode_not_aired_yet_cannot_be_marked(auth_client, auth_user, db_session):
    media, episodes = await _show(db_session, aired=1, upcoming=1)
    entry = await _entry(db_session, auth_user.id, media)

    response = await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched", json={"episode_ids": _ids(*episodes), "watched": True}
    )

    assert response.status_code == 422
    assert await _watched(db_session, entry.id) == set()


async def test_a_title_whose_episodes_are_not_loaded_yet_is_a_conflict(auth_client, auth_user, db_session):
    media, _ = await _show(db_session, synced=False)
    entry = await _entry(db_session, auth_user.id, media)

    response = await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched", json={"episode_ids": [str(uuid.uuid4())], "watched": True}
    )

    assert response.status_code == 409


async def test_someone_elses_entry_is_404_both_ways(auth_client, db_session):
    other = make_user(username="watch-other", email="watch-other@example.com")
    db_session.add(other)
    await db_session.flush()
    media, episodes = await _show(db_session)
    theirs = await _entry(db_session, other.id, media)
    url = f"/v1/library/{theirs.id}/episodes/watched"

    assert (await auth_client.get(url)).status_code == 404
    put = await auth_client.put(url, json={"episode_ids": _ids(episodes[0]), "watched": True})
    assert put.status_code == 404
    assert await _watched(db_session, theirs.id) == set()


async def test_an_empty_batch_is_rejected(auth_client, auth_user, db_session):
    media, _ = await _show(db_session)
    entry = await _entry(db_session, auth_user.id, media)

    response = await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched", json={"episode_ids": [], "watched": True}
    )

    assert response.status_code == 422


async def test_the_legacy_progress_patch_ticks_the_first_n_aired_episodes(auth_client, auth_user, db_session):
    media, episodes = await _show(db_session, aired=3, upcoming=1)
    entry = await _entry(db_session, auth_user.id, media)
    await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched", json={"episode_ids": _ids(episodes[2]), "watched": True}
    )

    response = await auth_client.patch(f"/v1/library/{entry.id}", json={"progress": 2})

    assert response.json()["progress"] == 2
    # The gap is replaced by the first two, as the legacy path has always meant.
    assert await _watched(db_session, entry.id) == {episodes[0].id, episodes[1].id}


async def test_progress_from_before_episode_tracking_is_backfilled_when_the_list_arrives(db_session):
    media, _ = await _show(db_session, synced=False)
    user = make_user(username="backfill", email="backfill@example.com")
    db_session.add(user)
    await db_session.flush()
    entry = await _entry(db_session, user.id, media, progress=2)

    await media_service.store_episodes(
        db_session,
        media.id,
        [ProviderEpisode(season_number=1, number=n, title=None, air_date=PAST) for n in (3, 1, 2)],
        NOW,
    )

    numbers = await db_session.scalars(
        select(Episode.number)
        .join(WatchedEpisode, WatchedEpisode.episode_id == Episode.id)
        .where(WatchedEpisode.user_media_id == entry.id)
        .order_by(Episode.number)
    )
    assert list(numbers) == [1, 2]


async def test_progress_ahead_of_the_known_episodes_is_kept_and_topped_up_later(db_session):
    """Mark all known, keep the higher progress, and fill in as more episodes arrive."""
    media, _ = await _show(db_session, synced=False)
    user = make_user(username="ahead", email="ahead@example.com")
    db_session.add(user)
    await db_session.flush()
    entry = await _entry(db_session, user.id, media, progress=3)
    two = [ProviderEpisode(season_number=1, number=n, title=None, air_date=PAST) for n in (1, 2)]

    await media_service.store_episodes(db_session, media.id, two, NOW)
    assert len(await _watched(db_session, entry.id)) == 2
    assert (await db_session.get(UserMedia, entry.id, populate_existing=True)).progress == 3

    three = [*two, ProviderEpisode(season_number=1, number=3, title=None, air_date=PAST)]
    await media_service.store_episodes(db_session, media.id, three, NOW)
    assert len(await _watched(db_session, entry.id)) == 3


async def test_an_entry_marked_episode_by_episode_is_never_backfilled_over(auth_client, auth_user, db_session):
    media, episodes = await _show(db_session)
    entry = await _entry(db_session, auth_user.id, media)
    await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched", json={"episode_ids": _ids(episodes[2]), "watched": True}
    )

    await library_service.backfill_watched(db_session, [media.id], NOW)

    assert await _watched(db_session, entry.id) == {episodes[2].id}


async def test_an_import_of_a_synced_title_lands_with_its_episodes_ticked(db_session):
    media, _ = await _show(db_session)
    user = make_user(username="importer", email="importer@example.com")
    db_session.add(user)
    await db_session.flush()

    await library_service.bulk_add_entries(
        db_session,
        user_id=user.id,
        rows=[{"media_id": media.id, "status": "watching", "progress": 2, "score": None}],
    )

    entry_id = await db_session.scalar(select(UserMedia.id).where(UserMedia.user_id == user.id))
    count = await db_session.scalar(
        select(func.count()).select_from(WatchedEpisode).where(WatchedEpisode.user_media_id == entry_id)
    )
    assert count == 2


async def test_a_renumbered_episode_takes_its_watched_mark_and_progress_with_it(auth_client, auth_user, db_session):
    """Progress drops with the cascade, so the backfill never reads the entry as ahead."""
    media, episodes = await _show(db_session, aired=3, upcoming=0)
    entry = await _entry(db_session, auth_user.id, media)
    await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched",
        json={"episode_ids": _ids(episodes[1], episodes[2]), "watched": True},
    )

    await media_service.store_episodes(
        db_session,
        media.id,
        [ProviderEpisode(season_number=1, number=n, title=None, air_date=PAST) for n in (1, 2)],
        NOW,
    )

    assert await _watched(db_session, entry.id) == {episodes[1].id}
    assert (await db_session.get(UserMedia, entry.id, populate_existing=True)).progress == 1


async def test_an_import_backfills_only_the_importers_entries(db_session):
    media, _ = await _show(db_session)
    someone = make_user(username="someone", email="someone@example.com")
    importer = make_user(username="importer2", email="importer2@example.com")
    db_session.add_all([someone, importer])
    await db_session.flush()
    theirs = await _entry(db_session, someone.id, media, progress=2)

    await library_service.bulk_add_entries(
        db_session,
        user_id=importer.id,
        rows=[{"media_id": media.id, "status": "watching", "progress": 1, "score": None}],
    )

    assert await _watched(db_session, theirs.id) == set()


@pytest.fixture
async def committed_entry():
    """A user, a synced show and an entry that really exist: the race below needs two real
    connections, which the transaction-bound db_session cannot give. Cleaned up afterwards.
    """
    tag = uuid.uuid4().hex[:8]
    async with get_sessionmaker()() as seed:
        user = make_user(username=f"r{tag}", email=f"r{tag}@example.com")
        media, _ = await _show(seed)
        seed.add(user)
        await seed.flush()
        entry = await _entry(seed, user.id, media)
        episode_ids = list(
            await seed.scalars(select(Episode.id).where(Episode.media_id == media.id).order_by(Episode.number))
        )
        await seed.commit()
        ids = (user.id, media.id, entry.id)
    try:
        yield ids[2], episode_ids
    finally:
        async with get_sessionmaker()() as cleanup:
            await cleanup.execute(delete(Media).where(Media.id == ids[1]))
            await cleanup.execute(delete(User).where(User.id == ids[0]))
            await cleanup.commit()


async def test_two_taps_at_once_still_leave_progress_equal_to_the_count(committed_entry):
    """The first tap's transaction is held open while the second runs: without the entry lock the
    second counts only its own insert and both write progress 1."""
    entry_id, episode_ids = committed_entry

    async def tap(session, episode_id):
        entry = await session.get(UserMedia, entry_id)
        media = await session.get(Media, entry.media_id)
        await library_service.set_watched(session, entry, media, [episode_id], watched=True, now=NOW)

    async def second_tap():
        async with get_sessionmaker()() as session:
            await tap(session, episode_ids[1])
            await session.commit()

    async with get_sessionmaker()() as first:
        await tap(first, episode_ids[0])
        racing = asyncio.create_task(second_tap())
        await asyncio.sleep(0.5)
        await first.commit()
    await racing

    async with get_sessionmaker()() as check:
        entry = await check.get(UserMedia, entry_id)
        count = await check.scalar(
            select(func.count()).select_from(WatchedEpisode).where(WatchedEpisode.user_media_id == entry_id)
        )
        assert (entry.progress, count) == (2, 2)
