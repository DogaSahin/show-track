import base64
import json
import uuid
from datetime import UTC, datetime
from decimal import Decimal

import pytest

from app.pagination import Cursor, InvalidCursor, decode_cursor, encode_cursor


def _raw(payload: object) -> str:
    """Build a cursor by hand, so a test can express a payload encode_cursor would never emit.

    Written as code rather than as an opaque base64 literal: the literal form is unreadable and
    silently untestable if it is mistyped.
    """
    return base64.urlsafe_b64encode(json.dumps(payload).encode()).decode()


@pytest.mark.parametrize(
    ("value", "parse"),
    [
        (Decimal("8.0"), Decimal),
        (datetime(2026, 9, 15, 12, 30, tzinfo=UTC), datetime.fromisoformat),
        ("Shingeki no Kyojin", str),
    ],
    ids=["decimal", "datetime", "text"],
)
def test_a_cursor_round_trips_every_sort_value_type(value, parse):
    entry_id = uuid.uuid4()

    decoded = decode_cursor(encode_cursor("score", value, entry_id), "score", parse)

    assert decoded == Cursor(value=value, id=entry_id)


def test_a_cursor_issued_for_another_sort_is_rejected():
    """Without this the comparison runs a score against a title column: no error, just quietly
    wrong pages. Binding the sort key into the cursor is what makes switching sort mid-pagination
    a 400 instead of silent corruption.
    """
    raw = encode_cursor("score", Decimal("8.0"), uuid.uuid4())

    with pytest.raises(InvalidCursor):
        decode_cursor(raw, "title", str)


@pytest.mark.parametrize(
    "raw",
    [
        "not-base64!!",
        "",
        _raw({"not": "a cursor"}),
        _raw(["a", "b"]),
        _raw({"k": "score", "v": "8.0", "i": 123}),
        _raw({"k": "score", "v": None, "i": str(uuid.uuid4())}),
    ],
    ids=[
        "not-base64",
        "empty",
        "object-missing-keys",
        "json-array-not-object",
        "wrong-typed-id",
        "null-value",
    ],
)
def test_an_unusable_cursor_raises_rather_than_crashing(raw):
    """Every one of these is client-supplied. They must reach the route as InvalidCursor, which
    it turns into a 400 — never as a stray ValueError or AttributeError that becomes a 500.

    "wrong-typed-id" is the one that motivated the isinstance checks: uuid.UUID(123) raises
    AttributeError, because it calls .replace() on its argument before checking the type.
    """
    with pytest.raises(InvalidCursor):
        decode_cursor(raw, "score", Decimal)


def test_a_value_the_parser_rejects_raises_invalid_cursor():
    """A cursor whose sort key matches but whose value is garbage for that column. Decimal raises
    InvalidOperation, which subclasses ArithmeticError and never ValueError, so it has to be
    caught by name.
    """
    raw = encode_cursor("score", "not-a-number", uuid.uuid4())

    with pytest.raises(InvalidCursor):
        decode_cursor(raw, "score", Decimal)
