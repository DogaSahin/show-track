from datetime import datetime

from sqlalchemy import DateTime, Index, String, func
from sqlalchemy.orm import Mapped, mapped_column

from app.db import Base, UUIDPrimaryKeyMixin


class User(UUIDPrimaryKeyMixin, Base):
    __tablename__ = "users"

    username: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    email: Mapped[str] = mapped_column(String(320), nullable=False)
    hashed_password: Mapped[str] = mapped_column(String(255), nullable=False)
    fcm_token: Mapped[str | None] = mapped_column(String(255), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())

    # Case-insensitive uniqueness as a database guarantee, with the original case preserved
    # on disk. Phase 2 must therefore query `WHERE lower(email) = lower(:email)` — a plain
    # equality lookup is both a sequential scan and semantically wrong.
    __table_args__ = (Index("uq_users_lower_email", func.lower(email), unique=True),)
