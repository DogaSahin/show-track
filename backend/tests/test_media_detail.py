import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.media import service
from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaProvider, MediaRef, NextEpisode, ProviderMedia
from tests.factories import make_media

WITCHER = ProviderMedia(
    ref=MediaRef(source=MediaSource.TMDB, external_id="71912"),
    type=MediaType.TV,
    title="The Witcher",
    year=2019,
    genres=("action", "adventure", "fantasy", "sci_fi"),
    cover_image_url="https://image.tmdb.org/t/p/w500/x.jpg",
    status=MediaStatus.AIRING,
    next_episode=NextEpisode(season_number=4, number=1, airs_at=datetime(2026, 9, 15, tzinfo=UTC)),
)


class CountingProvider(MediaProvider):
    source = MediaSource.TMDB
    media_type = MediaType.TV

    def __init__(self, result: ProviderMedia | None) -> None:
        self._result = result
        self.calls = 0

    async def search(self, query: str, page: int):
        raise AssertionError("not used in these tests")

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        self.calls += 1
        return self._result


async def test_get_or_create_inserts_a_row_from_provider_data(db_session):
    provider = CountingProvider(WITCHER)

    media = await service.get_or_create_media(db_session, {MediaSource.TMDB: provider}, WITCHER.ref)

    assert media is not None
    assert media.title == "The Witcher"
    assert media.year == 2019
    assert media.genres == ["action", "adventure", "fantasy", "sci_fi"]
    assert media.next_episode_season == 4
    assert media.next_episode_number == 1
    assert await db_session.scalar(select(Media).where(Media.external_id == "71912")) is not None


async def test_get_or_create_does_not_call_the_provider_for_an_existing_row(db_session):
    """Insert-once: only Phase 5's sync job refreshes a row, so a read never costs a request."""
    existing = make_media(source=MediaSource.TMDB, external_id="71912", type=MediaType.TV)
    db_session.add(existing)
    await db_session.flush()
    provider = CountingProvider(WITCHER)

    media = await service.get_or_create_media(db_session, {MediaSource.TMDB: provider}, WITCHER.ref)

    assert media.id == existing.id
    assert provider.calls == 0


async def test_get_or_create_returns_none_when_upstream_has_no_such_title(db_session):
    provider = CountingProvider(None)
    ref = MediaRef(source=MediaSource.TMDB, external_id="0")
    assert await service.get_or_create_media(db_session, {MediaSource.TMDB: provider}, ref) is None


async def test_detail_endpoint_returns_the_row(auth_client, db_session):
    media = make_media(next_episode_date=datetime.now(tz=UTC) + timedelta(days=3))
    db_session.add(media)
    await db_session.flush()

    body = (await auth_client.get(f"/v1/media/{media.id}")).json()

    assert body["title"] == media.title
    assert body["days_until_next_episode"] == 3


async def test_detail_endpoint_404s_for_an_unknown_id(auth_client):
    assert (await auth_client.get(f"/v1/media/{uuid.uuid4()}")).status_code == 404


async def test_search_still_routes_after_the_detail_route_exists(auth_client):
    """Regression guard for FastAPI's declaration-order matching: if /{media_id} were declared
    above /search, this would be a 422 on "search" failing UUID validation.
    """
    response = await auth_client.get("/v1/media/search", params={"q": "x"})
    assert response.status_code != 422


@pytest.mark.parametrize(
    ("delta", "expected"),
    [(timedelta(days=2), 2), (timedelta(days=-5), 0), (None, None)],
    ids=["future", "past-clamps-to-zero", "no-next-episode"],
)
def test_days_until_next_episode(delta, expected):
    now = datetime(2026, 6, 1, 12, 0, tzinfo=UTC)
    next_date = now + delta if delta is not None else None
    assert service.days_until(next_date, now) == expected
