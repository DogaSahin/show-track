import uuid
from datetime import datetime

from pydantic import BaseModel, Field

from app.groups.models import GroupRole


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
