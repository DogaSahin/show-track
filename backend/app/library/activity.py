"""Which activity kind a library change represents, and what its payload carries.

Pure: no session, no clock, no settings. Kept out of service.py for the same reason scoring.py
(Phase 7) and invites.py (Phase 7.5a) are separate modules — the precedence order is the thing
that will be argued about later, so it should be readable and testable in one place without a
database.
"""

import enum
from collections.abc import Mapping
from decimal import Decimal
from typing import Any

from app.library.models import ActivityKind, UserMediaStatus


def kind_for(changes: Mapping[str, Any]) -> ActivityKind | None:
    """The single kind one PATCH represents, or None when nothing feed-worthy changed.

    ONE row per request (decision S-B). Beyond readability, the reason is mechanical:
    `created_at` uses func.now(), which in Postgres is TRANSACTION-START time, so three rows
    written by one PATCH would share a byte-identical timestamp. The feed cursor is
    (created_at, id) and id is a random uuid4, so those rows would appear in ARBITRARY order —
    "completed" could sort before "rated". One row per request means there is nothing to order.

    Ordered by how much the change says: finishing or abandoning a title is a verdict, a score is
    an opinion, progress is bookkeeping.

    `==`, not `is`: UserMediaStatus is a StrEnum, and 7.5a established that a value constructed in
    Python arrives as a bare `str`, where identity comparison fails silently.
    """
    status = changes.get("status")
    if status == UserMediaStatus.COMPLETED:
        return ActivityKind.COMPLETED
    if status == UserMediaStatus.DROPPED:
        return ActivityKind.DROPPED
    # `in`, not a truthiness test: an explicit {"score": None} un-rates a title, which is a real
    # event. `changes` comes from model_dump(exclude_unset=True), so a key present means the
    # client sent it.
    if "score" in changes:
        return ActivityKind.RATED
    if "progress" in changes:
        return ActivityKind.PROGRESSED
    return None


def payload_for(changes: Mapping[str, Any]) -> dict[str, Any]:
    """A JSON-safe copy of the change set (decision S-M).

    Decimal -> str, per decision 4-N: a JSON number is an IEEE 754 double, and NUMERIC(3,1) exists
    precisely so scores do not go through one. asyncpg would not encode a Decimal into JSONB
    anyway, so this is correctness AND a working insert.
    """
    out: dict[str, Any] = {}
    for field, value in changes.items():
        if isinstance(value, Decimal):
            out[field] = str(value)
        elif isinstance(value, enum.Enum):
            out[field] = value.value
        else:
            out[field] = value
    return out
