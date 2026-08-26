import uuid

from app.groups.models import GroupRole
from app.library.models import UserMediaStatus
from tests.factories import make_group, make_group_member, make_media, make_user, make_user_media


async def _group_media(db_session, auth_user):
    group = make_group(created_by=auth_user.id)
    media = make_media()
    db_session.add_all([group, media])
    await db_session.flush()
    db_session.add(make_group_member(group.id, auth_user.id, role=GroupRole.OWNER))
    await db_session.flush()
    return group, media


def _names(body):
    return [entry["member"]["username"] for entry in body]


async def test_it_returns_one_entry_per_member_who_tracks_the_title(auth_client, auth_user, db_session):
    """Two exclusions in one setup: a member who tracks nothing, and the same member's row for a
    DIFFERENT title. The second is what pins `UserMedia.media_id == media_id` — without it every
    title the group tracks lands in every comparison.
    """
    group, media = await _group_media(db_session, auth_user)
    other_media = make_media(external_id="99999", title="Another Show")
    tracker = make_user(username="ada", email="ada@example.com")
    untracker = make_user(username="bob", email="bob@example.com")
    db_session.add_all([other_media, tracker, untracker])
    await db_session.flush()
    db_session.add_all(
        [
            make_group_member(group.id, tracker.id, role=GroupRole.MEMBER),
            make_group_member(group.id, untracker.id, role=GroupRole.MEMBER),
        ]
    )
    db_session.add_all(
        [
            make_user_media(tracker.id, media.id, progress=7, status=UserMediaStatus.WATCHING),
            make_user_media(tracker.id, other_media.id, progress=41, status=UserMediaStatus.WATCHING),
        ]
    )
    await db_session.flush()

    # Empty the identity map so the route cannot resolve a username without touching the
    # database. There is no relationship on UserMedia today, so this changes nothing for the
    # explicit join — it is here so that a future rewrite to a lazy `UserMedia.user` fails HERE
    # rather than in production: a many-to-one on the target's primary key takes SQLAlchemy's
    # `load_on_pk_identity` shortcut and quietly passes while the row is still in the map.
    # Verified by adding that relationship and switching the service to it: MissingGreenlet, 500.
    # This test is its home because it is the one that names the attribution — it asserts both
    # halves of `member` — and a guard parked in a test about ordering gets deleted by the next
    # person who rewrites the ordering.
    # ada must stay non-authenticated for it to hold: get_current_user re-SELECTs the bearer
    # token's user mid-request and puts it back in the map, so an assertion about `fixture-user`
    # would prove nothing here.
    db_session.expunge_all()

    body = (await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/progress")).json()

    assert _names(body) == ["ada"], "a member who does not track it is omitted"
    assert body[0]["member"]["id"] == str(tracker.id)
    assert body[0]["progress"] == 7, "the other title's row must not be reported for this one"
    assert body[0]["status"] == "watching"


async def test_progress_is_returned_raw_with_no_clamping(auth_client, auth_user, db_session):
    """Design doc §5.3: server-side clamping was rejected — it puts real logic on a hot path,
    destroys the ranking that makes "who's ahead" useful, and would have to extend to the feed to
    be coherent, since "Alex completed X" already tells you X ends.

    HONEST SCOPE: the no-clamping property this test is named for is currently UNFALSIFIABLE.
    Media carries no episode-count column — `next_episode_*` is a pointer to the next airing, not
    a total — so there is nothing server-side to clamp progress AGAINST, and no implementation of
    `compare_progress` could clamp 24 down to anything even if it tried. Do not add a column to
    make this assertable; the name records the decision, and the assertion earns its place today
    on its ordered-list form instead, which is what kills `desc` flipped to `asc`. When an
    episode-count column lands, this becomes a real assertion and should get a title whose count
    is below 24.
    """
    group, media = await _group_media(db_session, auth_user)
    ahead = make_user(username="ada", email="ada@example.com")
    db_session.add(ahead)
    await db_session.flush()
    db_session.add(make_group_member(group.id, ahead.id, role=GroupRole.MEMBER))
    db_session.add_all(
        [
            make_user_media(ahead.id, media.id, progress=24, status=UserMediaStatus.COMPLETED),
            make_user_media(auth_user.id, media.id, progress=2, status=UserMediaStatus.WATCHING),
        ]
    )
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/progress")).json()

    assert [(e["member"]["username"], e["progress"], e["status"]) for e in body] == [
        ("ada", 24, "completed"),
        ("fixture-user", 2, "watching"),
    ]


async def test_the_comparison_is_ranked_by_progress_descending(auth_client, auth_user, db_session):
    """The ordering IS the feature — "who is ahead" is the whole question — so it is asserted as a
    LIST, in order. A dict or a set comprehension over the same body cannot see it: delete the
    `order_by` and such an assertion still passes.

    Seeded in an order that is neither the ranked one nor its reverse, so both "no ORDER BY at
    all" (which falls through to insertion order) and `desc` flipped to `asc` break it.
    """
    group, media = await _group_media(db_session, auth_user)
    ada = make_user(username="ada", email="ada@example.com")
    zoe = make_user(username="zoe", email="zoe@example.com")
    db_session.add_all([ada, zoe])
    await db_session.flush()
    db_session.add_all(
        [
            make_group_member(group.id, ada.id, role=GroupRole.MEMBER),
            make_group_member(group.id, zoe.id, role=GroupRole.MEMBER),
        ]
    )
    db_session.add_all(
        [
            make_user_media(auth_user.id, media.id, progress=5),
            make_user_media(ada.id, media.id, progress=24),
            make_user_media(zoe.id, media.id, progress=12),
        ]
    )
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/progress")).json()

    assert _names(body) == ["ada", "zoe", "fixture-user"]
    assert [e["progress"] for e in body] == [24, 12, 5]


async def test_members_on_the_same_episode_are_ordered_by_username(auth_client, auth_user, db_session):
    """The common case in a group watching together: everyone is on the same episode. Progress
    alone leaves those rows in whatever order Postgres returns them, which is not stable across
    plans, so the username tiebreak is what keeps the list from reshuffling between refreshes.

    Three rows, seeded in an order that is not alphabetical, because Postgres' sort is not stable
    and a two-row assertion can hold by luck with the tiebreak removed.
    """
    group, media = await _group_media(db_session, auth_user)
    zoe = make_user(username="zoe", email="zoe@example.com")
    ada = make_user(username="ada", email="ada@example.com")
    db_session.add_all([zoe, ada])
    await db_session.flush()
    db_session.add_all(
        [
            make_group_member(group.id, zoe.id, role=GroupRole.MEMBER),
            make_group_member(group.id, ada.id, role=GroupRole.MEMBER),
        ]
    )
    db_session.add_all(
        [
            make_user_media(auth_user.id, media.id, progress=6),
            make_user_media(zoe.id, media.id, progress=6),
            make_user_media(ada.id, media.id, progress=6),
        ]
    )
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/progress")).json()

    assert _names(body) == ["ada", "fixture-user", "zoe"]


async def test_another_groups_tracker_is_never_in_this_groups_comparison(auth_client, auth_user, db_session):
    """The membership subquery, pinned directly. Task 5 shipped a live cross-group leak because
    its read paths' `group_id` scoping was asserted nowhere — the whole suite stayed green with it
    deleted. Drop `UserMedia.user_id.in_(members)` here and this endpoint reports the progress of
    every user on the instance who tracks the title, to anybody in any group.
    """
    group, media = await _group_media(db_session, auth_user)
    stranger = make_user(username="stranger", email="stranger@example.com")
    db_session.add(stranger)
    await db_session.flush()
    elsewhere = make_group(name="Elsewhere", invite_code="ZZZZZZZZ9876", created_by=stranger.id)
    db_session.add(elsewhere)
    await db_session.flush()
    db_session.add(make_group_member(elsewhere.id, stranger.id, role=GroupRole.OWNER))
    db_session.add_all(
        [
            make_user_media(stranger.id, media.id, progress=99),
            make_user_media(auth_user.id, media.id, progress=4),
        ]
    )
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/progress")).json()

    assert _names(body) == ["fixture-user"]


async def test_a_member_who_leaves_disappears_from_the_comparison(auth_client, auth_user, db_session):
    group, media = await _group_media(db_session, auth_user)
    other = make_user(username="ada", email="ada@example.com")
    db_session.add(other)
    await db_session.flush()
    membership = make_group_member(group.id, other.id, role=GroupRole.MEMBER)
    db_session.add(membership)
    db_session.add(make_user_media(other.id, media.id, progress=3))
    await db_session.flush()
    assert len((await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/progress")).json()) == 1

    await db_session.delete(membership)
    await db_session.flush()

    assert (await auth_client.get(f"/v1/groups/{group.id}/media/{media.id}/progress")).json() == []


async def test_an_untracked_title_is_an_empty_comparison_not_a_404(auth_client, auth_user, db_session):
    """200 with [], for a media_id that matches no row at all.

    Consistent with the sibling GET .../reviews, which answers the same way. The watchlist POST
    404s on an unknown media_id for a reason that does not apply to a read: it INSERTS a
    client-supplied foreign key and would otherwise surface a constraint violation as a 500.
    A read has no such failure — "nobody in this group tracks that" and "that title does not
    exist" are the same answer to the question being asked, and distinguishing them would hand a
    caller an existence oracle over the whole media table.

    Asserted because nothing else in the suite does: bolting `except MediaMissing -> 404` onto
    this route for symmetry with the watchlist POST leaves every other test green while the client
    starts getting 404s for a title nobody in the group happens to track.
    """
    group, _ = await _group_media(db_session, auth_user)
    unknown = uuid.uuid4()

    response = await auth_client.get(f"/v1/groups/{group.id}/media/{unknown}/progress")

    assert response.status_code == 200
    assert response.json() == []
