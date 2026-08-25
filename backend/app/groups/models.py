import enum
import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UUIDPrimaryKeyMixin, enum_column


class GroupRole(enum.StrEnum):
    OWNER = "owner"
    MEMBER = "member"


class Group(UUIDPrimaryKeyMixin, Base):
    """A closed group: a household, a couple, a friend group.

    Membership IS the relationship — there is no follow graph and no privacy tier, so every
    social read downstream is `WHERE user_id IN (SELECT user_id FROM group_members ...)`.
    """

    __tablename__ = "groups"

    name: Mapped[str] = mapped_column(String(64), nullable=False)
    # String(16) for a 12-character code: room to lengthen without a migration.
    invite_code: Mapped[str] = mapped_column(String(16), nullable=False, unique=True)
    # NOT NULL, and there is deliberately no "never expires" value. A server REGISTRATION_CODE
    # lives in a .env on your own hardware; an invite code gets pasted into a chat and stays in
    # someone's history forever — and since Phase 7.5a it can create an account. A household joins
    # within days, so a code that outlives that window is risk with no compensating use.
    invite_code_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # Nullable + SET NULL: deleting your account must not delete a group other people are using.
    created_by: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())


class GroupMember(UUIDPrimaryKeyMixin, Base):
    __tablename__ = "group_members"

    group_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), nullable=False)
    # Explicitly indexed: this is the "my groups" lookup, and the composite unique below is a
    # prefix index over group_id, which does not serve it.
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    role: Mapped[GroupRole] = mapped_column(enum_column(GroupRole, "role"), nullable=False)
    # NOT an audit column: this is the tiebreak that decides who inherits ownership when the
    # owner leaves (decision G-E). The transfer orders by (joined_at ASC, id ASC); the id makes
    # that ordering TOTAL, not correct. `now()` is transaction-start time in Postgres, so two
    # people inserted inside ONE transaction share a joined_at and the winner falls through to a
    # random uuid4 — arbitrary, not the earliest joiner. In production every join is its own
    # request and its own transaction, so joined_at really does separate them; only tests hit
    # the degenerate case, which is why they set joined_at explicitly.
    joined_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())

    __table_args__ = (UniqueConstraint("group_id", "user_id"),)


class GroupWatchlist(UUIDPrimaryKeyMixin, Base):
    """Titles a group has proposed to watch together.

    CASCADES when its group is deleted — which remove_member does when the last member leaves.
    That consequence was written into remove_member's docstring in 7.5a specifically so it would
    not be a surprise here: the shared list dies with the last person to walk out, and there is no
    confirmation step.
    """

    __tablename__ = "group_watchlist"

    group_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("groups.id", ondelete="CASCADE"), nullable=False)
    media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False)
    # Nullable + SET NULL per design doc §5.3: deleting your account must not erase the list the
    # group built.
    proposed_by: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())

    __table_args__ = (UniqueConstraint("group_id", "media_id"),)
