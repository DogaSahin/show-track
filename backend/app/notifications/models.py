import enum
import uuid
from datetime import UTC, datetime

from sqlalchemy import Boolean, DateTime, ForeignKey, Integer, String, UniqueConstraint, func, text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UUIDPrimaryKeyMixin, enum_column


def airs_on_for(airs_at: datetime) -> datetime:
    """UTC midnight of the air date — the dedup key's time component, and the value the
    dispatcher compares against to detect a reschedule.

    Truncated, because AniList revises airingAt by seconds for ordinary corrections and a precise
    key would mint a fresh notification for every nudge. `.astimezone(UTC)` first so the
    truncation is anchored to UTC rather than to whatever tzinfo the value happens to carry.

    Lives here rather than in app/sync/service.py because both the writer (the threshold scan)
    and the reader (the dispatcher) need it, and sync already imports this module — putting it
    the other way round is a circular import.
    """
    return airs_at.astimezone(UTC).replace(hour=0, minute=0, second=0, microsecond=0)


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
    # Four terminal outcomes rather than one, because they are different diagnoses (6-F).
    # "17 notifications failed overnight" is unactionable; "17 skipped, 0 failed" says the
    # system worked correctly and 17 episodes moved.
    #
    # The world changed between enqueue and send: rescheduled, untracked, push disabled, or no
    # target registered.
    SKIPPED = "skipped"
    # The air time passed before we could deliver. Separated from SKIPPED on purpose: this is
    # the one outcome that indicates OUR fault rather than the world's.
    EXPIRED = "expired"


class PushTransport(enum.StrEnum):
    NTFY = "ntfy"
    # UnifiedPush (decision A-A). The two differ in WHO MINTS THE TARGET, which is why they
    # cannot share a registration path: for ntfy the server generates the topic and a
    # client-supplied one is a 422 (6-L); for UnifiedPush the distributor on the device mints a
    # full callback URL and the client is the only party that can know it. TargetCreate's
    # validator encodes exactly that asymmetry.
    UNIFIEDPUSH = "unifiedpush"


class PushTarget(UUIDPrimaryKeyMixin, Base):
    """Where one of a user's devices receives pushes.

    A table rather than a column on `users` (6-C): a single `fcm_token` encoded "one device per
    user" in the schema, so installing on a phone and a tablet meant the tablet silently stole
    every notification — a failure that raises nothing anywhere and is diagnosed only by
    noticing the absence of something.
    """

    __tablename__ = "push_targets"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    transport: Mapped[PushTransport] = mapped_column(enum_column(PushTransport, "transport"), nullable=False)
    # The ntfy topic, or — for `unifiedpush` — the distributor's full callback URL. Either way a
    # BEARER SECRET, not an identifier: anyone who knows it can both read every notification on it
    # and post arbitrary ones to this phone. Never written to a log line. For `ntfy` it is
    # generated server-side from a CSPRNG and never client-supplied (6-L); for `unifiedpush` only
    # the device can know it, so it arrives from the client and is origin-checked instead (A-L).
    target: Mapped[str] = mapped_column(String(255), nullable=False)
    # For the human, never used in routing. "Pixel 8".
    label: Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    # Last SUCCESSFUL send. NULL until the first one, which is also how the UI can show
    # "registered but never used".
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

    # GLOBAL, not per user — deliberately (6-D). Scoping this to (user_id, transport, target)
    # would let account A register account B's topic and receive B's notifications, and nothing
    # would object. Accepted cost: two people genuinely sharing one device cannot both register
    # it, which is the correct answer anyway, since they would each get the other's pushes.
    __table_args__ = (UniqueConstraint("transport", "target", name="uq_push_targets_transport_target"),)


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
    # When this task next becomes eligible. NULL means "now", which keeps Phase 5's
    # scan_thresholds insert unchanged. A column rather than deriving from `attempts` +
    # `created_at` (6-I): created_at is the ENQUEUE time, not the first-attempt time, and the two
    # diverge by up to a threshold-scan interval — so a derived first retry would fire at an
    # interval that depends on how long the task happened to sit before its first send.
    next_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)

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
