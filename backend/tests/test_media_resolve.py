"""POST /v1/media/resolve (BT-03): open a search result without adding it."""

from datetime import UTC, datetime, timedelta
from typing import ClassVar

from sqlalchemy import func, select

from app.library.models import UserMedia
from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaProvider, MediaRef, NextEpisode, ProviderMedia
from app.media.providers.errors import ProviderTimeout
from tests.factories import make_media, make_user_media

AIRS = datetime(2026, 12, 1, tzinfo=UTC)


def _detail(source: MediaSource, external_id: str, title: str, media_type: MediaType) -> ProviderMedia:
    return ProviderMedia(
        ref=MediaRef(source=source, external_id=external_id),
        type=media_type,
        title=title,
        year=2024,
        genres=("drama",),
        cover_image_url=None,
        status=MediaStatus.AIRING,
        next_episode=NextEpisode(season_number=2, number=7, airs_at=AIRS),
    )


class StubProvider(MediaProvider):
    """Answers get_by_id from a canned payload, or raises, and counts calls."""

    source: ClassVar[MediaSource] = MediaSource.ANILIST
    media_type: ClassVar[MediaType] = MediaType.ANIME

    def __init__(self, result: ProviderMedia | None = None, error: Exception | None = None) -> None:
        self._result = result
        self._error = error
        self.calls = 0

    async def search(self, query: str, page: int):
        raise AssertionError("not used")

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        self.calls += 1
        if self._error is not None:
            raise self._error
        return self._result

    async def get_episodes(self, external_id: str):
        raise AssertionError("not used")

    async def fetch_similar(self, external_id: str):
        raise AssertionError("not used")


FRIEREN = _detail(MediaSource.ANILIST, "154587", "Frieren", MediaType.ANIME)
SEVERANCE = _detail(MediaSource.TMDB, "95396", "Severance", MediaType.TV)


async def test_resolving_creates_the_row_and_returns_its_id_for_each_source(auth_client, db_session, use_providers):
    use_providers({MediaSource.ANILIST: StubProvider(FRIEREN), MediaSource.TMDB: StubProvider(SEVERANCE)})

    anime = await auth_client.post("/v1/media/resolve", json={"source": "anilist", "external_id": "154587"})
    tv = await auth_client.post("/v1/media/resolve", json={"source": "tmdb", "external_id": "95396"})

    assert anime.status_code == tv.status_code == 200
    assert anime.json()["title"] == "Frieren"
    assert tv.json()["title"] == "Severance"
    assert anime.json()["id"] and tv.json()["id"]
    # Opening is not adding.
    assert await db_session.scalar(select(func.count()).select_from(UserMedia)) == 0


async def test_resolving_twice_returns_the_same_id(auth_client, db_session, use_providers):
    """Concurrent safety comes from get_or_create_media's ON CONFLICT upsert (shared with POST
    /v1/library). The test session is one connection, so the calls here are sequential."""
    provider = StubProvider(FRIEREN)
    use_providers({MediaSource.ANILIST: provider})
    body = {"source": "anilist", "external_id": "154587"}

    first = await auth_client.post("/v1/media/resolve", json=body)
    second = await auth_client.post("/v1/media/resolve", json=body)
    third = await auth_client.post("/v1/media/resolve", json=body)

    assert first.status_code == second.status_code == third.status_code == 200
    assert first.json()["id"] == second.json()["id"] == third.json()["id"]
    assert await db_session.scalar(select(func.count()).select_from(Media)) == 1
    # A new row has never been synced, so the second open refreshes it once through the sync
    # job's writer, which stamps it; the third finds it fresh and makes no request.
    assert provider.calls == 2


async def test_a_stale_row_nobody_tracks_is_refreshed(auth_client, db_session, use_providers):
    stored = make_media(
        source=MediaSource.ANILIST,
        external_id="154587",
        title="Frieren",
        status=MediaStatus.NOT_YET_AIRED,
        last_synced_at=datetime.now(tz=UTC) - timedelta(days=3),
    )
    db_session.add(stored)
    await db_session.flush()
    provider = StubProvider(FRIEREN)
    use_providers({MediaSource.ANILIST: provider})

    body = (await auth_client.post("/v1/media/resolve", json={"source": "anilist", "external_id": "154587"})).json()

    assert provider.calls == 1
    assert body["id"] == str(stored.id)
    assert body["status"] == "airing"
    assert body["next_episode_number"] == 7


async def test_a_fresh_or_tracked_row_is_returned_without_a_provider_call(
    auth_client, db_session, auth_user, use_providers
):
    fresh = make_media(
        source=MediaSource.ANILIST,
        external_id="1",
        title="Fresh",
        last_synced_at=datetime.now(tz=UTC) - timedelta(hours=1),
    )
    tracked = make_media(source=MediaSource.ANILIST, external_id="2", title="Tracked", last_synced_at=None)
    db_session.add_all([fresh, tracked])
    await db_session.flush()
    db_session.add(make_user_media(auth_user.id, tracked.id))
    await db_session.flush()
    provider = StubProvider(FRIEREN)
    use_providers({MediaSource.ANILIST: provider})

    for external_id, title in (("1", "Fresh"), ("2", "Tracked")):
        response = await auth_client.post("/v1/media/resolve", json={"source": "anilist", "external_id": external_id})
        assert response.json()["title"] == title

    assert provider.calls == 0


async def test_a_failed_refresh_still_returns_the_stored_row(auth_client, db_session, use_providers):
    stored = make_media(source=MediaSource.ANILIST, external_id="154587", title="Frieren", last_synced_at=None)
    db_session.add(stored)
    await db_session.flush()
    use_providers({MediaSource.ANILIST: StubProvider(error=ProviderTimeout("slow"))})

    response = await auth_client.post("/v1/media/resolve", json={"source": "anilist", "external_id": "154587"})

    assert response.status_code == 200
    assert response.json()["id"] == str(stored.id)
    assert (await db_session.get(Media, stored.id, populate_existing=True)).last_synced_at is None


async def test_errors_match_adding_to_the_library(auth_client, use_providers):
    use_providers({MediaSource.ANILIST: StubProvider(None)})
    missing = await auth_client.post("/v1/media/resolve", json={"source": "anilist", "external_id": "0"})

    use_providers({})
    unconfigured = await auth_client.post("/v1/media/resolve", json={"source": "anilist", "external_id": "0"})

    use_providers({MediaSource.ANILIST: StubProvider(error=ProviderTimeout("slow"))})
    timeout = await auth_client.post("/v1/media/resolve", json={"source": "anilist", "external_id": "0"})

    assert (missing.status_code, unconfigured.status_code, timeout.status_code) == (404, 503, 504)


async def test_an_unknown_source_is_rejected(auth_client):
    response = await auth_client.post("/v1/media/resolve", json={"source": "imdb", "external_id": "1"})

    assert response.status_code == 422
