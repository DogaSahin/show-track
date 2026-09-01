import uuid
from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field, model_validator

from app.notifications.models import PushTransport


class PrefsRead(BaseModel):
    push_enabled: bool


class PrefsUpdate(BaseModel):
    # Required, not optional-with-exclude_unset. Unlike PATCH /v1/library/{id}, which is a genuine
    # partial update over several fields, this resource has exactly one field — so "unset" and
    # "no-op" would be the same request, and accepting an empty body would silently do nothing.
    push_enabled: bool


class TargetCreate(BaseModel):
    """Registration for either transport, with 6-L SCOPED rather than weakened (decision A-K).

    `extra="forbid"` used to carry 6-L on its own — `target` was not a field, so
    `{"label": "phone", "target": "guessable"}` was a 422 rather than a 201 with the attacker's
    value silently dropped. UnifiedPush needs the field to exist, because the direction reverses:
    the distributor on the device mints the endpoint and the server cannot. So the guarantee moves
    from "the field does not exist" to "the field is refused for the transport it was written
    for", and gains a second half — a `unifiedpush` registration with no target is equally wrong
    and equally a 422. `extra="forbid"` stays, and still catches every OTHER invented field.
    """

    model_config = ConfigDict(extra="forbid")

    # Optional and cosmetic — it exists so a person can tell "phone" from "tablet" in the list.
    # Never used in routing.
    label: str | None = Field(default=None, max_length=64)
    # Defaulted to NTFY so every pre-existing client body stays valid to the byte.
    transport: PushTransport = PushTransport.NTFY
    # Client-supplied ONLY for unifiedpush. For ntfy the server mints the topic and a supplied
    # value is rejected outright.
    #
    # max_length mirrors PushTarget.target's VARCHAR(255) exactly, and it is not decoration: the
    # origin check pins the HOST but says nothing about the path, so a 10KB path on the right host
    # would pass every validator and then fail at INSERT with a Postgres 22001 — a 500 for what is
    # plainly a bad request. Mirroring the column turns it into a 422 at the boundary, the same
    # thing `label`'s max_length=64 does for its own column.
    target: str | None = Field(default=None, max_length=255)

    @model_validator(mode="after")
    def _target_matches_transport(self) -> "TargetCreate":
        """mode="after", so it runs on parsed fields rather than on the raw dict — `transport` is
        already the enum here, which is what lets the two branches be `is` comparisons instead of
        string matching. A ValueError raised in a validator becomes a 422 through FastAPI's
        RequestValidationError handler; no route code is involved.
        """
        if self.transport is PushTransport.NTFY and self.target is not None:
            raise ValueError("target is server-generated for ntfy and must not be supplied")
        if self.transport is PushTransport.UNIFIEDPUSH and not self.target:
            raise ValueError("target is required for unifiedpush")
        return self


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
