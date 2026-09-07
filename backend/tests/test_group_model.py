import pytest
from sqlalchemy.exc import IntegrityError

from app.groups.models import GroupRole
from tests.factories import make_group, make_group_member, make_user


async def test_a_user_cannot_join_the_same_group_twice(db_session):
    """The unique constraint is what makes idempotent join a DATABASE property rather than
    application logic that a later refactor could drop."""
    user = make_user()
    group = make_group()
    db_session.add_all([user, group])
    await db_session.flush()

    db_session.add(make_group_member(group.id, user.id, role=GroupRole.OWNER))
    await db_session.flush()

    db_session.add(make_group_member(group.id, user.id, role=GroupRole.MEMBER))
    with pytest.raises(IntegrityError):
        await db_session.flush()


async def test_deleting_the_creator_leaves_the_group_standing(db_session):
    """SET NULL, not CASCADE: deleting your account must not delete a group other people use."""
    creator = make_user()
    db_session.add(creator)
    await db_session.flush()
    group = make_group(created_by=creator.id)
    db_session.add(group)
    await db_session.flush()

    await db_session.delete(creator)
    await db_session.flush()
    await db_session.refresh(group)

    assert group.created_by is None


async def test_deleting_a_group_removes_its_memberships(db_session):
    user = make_user()
    group = make_group()
    db_session.add_all([user, group])
    await db_session.flush()
    member = make_group_member(group.id, user.id, role=GroupRole.OWNER)
    db_session.add(member)
    await db_session.flush()

    await db_session.delete(group)
    await db_session.flush()

    assert await db_session.get(type(member), member.id, populate_existing=True) is None
