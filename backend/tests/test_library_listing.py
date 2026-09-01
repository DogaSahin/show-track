import uuid
from datetime import UTC, datetime, timedelta
from decimal import Decimal

import pytest

from app.library.models import UserMediaStatus
from app.media.models import MediaSource
from app.pagination import encode_cursor
from tests.factories import make_media, make_user, make_user_media


async def _seed(db_session, user_id, rows):
    """rows: list of (title, score, days_until_next_episode, status).

    external_id is a fresh uuid per media row, NOT a per-call counter: `media` carries
    UniqueConstraint("source", "external_id"), so a counter restarting at 0 on each call makes
    the second _seed in a test collide and raise IntegrityError before any assertion runs.
    """
    now = datetime.now(tz=UTC)
    for title, score, days, entry_status in rows:
        media = make_media(
            source=MediaSource.ANILIST,
            external_id=uuid.uuid4().hex[:12],
            title=title,
            next_episode_date=None if days is None else now + timedelta(days=days),
        )
        db_session.add(media)
        await db_session.flush()
        db_session.add(
            make_user_media(
                user_id,
                media.id,
                status=entry_status,
                score=None if score is None else Decimal(str(score)),
            )
        )
    await db_session.flush()


async def _page_through(client, params, limit):
    """Follow next_cursor to exhaustion, returning every entry id in order."""
    seen, cursor = [], None
    for _ in range(50):  # loop guard: a broken cursor must fail the test, not hang it
        query = {**params, "limit": limit}
        if cursor is not None:
            query["cursor"] = cursor
        body = (await client.get("/v1/library", params=query)).json()
        seen.extend(item["id"] for item in body["items"])
        cursor = body["next_cursor"]
        if cursor is None:
            return seen
    raise AssertionError("next_cursor never went null — pagination does not terminate")


@pytest.mark.parametrize("sort", ["score", "next_episode_date", "title"])
async def test_paging_a_tied_dataset_returns_every_row_exactly_once(auth_client, db_session, auth_user, sort):
    """The test this whole cursor design exists to pass.

    Twenty entries share score 8.0 and five have none at all, so a cursor over the sort value
    alone would skip or duplicate rows at every page boundary, and a NULL would poison the row
    comparison and drop the unrated ones entirely. Ties break on id; NULLs sort last via the
    COALESCE sentinel.
    """
    rows = [(f"Title {i:02d}", 8.0, 3, UserMediaStatus.WATCHING) for i in range(20)]
    rows += [(f"Unrated {i:02d}", None, None, UserMediaStatus.PLANNED) for i in range(5)]
    await _seed(db_session, auth_user.id, rows)

    seen = await _page_through(auth_client, {"sort": sort}, limit=7)

    assert len(seen) == 25
    assert len(set(seen)) == 25


@pytest.mark.parametrize(
    ("sort", "expected"),
    [("title", ["Alpha", "Beta", "Gamma"]), ("next_episode_date", ["Beta", "Gamma", "Alpha"])],
    ids=["title-ascending", "next-episode-soonest-first"],
)
async def test_each_sort_uses_its_fixed_direction(auth_client, db_session, auth_user, sort, expected):
    """Decision 4-J is the API contract here — there is no `order=` parameter — and nothing else
    pins it. `SortSpec.descending` drives BOTH the ORDER BY and the keyset WHERE, so flipping it
    keeps pagination perfectly self-consistent and merely reverses the results: the tied-dataset
    test still passes. This is the only test that fails if a direction is wrong.

    Alpha has no next episode, so it sorts last on date and first on title — one dataset
    distinguishes both orderings.
    """
    await _seed(
        db_session,
        auth_user.id,
        [
            ("Alpha", 5.0, None, UserMediaStatus.WATCHING),
            ("Gamma", 6.0, 9, UserMediaStatus.WATCHING),
            ("Beta", 7.0, 2, UserMediaStatus.WATCHING),
        ],
    )

    body = (await auth_client.get("/v1/library", params={"sort": sort})).json()

    assert [item["media"]["title"] for item in body["items"]] == expected


@pytest.mark.parametrize("total", [7, 8], ids=["exactly-limit", "limit-plus-one"])
async def test_the_page_boundary_is_exact(auth_client, db_session, auth_user, total):
    """`limit + 1` is the has-more probe, so the off-by-one lives exactly here. With
    `total == limit` there must be NO next_cursor; with one more row there must be exactly two
    pages. The 25-row/limit-7 dataset never lands on a multiple of the limit, so it cannot catch
    a cursor wrongly emitted on a full final page.
    """
    await _seed(db_session, auth_user.id, [(f"T{i:02d}", 8.0, None, UserMediaStatus.WATCHING) for i in range(total)])

    first = (await auth_client.get("/v1/library", params={"limit": 7})).json()

    assert len(first["items"]) == 7
    if total == 7:
        assert first["next_cursor"] is None
    else:
        assert first["next_cursor"] is not None
        second = (await auth_client.get("/v1/library", params={"limit": 7, "cursor": first["next_cursor"]})).json()
        assert len(second["items"]) == 1
        assert second["next_cursor"] is None


async def test_paging_identical_titles_returns_each_once(auth_client, db_session, auth_user):
    """Under sort=title the tied-dataset test has 25 DISTINCT titles, so the id tiebreaker is
    never exercised for the text sort at all. Real libraries carry duplicate titles (a remake, a
    season listed twice by two sources), which is precisely when a missing tiebreaker skips or
    duplicates rows.
    """
    await _seed(db_session, auth_user.id, [("Same Title", None, None, UserMediaStatus.PLANNED) for _ in range(9)])

    seen = await _page_through(auth_client, {"sort": "title"}, limit=4)

    assert len(seen) == 9
    assert len(set(seen)) == 9


async def test_the_date_sentinel_round_trips_as_an_accepted_cursor(auth_client, db_session, auth_user):
    """The server must accept its own cursor. With datetime.max as the sentinel it does not:
    asyncpg stores it as Postgres `infinity` and reads it back NAIVE, so the emitted cursor has
    no offset and _parse_next_episode_date rejects it — a 400 at the first NULL-date page
    boundary. This pages right through that boundary, which is where it bites.
    """
    rows = [(f"Dated {i}", None, i + 1, UserMediaStatus.WATCHING) for i in range(3)]
    rows += [(f"Undated {i}", None, None, UserMediaStatus.PLANNED) for i in range(3)]
    await _seed(db_session, auth_user.id, rows)

    seen = await _page_through(auth_client, {"sort": "next_episode_date"}, limit=2)

    assert len(seen) == 6
    assert len(set(seen)) == 6


async def test_unrated_entries_sort_last(auth_client, db_session, auth_user):
    await _seed(
        db_session,
        auth_user.id,
        [("Unrated", None, None, UserMediaStatus.PLANNED), ("Rated", 7.5, None, UserMediaStatus.WATCHING)],
    )

    body = (await auth_client.get("/v1/library", params={"sort": "score"})).json()

    assert [item["media"]["title"] for item in body["items"]] == ["Rated", "Unrated"]


async def test_next_cursor_is_null_on_the_last_page(auth_client, db_session, auth_user):
    await _seed(db_session, auth_user.id, [("Only", 8.0, None, UserMediaStatus.WATCHING)])

    body = (await auth_client.get("/v1/library", params={"limit": 20})).json()

    assert len(body["items"]) == 1
    assert body["next_cursor"] is None


async def test_the_status_filter_narrows_the_page(auth_client, db_session, auth_user):
    await _seed(
        db_session,
        auth_user.id,
        [
            ("Watching one", 8.0, None, UserMediaStatus.WATCHING),
            ("Planned one", 8.0, None, UserMediaStatus.PLANNED),
        ],
    )

    body = (await auth_client.get("/v1/library", params={"status": "watching"})).json()

    assert [item["media"]["title"] for item in body["items"]] == ["Watching one"]


async def test_the_library_shows_only_the_callers_rows(auth_client, db_session, auth_user):
    """user_media is the multi-user join. A missing user_id filter would leak another account's
    library, so this asserts the scoping rather than assuming it.
    """
    other = make_user(username="someone-else", email="else@example.com")
    db_session.add(other)
    await db_session.flush()
    await _seed(db_session, other.id, [("Not yours", 9.0, None, UserMediaStatus.WATCHING)])
    await _seed(db_session, auth_user.id, [("Yours", 8.0, None, UserMediaStatus.WATCHING)])

    body = (await auth_client.get("/v1/library")).json()

    assert [item["media"]["title"] for item in body["items"]] == ["Yours"]


async def test_a_cursor_from_another_sort_is_a_400(auth_client, db_session, auth_user):
    """Replaying a score cursor under sort=title would compare a number against a text column —
    no error, just quietly wrong pages. Binding the sort key into the cursor makes it a 400.
    """
    await _seed(db_session, auth_user.id, [(f"T{i}", 8.0, None, UserMediaStatus.WATCHING) for i in range(3)])
    first = (await auth_client.get("/v1/library", params={"sort": "score", "limit": 1})).json()

    response = await auth_client.get(
        "/v1/library", params={"sort": "title", "limit": 1, "cursor": first["next_cursor"]}
    )

    assert response.status_code == 400


async def test_a_garbage_cursor_is_a_400_not_a_500(auth_client):
    assert (await auth_client.get("/v1/library", params={"cursor": "!!!not-a-cursor"})).status_code == 400


@pytest.mark.parametrize("value", ["NaN", "Infinity", "-Infinity", "sNaN", "100", "-100", "1E+1000"])
async def test_an_out_of_domain_score_cursor_is_a_400(auth_client, db_session, auth_user, value):
    """Two distinct hazards, one guard. Non-finite: Postgres sorts NaN above every value, so the
    row comparison matches everything and the caller silently re-reads page one. Out of range:
    the bind inherits NUMERIC(3,1) from the COALESCE, so 100 is an asyncpg
    NumericValueOutOfRangeError — an unhandled 500 from client input.
    """
    await _seed(db_session, auth_user.id, [("T", 8.0, None, UserMediaStatus.WATCHING)])
    cursor = encode_cursor("score", value, uuid.uuid4())

    response = await auth_client.get("/v1/library", params={"sort": "score", "cursor": cursor})

    assert response.status_code == 400


@pytest.mark.parametrize(
    "params",
    [{"limit": 0}, {"limit": 101}, {"sort": "nope"}, {"status": "nope"}],
    ids=["limit-too-small", "limit-too-large", "unknown-sort", "unknown-status"],
)
async def test_invalid_query_parameters_are_422(auth_client, params):
    assert (await auth_client.get("/v1/library", params=params)).status_code == 422


# No hand-written 401 test: /v1/library carries no "{" in its path, so
# tests/test_auth_protection.py already parametrizes over it.


async def test_the_media_id_filter_returns_only_that_entry(auth_client, db_session, auth_user):
    """The detail screen's only way to ask "is this title in my library?" (decision C-C)."""
    wanted = make_media(external_id="1", title="Wanted")
    other = make_media(external_id="2", title="Other")
    db_session.add_all([wanted, other])
    await db_session.flush()
    db_session.add_all([make_user_media(auth_user.id, wanted.id), make_user_media(auth_user.id, other.id)])
    await db_session.flush()

    body = (await auth_client.get("/v1/library", params={"media_id": str(wanted.id)})).json()

    assert [item["media"]["title"] for item in body["items"]] == ["Wanted"]
    assert body["next_cursor"] is None


async def test_the_media_id_filter_answers_empty_for_a_title_not_in_the_library(auth_client, db_session, auth_user):
    """Not an error. "Not in your library" is the answer the Add button is drawn from."""
    stranger = make_media(external_id="3", title="Never added")
    db_session.add(stranger)
    await db_session.flush()

    body = (await auth_client.get("/v1/library", params={"media_id": str(stranger.id)})).json()

    assert body == {"items": [], "next_cursor": None}


async def test_the_media_id_filter_does_not_replace_the_caller_scope(auth_client, db_session, auth_user):
    """The failure this pins: a filter written as `WHERE media_id = ...` that drops the
    user_id predicate would answer with SOMEONE ELSE'S entry for a shared title — and every
    other test in this file would still pass, because they never ask for a media the caller
    does not own.
    """
    shared = make_media(external_id="4", title="Shared title")
    other_user = make_user(username="someone-else", email="else@example.com")
    db_session.add_all([shared, other_user])
    await db_session.flush()
    db_session.add(make_user_media(other_user.id, shared.id))
    await db_session.flush()

    body = (await auth_client.get("/v1/library", params={"media_id": str(shared.id)})).json()

    assert body["items"] == []
