import enum
import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UUIDPrimaryKeyMixin, enum_column


class UserMediaStatus(enum.StrEnum):
    WATCHING = "watching"
    COMPLETED = "completed"
    DROPPED = "dropped"
    PLANNED = "planned"
    # Distinct from DROPPED on purpose. AniList models on-hold separately and so does every
    # other tracker; collapsing the two destroys information that cannot be recovered after
    # an import has run.
    PAUSED = "paused"


class UserMedia(UUIDPrimaryKeyMixin, Base):
    """A library entry: the join that makes the schema multi-user-ready. Your list is just
    the rows scoped to your user_id, so adding a second user touches zero schema.
    """

    __tablename__ = "user_media"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # Explicitly indexed: Postgres does not auto-index foreign keys, and the composite
    # unique below is a prefix index that covers user_id but not media_id.
    media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False, index=True)
    status: Mapped[UserMediaStatus] = mapped_column(enum_column(UserMediaStatus, "status"), nullable=False)
    # NUMERIC, not float: binary floating point cannot represent 7.1 exactly, so equality
    # comparisons and the AVG() behind the Profile stats screen would drift.
    score: Mapped[Decimal | None] = mapped_column(Numeric(3, 1), nullable=True)
    progress: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    favorite: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    # SQLAlchemy is what adds `updated_at` to the SET clause of every UPDATE it emits; the
    # timestamp itself is Postgres's, because `func.now()` renders inline as SQL
    # (`SET progress=%(progress)s, updated_at=now()`). Supplying the assignment is the
    # client-side half, so a raw SQL UPDATE that does not name the column will not bump it.
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (
        UniqueConstraint("user_id", "media_id"),
        # Enforced in the database, not only in Pydantic, because the Phase 4 AniList
        # importer bypasses the API schema entirely and must convert from whichever scale
        # the user picked (POINT_100, POINT_10_DECIMAL, POINT_5, POINT_3).
        #
        # The `score IS NULL OR` clause is documentation, not logic: a CHECK passes when it
        # evaluates to UNKNOWN, so a NULL score would satisfy the range test on its own.
        # Spelling it out stops the next reader assuming the constraint made score required.
        CheckConstraint("score IS NULL OR (score >= 1.0 AND score <= 10.0)", name="score_range"),
    )


class ActivityKind(enum.StrEnum):
    ADDED = "added"
    # A sixth kind beyond the task breakdown's five (decision S-A). The AniList importer is
    # bounded at MAX_LIST_CHUNKS x PER_CHUNK = 10,000 entries; one row per title would let a
    # single import bury every other member's activity for as far back as anyone scrolls, and
    # read-fanout means there is no per-group copy to prune.
    IMPORTED = "imported"
    PROGRESSED = "progressed"
    RATED = "rated"
    COMPLETED = "completed"
    DROPPED = "dropped"


class Activity(UUIDPrimaryKeyMixin, Base):
    """What a user did, as a log rather than a view of current state.

    Not group-scoped, deliberately (design doc §5.3): the group feed is "activity by members of
    group G", resolved by joining `group_members` at read time. Write-fanout would make a user in
    three groups accumulate three rows per action, joining need a backfill and leaving need a purge.
    """

    __tablename__ = "activity"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # NULLABLE, and this is decision S-A showing up in the schema: an `imported` row is about N
    # titles, not one. The task breakdown specified this column without nullability because it
    # assumed one row per title. A sentinel media row or a second table would both be worse than
    # a nullable column that exactly one kind uses.
    media_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=True)
    kind: Mapped[ActivityKind] = mapped_column(enum_column(ActivityKind, "kind"), nullable=False)
    # The project's first JSONB column. Contents are a per-kind contract (decision S-M), enforced
    # by the emitting code rather than by the schema — JSONB accepts anything.
    payload: Mapped[dict] = mapped_column(JSONB, nullable=False, server_default=text("'{}'::jsonb"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())

    __table_args__ = (
        # Leads with user_id because the feed's WHERE is always the read-fanout membership
        # subquery; the rest is the composite the feed cursor pages over.
        Index("ix_activity_user_id_created_at_id", "user_id", text("created_at DESC"), text("id DESC")),
    )


class Review(UUIDPrimaryKeyMixin, Base):
    """One review per person per title, edited rather than appended."""

    __tablename__ = "reviews"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    # Explicitly indexed: the group-scoped read filters on media_id and joins group_members. The
    # composite unique below is a user_id-prefix index and does not serve that.
    media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False, index=True)
    # No DB-level length cap; capped in Pydantic instead (decision S-N). Same reasoning 7.5a used
    # for groups.name: nothing writes a review except this one endpoint. UserMedia.score got a
    # CHECK because the AniList importer bypasses the API schema entirely; reviews have no such path.
    body: Mapped[str] = mapped_column(Text, nullable=False)
    contains_spoilers: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("false"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (UniqueConstraint("user_id", "media_id"),)
