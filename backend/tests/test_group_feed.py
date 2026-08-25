from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import select

from app.groups.models import GroupMember, GroupRole
from app.groups.service import parse_created_at
from app.library.models import ActivityKind
from tests.factories import make_activity, make_group, make_group_member, make_media, make_user


async def _group_with_me(db_session, auth_user):
    group = make_group(created_by=auth_user.id)
    db_session.add(group)
    await db_session.flush()
    db_session.add(make_group_member(group.id, auth_user.id, role=GroupRole.OWNER))
    await db_session.flush()
    return group


async def _member(db_session, group, name):
    user = make_user(username=name, email=f"{name}@example.com")
    db_session.add(user)
    await db_session.flush()
    db_session.add(make_group_member(group.id, user.id, role=GroupRole.MEMBER))
    await db_session.flush()
    return user


async def test_the_feed_shows_activity_by_every_member(auth_client, auth_user, db_session):
    group = await _group_with_me(db_session, auth_user)
    other = await _member(db_session, group, "ada")
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    base = datetime(2026, 1, 1, tzinfo=UTC)
    db_session.add_all(
        [
            make_activity(auth_user.id, media_id=media.id, created_at=base),
            make_activity(other.id, media_id=media.id, created_at=base + timedelta(minutes=1)),
        ]
    )
    await db_session.flush()

    # A cold session, the same pin test_reviews.py's
    # test_the_group_read_attributes_each_review_to_its_own_author carries. `list_feed`'s explicit
    # joins on User and Media are load-bearing: a lazy many-to-one on the target's PRIMARY KEY takes
    # SQLAlchemy's `load_on_pk_identity` shortcut, resolves with zero IO while the row happens to be
    # in the map, and raises MissingGreenlet — a production 500 — the moment it is not. Measured: a
    # relationship-based list_feed passes every OTHER test in this file, because the seeding session
    # is the route's. Drift protection, not a live bug.
    # FRAGILE IN A NON-OBVIOUS WAY: works only because ada is NOT the authenticated user —
    # get_current_user re-SELECTs the bearer token's user back into the map mid-request.
    db_session.expunge_all()

    body = (await auth_client.get(f"/v1/groups/{group.id}/feed")).json()

    assert [i["actor"]["username"] for i in body["items"]] == ["ada", "fixture-user"]
    # The populated half of the LEFT join. `media is null` is asserted elsewhere for the one kind
    # that carries no title; without this, a join that matches NOTHING — a mistyped ON clause, a
    # dropped to_detail — ships green with every non-imported item arriving titleless.
    assert [i["media"]["id"] for i in body["items"]] == [str(media.id), str(media.id)]


async def test_an_import_summary_survives_the_media_join(auth_client, auth_user, db_session):
    """Decision S-H. `imported` rows carry media_id = NULL; an INNER join silently drops every
    one of them, and the feature looks like it works with one kind quietly missing."""
    group = await _group_with_me(db_session, auth_user)
    db_session.add(make_activity(auth_user.id, media_id=None, kind=ActivityKind.IMPORTED, payload={"count": 412}))
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/feed")).json()

    assert len(body["items"]) == 1
    assert body["items"][0]["kind"] == "imported"
    assert body["items"][0]["media"] is None
    assert body["items"][0]["payload"] == {"count": 412}


async def test_paging_the_feed_yields_every_row_exactly_once(auth_client, auth_user, db_session):
    group = await _group_with_me(db_session, auth_user)
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    base = datetime(2026, 1, 1, tzinfo=UTC)
    db_session.add_all(
        [make_activity(auth_user.id, media_id=media.id, created_at=base + timedelta(minutes=n)) for n in range(5)]
    )
    await db_session.flush()

    first = (await auth_client.get(f"/v1/groups/{group.id}/feed?limit=2")).json()
    second = (await auth_client.get(f"/v1/groups/{group.id}/feed?limit=2&cursor={first['next_cursor']}")).json()
    third = (await auth_client.get(f"/v1/groups/{group.id}/feed?limit=2&cursor={second['next_cursor']}")).json()

    ids = [i["id"] for i in first["items"] + second["items"] + third["items"]]
    assert len(ids) == 5, "a page was skipped"
    assert len(set(ids)) == 5, "a row was returned twice"
    assert third["next_cursor"] is None


async def test_a_member_who_joins_today_sees_older_activity(auth_client, auth_user, db_session):
    """Read-fanout (design doc §5.3): membership resolves at query time, so there is no history
    to backfill."""
    group = await _group_with_me(db_session, auth_user)
    other = await _member(db_session, group, "ada")
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    db_session.add(make_activity(other.id, media_id=media.id, created_at=datetime(2020, 1, 1, tzinfo=UTC)))
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/feed")).json()

    assert len(body["items"]) == 1


async def test_another_groups_activity_is_never_in_this_groups_feed(auth_client, auth_user, db_session):
    """The membership subquery, pinned directly — mirroring
    test_another_groups_tracker_is_never_in_this_groups_comparison in test_group_progress.py.

    Every other scoping test in this file is structurally blind to the leak this one catches:
    they either put everyone in the SAME group, or (the leaver test) delete the membership row so
    the user then belongs to NO group. `Activity.user_id.in_(members)` with the
    `GroupMember.group_id == group_id` clause deleted still excludes a user in no group at all,
    so all of them stay green. A user who is a member of a DIFFERENT group is the one case that
    separates "activity by members of this group" from "activity by anybody who is in some
    group", and measured: without this test the whole suite passes with the scope removed, while
    zed's activity is served into every group's feed on the instance.
    """
    group = await _group_with_me(db_session, auth_user)
    zed = make_user(username="zed", email="zed@example.com")
    db_session.add(zed)
    await db_session.flush()
    elsewhere = make_group(name="Elsewhere", invite_code="ZZZZZZZZ9876", created_by=zed.id)
    db_session.add(elsewhere)
    await db_session.flush()
    db_session.add(make_group_member(elsewhere.id, zed.id, role=GroupRole.OWNER))
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    base = datetime(2026, 1, 1, tzinfo=UTC)
    db_session.add_all(
        [
            make_activity(zed.id, media_id=media.id, created_at=base + timedelta(minutes=1)),
            make_activity(auth_user.id, media_id=media.id, created_at=base),
        ]
    )
    await db_session.flush()

    body = (await auth_client.get(f"/v1/groups/{group.id}/feed")).json()

    assert [i["actor"]["username"] for i in body["items"]] == ["fixture-user"]


async def test_a_member_who_leaves_disappears_from_the_feed(auth_client, auth_user, db_session):
    """The other half of read-fanout: leaving revokes instantly, with no purge."""
    group = await _group_with_me(db_session, auth_user)
    other = await _member(db_session, group, "ada")
    media = make_media()
    db_session.add(media)
    await db_session.flush()
    db_session.add(make_activity(other.id, media_id=media.id))
    await db_session.flush()
    assert len((await auth_client.get(f"/v1/groups/{group.id}/feed")).json()["items"]) == 1

    membership = await db_session.scalar(
        select(GroupMember).where(GroupMember.group_id == group.id, GroupMember.user_id == other.id)
    )
    await db_session.delete(membership)
    await db_session.flush()

    assert (await auth_client.get(f"/v1/groups/{group.id}/feed")).json()["items"] == []


async def test_a_malformed_cursor_is_a_400_not_a_500(auth_client, auth_user, db_session):
    group = await _group_with_me(db_session, auth_user)

    response = await auth_client.get(f"/v1/groups/{group.id}/feed?cursor=not-a-cursor")

    assert response.status_code == 400
    assert "not-a-cursor" not in response.text, "do not echo client input back"


@pytest.mark.parametrize(
    "raw",
    [
        # Naive: asyncpg binds it against timestamptz in the SERVER's timezone, so the keyset
        # walks a window offset by that zone's UTC offset — rows silently skipped, no error, and
        # a different window again after a DST change.
        "2026-01-01T00:00:00",
        # datetime.min encodes as `-infinity`, which sorts below every row and makes the
        # descending comparison match all of them. Same failure mode at the top end.
        "0001-01-01T00:00:00+00:00",
        "9999-12-31T00:00:00+00:00",
    ],
)
def test_parse_created_at_rejects_a_cursor_outside_the_columns_domain(raw):
    """ValueError, not a bare parse: decode_cursor turns it into InvalidCursor and the route into
    a 400. Cursors are unsigned and documented as opaque rather than secret, so every one of
    these is reachable by hand."""
    with pytest.raises(ValueError):
        parse_created_at(raw)


def test_parse_created_at_accepts_an_aware_datetime_in_range():
    """The other half: the guards must not reject what encode_cursor legitimately emits."""
    assert parse_created_at("2026-01-01T00:00:00+00:00") == datetime(2026, 1, 1, tzinfo=UTC)
