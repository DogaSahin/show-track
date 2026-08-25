import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from app.groups.models import GroupRole
from app.library.models import ActivityKind
from app.media.schemas import MediaDetail


class CreateGroupRequest(BaseModel):
    name: str = Field(min_length=1, max_length=64)


class JoinGroupRequest(BaseModel):
    # No pattern: the code is normalised before it is looked up (G-C), so rejecting a
    # hyphenated or lowercase code here would 422 exactly the input normalisation exists
    # to accept. Length is generous for the same reason.
    invite_code: str = Field(min_length=1, max_length=64)


class GroupRead(BaseModel):
    id: uuid.UUID
    name: str
    created_at: datetime


class GroupWithInvite(GroupRead):
    """Returned only to a member — on create, join and rotate. The invite code is a
    credential, so it is never part of the plain group representation.
    """

    invite_code: str
    invite_code_expires_at: datetime


class MemberRead(BaseModel):
    user_id: uuid.UUID
    username: str
    role: GroupRole
    joined_at: datetime


class FeedActor(BaseModel):
    id: uuid.UUID
    username: str


class FeedItem(BaseModel):
    """`media` is optional because an `imported` row is about N titles (S-A/S-D). A client must
    handle null rather than assuming every item has a title attached."""

    id: uuid.UUID
    actor: FeedActor
    kind: ActivityKind
    media: MediaDetail | None
    payload: dict[str, Any]
    created_at: datetime


class FeedPage(BaseModel):
    items: list[FeedItem]
    next_cursor: str | None
