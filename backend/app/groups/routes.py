import uuid
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, HTTPException, Query, Response, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.groups import service
from app.groups.dependencies import GroupMemberDep, GroupOwnerDep
from app.groups.models import Group
from app.groups.schemas import (
    CreateGroupRequest,
    FeedPage,
    GroupRead,
    GroupWithInvite,
    JoinGroupRequest,
    MemberRead,
)
from app.library.schemas import ReviewRead
from app.pagination import InvalidCursor, decode_cursor
from app.users.dependencies import get_current_user
from app.users.models import User

router = APIRouter(prefix="/groups", tags=["groups"])

# Depends inside Annotated, not a default value: ruff B008. Same convention as library/routes.py.
SessionDep = Annotated[AsyncSession, Depends(get_session)]
CurrentUserDep = Annotated[User, Depends(get_current_user)]

_INVALID_CODE = "invalid invite code"


@router.post("", response_model=GroupWithInvite, status_code=status.HTTP_201_CREATED)
async def create_group(
    payload: CreateGroupRequest, session: SessionDep, current_user: CurrentUserDep
) -> GroupWithInvite:
    group = await service.create_group(session, name=payload.name, owner=current_user, now=datetime.now(tz=UTC))
    await session.commit()
    return GroupWithInvite.model_validate(group, from_attributes=True)


@router.get("", response_model=list[GroupRead])
async def list_my_groups(session: SessionDep, current_user: CurrentUserDep) -> list[GroupRead]:
    """A plain list, not {items, next_cursor} — decision G-H. Architecture rule 4 governs
    collections that grow without bound; the number of groups one person belongs to does not,
    and the design doc's §8 API table already types this endpoint as `list`.
    """
    groups = await service.list_groups(session, user_id=current_user.id)
    return [GroupRead.model_validate(g, from_attributes=True) for g in groups]


@router.post("/join", response_model=GroupWithInvite)
async def join_group(payload: JoinGroupRequest, session: SessionDep, current_user: CurrentUserDep) -> GroupWithInvite:
    group, _ = await service.join_by_code(
        session, code=payload.invite_code, user=current_user, now=datetime.now(tz=UTC)
    )
    if group is None:
        # One generic message for wrong, unknown AND expired — see resolve_invite_code.
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=_INVALID_CODE)
    await session.commit()
    return GroupWithInvite.model_validate(group, from_attributes=True)


@router.get("/{group_id}/members", response_model=list[MemberRead])
async def list_members(group_id: uuid.UUID, session: SessionDep, member: GroupMemberDep) -> list[MemberRead]:
    rows = await service.list_members(session, group_id=group_id)
    return [MemberRead(user_id=m.user_id, username=u.username, role=m.role, joined_at=m.joined_at) for m, u in rows]


@router.post("/{group_id}/invite/rotate", response_model=GroupWithInvite)
async def rotate_invite(group_id: uuid.UUID, session: SessionDep, owner: GroupOwnerDep) -> GroupWithInvite:
    """require_ownership proves the group existed and that the caller owned it when the
    dependency ran — not that it still exists now. The last member leaving deletes the group
    outright (G-E), so between the dependency's SELECT and this one the row can be gone, and an
    unchecked load would 500 with an AttributeError inside rotate_invite_code. Same 404, same
    body as the dependency's: a group that no longer exists is indistinguishable from one the
    caller was never in, which is the answer G-D wants anyway.
    """
    group = await session.get(Group, group_id)
    if group is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="no such group")
    await service.rotate_invite_code(session, group=group, now=datetime.now(tz=UTC))
    await session.commit()
    return GroupWithInvite.model_validate(group, from_attributes=True)


@router.delete("/{group_id}/members/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def remove_member(
    group_id: uuid.UUID, user_id: uuid.UUID, session: SessionDep, member: GroupMemberDep
) -> Response:
    """Any member may remove themselves; only the owner may remove anybody else.

    Takes GroupMemberDep, not GroupOwnerDep — the role check is conditional on WHO is being
    removed, so it belongs in the service beside the lifecycle rules rather than in a
    dependency that cannot see the target.
    """
    try:
        await service.remove_member(session, group_id=group_id, actor=member, target_user_id=user_id)
    except service.NotPermitted as exc:
        raise HTTPException(status_code=status.HTTP_403_FORBIDDEN, detail="only the group owner may do that") from exc
    except service.NotAMember as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="no such group") from exc

    await session.commit()
    return Response(status_code=status.HTTP_204_NO_CONTENT)


@router.get("/{group_id}/feed", response_model=FeedPage, responses={400: {"description": "unusable cursor"}})
async def group_feed(
    group_id: uuid.UUID,
    session: SessionDep,
    member: GroupMemberDep,
    limit: Annotated[int, Query(ge=1, le=100)] = 20,
    cursor: Annotated[str | None, Query(max_length=2048)] = None,
) -> FeedPage:
    """`member` is unused in the body and that is the point: taking GroupMemberDep is what
    authorizes this route, and 7.5a's route-table walk asserts it is present on every path under
    /v1/groups/{group_id}.
    """
    decoded = None
    if cursor is not None:
        try:
            decoded = decode_cursor(cursor, service.FEED_SORT_KEY, service.parse_created_at)
        except InvalidCursor as exc:
            # A fixed detail, not str(exc): the message would echo client-supplied cursor content.
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="invalid cursor") from exc

    items, next_cursor = await service.list_feed(
        session, group_id=group_id, limit=limit, cursor=decoded, now=datetime.now(tz=UTC)
    )
    return FeedPage(items=items, next_cursor=next_cursor)


@router.get("/{group_id}/media/{media_id}/reviews", response_model=list[ReviewRead])
async def group_reviews(
    group_id: uuid.UUID, media_id: uuid.UUID, session: SessionDep, member: GroupMemberDep
) -> list[ReviewRead]:
    """`member` is unused in the body for the same reason it is on group_feed above: taking
    GroupMemberDep is what authorizes this route, and 7.5a's walk asserts it is present on every
    path under /v1/groups/{group_id}.
    """
    return await service.list_group_reviews(session, group_id=group_id, media_id=media_id)
