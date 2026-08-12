import enum
import uuid
from datetime import date, datetime

from sqlalchemy import ARRAY, Date, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UUIDPrimaryKeyMixin, enum_column


class MediaType(enum.StrEnum):
    ANIME = "anime"
    TV = "tv"


class MediaSource(enum.StrEnum):
    ANILIST = "anilist"
    TMDB = "tmdb"


class MediaStatus(enum.StrEnum):
    AIRING = "airing"
    FINISHED = "finished"
    NOT_YET_AIRED = "not_yet_aired"


class Media(UUIDPrimaryKeyMixin, Base):
    """One row per provider entity: an AniList cour, or a whole TMDB show.

    `next_episode_*` is a denormalised pointer so the countdown is a column read rather
    than a join. It carries a season alongside the number because TMDB numbers episodes
    within a season, so the number alone is ambiguous across seasons of the same show.
    """

    __tablename__ = "media"

    type: Mapped[MediaType] = mapped_column(enum_column(MediaType, "type"), nullable=False)
    source: Mapped[MediaSource] = mapped_column(enum_column(MediaSource, "source"), nullable=False)
    external_id: Mapped[str] = mapped_column(String(64), nullable=False)
    title: Mapped[str] = mapped_column(Text, nullable=False)
    # Canonical genre names, not raw provider strings. Provider -> canonical mapping is
    # Phase 3.4's job, at the provider boundary.
    genres: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False, server_default=text("'{}'::text[]"))
    cover_image_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    next_episode_season: Mapped[int | None] = mapped_column(Integer, nullable=True)
    next_episode_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    next_episode_date: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    status: Mapped[MediaStatus] = mapped_column(enum_column(MediaStatus, "status"), nullable=False)

    __table_args__ = (UniqueConstraint("source", "external_id"),)


class Episode(UUIDPrimaryKeyMixin, Base):
    """One row per episode. `season_number` exists because a Media row can be a whole TMDB
    show, in which case S1E1 and S2E1 both have number 1.

    The unique constraint is what lets the Phase 5 sync job write
    `INSERT ... ON CONFLICT (media_id, season_number, number) DO UPDATE` — without a unique
    index that statement cannot be expressed at all, forcing SELECT-then-INSERT, which
    races between sync passes.
    """

    __tablename__ = "episodes"

    media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False)
    season_number: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("1"))
    number: Mapped[int] = mapped_column(Integer, nullable=False)
    # `date`, not `timestamptz`: TMDB gives day granularity only. The countdown UI reads
    # Media.next_episode_date, which does carry a time.
    air_date: Mapped[date | None] = mapped_column(Date, nullable=True)

    __table_args__ = (UniqueConstraint("media_id", "season_number", "number"),)
