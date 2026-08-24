import uuid
from datetime import datetime
from decimal import Decimal

from sqlalchemy import (
    CheckConstraint,
    DateTime,
    ForeignKey,
    Integer,
    Numeric,
    SmallInteger,
    Text,
    UniqueConstraint,
    text,
)
from sqlalchemy.dialects.postgresql import ARRAY
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UUIDPrimaryKeyMixin


class MediaSimilarity(UUIDPrimaryKeyMixin, Base):
    """ "Upstream says B is similar to A" — global provider knowledge, not per user.

    This table is what lets the per-user recompute be pure SQL over local rows, which is what
    lets it run on a read without breaking the DB-only read-path guarantee (decision 7-C).
    """

    __tablename__ = "media_similarity"

    source_media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False)
    # Explicitly indexed: Postgres does not auto-index foreign keys, and the composite unique
    # below is a prefix index covering source_media_id only. The recompute joins both ways.
    similar_media_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("media.id", ondelete="CASCADE"), nullable=False, index=True
    )
    # POSITION, not a normalised affinity. AniList reports unbounded net upvote counts and TMDB
    # reports nothing comparable; ordinal position is the only signal both express identically, so
    # the ranking fuses ranks rather than scores (decision 7-E). Storing position rather than a
    # derived affinity also means retuning the decay curve is a one-line change and never a
    # re-fetch of every edge.
    position: Mapped[int] = mapped_column(SmallInteger, nullable=False)
    fetched_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)

    __table_args__ = (
        # The ON CONFLICT target that makes a re-seed idempotent.
        UniqueConstraint("source_media_id", "similar_media_id"),
        # Both providers list a title among its own recommendations often enough to matter, and a
        # self-edge would recommend you something you already rated.
        CheckConstraint("source_media_id <> similar_media_id", name="not_self"),
    )


class RecommendationRun(Base):
    """Per-user cache header. Deliberately NOT UUIDPrimaryKeyMixin — decision 7-N.

    Exists so that "never computed" and "computed, correctly empty" are distinguishable. Without
    it a user with zero candidates stores zero Recommendation rows and would recompute on every
    single request forever — the cold-start user, who can least afford it.
    """

    __tablename__ = "recommendation_run"

    # The natural key IS the invariant: exactly one row per user. A surrogate id would permit two
    # and need a unique constraint to forbid it.
    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    computed_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    # How many library entries the ranking was computed from. Deleting an entry does not move
    # max(user_media.updated_at), so a count comparison is the only cheap way to notice a removal.
    source_entry_count: Mapped[int] = mapped_column(Integer, nullable=False)


class Recommendation(UUIDPrimaryKeyMixin, Base):
    """One materialised, ranked suggestion for one user."""

    __tablename__ = "recommendation"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False, index=True)
    # Dense and unique per user, which is what makes the cursor's uniqueness structural rather
    # than merely handled. An integer also avoids paginating on a float: a blended score would
    # round-trip through JSON text on every page, and a drifted value in `(score, id) < (:v, :i)`
    # skips or repeats rows — the same reasoning that made UserMedia.score NUMERIC.
    rank: Mapped[int] = mapped_column(Integer, nullable=False)
    # Stored, never serialised (decision 7-K). Publishing it would let clients render a number
    # whose scale we never defined, making every retune a visible unexplained change. Keeping it
    # is what makes "why is this ranked fourth?" answerable without re-running the computation.
    score: Mapped[Decimal] = mapped_column(Numeric(10, 6), nullable=False)
    seed_media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False)
    matched_genres: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False, server_default=text("'{}'::text[]"))

    __table_args__ = (
        UniqueConstraint("user_id", "rank"),
        UniqueConstraint("user_id", "media_id"),
    )
