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
    DAY_OF = "day_of"


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
    threshold: Mapped[NotificationThreshold] = mapped_column(
        enum_column(NotificationThreshold, "threshold"), nullable=False
    )
    status: Mapped[NotificationTaskStatus] = mapped_column(
        enum_column(NotificationTaskStatus, "status"), nullable=False, server_default=text("'pending'")
    )
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    sent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # This constraint's generated name, uq_notification_tasks_user_id_media_id_episode_number_threshold,
    # is 63 characters — exactly Postgres's max_identifier_length, with zero characters of headroom.
    # It is not truncated today (confirmed against pg_constraint). If a future column ever joins this
    # key, SQLAlchemy's IdentifierPreparer truncates the over-length name itself, deterministically,
    # replacing the tail with a short hash before the DDL is ever sent — so the name Postgres stores
    # would no longer match this literal, and neither would the hardcoded string in
    # tests/test_notifications_model.py that asserts on it.
    __table_args__ = (UniqueConstraint("user_id", "media_id", "episode_number", "threshold"),)
