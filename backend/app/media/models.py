import enum
from datetime import datetime

from sqlalchemy import ARRAY, DateTime, Integer, String, Text, UniqueConstraint, text
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
