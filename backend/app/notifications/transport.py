import uuid
from typing import ClassVar, Protocol

from pydantic import BaseModel

from app.notifications.models import NotificationThreshold


class PushMessage(BaseModel):
    """What to say, not how to say it (6-O).

    Structured fields rather than a transport-shaped payload: the dispatcher never learns what a
    topic, a priority header, or a data message is. That is what lets a UnifiedPush transport in
    Phase 8-9 emit a data-only message the Android app renders itself, with deep-linking, without
    anything above send() changing.
    """

    title: str
    body: str
    media_id: uuid.UUID
    episode_number: int
    threshold: NotificationThreshold


class TransportError(Exception):
    """Base. Never carries the target or the auth token in its message — both are credentials
    and an exception string reaches a log line."""


class TransportRetryable(TransportError):
    """Transient: 5xx, 429, timeouts, connection errors. Back off and try again."""


class TransportPermanent(TransportError):
    """Will never succeed: unknown topic, malformed target. Prune the target row."""


class NotificationTransport(Protocol):
    """The seam that makes the ntfy-vs-FCM decision reversible (6-B).

    A Protocol rather than an ABC because — unlike MediaProvider — there is no shared default
    behaviour to inherit. Every transport's send() is entirely its own.

    send() returns None and reports failure by raising, mirroring MediaProvider. The
    retryable/permanent split is not decoration: it is what decides "back off" from "this target
    is dead, delete the row", and collapsing it either loses notifications or accumulates dead
    targets forever.
    """

    name: ClassVar[str]

    async def send(self, target: str, message: PushMessage) -> None: ...
