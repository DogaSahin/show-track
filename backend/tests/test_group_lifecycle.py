import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from app.groups.models import Group, GroupMember, GroupRole
from tests.factories import make_group, make_group_member, make_user


async def _group_with(db_session, auth_user, *, my_role, others):
    """A group containing the fixture user plus `others` = [(username, joined_offset_minutes)],
    listed in ascending join order.

    Two things are pinned here rather than left to the defaults, and both defend the same
    assertion — that ownership goes to the LONGEST-STANDING member.

    `joined_at`, because `server_default=func.now()` renders `transaction_timestamp()`, which is
    frozen for the life of a transaction, and conftest runs each test inside one. Every row would
    otherwise share a `joined_at` and the ordering would resolve on the `id` tiebreak instead.

    `id`, in REVERSE `joined_at` order — the earliest joiner gets the LARGEST id. `id`'s
    `uuid.uuid4` default is client-side, so an explicit value wins. This makes the two orderings
    disagree on purpose: the real `(joined_at, id)` promotes the earliest joiner, while a
    regression that dropped `joined_at` and ordered on the unique `id` alone promotes the NEWEST
    and fails every time. With random uuid4s that regression passes on roughly half of runs.
    """
    group = make_group(created_by=auth_user.id)
    db_session.add(group)
    await db_session.flush()
    base = datetime(2026, 1, 1, tzinfo=UTC)
    # Rank 0 is the fixture user (earliest); it takes the largest id, each later row a smaller one.
    ids = [uuid.UUID(int=len(others) + 1 - rank) for rank in range(len(others) + 1)]
    db_session.add(make_group_member(group.id, auth_user.id, id=ids[0], role=my_role, joined_at=base))
    made = []
    for rank, (name, offset) in enumerate(others, start=1):
        user = make_user(username=name, email=f"{name}@example.com")
        db_session.add(user)
        await db_session.flush()
        db_session.add(
            make_group_member(
                group.id,
                user.id,
                id=ids[rank],
                role=GroupRole.MEMBER,
                joined_at=base + timedelta(minutes=offset),
            )
        )
        made.append(user)
    await db_session.flush()
    return group, made


async def test_the_owner_leaving_hands_ownership_to_the_longest_standing_member(auth_client, auth_user, db_session):
    """Not the newest, not arbitrary. Without this rule the group reaches a state where nobody
    can rotate the code or remove anyone — and with an expiring code, nobody can join either."""
    group, (early, late) = await _group_with(
        db_session, auth_user, my_role=GroupRole.OWNER, others=[("early", 10), ("late", 20)]
    )

    response = await auth_client.delete(f"/v1/groups/{group.id}/members/{auth_user.id}")

    assert response.status_code == 204
    rows = {
        m.user_id: m.role for m in await db_session.scalars(select(GroupMember).where(GroupMember.group_id == group.id))
    }
    assert rows[early.id] is GroupRole.OWNER
    assert rows[late.id] is GroupRole.MEMBER
    assert auth_user.id not in rows


async def test_the_last_member_leaving_deletes_the_group(auth_client, auth_user, db_session):
    group, _ = await _group_with(db_session, auth_user, my_role=GroupRole.OWNER, others=[])

    response = await auth_client.delete(f"/v1/groups/{group.id}/members/{auth_user.id}")

    assert response.status_code == 204
    assert await db_session.get(Group, group.id) is None


async def test_the_owner_can_remove_another_member(auth_client, auth_user, db_session):
    group, (other,) = await _group_with(db_session, auth_user, my_role=GroupRole.OWNER, others=[("other", 10)])

    response = await auth_client.delete(f"/v1/groups/{group.id}/members/{other.id}")

    assert response.status_code == 204
    remaining = list(await db_session.scalars(select(GroupMember).where(GroupMember.group_id == group.id)))
    assert [m.user_id for m in remaining] == [auth_user.id]


async def test_a_member_cannot_remove_someone_else(auth_client, auth_user, db_session):
    group, (other,) = await _group_with(db_session, auth_user, my_role=GroupRole.MEMBER, others=[("other", 10)])

    response = await auth_client.delete(f"/v1/groups/{group.id}/members/{other.id}")

    assert response.status_code == 403


async def test_a_member_can_always_remove_themselves(auth_client, auth_user, db_session):
    group, _ = await _group_with(db_session, auth_user, my_role=GroupRole.MEMBER, others=[("owner2", 10)])

    response = await auth_client.delete(f"/v1/groups/{group.id}/members/{auth_user.id}")

    assert response.status_code == 204


async def test_removing_someone_who_is_not_a_member_is_a_404(auth_client, auth_user, db_session):
    group, _ = await _group_with(db_session, auth_user, my_role=GroupRole.OWNER, others=[])
    outsider = make_user(username="outsider", email="outsider@example.com")
    db_session.add(outsider)
    await db_session.flush()

    response = await auth_client.delete(f"/v1/groups/{group.id}/members/{outsider.id}")

    assert response.status_code == 404
