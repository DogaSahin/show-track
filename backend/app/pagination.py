"""Keyset (cursor) pagination, shared by every paginated endpoint.

Deliberately domain-agnostic: it knows nothing about UserMedia or Media. The design doc
specifies {items, next_cursor} for /v1/recommendations, the group feed, the group watchlist and
reviews as well as /v1/library — a primitive living inside its first consumer is a primitive the
second consumer copies.
"""

import base64
import json
import uuid
from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from decimal import InvalidOperation
from typing import Any


class InvalidCursor(Exception):
    """The cursor is malformed, or was issued for a different sort.

    Route-local by design: it has one call site, so it becomes an HTTPException(400) there
    rather than an entry in app/errors.py, which is for failures several unrelated routes share.
    """


@dataclass(frozen=True, slots=True)
class Cursor:
    value: Any
    id: uuid.UUID


def _serialize(value: Any) -> str:
    if isinstance(value, datetime):
        return value.isoformat()
    return str(value)


def encode_cursor(sort_key: str, value: Any, entry_id: uuid.UUID) -> str:
    """Base64url over compact JSON.

    Opaque, not secret — "do not parse this", not "you cannot read this". It is deliberately
    unsigned: user_id comes from the bearer token and never from the cursor, so a forged cursor
    can only reposition a caller within their own rows. Signing would add a key to rotate and
    buy nothing.

    The sort key travels inside the payload so that replaying a cursor under a different sort is
    detectable. The `id` half of the composite is mandatory: no sort column is unique, and
    without a tiebreaker, ties silently skip or duplicate rows across page boundaries.
    """
    payload = {"k": sort_key, "v": _serialize(value), "i": str(entry_id)}
    return base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode()


def decode_cursor(raw: str, sort_key: str, parse: Callable[[str], Any]) -> Cursor:
    """`parse` comes from the caller's sort descriptor, which is the only thing that knows what
    type the value should be — and which is responsible for being total into that column's
    domain, not merely well-typed. Every failure below is client-supplied input, so all of them
    raise InvalidCursor rather than escaping as a 500.
    """
    try:
        payload = json.loads(base64.urlsafe_b64decode(raw))
    except (ValueError, RecursionError) as exc:
        # ValueError covers json.JSONDecodeError, a non-ASCII input to b64decode, AND
        # binascii.Error (bad padding) — which HAS subclassed ValueError since Python 3.0, so
        # naming it separately would be redundant. RecursionError is the one that is genuinely
        # not a ValueError: json.loads raises it on deeply nested input, and it would otherwise
        # escape as a 500 from client-controlled bytes.
        raise InvalidCursor("cursor is not decodable") from exc

    if not isinstance(payload, dict):
        raise InvalidCursor("cursor payload is not an object")
    if payload.get("k") != sort_key:
        raise InvalidCursor(f"cursor was issued for sort {payload.get('k')!r}, not {sort_key!r}")

    raw_value, raw_id = payload.get("v"), payload.get("i")
    # A type check, not a wider except clause. uuid.UUID(123) raises AttributeError — it calls
    # .replace() on its argument before checking the type — which no reasonable except tuple
    # below would catch, so a cursor carrying a JSON number for "i" would 500 on entirely
    # client-controlled input. Stating the contract is better than catching its violations.
    if not isinstance(raw_value, str) or not isinstance(raw_id, str):
        raise InvalidCursor("cursor payload fields must be strings")

    try:
        return Cursor(value=parse(raw_value), id=uuid.UUID(raw_id))
    except (ValueError, InvalidOperation) as exc:
        # InvalidOperation is Decimal's parse failure; it subclasses ArithmeticError, never
        # ValueError, so it has to be named explicitly.
        raise InvalidCursor("cursor payload is unusable") from exc
