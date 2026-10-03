"""GET /v1/groups as summaries (BT-06) and GET /v1/groups/{id}/invite (BT-05)."""

import uuid
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta

from sqlalchemy import event

from app.groups.models import GroupRole
from tests.factories import make_group, make_group_member, make_media, make_user, make_watchlist_entry

BASE = datetime(2025, 3, 1, tzinfo=UTC)


async def _user(db_session, name):
    user = make_user(username=name, email=f"{name}@example.com")
    db_session.add(user)
    await db_session.flush()
    return user


async def _group(db_session, owner, *, name="Home", members=(), my_role=None, me=None, **group_fields):
    """A group owned by `owner`, with `members` joining one day apart after the owner, and `me`
    (the fixture user) joining last with `my_role` when given."""
    group = make_group(name=name, created_by=owner.id, invite_code=uuid.uuid4().hex[:12].upper(), **group_fields)
    db_session.add(group)
    await db_session.flush()
    db_session.add(make_group_member(group.id, owner.id, role=GroupRole.OWNER, joined_at=BASE))
    for offset, member in enumerate(members, start=1):
        db_session.add(make_group_member(group.id, member.id, joined_at=BASE + timedelta(days=offset)))
    if me is not None:
        db_session.add(make_group_member(group.id, me.id, role=my_role, joined_at=BASE + timedelta(days=100)))
    await db_session.flush()
    return group


async def _watchlist(db_session, group, count):
    """`count` entries proposed one hour apart; the last one added is the newest."""
    titles = []
    for index in range(count):
        media = make_media(
            external_id=uuid.uuid4().hex[:12], title=f"Title {index}", cover_image_url=f"https://c/{index}"
        )
        db_session.add(media)
        await db_session.flush()
        db_session.add(make_watchlist_entry(group.id, media.id, created_at=BASE + timedelta(hours=index)))
        titles.append(media)
    await db_session.flush()
    return titles


@contextmanager
def _counting_queries(db_session):
    statements: list[str] = []

    def count(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    engine = db_session.bind.engine.sync_engine
    event.listen(engine, "before_cursor_execute", count)
    try:
        yield statements
    finally:
        event.remove(engine, "before_cursor_execute", count)


async def test_a_summary_carries_role_counts_and_ordered_previews(auth_client, auth_user, db_session):
    owner = await _user(db_session, "owner")
    others = [await _user(db_session, f"member{index}") for index in range(4)]
    group = await _group(db_session, owner, members=others, my_role=GroupRole.MEMBER, me=auth_user)
    media = await _watchlist(db_session, group, 5)

    [summary] = (await auth_client.get("/v1/groups")).json()

    assert summary["my_role"] == "member"
    assert summary["member_count"] == 6
    # The owner first, then by joined_at, at most four: the fixture user joined last, so is cut.
    assert [m["username"] for m in summary["member_preview"]] == ["owner", "member0", "member1", "member2"]
    assert summary["watchlist_count"] == 5
    # Newest first, at most four.
    assert [w["media_id"] for w in summary["watchlist_preview"]] == [str(m.id) for m in reversed(media[1:])]
    assert summary["watchlist_preview"][0]["cover_image_url"] == "https://c/4"
    assert "invite_code" not in summary


async def test_the_owner_comes_first_even_when_someone_joined_earlier(auth_client, auth_user, db_session):
    """Ownership transfers (G-E), so the owner is not always the earliest joiner."""
    early = await _user(db_session, "early")
    group = make_group(name="Transferred", created_by=early.id, invite_code=uuid.uuid4().hex[:12].upper())
    db_session.add(group)
    await db_session.flush()
    db_session.add_all(
        [
            make_group_member(group.id, early.id, role=GroupRole.MEMBER, joined_at=BASE),
            make_group_member(group.id, auth_user.id, role=GroupRole.OWNER, joined_at=BASE + timedelta(days=9)),
        ]
    )
    await db_session.flush()

    [summary] = (await auth_client.get("/v1/groups")).json()

    assert summary["my_role"] == "owner"
    assert [m["username"] for m in summary["member_preview"]] == [auth_user.username, "early"]


async def test_an_empty_watchlist_is_zero_and_an_empty_preview(auth_client, auth_user, db_session):
    created = (await auth_client.post("/v1/groups", json={"name": "Quiet"})).json()

    [summary] = (await auth_client.get("/v1/groups")).json()

    assert summary["id"] == created["id"]
    assert summary["my_role"] == "owner"
    assert summary["member_count"] == 1
    assert summary["watchlist_count"] == 0
    assert summary["watchlist_preview"] == []


async def test_the_number_of_queries_does_not_grow_with_the_number_of_groups(auth_client, auth_user, db_session):
    owner = await _user(db_session, "owner")
    first = await _group(db_session, owner, name="First", me=auth_user, my_role=GroupRole.MEMBER)
    await _watchlist(db_session, first, 2)

    with _counting_queries(db_session) as one_group:
        assert len((await auth_client.get("/v1/groups")).json()) == 1

    for index in range(3):
        extra = await _group(db_session, owner, name=f"Extra {index}", me=auth_user, my_role=GroupRole.MEMBER)
        await _watchlist(db_session, extra, 2)

    with _counting_queries(db_session) as four_groups:
        assert len((await auth_client.get("/v1/groups")).json()) == 4

    assert one_group, "the listener saw no statements, so the comparison below would prove nothing"
    assert len(four_groups) == len(one_group)


async def test_only_the_callers_groups_and_their_rows_are_summarised(auth_client, auth_user, db_session):
    stranger = await _user(db_session, "stranger")
    theirs = await _group(db_session, stranger, name="Not mine")
    await _watchlist(db_session, theirs, 3)
    mine = (await auth_client.post("/v1/groups", json={"name": "Mine"})).json()

    listed = (await auth_client.get("/v1/groups")).json()

    assert [g["id"] for g in listed] == [mine["id"]]
    assert [m["username"] for m in listed[0]["member_preview"]] == [auth_user.username]
    assert listed[0]["watchlist_count"] == 0


async def test_the_owner_reads_the_current_code_without_rotating_it(auth_client, db_session):
    created = (await auth_client.post("/v1/groups", json={"name": "Home"})).json()

    first = await auth_client.get(f"/v1/groups/{created['id']}/invite")
    second = await auth_client.get(f"/v1/groups/{created['id']}/invite")

    assert first.status_code == 200
    assert first.json()["invite_code"] == created["invite_code"] == second.json()["invite_code"]
    assert first.json()["invite_code_expires_at"] == created["invite_code_expires_at"]
    joined = await auth_client.post("/v1/groups/join", json={"invite_code": created["invite_code"]})
    assert joined.status_code == 200


async def test_an_expired_code_comes_back_unchanged(auth_client, auth_user, db_session):
    expired_at = datetime.now(tz=UTC) - timedelta(days=2)
    group = await _group(db_session, auth_user, invite_code_expires_at=expired_at)

    response = await auth_client.get(f"/v1/groups/{group.id}/invite")

    assert response.status_code == 200
    assert response.json()["invite_code"] == group.invite_code
    assert datetime.fromisoformat(response.json()["invite_code_expires_at"]) == expired_at


async def test_a_member_who_is_not_the_owner_cannot_read_the_code(auth_client, auth_user, db_session):
    owner = await _user(db_session, "owner")
    group = await _group(db_session, owner, me=auth_user, my_role=GroupRole.MEMBER)

    response = await auth_client.get(f"/v1/groups/{group.id}/invite")

    assert response.status_code == 403
