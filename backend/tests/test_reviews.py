from sqlalchemy import select

from app.groups.models import GroupRole
from app.library.models import Review
from tests.factories import make_group, make_group_member, make_media, make_review, make_user


async def _my_group(db_session, auth_user):
    group = make_group(created_by=auth_user.id)
    db_session.add(group)
    await db_session.flush()
    db_session.add(make_group_member(group.id, auth_user.id, role=GroupRole.OWNER))
    await db_session.flush()
    return group


async def _media(db_session):
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    return media


async def test_writing_a_review(auth_client, auth_user, db_session):
    media = await _media(db_session)

    response = await auth_client.post(
        "/v1/reviews",
        json={"media_id": str(media.id), "body": "Superb.", "contains_spoilers": False},
    )

    assert response.status_code == 201
    assert response.json()["body"] == "Superb."


async def test_a_second_review_of_the_same_title_is_a_409(auth_client, auth_user, db_session):
    """Deliberately NOT idempotent, unlike POST /v1/library and POST /v1/groups/join (S-K).
    Those carry no payload, so returning the existing row loses nothing. A review carries a body,
    and returning the old one would silently discard what the user just wrote. PATCH is how you
    change it."""
    media = await _media(db_session)
    body = {"media_id": str(media.id), "body": "First.", "contains_spoilers": False}
    assert (await auth_client.post("/v1/reviews", json=body)).status_code == 201

    second = await auth_client.post("/v1/reviews", json={**body, "body": "Second."})

    assert second.status_code == 409


async def test_patching_updates_in_place_and_returns_200_not_500(auth_client, auth_user, db_session):
    """PATCH edits the row rather than appending one — and the STATUS is what guards the
    `session.refresh` in `update_review`, not a timestamp comparison.

    The brief asked for `patched["updated_at"] > created["updated_at"]`. That assertion is
    unfalsifiable in this harness, for the reason `test_library_routes.py`'s
    `test_a_non_empty_patch_returns_200_and_not_500` already measured: `updated_at` carries
    `onupdate=func.now()`, Postgres' `now()` is `transaction_timestamp()`, and the whole test
    runs inside one transaction — so the INSERT and the UPDATE stamp an identical value whether
    the route is correct or broken.

    What dropping the refresh actually produces is a MissingGreenlet 500 raised AFTER a
    successful commit: the flush expires the SQL-computed `updated_at`, and serialising it is a
    synchronous lazy reload inside async code. So 200-not-500 is the falsifiable assertion.
    """
    media = await _media(db_session)
    created = (
        await auth_client.post(
            "/v1/reviews", json={"media_id": str(media.id), "body": "First.", "contains_spoilers": False}
        )
    ).json()

    response = await auth_client.patch(f"/v1/reviews/{created['id']}", json={"body": "Revised."})
    patched = response.json()

    assert response.status_code == 200, "a dropped session.refresh 500s here, after the commit"
    assert patched["id"] == created["id"], "PATCH must edit, not append"
    assert patched["body"] == "Revised."


async def test_a_user_cannot_modify_another_users_review(auth_client, auth_user, db_session):
    """404, not 403: the endpoint must not confirm which review ids exist."""
    stranger = make_user(username="stranger", email="stranger@example.com")
    media = await _media(db_session)
    db_session.add(stranger)
    await db_session.flush()
    theirs = make_review(stranger.id, media.id)
    db_session.add(theirs)
    await db_session.flush()

    response = await auth_client.patch(f"/v1/reviews/{theirs.id}", json={"body": "Hijacked."})

    assert response.status_code == 404
    assert (await db_session.scalar(select(Review).where(Review.id == theirs.id))).body != "Hijacked."


async def test_deleting_another_users_review_is_also_a_404(auth_client, auth_user, db_session):
    """DELETE is scoped by the same `get_own_review`, but it is a separate route: an ownership
    check present on PATCH and absent here would destroy data, and the PATCH test above cannot
    see it."""
    stranger = make_user(username="stranger", email="stranger@example.com")
    media = await _media(db_session)
    db_session.add(stranger)
    await db_session.flush()
    theirs = make_review(stranger.id, media.id)
    db_session.add(theirs)
    await db_session.flush()

    response = await auth_client.delete(f"/v1/reviews/{theirs.id}")

    assert response.status_code == 404
    assert await db_session.scalar(select(Review).where(Review.id == theirs.id)) is not None


async def test_deleting_your_own_review_removes_it(auth_client, auth_user, db_session):
    media = await _media(db_session)
    created = (
        await auth_client.post(
            "/v1/reviews", json={"media_id": str(media.id), "body": "Regrettable.", "contains_spoilers": False}
        )
    ).json()

    response = await auth_client.delete(f"/v1/reviews/{created['id']}")

    assert response.status_code == 204
    assert await db_session.scalar(select(Review).where(Review.id == created["id"])) is None


async def test_the_group_read_returns_reviews_by_members_only(auth_client, auth_user, db_session):
    """The group-scoped read lives under /v1/groups/{group_id} (S-C), not
    /v1/media/{id}/reviews?group_id= — 7.5a's authorization walk keys on that literal prefix and
    would not have collected the other shape."""
    group = await _my_group(db_session, auth_user)
    media = await _media(db_session)
    member = make_user(username="ada", email="ada@example.com")
    outsider = make_user(username="bob", email="bob@example.com")
    db_session.add_all([member, outsider])
    await db_session.flush()
    db_session.add(make_group_member(group.id, member.id, role=GroupRole.MEMBER))
    db_session.add_all(
        [
            make_review(member.id, media.id, body="From a member."),
            make_review(outsider.id, media.id, body="From an outsider."),
        ]
    )
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/reviews")).json()

    assert [r["body"] for r in body] == ["From a member."]
    assert isinstance(body, list), "bounded by membership, so a plain list (S-J)"


async def test_the_group_read_is_scoped_to_the_requested_title(auth_client, auth_user, db_session):
    """`media_id` is a filter, not decoration: without it the endpoint leaks every review a
    member ever wrote onto whichever title was asked for."""
    group = await _my_group(db_session, auth_user)
    media = await _media(db_session)
    other_media = make_media(external_id="1535", title="Death Note")
    db_session.add(other_media)
    await db_session.flush()
    db_session.add_all(
        [
            make_review(auth_user.id, media.id, body="About this title."),
            make_review(auth_user.id, other_media.id, body="About something else."),
        ]
    )
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/reviews")).json()

    assert [r["body"] for r in body] == ["About this title."]


async def test_patching_a_field_to_null_is_a_422_not_a_500(auth_client, auth_user, db_session):
    """Both review columns are NOT NULL and neither has a "clear it" meaning, so an explicit
    null has nothing to express. Without the schema validator it reaches the flush as an
    uncaught IntegrityError — measured as a 500, after `get_own_review` had already succeeded.
    """
    media = await _media(db_session)
    created = (
        await auth_client.post(
            "/v1/reviews", json={"media_id": str(media.id), "body": "First.", "contains_spoilers": False}
        )
    ).json()

    for field in ("body", "contains_spoilers"):
        response = await auth_client.patch(f"/v1/reviews/{created['id']}", json={field: None})
        assert response.status_code == 422, f"{field}: null answered {response.status_code}"
