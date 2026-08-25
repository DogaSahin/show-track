import uuid
from typing import Annotated

from fastapi import Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.groups.models import GroupMember, GroupRole
from app.users.dependencies import get_current_user
from app.users.models import User

# ONE exit for every failure: unknown group, non-member, or an id that matches nothing.
# Phase 2 returns a single 401 for every authentication failure because "distinguishing them
# would tell an attacker which of their guesses was closer"; the same reasoning applies to
# group existence. A 403 on a real group and a 404 on a fake one is an oracle telling a prober
# which ids are real.
_NO_SUCH_GROUP = HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="no such group")


async def require_membership(
    group_id: uuid.UUID,
    session: Annotated[AsyncSession, Depends(get_session)],
    current_user: Annotated[User, Depends(get_current_user)],
) -> GroupMember:
    """Assert the caller belongs to `{group_id}` and hand back their membership row.

    Returns the ROW, not a boolean, because it carries `role` — which require_ownership then
    checks without a second query, and which every 7.5b route would otherwise re-derive.
    """
    member = await session.scalar(
        select(GroupMember).where(GroupMember.group_id == group_id, GroupMember.user_id == current_user.id)
    )
    if member is None:
        raise _NO_SUCH_GROUP
    return member


GroupMemberDep = Annotated[GroupMember, Depends(require_membership)]


async def require_ownership(member: GroupMemberDep) -> GroupMember:
    """403, not 404: the caller has already PROVEN membership, so nothing is left to hide and
    the error can be truthful and useful.
    """
    # `!=`, not `is not`: a GroupMember built in Python with role="owner" (a plain str, which
    # SQLAlchemy binds happily) comes back from the identity map as that same instance, with
    # `.role` still a bare str — and an identity check would 403 a real owner. GroupRole is a
    # StrEnum, so equality holds for both forms and nothing is lost.
    if member.role != GroupRole.OWNER:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="only the group owner may do that")
    return member


GroupOwnerDep = Annotated[GroupMember, Depends(require_ownership)]
