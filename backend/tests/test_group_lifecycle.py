from datetime import UTC, datetime, timedelta

from sqlalchemy import select

from app.groups.models import Group, GroupMember, GroupRole
from tests.factories import make_group, make_group_member, make_user


async def _group_with(db_session, auth_user, *, my_role, others):
    """A group containing the fixture user plus `others` = [(username, joined_offset_minutes)]."""
    group = make_group(created_by=auth_user.id)
    db_session.add(group)
    await db_session.flush()
    base = datetime(2026, 1, 1, tzinfo=UTC)
    db_session.add(make_group_member(group.id, auth_user.id, role=my_role, joined_at=base))
    made = []
    for name, offset in others:
        user = make_user(username=name, email=f"{name}@example.com")
        db_session.add(user)
        await db_session.flush()
        db_session.add(
            make_group_member(group.id, user.id, role=GroupRole.MEMBER, joined_at=base + timedelta(minutes=offset))
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
