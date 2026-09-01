import uuid
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.library.models import UserMedia, UserMediaStatus
from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.pagination import encode_cursor
from app.recommendations import service
from app.recommendations.models import MediaSimilarity
from tests.factories import make_user


async def _media(session, external_id, title, genres=("mecha",)):
    row = Media(
        type=MediaType.ANIME,
        source=MediaSource.ANILIST,
        external_id=external_id,
        title=title,
        genres=list(genres),
        status=MediaStatus.FINISHED,
    )
    session.add(row)
    await session.flush()
    return row


async def _seeded_user(session, user_id, *, candidates=3):
    seed = await _media(session, "1", "seed title")
    session.add(UserMedia(user_id=user_id, media_id=seed.id, status=UserMediaStatus.COMPLETED, score=10))
    await session.flush()
    for n in range(candidates):
        candidate = await _media(session, f"10{n}", f"candidate {n}")
        session.add(
            MediaSimilarity(
                source_media_id=seed.id,
                similar_media_id=candidate.id,
                position=n,
                fetched_at=datetime.now(tz=UTC),
            )
        )
    await session.flush()
    return seed


async def test_unauthenticated_requests_are_rejected(client):
    # Must NOT request auth_user: that fixture pulls in auth_client, which mutates the shared
    # client with an Authorization header.
    response = await client.get("/v1/recommendations")
    assert response.status_code == 401


async def test_a_cold_user_gets_an_empty_page_not_an_error(auth_client, auth_user, db_session):
    response = await auth_client.get("/v1/recommendations")

    assert response.status_code == 200
    assert response.json() == {"items": [], "next_cursor": None}


async def test_each_item_explains_itself(auth_client, auth_user, db_session):
    seed = await _seeded_user(db_session, auth_user.id, candidates=1)

    body = (await auth_client.get("/v1/recommendations")).json()

    assert len(body["items"]) == 1
    reason = body["items"][0]["reason"]
    assert body["items"][0]["media"]["title"] == "candidate 0"
    assert reason["seed_media_id"] == str(seed.id)
    assert reason["seed_title"] == "seed title"
    assert reason["matched_genres"] == ["mecha"]


async def test_the_embedded_media_carries_no_field_that_cannot_be_kept_fresh(auth_client, auth_user, db_session):
    """The item embeds PersistedMedia, NOT MediaDetail, and restoring the difference is a bug.

    `status` and the next-episode block are refreshed by the sync job alone, whose worklist is
    scoped to titles that are in at least one user's library (`app/sync/service.py`). A
    recommendation candidate is by construction NOT in the reader's library, so those fields
    would be frozen at the moment the seed job created the row — and because
    `days_until_next_episode` clamps at 0, a stale one renders as "airs today" forever.

    `id` IS present and must stay: unlike a search result, a recommendation points at a persisted
    row, and the client needs the id to add it to the library.
    """
    await _seeded_user(db_session, auth_user.id, candidates=1)

    media = (await auth_client.get("/v1/recommendations")).json()["items"][0]["media"]

    assert media["id"]
    assert not {"status", "next_episode_season", "next_episode_number", "next_episode_date"} & media.keys()
    assert "days_until_next_episode" not in media


async def test_the_internal_score_is_never_serialised(auth_client, auth_user, db_session):
    await _seeded_user(db_session, auth_user.id, candidates=1)

    body = (await auth_client.get("/v1/recommendations")).json()

    assert "score" not in body["items"][0]


async def test_paging_a_stale_ranking_yields_every_row_exactly_once(auth_client, auth_user, db_session):
    """The headline test for decision 7-C. Mutating the library mid-pagination must not move the
    ranking the cursor is walking."""
    await _seeded_user(db_session, auth_user.id, candidates=4)

    first = (await auth_client.get("/v1/recommendations?limit=2")).json()
    assert first["next_cursor"] is not None

    # Make the cache stale between the two pages, in a way that genuinely REORDERS the ranking.
    # Staleness alone is not enough to test anything: four candidates whose only difference is
    # provider position re-rank to the identical sequence, so a recompute here would be invisible
    # and this test would pass with the guarantee deleted (measured — see the report). The new
    # entry therefore carries an edge of its own, pointing at the LAST candidate from position 0,
    # which lifts it to rank 0 and pushes everything the first page did not return down one rank.
    # A cursor-bearing request must ignore all of it.
    extra = await _media(db_session, "999", "newly rated")
    db_session.add(UserMedia(user_id=auth_user.id, media_id=extra.id, status=UserMediaStatus.COMPLETED, score=10))
    last = await db_session.scalar(select(Media).where(Media.external_id == "103"))
    db_session.add(
        MediaSimilarity(
            source_media_id=extra.id,
            similar_media_id=last.id,
            position=0,
            fetched_at=datetime.now(tz=UTC),
        )
    )
    await db_session.flush()

    second = (await auth_client.get(f"/v1/recommendations?limit=2&cursor={first['next_cursor']}")).json()

    ids = [item["media"]["id"] for item in first["items"] + second["items"]]
    assert len(ids) == len(set(ids)), "a recompute mid-pagination duplicated a row"
    assert len(ids) == 4, "a recompute mid-pagination skipped a row"


async def test_a_cursor_less_read_does_pick_up_a_library_change(auth_client, auth_user, db_session):
    await _seeded_user(db_session, auth_user.id, candidates=2)
    await auth_client.get("/v1/recommendations")

    third = await _media(db_session, "555", "late arrival")
    seed = await db_session.scalar(select(Media).where(Media.external_id == "1"))
    db_session.add(
        MediaSimilarity(
            source_media_id=seed.id,
            similar_media_id=third.id,
            position=2,
            fetched_at=datetime.now(tz=UTC),
        )
    )
    entry = await db_session.scalar(select(UserMedia).where(UserMedia.media_id == seed.id))
    entry.score = 9
    # Dated explicitly rather than left to onupdate=func.now(): Postgres now() is
    # transaction_timestamp(), frozen for the life of this fixture's single external transaction,
    # so the rating would land BEFORE computed_at and is_stale would answer False. Production runs
    # the rating and the read in two transactions; this reproduces what it writes.
    entry.updated_at = datetime.now(tz=UTC) + timedelta(minutes=1)
    await db_session.flush()

    body = (await auth_client.get("/v1/recommendations")).json()

    assert len(body["items"]) == 3


async def test_a_malformed_cursor_is_a_400_not_a_500(auth_client, auth_user):
    response = await auth_client.get("/v1/recommendations?cursor=not-a-cursor")

    assert response.status_code == 400
    assert "not-a-cursor" not in response.text, "do not echo client input back"

    # A cursor that DECODES but was issued for another sort. This is the input that makes the "do
    # not echo" rule bite: decode_cursor's sort-key message interpolates the client's own payload,
    # so a detail of str(exc) would reflect it straight back. The undecodable cursor above fails
    # earlier, with a message carrying no client content, and cannot detect that on its own.
    reflected = encode_cursor("pwned-sort", 1, uuid.uuid4())
    response = await auth_client.get(f"/v1/recommendations?cursor={reflected}")

    assert response.status_code == 400
    assert "pwned-sort" not in response.text, "do not echo client input back"


async def test_the_feed_shows_only_the_callers_rows(auth_client, auth_user, db_session):
    """`recommendation` is a multi-user table. The user_id filter in list_page is the only thing
    scoping it, and deleting that one line leaks every account's feed to every caller — with no
    other test noticing. Mirrors test_the_library_shows_only_the_callers_rows.
    """
    other = make_user(username="someone-else", email="else@example.com")
    db_session.add(other)
    await db_session.flush()
    await _seeded_user(db_session, auth_user.id, candidates=1)

    # The second account's ranking is built with the real recompute rather than hand-written rows,
    # so the fixture cannot drift from the shape the endpoint actually reads.
    other_seed = await _media(db_session, "2", "not yours seed")
    db_session.add(UserMedia(user_id=other.id, media_id=other_seed.id, status=UserMediaStatus.COMPLETED, score=10))
    other_candidate = await _media(db_session, "200", "not yours")
    db_session.add(
        MediaSimilarity(
            source_media_id=other_seed.id,
            similar_media_id=other_candidate.id,
            position=0,
            fetched_at=datetime.now(tz=UTC),
        )
    )
    await db_session.flush()
    await service.recompute(db_session, user_id=other.id, now=datetime.now(tz=UTC))

    body = (await auth_client.get("/v1/recommendations")).json()

    assert [item["media"]["title"] for item in body["items"]] == ["candidate 0"]


@pytest.mark.parametrize("total", [2, 3], ids=["exactly-limit", "limit-plus-one"])
async def test_the_page_boundary_is_exact(auth_client, auth_user, db_session, total):
    """`limit + 1` is the has-more probe, so the off-by-one lives exactly here. With
    `total == limit` there must be NO next_cursor: a full final page that still hands back a
    cursor walks a paginating client into an endless loop of empty pages. Mirrors
    test_the_page_boundary_is_exact in tests/test_library_listing.py.
    """
    await _seeded_user(db_session, auth_user.id, candidates=total)

    first = (await auth_client.get("/v1/recommendations?limit=2")).json()

    assert len(first["items"]) == 2
    if total == 2:
        assert first["next_cursor"] is None
    else:
        assert first["next_cursor"] is not None
        second = (await auth_client.get(f"/v1/recommendations?limit=2&cursor={first['next_cursor']}")).json()
        assert len(second["items"]) == 1
        assert second["next_cursor"] is None


@pytest.mark.parametrize(
    "value",
    ["2147483648", "-1", "not-a-number"],
    ids=["above-int4", "negative", "non-numeric"],
)
async def test_an_out_of_domain_rank_cursor_is_a_400(auth_client, auth_user, db_session, value):
    """Two hazards, one guard, and parse_rank is where both are caught. Above int4: the bind
    inherits Integer from the rank column, so the value never reaches a comparison — it is a
    driver-level overflow, an unhandled 500 from wholly client-supplied input. Negative: a
    perfectly valid int4 that matches every row, so the caller silently re-reads page one forever.
    Mirrors test_an_out_of_domain_score_cursor_is_a_400.
    """
    await _seeded_user(db_session, auth_user.id, candidates=1)
    cursor = encode_cursor(service.SORT_KEY, value, uuid.uuid4())

    response = await auth_client.get(f"/v1/recommendations?cursor={cursor}")

    assert response.status_code == 400
