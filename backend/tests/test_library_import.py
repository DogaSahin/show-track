import uuid
from decimal import Decimal

import pytest
from sqlalchemy import select

from app.library import service as library_service
from app.library.models import UserMedia
from app.media import service as media_service
from app.media.models import MediaSource, MediaStatus, MediaType
from app.media.providers.base import (
    ListEntryStatus,
    MediaRef,
    ProviderListEntry,
    ProviderMedia,
    ProviderUserList,
)
from app.media.providers.errors import UserListNotAvailable
from tests.factories import make_user

IMPORT_URL = "/v1/library/import/anilist"


class FakeListProvider:
    """Implements the capability and nothing else — no search, no get_by_id, no MediaProvider
    subclassing. That is exactly what making UserListProvider a Protocol buys.
    """

    def __init__(self, result: ProviderUserList | None = None, error: Exception | None = None) -> None:
        self._result = result
        self._error = error
        self.calls = 0

    async def fetch_user_list(self, username: str) -> ProviderUserList:
        self.calls += 1
        if self._error is not None:
            raise self._error
        return self._result


def _entry(
    external_id: str,
    *,
    status: ListEntryStatus = ListEntryStatus.WATCHING,
    score: Decimal | None = None,
    progress: int = 0,
) -> ProviderListEntry:
    return ProviderListEntry(
        media=ProviderMedia(
            ref=MediaRef(source=MediaSource.ANILIST, external_id=external_id),
            type=MediaType.ANIME,
            title=f"Show {external_id}",
            year=2020,
            genres=("action",),
            cover_image_url=None,
            status=MediaStatus.FINISHED,
            next_episode=None,
        ),
        status=status,
        score=score,
        progress=progress,
    )


def _list(*entries: ProviderListEntry, dropped: int = 0, truncated: bool = False) -> ProviderUserList:
    return ProviderUserList(entries=entries, dropped=dropped, truncated=truncated)


async def test_importing_into_an_empty_library_creates_every_row(auth_client, use_providers):
    use_providers(
        {
            MediaSource.ANILIST: FakeListProvider(
                _list(
                    _entry("1", status=ListEntryStatus.COMPLETED, score=Decimal("8.5"), progress=25),
                    _entry("2", status=ListEntryStatus.PLANNED),
                )
            )
        }
    )

    response = await auth_client.post(IMPORT_URL, json={"username": "someone"})

    assert response.status_code == 200
    assert response.json() == {"imported": 2, "skipped": 0, "failed": 0, "truncated": False}

    items = (await auth_client.get("/v1/library")).json()["items"]
    by_title = {item["media"]["title"]: item for item in items}
    assert by_title["Show 1"]["status"] == "completed"
    assert by_title["Show 1"]["score"] == "8.5"
    assert by_title["Show 1"]["progress"] == 25
    assert by_title["Show 2"]["status"] == "planned"
    assert by_title["Show 2"]["score"] is None


async def test_reimporting_changes_nothing_and_preserves_a_local_score(auth_client, db_session, use_providers):
    """The acceptance criterion for the whole phase.

    After an import, ShowTrack is the source of truth. A re-import inserts what is missing and
    must never touch what is already here — enforced by ON CONFLICT DO NOTHING, so there is no
    code path that could overwrite a score even by accident.
    """
    use_providers(
        {MediaSource.ANILIST: FakeListProvider(_list(_entry("1", score=Decimal("6.0"), progress=1), _entry("2")))}
    )

    first = await auth_client.post(IMPORT_URL, json={"username": "someone"})
    assert first.json() == {"imported": 2, "skipped": 0, "failed": 0, "truncated": False}

    # Rate it locally, the way the owner would after actually watching it.
    entry_id = (await auth_client.get("/v1/library")).json()["items"][0]["id"]
    await auth_client.patch(f"/v1/library/{entry_id}", json={"score": 9.5, "progress": 12})

    second = await auth_client.post(IMPORT_URL, json={"username": "someone"})

    assert second.json() == {"imported": 0, "skipped": 2, "failed": 0, "truncated": False}

    # Read the COLUMNS, not the API response. Every request in this test shares one db_session,
    # so after the PATCH the UserMedia instance sits in the identity map with score 9.5 and
    # expire_on_commit=False; an ORM `select(UserMedia, Media)` returns that cached object
    # WITHOUT refreshing it. Measured: with the row overwritten to 6.0 in Postgres, an ORM select
    # still reported 9.5 while a column select reported 6.0. So a DO UPDATE that wrecked the
    # score would be invisible here — this test would pass for exactly the reason it exists to
    # disprove.
    row = (
        await db_session.execute(select(UserMedia.score, UserMedia.progress).where(UserMedia.id == uuid.UUID(entry_id)))
    ).one()
    assert row.score == Decimal("9.5")
    assert row.progress == 12


async def test_unmappable_entries_are_counted_as_failed(auth_client, use_providers):
    """`failed` comes from the mapper's drop count, so an unknown AniList status is visible in
    the response rather than silently defaulted to `planned`.
    """
    use_providers({MediaSource.ANILIST: FakeListProvider(_list(_entry("1"), dropped=3))})

    body = (await auth_client.post(IMPORT_URL, json={"username": "someone"})).json()

    assert body == {"imported": 1, "skipped": 0, "failed": 3, "truncated": False}


async def test_a_truncated_list_says_so(auth_client, use_providers):
    """Decision 4-L. Without this flag a list cut off at the chunk cap returns a body identical
    to a complete import.
    """
    use_providers({MediaSource.ANILIST: FakeListProvider(_list(_entry("1"), truncated=True))})

    body = (await auth_client.post(IMPORT_URL, json={"username": "someone"})).json()

    assert body["truncated"] is True


async def test_an_empty_list_imports_nothing(auth_client, use_providers):
    use_providers({MediaSource.ANILIST: FakeListProvider(_list(dropped=2))})

    body = (await auth_client.post(IMPORT_URL, json={"username": "someone"})).json()

    assert body == {"imported": 0, "skipped": 0, "failed": 2, "truncated": False}


async def test_a_payload_larger_than_one_chunk_still_imports_everything(auth_client, use_providers, monkeypatch):
    """Postgres' Bind message caps a statement at 32,767 parameters, so both bulk inserts chunk.
    Lowering the constant proves the loop; generating three thousand fixtures would prove the
    same thing far more slowly.

    Patched on each consuming module, not on app.db: both import the name at module load, so
    rebinding it in app.db would not be seen.
    """
    monkeypatch.setattr(media_service, "BULK_INSERT_CHUNK_SIZE", 2)
    monkeypatch.setattr(library_service, "BULK_INSERT_CHUNK_SIZE", 2)
    use_providers({MediaSource.ANILIST: FakeListProvider(_list(*(_entry(str(i)) for i in range(5))))})

    body = (await auth_client.post(IMPORT_URL, json={"username": "someone"})).json()

    assert body == {"imported": 5, "skipped": 0, "failed": 0, "truncated": False}
    assert len((await auth_client.get("/v1/library", params={"limit": 100})).json()["items"]) == 5


async def test_a_private_or_unknown_username_is_a_404(auth_client, use_providers):
    """A clean 4xx, not a 500 and not a 502: the upstream is healthy, the username is the
    problem.
    """
    use_providers({MediaSource.ANILIST: FakeListProvider(error=UserListNotAvailable("no public list"))})

    assert (await auth_client.post(IMPORT_URL, json={"username": "ghost"})).status_code == 404


async def test_an_unregistered_anilist_provider_is_a_503(auth_client, use_providers):
    use_providers({})

    assert (await auth_client.post(IMPORT_URL, json={"username": "someone"})).status_code == 503


async def test_the_import_only_touches_the_callers_library(auth_client, db_session, auth_user, use_providers):
    """user_id comes from the bearer token and never from the body, so there is nothing to get
    wrong — asserted rather than assumed, because the alternative leaks into another account.
    """
    other = make_user(username="someone-else", email="else@example.com")
    db_session.add(other)
    await db_session.flush()
    use_providers({MediaSource.ANILIST: FakeListProvider(_list(_entry("1")))})
    # Read the id BEFORE the request, for the same reason the route does: the import calls
    # session.rollback() (4-M), which expires every persistent object in this shared session —
    # auth_user included. Touching auth_user.id afterwards is a lazy load in async code.
    owner_id = auth_user.id

    await auth_client.post(IMPORT_URL, json={"username": "someone"})

    # Assert over ALL rows, not over `other`'s. Seeding with flush() only leaves that row inside
    # the shared savepoint, and import_anilist_library's own rollback (4-M) discards it — so
    # `where(user_id == other.id) == []` would hold because the user vanished, not because the
    # import is scoped. This form cannot pass for that reason.
    owners = set((await db_session.scalars(select(UserMedia.user_id))).all())
    assert owners == {owner_id}


@pytest.mark.parametrize(
    "body",
    [{}, {"username": ""}, {"username": "   "}, {"username": "x" * 51}],
    ids=["missing", "empty", "whitespace-only", "too-long"],
)
async def test_a_missing_username_is_a_422(auth_client, use_providers, body):
    """strip_whitespace lives inside the constraint so min_length applies to the STRIPPED value.
    Validating first and stripping in the route would let "   " reach AniList as an empty name.
    """
    use_providers({MediaSource.ANILIST: FakeListProvider(_list())})

    assert (await auth_client.post(IMPORT_URL, json=body)).status_code == 422


# No hand-written 401 test: /v1/library/import/anilist carries no "{" in its path, so
# tests/test_auth_protection.py already parametrizes over it.
