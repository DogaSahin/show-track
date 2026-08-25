import uuid

import pytest
from sqlalchemy import func, select

from app.groups.models import GroupRole
from app.library import service
from app.library.models import Review
from app.media.models import Media
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


async def test_a_duplicate_does_not_unwind_the_callers_pending_work(auth_user, db_session):
    """The savepoint in `create_review` is a discipline, and a discipline with no test is a
    comment.

    Deliberately a SERVICE-level test, because a route-level one is structurally incapable of
    seeing this. `test_a_second_review_of_the_same_title_is_a_409` makes two independent HTTP
    requests and never queries afterwards, so a poisoned transaction has nothing left to damage
    and the 409 comes out identically either way — measured: replacing the `begin_nested()`
    block with a bare `await session.flush()` leaves the whole suite green.

    What the savepoint actually buys is that an IntegrityError is contained to the failed INSERT
    instead of unwinding everything the caller had pending. Without it, SQLAlchemy's failed
    flush rolls the SessionTransaction back and the caller's uncommitted work vanishes — the
    exact incident 7.5a hit, where `join_by_code`'s `session.rollback()` discarded a caller's
    pending transaction.

    Asserted through a Core `select(func.count())` rather than `session.get()`, per architecture
    rule 8: a rollback is not this session's own ORM write, so the identity map is no guide to
    what actually survived in the database.
    """
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    await service.create_review(
        db_session, user_id=auth_user.id, media_id=media.id, body="First.", contains_spoilers=False
    )

    # The caller's unrelated, pending, uncommitted work. This is what must survive.
    pending = make_media(external_id="1535", title="Death Note")
    db_session.add(pending)
    await db_session.flush()
    pending_id = pending.id

    with pytest.raises(service.ReviewExists):
        await service.create_review(
            db_session, user_id=auth_user.id, media_id=media.id, body="Second.", contains_spoilers=False
        )

    # The session is still usable at all. A real statement, NOT `flush()`: nothing is dirty,
    # new or deleted at this point, so a flush short-circuits without touching the connection and
    # would pass even on a poisoned transaction.
    assert await db_session.scalar(select(1)) == 1
    # ...and neither the caller's pending row nor the first review was rolled back with the
    # failed INSERT.
    assert await db_session.scalar(select(func.count()).select_from(Media).where(Media.id == pending_id)) == 1
    assert await db_session.scalar(select(func.count()).select_from(Review).where(Review.media_id == media.id)) == 1


async def test_reviewing_a_title_that_does_not_exist_is_a_404_not_a_409(auth_client, auth_user, db_session):
    """`reviews.media_id` is an FK, so an unknown media_id raises IntegrityError from the same
    flush a duplicate does. Collapsing both into ReviewExists tells the client it has already
    written a review that does not exist — and the obvious recovery, PATCH it or read the group
    list, then answers 404/empty. A dead end, and a 409 is not even the right shape: nothing
    conflicts.

    This is the first endpoint that takes a client-supplied internal media_id, which is why no
    earlier route had to discriminate here.
    """
    response = await auth_client.post(
        "/v1/reviews",
        json={"media_id": str(uuid.uuid4()), "body": "Ghost.", "contains_spoilers": False},
    )

    assert response.status_code == 404, "an unknown media_id must not be reported as a duplicate"
    assert response.json()["detail"] == "no such title"


async def test_a_genuine_duplicate_is_still_a_409(auth_client, auth_user, db_session):
    """The other branch of the same discrimination. Paired with the 404 test above so that
    collapsing the two cases back together cannot leave both green."""
    media = await _media(db_session)
    body = {"media_id": str(media.id), "body": "First.", "contains_spoilers": False}
    assert (await auth_client.post("/v1/reviews", json=body)).status_code == 201

    second = await auth_client.post("/v1/reviews", json=body)

    assert second.status_code == 409
    assert second.json()["detail"] == "you have already reviewed this title"


async def test_a_whitespace_only_body_is_rejected(auth_client, auth_user, db_session):
    """min_length alone does not strip, so "   " is length 3 and passes. It would then occupy the
    (user_id, media_id) slot, making the user's next real review of that title a 409 they can
    only escape via PATCH — a blank review is worse than no review.
    """
    media = await _media(db_session)

    response = await auth_client.post(
        "/v1/reviews", json={"media_id": str(media.id), "body": "   ", "contains_spoilers": False}
    )

    assert response.status_code == 422
    assert await db_session.scalar(select(func.count()).select_from(Review).where(Review.media_id == media.id)) == 0


async def test_a_body_is_stored_stripped(auth_client, auth_user, db_session):
    """The other half of strip_whitespace: it does not merely gate, it normalises what is stored,
    so the value read back is not the value sent."""
    media = await _media(db_session)

    created = (
        await auth_client.post(
            "/v1/reviews", json={"media_id": str(media.id), "body": "  Superb.  ", "contains_spoilers": False}
        )
    ).json()

    assert created["body"] == "Superb."


async def test_the_group_read_attributes_each_review_to_its_own_author(auth_client, auth_user, db_session):
    """TWO reviews by two different members, because a single-row test cannot tell "loaded the
    right author" from "loaded the only author there was".

    The `expunge_all()` below is what makes this pin the JOIN and not merely the attribution.
    Without it a lazy `Review.user` relationship passes: a many-to-one on the target's primary key
    takes SQLAlchemy's `load_on_pk_identity` identity-map shortcut and never reaches the
    connection, so seeding ada and bob through this same session hides the load entirely.
    Emptying the map first makes the route's session as cold as a real request's, and the lazy
    version then raises MissingGreenlet inside `list_group_reviews` — the production 500 itself,
    not a proxy for it.
    """
    group = await _my_group(db_session, auth_user)
    media = await _media(db_session)
    ada = make_user(username="ada", email="ada@example.com")
    bob = make_user(username="bob", email="bob@example.com")
    db_session.add_all([ada, bob])
    await db_session.flush()
    db_session.add_all(
        [
            make_group_member(group.id, ada.id, role=GroupRole.MEMBER),
            make_group_member(group.id, bob.id, role=GroupRole.MEMBER),
        ]
    )
    db_session.add_all(
        [
            make_review(ada.id, media.id, body="Ada's take."),
            make_review(bob.id, media.id, body="Bob's take."),
        ]
    )
    await db_session.flush()

    # Empty the identity map so the route cannot resolve an author without touching the database.
    # FRAGILE IN A NON-OBVIOUS WAY: this works only because ada and bob are NOT the authenticated
    # user. get_current_user re-SELECTs the bearer token's user and puts it back in the map
    # mid-request, so if a rewrite ever makes one of these authors the caller, that row silently
    # stops proving anything and this test goes green over a lazy load.
    db_session.expunge_all()

    response = await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/reviews")

    assert response.status_code == 200, "a lazy author load raises MissingGreenlet from a cold session"
    # Keyed by body, so the assertion does not depend on row order — each name must land on the
    # RIGHT review, not merely appear somewhere in the payload.
    by_body = {r["body"]: r["author"] for r in response.json()}
    assert by_body["Ada's take."]["username"] == "ada"
    assert by_body["Bob's take."]["username"] == "bob"
    assert by_body["Ada's take."]["id"] == str(ada.id)
    assert by_body["Bob's take."]["id"] == str(bob.id)


async def test_your_own_review_comes_back_with_you_as_the_author(auth_client, auth_user, db_session):
    """The own-review routes build the author from `current_user` rather than re-querying, so
    they are a separate path from the group read above and need their own assertion."""
    media = await _media(db_session)

    created = (
        await auth_client.post(
            "/v1/reviews", json={"media_id": str(media.id), "body": "Mine.", "contains_spoilers": False}
        )
    ).json()

    assert created["author"] == {"id": str(auth_user.id), "username": auth_user.username}
    assert "user_id" not in created, "author.id replaces it; a redundant wire field would drift"
