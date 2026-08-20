import enum
import uuid
from datetime import datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, UniqueConstraint, func, text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UUIDPrimaryKeyMixin, enum_column


class NotificationPrefs(UUIDPrimaryKeyMixin, Base):
    """Per-user push settings. `user_id` is unique, so "one row per user" is a database
    guarantee rather than something the service layer has to remember."""

    __tablename__ = "notification_prefs"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False, unique=True)
    push_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("true"))


class NotificationThreshold(enum.StrEnum):
    # "24h" is not a legal Python identifier, which is exactly why enum_column passes
    # values_callable — only the value reaches the database.
    TWENTY_FOUR_HOURS = "24h"
    # Was DAY_OF = "day_of". A calendar-date rule fires only between UTC midnight and the air
    # time, so an episode airing at 00:05 UTC had a FIVE MINUTE window against a 15-minute scan —
    # never late, simply never enqueued, and indistinguishable in a summary from a healthy quiet
    # scan. A fixed lead time (notify_soon_hours) has no midnight cliff. Renamed rather than
    # redefined, because a name that no longer means "on the day" is drift; affordable because
    # this table was still empty when it happened.
    AIRING_SOON = "airing_soon"


class NotificationTaskStatus(enum.StrEnum):
    PENDING = "pending"
    SENT = "sent"
    FAILED = "failed"


class NotificationTask(UUIDPrimaryKeyMixin, Base):
    """A queued push, written by the sync job and sent by the dispatcher.

    Sync never sends: it inserts a row here and the dispatcher picks it up (design doc
    §5, CLAUDE.md rule 5). Dedup is the unique constraint below, not application logic —
    per design doc §5.2, that constraint is what survives a retry, a race, and a redeploy
    mid-job, which an `if` statement does not.
    """

    __tablename__ = "notification_tasks"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False)
    media_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("media.id", ondelete="CASCADE"), nullable=False, index=True)
    episode_number: Mapped[int] = mapped_column(Integer, nullable=False)
    # The air DATE this notification is about, truncated to UTC midnight on write. NOT the precise
    # instant, and named `airs_on` so the next reader does not store one: AniList revises
    # nextAiringEpisode.airingAt by seconds for ordinary corrections, and a precise key would mint
    # a fresh notification for every nudge — six pushes for one episode after three revisions. A
    # genuine anime delay is days, so it still changes this value and still notifies.
    #
    # The dispatcher reads the precise time from media.next_episode_date, so nothing is lost.
    airs_on: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    threshold: Mapped[NotificationThreshold] = mapped_column(
        enum_column(NotificationThreshold, "threshold"), nullable=False
    )
    status: Mapped[NotificationTaskStatus] = mapped_column(
        enum_column(NotificationTaskStatus, "status"), nullable=False, server_default=text("'pending'")
    )
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    sent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # Named explicitly, and not for style. The convention would generate
    # `uq_notification_tasks_user_id_media_id_episode_number_threshold_airs_on` — 71 characters
    # against Postgres's 63-character max_identifier_length — and SQLAlchemy's IdentifierPreparer
    # would silently truncate it to a hash-suffixed name before the DDL was ever sent. Measured.
    # An earlier version of this comment predicted exactly that for exactly this scenario.
    #
    # `airs_on` is in the key on purpose: without it, an episode delayed after its 24h
    # notification was created could never be enqueued again, so the user got one wrong push and
    # then silence. Date-truncated rather than precise so provider jitter is not mistaken for a
    # reschedule. Accepted cost: a same-day move (09:00 -> 22:00) does not re-notify.
    __table_args__ = (
        UniqueConstraint(
            "user_id",
            "media_id",
            "episode_number",
            "threshold",
            "airs_on",
            name="uq_notification_tasks_dedup",
        ),
    )
