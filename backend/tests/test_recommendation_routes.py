import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from app.library.models import UserMedia, UserMediaStatus
from app.media.models import Media, MediaSource, MediaStatus, MediaType
from app.pagination import encode_cursor
from app.recommendations.models import MediaSimilarity


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
