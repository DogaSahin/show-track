import uuid
from datetime import datetime

from sqlalchemy import DateTime, ForeignKey, Index, String, Uuid, func
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
    #
    # This is an Index, not a UniqueConstraint, despite the `uq_` name — a functional
    # unique constraint on `lower(email)` isn't expressible as a UniqueConstraint, only
    # as a unique Index. A later migration must drop it with `op.drop_index(...)`, not
    # `op.drop_constraint(..., type_="unique")`.
    __table_args__ = (Index("uq_users_lower_email", func.lower(email), unique=True),)


class RefreshToken(UUIDPrimaryKeyMixin, Base):
    """One row per issued refresh token, rotated on every use.

    `family_id` links a rotation chain. Presenting an already-revoked token means someone
    replayed a stolen one, so the whole family is revoked — that is the only signal available
    that a token was stolen rather than merely expired.
    """

    __tablename__ = "refresh_tokens"

    user_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    family_id: Mapped[uuid.UUID] = mapped_column(Uuid, nullable=False, index=True)
    # sha256 hexdigest, measured at 64 characters. The token itself is never stored: a
    # database leak must not hand over live sessions.
    token_hash: Mapped[str] = mapped_column(String(64), nullable=False, unique=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
