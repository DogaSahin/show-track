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
    # extra="forbid" enforces "the topic is never client-supplied" (6-L) AT the boundary: without
    # it, `{"label": "phone", "target": "guessable"}` gets a 201 with the extra field silently
    # dropped rather than a 422, which relies on the field merely not existing rather than
    # rejecting the attempt outright.
    model_config = ConfigDict(extra="forbid")

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


class DispatchSummary(BaseModel):
    """`ran` is False exactly when the advisory lock was held elsewhere, mirroring SyncSummary.

    The four terminal counts are separate because they are different diagnoses (6-F): `skipped`
    means the world changed, `expired` means we were too slow, `failed` means the transport gave
    up. Collapsing them makes the one log line anyone actually reads useless.
    """

    ran: bool
    claimed: int = 0
    sent: int = 0
    skipped: int = 0
    expired: int = 0
    failed: int = 0
    # Retryable failures still pending. Not a failure — the expected state mid-backoff.
    retrying: int = 0
    # Due tasks this run did not claim because of DISPATCH_BATCH_SIZE. Reported rather than
    # silent: a capped run must not look like a completed one.
    remaining: int = 0
