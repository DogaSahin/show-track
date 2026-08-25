import uuid
from datetime import datetime, timedelta

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.groups import invites
from app.groups.models import Group, GroupMember, GroupRole
from app.users.models import User

# Bounded, not optimistic: at 60 bits a collision is not a probability worth reasoning about,
# so this exists to make the impossible case a retry rather than a 500.
_CODE_ATTEMPTS = 5


def _expiry(now: datetime) -> datetime:
    return now + timedelta(hours=get_settings().group_invite_ttl_hours)


async def _fresh_code(session: AsyncSession) -> str:
    for _ in range(_CODE_ATTEMPTS):
        code = invites.generate_code()
        if await session.scalar(select(Group.id).where(Group.invite_code == code)) is None:
            return code
    raise RuntimeError("could not allocate a unique invite code")


async def create_group(session: AsyncSession, *, name: str, owner: User, now: datetime) -> Group:
    """The creator is the owner. This and G-E's transfer are the ONLY ways to become one —
    no invite code can mint an owner, or a leaked code would hand over administrative control
    of the group rather than merely access to it.
    """
    group = Group(
        name=name,
        invite_code=await _fresh_code(session),
        invite_code_expires_at=_expiry(now),
        created_by=owner.id,
    )
    session.add(group)
    await session.flush()
    session.add(GroupMember(group_id=group.id, user_id=owner.id, role=GroupRole.OWNER))
    await session.flush()
    return group


async def resolve_invite_code(session: AsyncSession, code: str, *, now: datetime) -> Group | None:
    """None for unknown AND for expired — deliberately indistinguishable.

    An expired code that reported itself as expired would confirm the group exists and that
    you were merely too late, which is the oracle G-D closes everywhere else.

    Matched by EQUALITY on the normalised value, never a prefix or LIKE. normalise_code("")
    and normalise_code("---") both return "", and equality against a column whose every value
    is 12 characters can never match that; a prefix match would match everything.
    """
    group = await session.scalar(select(Group).where(Group.invite_code == invites.normalise_code(code)))
    if group is None or group.invite_code_expires_at <= now:
        return None
    return group


async def add_member(session: AsyncSession, *, group_id: uuid.UUID, user_id: uuid.UUID, role: GroupRole) -> GroupMember:
    member = GroupMember(group_id=group_id, user_id=user_id, role=role)
    session.add(member)
    await session.flush()
    return member


async def join_by_code(session: AsyncSession, *, code: str, user: User, now: datetime) -> tuple[Group | None, bool]:
    """Returns (group, joined). `joined` is False when the caller was already a member.

    Idempotent (decision G-I), matching decision 4-D under which POST /v1/library returns 200
    rather than 409 for a title already tracked: re-pasting a code you already used is not a
    failure.
    """
    group = await resolve_invite_code(session, code, now=now)
    if group is None:
        return None, False

    existing = await session.scalar(
        select(GroupMember).where(GroupMember.group_id == group.id, GroupMember.user_id == user.id)
    )
    if existing is not None:
        return group, False

    try:
        # A SAVEPOINT, not a bare insert: Postgres aborts the whole transaction on a constraint
        # violation, so SOMETHING has to unwind before the next statement. `session.rollback()`
        # would unwind the CALLER's transaction — this service leaves commit and rollback to the
        # route everywhere else, and Task 6 creates a user and joins a group in one request, so a
        # lost race there would silently discard the new user and still answer 200. begin_nested
        # scopes the unwind to the failed insert, leaving the enclosing transaction and the
        # already-loaded `group` intact.
        async with session.begin_nested():
            await add_member(session, group_id=group.id, user_id=user.id, role=GroupRole.MEMBER)
    except IntegrityError:
        # Two concurrent joins with the same code. The constraint is the arbiter; the loser
        # simply reports "already a member", which is the same answer it would have got a
        # millisecond earlier.
        return group, False
    return group, True


async def list_groups(session: AsyncSession, *, user_id: uuid.UUID) -> list[Group]:
    statement = (
        select(Group)
        .join(GroupMember, GroupMember.group_id == Group.id)
        .where(GroupMember.user_id == user_id)
        .order_by(Group.created_at.asc(), Group.id.asc())
    )
    return list(await session.scalars(statement))


async def list_members(session: AsyncSession, *, group_id: uuid.UUID) -> list[tuple[GroupMember, User]]:
    statement = (
        select(GroupMember, User)
        .join(User, User.id == GroupMember.user_id)
        .where(GroupMember.group_id == group_id)
        .order_by(GroupMember.joined_at.asc(), GroupMember.id.asc())
    )
    return list((await session.execute(statement)).all())


async def rotate_invite_code(session: AsyncSession, *, group: Group, now: datetime) -> Group:
    """Rotation is the revocation mechanism — there is no per-invite tracking. It issues a new
    expiry as well as a new code, so an owner adding a seventh housemate next month just rotates.
    """
    group.invite_code = await _fresh_code(session)
    group.invite_code_expires_at = _expiry(now)
    await session.flush()
    return group
