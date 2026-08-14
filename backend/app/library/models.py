import enum
import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    UniqueConstraint,
    func,
    text,
)
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
