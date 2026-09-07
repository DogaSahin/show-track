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
from tests.factories import make_media, make_user, make_user_media

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

    async def fetch_similar(self, external_id: str):
        raise AssertionError("not used in these tests")


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


async def _seed_entry(db_session, user_id, **overrides):
    media = make_media(source=MediaSource.ANILIST, external_id="154587")
    db_session.add(media)
    await db_session.flush()
    entry = make_user_media(user_id, media.id, **overrides)
    db_session.add(entry)
    await db_session.flush()
    return entry


async def test_patching_one_field_leaves_the_others_alone(auth_client, db_session, auth_user):
    entry = await _seed_entry(db_session, auth_user.id, status=UserMediaStatus.WATCHING, progress=12, favorite=True)

    body = (await auth_client.patch(f"/v1/library/{entry.id}", json={"progress": 13})).json()

    assert body["progress"] == 13
    assert body["status"] == "watching"
    assert body["favorite"] is True


async def test_patching_score_to_null_unrates_the_title(auth_client, db_session, auth_user):
    """The exclude_unset proof. `{"score": null}` is a real update — it unrates — and must stay
    distinguishable from a body that simply omits score. exclude_none would make unrating
    impossible to express at all.
    """
    entry = await _seed_entry(db_session, auth_user.id, score=Decimal("9.0"))

    body = (await auth_client.patch(f"/v1/library/{entry.id}", json={"score": None})).json()

    assert body["score"] is None


async def test_an_empty_patch_returns_the_row_unchanged(auth_client, db_session, auth_user):
    entry = await _seed_entry(db_session, auth_user.id, progress=7)

    response = await auth_client.patch(f"/v1/library/{entry.id}", json={})

    assert response.status_code == 200
    assert response.json()["progress"] == 7


async def test_a_non_empty_patch_returns_200_and_not_500(auth_client, db_session, auth_user):
    """The regression guard for the flush-expiry bug, and the STATUS is the guard — not the
    timestamp.

    `updated_at` carries `onupdate=func.now()`, and Postgres' now() is transaction_timestamp():
    the whole test runs inside one transaction, so the seed INSERT and this UPDATE stamp an
    identical value. Measured — `updated_at > before` is False here even when the route is
    correct, and `updated_at == before` is True even when it is not. A timestamp assertion in
    this harness is unfalsifiable in both directions.

    What the bug actually produced was a 500 after a successful commit, so that is what this
    asserts. The empty-patch test above cannot catch it: an empty body dirties nothing, so
    nothing is expired and nothing lazy-loads.
    """
    entry = await _seed_entry(db_session, auth_user.id, progress=7)

    response = await auth_client.patch(f"/v1/library/{entry.id}", json={"progress": 8})

    assert response.status_code == 200
    assert response.json()["progress"] == 8


async def test_patching_another_users_entry_is_a_404_not_a_403(auth_client, db_session):
    """A 403 would confirm the entry exists, turning the endpoint into an existence oracle. Same
    reasoning as the auth module's single _UNAUTHENTICATED response.
    """
    other = make_user(username="someone-else", email="else@example.com")
    db_session.add(other)
    await db_session.flush()
    entry = await _seed_entry(db_session, other.id)

    response = await auth_client.patch(f"/v1/library/{entry.id}", json={"progress": 1})

    assert response.status_code == 404


async def test_patching_an_unknown_entry_is_a_404(auth_client):
    assert (await auth_client.patch(f"/v1/library/{uuid.uuid4()}", json={"progress": 1})).status_code == 404


@pytest.mark.parametrize(
    "body",
    [
        {"score": 0.5},
        {"score": 10.5},
        {"score": 8.25},
        {"progress": -1},
        {"progress": 2**31},
        {"status": "nope"},
        {"status": None},
        {"progress": None},
        {"favorite": None},
        {"scores": 9},
    ],
    ids=[
        "score-below-range",
        "score-above-range",
        "score-too-precise",
        "negative-progress",
        "progress-overflows-int4",
        "unknown-status",
        "null-status",
        "null-progress",
        "null-favorite",
        "unknown-field",
    ],
)
async def test_an_invalid_patch_body_is_a_422(auth_client, db_session, auth_user, body):
    """score is NUMERIC(3,1): without decimal_places=1, 8.25 is silently rounded by Postgres and
    the client reads back a number it never sent. Only `score` is nullable in user_media, so an
    explicit null on the other three would reach the flush as an IntegrityError 500 — and an
    unknown field would 200 having changed nothing. `{"score": null}` stays in the SUCCESS test
    above: the two must remain distinguishable, which is the whole reason for exclude_unset.
    """
    entry = await _seed_entry(db_session, auth_user.id)

    assert (await auth_client.patch(f"/v1/library/{entry.id}", json=body)).status_code == 422


async def test_patching_requires_authentication(client):
    """The auth-protection invariant test skips routes with a {param} in their path — see the
    comment in main.py. This route is one of the first two such routes in the codebase, so its
    401 is asserted explicitly or not at all.

    Do NOT request `auth_user` (or `auth_client`) here. `auth_client` does not build a second
    client: it MUTATES the shared `client` object, setting an Authorization header, and yields
    the same instance. Pulling either fixture in authenticates the very client this test asserts
    is anonymous. No seeded row is needed — authentication runs before the lookup.
    """
    assert (await client.patch(f"/v1/library/{uuid.uuid4()}", json={"progress": 1})).status_code == 401


async def test_deleting_an_entry_leaves_the_shared_media_row(auth_client, db_session, auth_user):
    """media is shared across users, so removing one person's entry must not remove the title.
    Structurally guaranteed — the CASCADE is declared on user_media.media_id, so it fires when a
    MEDIA row is deleted, and there is no path from a user_media delete back to media — but this
    is the phase's stated acceptance criterion, so it is asserted rather than assumed.
    """
    entry = await _seed_entry(db_session, auth_user.id)

    response = await auth_client.delete(f"/v1/library/{entry.id}")

    assert response.status_code == 204
    assert await db_session.scalar(select(func.count()).select_from(UserMedia)) == 0
    assert await db_session.scalar(select(func.count()).select_from(Media)) == 1


async def test_deleting_twice_is_a_404_the_second_time(auth_client, db_session, auth_user):
    entry = await _seed_entry(db_session, auth_user.id)

    assert (await auth_client.delete(f"/v1/library/{entry.id}")).status_code == 204
    assert (await auth_client.delete(f"/v1/library/{entry.id}")).status_code == 404


async def test_deleting_another_users_entry_is_a_404(auth_client, db_session):
    other = make_user(username="someone-else", email="else@example.com")
    db_session.add(other)
    await db_session.flush()
    entry = await _seed_entry(db_session, other.id)

    assert (await auth_client.delete(f"/v1/library/{entry.id}")).status_code == 404
    assert await db_session.scalar(select(func.count()).select_from(UserMedia)) == 1


async def test_deleting_requires_authentication(client):
    """The second of the two {param} routes the auth invariant test cannot reach.

    As in the PATCH case: no `auth_user`, no `auth_client` — they mutate this same client object.
    """
    assert (await client.delete(f"/v1/library/{uuid.uuid4()}")).status_code == 401
