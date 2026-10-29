"""The derived display `section` on library entries, and the `section=` filter."""

import uuid
from datetime import UTC, date, datetime, timedelta

from sqlalchemy import select

from app.library.models import UserMediaStatus
from app.media.models import Episode, MediaSource, MediaStatus
from tests.factories import make_episode, make_media, make_user, make_user_media

TODAY = datetime.now(tz=UTC).date()


async def _seed(
    db_session,
    user_id,
    *,
    status=UserMediaStatus.WATCHING,
    progress=0,
    media_status=MediaStatus.AIRING,
    synced=True,
    total_episodes=None,
    air_dates=(),
    season=1,
    next_episode_season=None,
    next_episode_in_days=None,
    title="Title",
):
    """One media row + its episodes (one per entry of `air_dates`, numbered in order) + an entry."""
    now = datetime.now(tz=UTC)
    media = make_media(
        external_id=uuid.uuid4().hex[:12],
        title=title,
        status=media_status,
        total_episodes=total_episodes,
        episodes_synced_at=now if synced else None,
        next_episode_season=next_episode_season,
        next_episode_date=None if next_episode_in_days is None else now + timedelta(days=next_episode_in_days),
    )
    db_session.add(media)
    await db_session.flush()
    for number, air_date in enumerate(air_dates, start=1):
        db_session.add(make_episode(media.id, season_number=season, number=number, air_date=air_date))
    entry = make_user_media(user_id, media.id, status=status, progress=progress)
    db_session.add(entry)
    await db_session.flush()
    return entry


def _past(n: int) -> list[date]:
    return [TODAY - timedelta(days=7 * (n - i)) for i in range(n)]


async def _sections(client, **params) -> dict[str, str]:
    response = await client.get("/v1/library", params={"limit": 100, **params})
    assert response.status_code == 200, response.text
    return {item["id"]: item["section"] for item in response.json()["items"]}


async def test_non_watching_status_maps_to_itself(auth_client, db_session, auth_user):
    entries = {
        s: await _seed(db_session, auth_user.id, status=s, title=s.value)
        for s in (
            UserMediaStatus.PAUSED,
            UserMediaStatus.PLANNED,
            UserMediaStatus.COMPLETED,
            UserMediaStatus.DROPPED,
        )
    }

    sections = await _sections(auth_client)

    for s, entry in entries.items():
        assert sections[str(entry.id)] == s.value


async def test_finished_and_fully_watched_watching_is_completed(auth_client, db_session, auth_user):
    entry = await _seed(
        db_session,
        auth_user.id,
        progress=12,
        media_status=MediaStatus.FINISHED,
        total_episodes=12,
        air_dates=_past(12),
    )

    response = await auth_client.get("/v1/library", params={"limit": 100})

    [item] = response.json()["items"]
    assert item["id"] == str(entry.id)
    assert item["status"] == "watching"
    assert item["section"] == "completed"


async def test_caught_up_between_seasons(auth_client, db_session, auth_user):
    entry = await _seed(
        db_session,
        auth_user.id,
        progress=10,
        air_dates=_past(10),
        next_episode_season=2,
        next_episode_in_days=30,
    )

    assert (await _sections(auth_client))[str(entry.id)] == "caught_up"


async def test_caught_up_no_next_episode(auth_client, db_session, auth_user):
    entry = await _seed(db_session, auth_user.id, progress=6, air_dates=_past(6))

    assert (await _sections(auth_client))[str(entry.id)] == "caught_up"


async def test_weekly_show_up_to_date_is_watching(auth_client, db_session, auth_user):
    """Up to date, but the next episode is in the same season: still actively watching."""
    entry = await _seed(
        db_session,
        auth_user.id,
        progress=5,
        air_dates=_past(5),
        next_episode_season=1,
        next_episode_in_days=3,
    )

    assert (await _sections(auth_client))[str(entry.id)] == "watching"


async def test_caught_up_ignores_unaired_rows(auth_client, db_session, auth_user):
    future = [TODAY + timedelta(days=7 * (i + 1)) for i in range(4)]
    entry = await _seed(
        db_session,
        auth_user.id,
        progress=8,
        air_dates=_past(8) + future,
        next_episode_season=2,
        next_episode_in_days=30,
    )

    assert (await _sections(auth_client))[str(entry.id)] == "caught_up"


async def test_unsynced_episodes_stay_watching(auth_client, db_session, auth_user):
    entry = await _seed(
        db_session,
        auth_user.id,
        progress=12,
        media_status=MediaStatus.FINISHED,
        total_episodes=12,
        synced=False,
    )

    assert (await _sections(auth_client))[str(entry.id)] == "watching"


async def test_nothing_aired_is_watching(auth_client, db_session, auth_user):
    entry = await _seed(db_session, auth_user.id, media_status=MediaStatus.NOT_YET_AIRED)

    assert (await _sections(auth_client))[str(entry.id)] == "watching"


async def test_section_filter(auth_client, db_session, auth_user):
    caught_up = await _seed(db_session, auth_user.id, progress=4, air_dates=_past(4), title="Caught")
    watching = await _seed(
        db_session,
        auth_user.id,
        progress=2,
        air_dates=_past(4),
        next_episode_season=1,
        next_episode_in_days=3,
        title="Behind",
    )
    finished_watching = await _seed(
        db_session,
        auth_user.id,
        progress=3,
        media_status=MediaStatus.FINISHED,
        total_episodes=3,
        air_dates=_past(3),
        title="Done but Watching",
    )
    saved_completed = await _seed(db_session, auth_user.id, status=UserMediaStatus.COMPLETED, title="Saved done")

    assert set(await _sections(auth_client, section="caught_up")) == {str(caught_up.id)}
    assert set(await _sections(auth_client, section="watching")) == {str(watching.id)}
    assert set(await _sections(auth_client, section="completed")) == {
        str(finished_watching.id),
        str(saved_completed.id),
    }
    # Combinable with the other filters: status=watching narrows "completed" to the derived one.
    assert set(await _sections(auth_client, section="completed", status="watching")) == {str(finished_watching.id)}


async def test_section_filter_pages_across_a_cursor(auth_client, db_session, auth_user):
    """The section filter holds on every page: no row twice, none missing, none from elsewhere."""
    planned = {
        str((await _seed(db_session, auth_user.id, status=UserMediaStatus.PLANNED, title=f"Planned {n}")).id)
        for n in range(3)
    }
    for n in range(2):
        await _seed(db_session, auth_user.id, status=UserMediaStatus.DROPPED, title=f"Dropped {n}")
    await _seed(db_session, auth_user.id, progress=4, air_dates=_past(4), title="Caught")

    first = (await auth_client.get("/v1/library", params={"section": "planned", "limit": 2})).json()
    assert len(first["items"]) == 2
    assert first["next_cursor"] is not None
    second = (
        await auth_client.get("/v1/library", params={"section": "planned", "limit": 2, "cursor": first["next_cursor"]})
    ).json()
    assert second["next_cursor"] is None

    ids = [item["id"] for item in first["items"] + second["items"]]
    assert len(ids) == len(set(ids))
    assert set(ids) == planned
    assert {item["section"] for item in first["items"] + second["items"]} == {"planned"}


async def test_section_filter_rejects_unknown_value(auth_client):
    response = await auth_client.get("/v1/library", params={"section": "binging"})

    assert response.status_code == 422


async def test_section_filter_is_caller_scoped(auth_client, db_session, auth_user):
    other = make_user(username="section-other", email="section-other@example.com")
    db_session.add(other)
    await db_session.flush()
    await _seed(db_session, other.id, progress=4, air_dates=_past(4), title="Theirs")
    mine = await _seed(db_session, auth_user.id, progress=4, air_dates=_past(4), title="Mine")

    assert set(await _sections(auth_client, section="caught_up")) == {str(mine.id)}


async def test_patch_response_carries_section(auth_client, db_session, auth_user):
    entry = await _seed(
        db_session,
        auth_user.id,
        progress=11,
        media_status=MediaStatus.FINISHED,
        total_episodes=12,
        air_dates=_past(12),
    )

    response = await auth_client.patch(f"/v1/library/{entry.id}", json={"progress": 12})

    assert response.status_code == 200, response.text
    body = response.json()
    assert body["status"] == "watching"
    assert body["section"] == "completed"


async def test_finished_but_behind_stays_watching(auth_client, db_session, auth_user):
    """Finished and synced, but not every episode watched: neither completed nor caught up."""
    entry = await _seed(
        db_session,
        auth_user.id,
        progress=8,
        media_status=MediaStatus.FINISHED,
        total_episodes=12,
        air_dates=_past(12),
    )

    assert (await _sections(auth_client))[str(entry.id)] == "watching"


async def test_next_episode_without_season_stays_watching(auth_client, db_session, auth_user):
    """Everything aired is watched, but a next episode with no season can't be told apart from a
    mid-season week, so the entry stays watching."""
    entry = await _seed(
        db_session,
        auth_user.id,
        progress=5,
        air_dates=_past(5),
        next_episode_season=None,
        next_episode_in_days=3,
    )

    assert (await _sections(auth_client))[str(entry.id)] == "watching"


async def test_add_response_carries_section(auth_client, db_session):
    media = make_media(source=MediaSource.ANILIST, external_id="777001", title="Already Cached")
    db_session.add(media)
    await db_session.flush()

    response = await auth_client.post("/v1/library", json={"source": "anilist", "external_id": "777001"})

    assert response.status_code == 201, response.text
    assert response.json()["section"] == "planned"


async def test_mark_watched_response_carries_section(auth_client, db_session, auth_user):
    entry = await _seed(db_session, auth_user.id, progress=0, air_dates=_past(2))
    episodes = list(await db_session.scalars(select(Episode).where(Episode.media_id == entry.media_id)))

    response = await auth_client.put(
        f"/v1/library/{entry.id}/episodes/watched",
        json={"episode_ids": [str(e.id) for e in episodes], "watched": True},
    )

    assert response.status_code == 200, response.text
    assert response.json()["section"] == "caught_up"
