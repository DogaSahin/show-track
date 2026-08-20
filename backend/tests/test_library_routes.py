import uuid
from datetime import UTC, datetime
from decimal import Decimal
from typing import ClassVar

import pytest
from sqlalchemy import func, select

from app.library.models import UserMedia, UserMediaStatus
from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.media.providers.base import MediaProvider, MediaRef, NextEpisode, ProviderMedia
from app.media.providers.errors import ProviderTimeout
from tests.factories import make_media, make_user_media

FRIEREN = ProviderMedia(
    ref=MediaRef(source=MediaSource.ANILIST, external_id="154587"),
    type=MediaType.ANIME,
    title="Frieren: Beyond Journey's End",
    year=2023,
    genres=("adventure", "drama", "fantasy"),
    cover_image_url="https://img.anili.st/154587.jpg",
    status=MediaStatus.FINISHED,
    next_episode=NextEpisode(season_number=1, number=29, airs_at=datetime(2026, 10, 1, tzinfo=UTC)),
)


class StubProvider(MediaProvider):
    """Answers get_by_id from a canned payload, or raises. Counts calls, so a test can prove the
    add path did not re-fetch a title already in `media`.
    """

    source: ClassVar[MediaSource] = MediaSource.ANILIST
    media_type: ClassVar[MediaType] = MediaType.ANIME

    def __init__(self, result: ProviderMedia | None = None, error: Exception | None = None) -> None:
        self._result = result
        self._error = error
        self.calls = 0

    async def search(self, query: str, page: int):
        raise AssertionError("not used in these tests")

    async def get_by_id(self, external_id: str) -> ProviderMedia | None:
        self.calls += 1
        if self._error is not None:
            raise self._error
        return self._result


def _add_body(external_id: str = "154587") -> dict[str, str]:
    return {"source": "anilist", "external_id": external_id}


async def test_adding_a_title_creates_both_rows(auth_client, db_session, use_providers):
    use_providers({MediaSource.ANILIST: StubProvider(FRIEREN)})

    response = await auth_client.post("/v1/library", json=_add_body())

    assert response.status_code == 201
    body = response.json()
    assert body["status"] == "planned"
    assert body["progress"] == 0
    assert body["favorite"] is False
    assert body["score"] is None
    assert body["media"]["title"] == "Frieren: Beyond Journey's End"
    assert body["media"]["source"] == "anilist"
    assert await db_session.scalar(select(func.count()).select_from(Media)) == 1
    assert await db_session.scalar(select(func.count()).select_from(UserMedia)) == 1


async def test_adding_the_same_title_twice_is_idempotent(auth_client, db_session, use_providers):
    """Decision 4-D. A mobile client whose successful request lost its response retries; that
    retry must not be an error, and must not duplicate the shared media row.
    """
    provider = StubProvider(FRIEREN)
    use_providers({MediaSource.ANILIST: provider})

    first = await auth_client.post("/v1/library", json=_add_body())
    second = await auth_client.post("/v1/library", json=_add_body())

    assert first.status_code == 201
    assert second.status_code == 200
    assert first.json()["id"] == second.json()["id"]
    assert await db_session.scalar(select(func.count()).select_from(Media)) == 1
    assert await db_session.scalar(select(func.count()).select_from(UserMedia)) == 1
    # Insert-once: the second add resolved the existing media row without a provider request.
    assert provider.calls == 1


async def test_adding_a_title_already_tracked_does_not_reset_it(auth_client, db_session, auth_user, use_providers):
    """The other half of 4-D, and the same invariant the import enforces in bulk: re-adding must
    never overwrite a score or progress set locally.
    """
    media = make_media(source=MediaSource.ANILIST, external_id="154587")
    db_session.add(media)
    await db_session.flush()
    db_session.add(
        make_user_media(
            auth_user.id,
            media.id,
            status=UserMediaStatus.WATCHING,
            progress=12,
            # Decimal, not int: the column is NUMERIC(3,1) and asyncpg's numeric codec expects a
            # Decimal. It also round-trips as Decimal("9.0"), which the assertions below check.
            score=Decimal("9.0"),
        )
    )
    await db_session.flush()
    use_providers({MediaSource.ANILIST: StubProvider(FRIEREN)})

    body = (await auth_client.post("/v1/library", json=_add_body())).json()

    assert body["status"] == "watching"
    assert body["progress"] == 12
    assert body["score"] == "9.0"
    # Read the COLUMN, not the ORM object. A column-level select bypasses the identity map and
    # is the only assertion here that is about the database rather than about an object already
    # in the session.
    stored = await db_session.scalar(select(UserMedia.score).where(UserMedia.id == uuid.UUID(body["id"])))
    assert stored == Decimal("9.0")


async def test_an_unknown_external_id_is_a_404(auth_client, use_providers):
    use_providers({MediaSource.ANILIST: StubProvider(None)})

    assert (await auth_client.post("/v1/library", json=_add_body("0"))).status_code == 404


async def test_an_unconfigured_source_is_a_503(auth_client, use_providers):
    """The default local setup: no TMDB_API_KEY, so TMDB is never registered. That is a fact
    about this server, not about the request, which is why it is not a 404.
    """
    use_providers({MediaSource.ANILIST: StubProvider(FRIEREN)})

    response = await auth_client.post("/v1/library", json={"source": "tmdb", "external_id": "1"})

    assert response.status_code == 503


async def test_a_provider_timeout_is_a_504(auth_client, use_providers):
    """End-to-end proof that app/errors.py is wired: nothing in the route catches this."""
    use_providers({MediaSource.ANILIST: StubProvider(error=ProviderTimeout("slow"))})

    assert (await auth_client.post("/v1/library", json=_add_body())).status_code == 504


# No hand-written 401 test for POST /v1/library. tests/test_auth_protection.py skips only paths
# containing "{", so it already parametrizes over this route and asserts both the 401 and the
# mount-level dependency. Only the two {entry_id} routes need explicit ones (Tasks 4.3/4.4).


@pytest.mark.parametrize(
    "body",
    [
        {"source": "nope", "external_id": "1"},
        {"source": "anilist"},
        {"source": "anilist", "external_id": ""},
        {"source": "anilist", "external_id": "0154587"},
        {"source": "tmdb", "external_id": "../../authentication/token/new"},
    ],
    ids=["unknown-source", "missing-external-id", "empty-external-id", "leading-zero", "path-traversal"],
)
async def test_a_malformed_body_is_a_422(auth_client, use_providers, body):
    """The two interesting cases are the last pair. "0154587" folds to 154587 under int(), so
    without canonicalisation it would be a SECOND media row for one upstream title. And httpx
    normalises "../.." out of a URL path, so an unconstrained external_id would point the
    server's own TMDB key at an arbitrary v3 endpoint.
    """
    use_providers({MediaSource.ANILIST: StubProvider(FRIEREN)})

    assert (await auth_client.post("/v1/library", json=body)).status_code == 422
