import enum
import uuid
from datetime import date, datetime

from sqlalchemy import Date, DateTime, ForeignKey, Integer, String, Text, UniqueConstraint, text
from sqlalchemy.dialects.postgresql import ARRAY
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
    # First air year. Nullable because both providers omit it for unannounced titles. Added in
    # Phase 3 rather than later because search results need it to disambiguate (there are three
    # "Fullmetal Alchemist" entries) — and while `media` is still empty this is a pure DDL add,
    # where after Phase 4 it would need a backfill re-sync of every row.
    year: Mapped[int | None] = mapped_column(Integer, nullable=True)
    # Canonical genre names, not raw provider strings. Provider -> canonical mapping is
    # Phase 3.4's job, at the provider boundary.
    #
    # The dialect ARRAY, not `sqlalchemy.ARRAY`: only the postgresql one exposes
    # `overlap`/`contains`/`contained_by`. Phase 7 turned out NOT to use those operators — genre
    # decides a recommendation's RANK, never its membership, so there is no genre predicate to
    # index (decision 7-J). The dialect type stays because the generic one lacks these methods
    # entirely and any future query wanting them would need a migration to get them back.
    genres: Mapped[list[str]] = mapped_column(ARRAY(Text), nullable=False, server_default=text("'{}'::text[]"))
    cover_image_url: Mapped[str | None] = mapped_column(Text, nullable=True)
    next_episode_season: Mapped[int | None] = mapped_column(Integer, nullable=True)
    next_episode_number: Mapped[int | None] = mapped_column(Integer, nullable=True)
    next_episode_date: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    status: Mapped[MediaStatus] = mapped_column(enum_column(MediaStatus, "status"), nullable=False)
    # When the sync job last got an ANSWER from the provider about this title — the input to the
    # tiered refresh cadence in app/sync/service.py, not an audit field.
    #
    # Nullable with no server default, deliberately: NULL means "never fetched" and the due
    # predicate treats it as always due, so every pre-existing row is picked up on the first run
    # after this migration. A `server_default=now()` would be tidier DDL and would tell a lie —
    # it would mark rows fresh that had never been synced at all, idling them for a full tier
    # interval.
    last_synced_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    __table_args__ = (UniqueConstraint("source", "external_id"),)


class Episode(UUIDPrimaryKeyMixin, Base):
    """One row per episode. `season_number` exists because a Media row can be a whole TMDB
    show, in which case S1E1 and S2E1 both have number 1.

    The unique constraint is what lets the Phase 5 sync job write
    `INSERT ... ON CONFLICT (media_id, season_number, number) DO UPDATE` — without a unique
    index that statement cannot be expressed at all.
    """

    __tablename__ = "episodes"

    media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False)
    # A source without seasons (e.g. an AniList cour) is season 1 — that's the domain
    # assumption this default encodes, not a backfill convenience.
    season_number: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("1"))
    number: Mapped[int] = mapped_column(Integer, nullable=False)
    # `date`, not `timestamptz`: TMDB gives day granularity only, and AniList's
    # `AiringSchedule.airingAt` (a Unix timestamp with time-of-day) has its time discarded
    # here. The countdown UI reads Media.next_episode_date, which does carry a time.
    air_date: Mapped[date | None] = mapped_column(Date, nullable=True)

    __table_args__ = (UniqueConstraint("media_id", "season_number", "number"),)
