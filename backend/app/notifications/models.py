import uuid

from sqlalchemy import Boolean, ForeignKey, text
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UUIDPrimaryKeyMixin


class NotificationPrefs(UUIDPrimaryKeyMixin, Base):
    """Per-user push settings. `user_id` is unique, so "one row per user" is a database
    guarantee rather than something the service layer has to remember."""

    __tablename__ = "notification_prefs"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False, unique=True)
    push_enabled: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=text("true"))
