import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, Field

from app.groups.models import GroupRole
from app.library.models import ActivityKind, UserMediaStatus
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


class MemberPreview(BaseModel):
    id: uuid.UUID
    username: str


class WatchlistPreview(BaseModel):
    media_id: uuid.UUID
    cover_image_url: str | None


class GroupSummary(GroupRead):
    """One card of the groups list: enough to draw it without a request per group. A separate
    schema rather than new GroupRead fields, so create, join and rotate keep their exact shape.
    Carries no invite code: that stays a credential only an owner reads.
    """

    my_role: GroupRole
    member_count: int
    # The owner first, then by joined_at; at most MEMBER_PREVIEW_SIZE.
    member_preview: list[MemberPreview]
    watchlist_count: int
    # The newest entries first; at most WATCHLIST_PREVIEW_SIZE.
    watchlist_preview: list[WatchlistPreview]


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


class ProposeTitleRequest(BaseModel):
    media_id: uuid.UUID


class WatchlistItem(BaseModel):
    id: uuid.UUID
    media: MediaDetail
    # Nullable per design doc §5.3: deleting your account leaves the entry standing.
    proposed_by: uuid.UUID | None
    created_at: datetime


class WatchlistPage(BaseModel):
    items: list[WatchlistItem]
    next_cursor: str | None


class ProgressEntry(BaseModel):
    """One member's position on one title. `member` NESTS FeedActor rather than carrying flat
    `user_id`/`username`, so all three group-scoped reads — feed, reviews and this — attribute a
    row the same way and the client needs one shape, not three.

    Reuses FeedActor rather than minting a structurally identical third DTO: there is no import
    cycle to dodge inside the groups domain, which is the only reason library.ReviewAuthor is a
    separate class at all.
    """

    member: FeedActor
    status: UserMediaStatus
    progress: int
