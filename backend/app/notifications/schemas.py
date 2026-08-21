import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field

from app.notifications.models import PushTransport


class PrefsRead(BaseModel):
    push_enabled: bool


class PrefsUpdate(BaseModel):
    # Required, not optional-with-exclude_unset. Unlike PATCH /v1/library/{id}, which is a genuine
    # partial update over several fields, this resource has exactly one field — so "unset" and
    # "no-op" would be the same request, and accepting an empty body would silently do nothing.
    push_enabled: bool


class TargetCreate(BaseModel):
    # Optional and cosmetic — it exists so a person can tell "phone" from "tablet" in the list.
    # Never used in routing.
    label: str | None = Field(default=None, max_length=64)


class TargetRead(BaseModel):
    """The list shape. Note what is ABSENT: `target`.

    The topic is a bearer secret returned exactly once, at creation. Adding it here would turn a
    leaked read-only access token into a full notification-stream compromise, in both directions.
    """

    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    transport: PushTransport
    label: str | None
    created_at: datetime
    last_seen_at: datetime | None


class TargetCreated(TargetRead):
    """The creation shape, and the ONLY response anywhere that carries the topic."""

    target: str
