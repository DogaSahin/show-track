import uuid
from datetime import UTC, datetime, timedelta, timezone

import pytest
from sqlalchemy import func, select

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


ARCANE = ProviderMedia(
    ref=MediaRef(source=MediaSource.TMDB, external_id="94605"),
    type=MediaType.TV,
    title="Arcane",
    year=2021,
    genres=("action", "adventure"),
    cover_image_url=None,
    status=MediaStatus.FINISHED,
    next_episode=None,
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


async def test_get_or_create_raises_when_upstream_has_no_such_title(db_session):
    provider = CountingProvider(None)
    ref = MediaRef(source=MediaSource.TMDB, external_id="0")

    with pytest.raises(service.MediaNotFound):
        await service.get_or_create_media(db_session, {MediaSource.TMDB: provider}, ref)


async def test_get_or_create_raises_when_the_source_has_no_configured_provider(db_session):
    """An absent TMDB_API_KEY leaves TMDB out of the registry entirely. That has to be
    distinguishable from "no such title": one is a 503 about this server, the other a 404 about
    the request. Collapsing them into one None is what this task removes.
    """
    ref = MediaRef(source=MediaSource.TMDB, external_id="999999999")

    with pytest.raises(service.MediaSourceNotConfigured):
        await service.get_or_create_media(db_session, {}, ref)


async def test_persist_media_returns_the_same_row_when_called_twice(db_session):
    """Decision 4-A: ON CONFLICT DO UPDATE always RETURNS a row, so the second call resolves to
    the first one. Under the old DO NOTHING this returned None whenever the conflicting row was
    not yet visible.
    """
    first = await service.persist_media(db_session, WITCHER)
    second = await service.persist_media(db_session, WITCHER)

    assert first.id == second.id
    assert await db_session.scalar(select(func.count()).select_from(Media)) == 1


async def test_persist_media_does_not_overwrite_an_existing_row(db_session):
    """The SET clause is a no-op on purpose. Insert-once: refreshing here would give freshness
    two owners, and Phase 5's sync job is the one that owns it.

    `refresh` re-SELECTs rather than reading the identity map, which is the only way this
    assertion is about the database rather than about the object already in memory.
    """
    existing = make_media(source=MediaSource.TMDB, external_id="71912", type=MediaType.TV, title="Stale Title")
    db_session.add(existing)
    await db_session.flush()

    await service.persist_media(db_session, WITCHER)
    await db_session.refresh(existing)

    assert existing.title == "Stale Title"


async def test_persist_media_bulk_returns_ids_for_new_and_existing_rows(db_session):
    """The import needs an id for every entry it was given, not only the ones it inserted —
    which is exactly what the no-op DO UPDATE buys over DO NOTHING.
    """
    existing = make_media(source=MediaSource.TMDB, external_id="71912", type=MediaType.TV)
    db_session.add(existing)
    await db_session.flush()

    resolved = await service.persist_media_bulk(db_session, [WITCHER, ARCANE])

    assert resolved[WITCHER.ref] == existing.id
    assert ARCANE.ref in resolved


async def test_persist_media_bulk_tolerates_a_duplicated_ref(db_session):
    """Postgres raises cardinality_violation — "ON CONFLICT DO UPDATE command cannot affect row
    a second time" — when one statement carries two rows sharing a conflict key. DO NOTHING
    tolerates that; DO UPDATE does not. Deduplicating inside this function makes it total, so no
    caller can turn a title AniList lists twice into a 500.
    """
    resolved = await service.persist_media_bulk(db_session, [WITCHER, WITCHER])

    assert len(resolved) == 1
    assert WITCHER.ref in resolved


async def test_detail_endpoint_returns_the_row(auth_client, db_session):
    media = make_media(next_episode_date=datetime.now(tz=UTC) + timedelta(days=3))
    db_session.add(media)
    await db_session.flush()

    body = (await auth_client.get(f"/v1/media/{media.id}")).json()

    assert body["title"] == media.title
    assert body["days_until_next_episode"] == 3
    assert body["status"] == media.status.value
    assert body["year"] == media.year


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


def test_days_until_normalizes_non_utc_timezones():
    """`.date()` reads the datetime's own tzinfo, not UTC — without normalizing first, a
    non-UTC-but-equal instant would silently shift the answer by a day.
    """
    now = datetime(2026, 6, 1, 12, 0, tzinfo=UTC)
    plus_five = timezone(timedelta(hours=5))
    # Same instant as `now + 2 days` in UTC, just expressed in a +05:00 offset.
    next_date_non_utc = (now + timedelta(days=2)).astimezone(plus_five)

    assert service.days_until(next_date_non_utc, now) == service.days_until(now + timedelta(days=2), now)
